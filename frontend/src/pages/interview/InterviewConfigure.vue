<script setup lang="ts">
import { onModelConfigurationChanged } from '../../model-settings'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request, errorMessage } from '../../api'
import type { Material } from '../../types'
import Notice from '../../components/common/Notice.vue'
import ConfigurationFields from '../../components/interview/InterviewConfigurationFields.vue'
import { createSession, displayDate, getInterviewStatus, levelLabels, listSessions, requestId, statusLabels } from '../../api/interviews'
import type { InterviewConfig, InterviewSessionSummary, InterviewStatus } from '../../models/interview'

const route = useRoute(), router = useRouter()
const config = ref<InterviewConfig>({ role: String(route.query.role || ''), level: String(route.query.level || ''), resumeId: '', jdId: '', jd: '', plannedQuestionCount: 8, interviewMode: 'TRAINING', timeLimitMinutes: 30, projectId: '', projectBrief: '' })
const materials = ref<Material[]>([]), sessions = ref<InterviewSessionSummary[]>([]), health = ref<InterviewStatus | null>(null)
const state = reactive({ busy: false, loading: true, error: '', historyError: '', materialError: '' })
const valid = computed(() => !!config.value.role.trim() && ['INTERN', 'JUNIOR', 'SENIOR'].includes(config.value.level))
const generic = computed(() => !config.value.resumeId && !config.value.jdId && !config.value.jd.trim() && !config.value.projectId && !config.value.projectBrief?.trim())
let pending: { fingerprint: string; id: string } | null = null

onModelConfigurationChanged(async () => { try { health.value = await getInterviewStatus() } catch { health.value = null } })
onMounted(async () => {
  const results = await Promise.allSettled([request<Material[]>('/materials'), listSessions(), getInterviewStatus()])
  if (results[0].status === 'fulfilled') materials.value = results[0].value
  else state.materialError = `${errorMessage(results[0].reason)} 暂时无法选择资料，可刷新重试或进行通用练习。`
  if (results[1].status === 'fulfilled') sessions.value = results[1].value.filter(session => session.sessionType !== 'QUICK')
  else state.historyError = errorMessage(results[1].reason)
  if (results[2].status === 'fulfilled') health.value = results[2].value
  state.loading = false
})

async function prepare() {
  if (!valid.value || state.busy) return
  state.busy = true; state.error = ''
  const fingerprint = JSON.stringify(config.value)
  if (pending?.fingerprint !== fingerprint) pending = { fingerprint, id: requestId() }
  try {
    const created = await createSession(config.value, pending.id)
    pending = null
    await router.push(`/interviews/${encodeURIComponent(created.id)}`)
  } catch (error) { state.error = errorMessage(error) } finally { state.busy = false }
}
</script>

<template>
  <div class="page interview-page configure-page">
    <header class="page-heading"><h1>先理解你的经历，再深入面试。</h1><p>结合简历、目标岗位与可回查的技术资料，围绕真实职责、选型权衡和异常处理提问。</p></header>
    <Notice :message="state.error" kind="error" />
    <Notice :message="state.materialError" kind="warning" />
    <Notice v-if="health?.provider === 'mock'" kind="warning" message="当前为 Mock 测试模式，合成结果仅验证流程，不代表真实模型质量。" />
    <Notice v-if="health && !health.configured" kind="warning" message="模型服务尚未配置。请先配置服务再开始，不会自动切换为 Mock。" />
    <div class="two-columns configure-layout">
      <form class="panel" @submit.prevent="prepare">
        <div class="heading-row"><h2>本场面试配置</h2><span v-if="state.loading" class="tiny muted" role="status">正在读取资料…</span></div>
        <ConfigurationFields v-model="config" :materials="materials" :disabled="state.busy" />
        <Notice v-if="generic" message="未选择简历或 JD，本场将作为通用基准摸底，不会推断你的项目经历。" />
        <p v-else-if="!config.resumeId" class="form-help">本场围绕岗位要求展开，不会推断你没有提供的项目经历。</p>
        <button class="btn btn-primary" type="submit" data-action="prepare" :disabled="state.busy || !valid || state.loading || health?.configured === false"><span v-if="state.busy" class="spinner"></span>{{ state.busy ? '正在创建准备任务…' : '进入深度准备' }}</button>
      </form>
      <aside class="configure-aside">
        <div class="panel"><h2>让准备充分，让对话流畅。</h2><ol class="reading-list"><li>深度分析简历与岗位，预取相关技术资料，通常需要 1～3 分钟。</li><li>查看简短摘要，纠正职责误解后，一键确认开考。</li><li>逐题对话，优先复用本场资料；回答提交后立即显示思考状态。</li><li>结束后异步查证复盘，关键评价可以回查具体来源。</li></ol><p class="form-help">准备和复盘期间可以离开页面，稍后从下面的记录继续。</p></div>
        <div class="panel"><h3>继续使用已有功能</h3><nav class="interview-links" aria-label="已有练习入口"><RouterLink to="/configure/quick" class="text-link">快速练习</RouterLink><RouterLink to="/configure/resume" class="text-link">简历押题</RouterLink><RouterLink to="/configure/project" class="text-link">项目深挖</RouterLink><RouterLink to="/review" class="text-link">专项训练</RouterLink><RouterLink to="/history" class="text-link">原有面试历史</RouterLink></nav></div>
      </aside>
    </div>
    <section class="section" aria-labelledby="interview-recent-title">
      <div class="heading-row"><h2 id="interview-recent-title">综合面试记录</h2><span class="tiny muted">准备、面试和复盘均可恢复</span></div>
      <Notice :message="state.historyError" kind="error" />
      <p v-if="!state.loading && !sessions.length && !state.historyError" class="muted">还没有综合面试记录。</p>
      <div v-if="sessions.length" class="panel interview-history"><article v-for="item in sessions" :key="item.id" class="interview-history-row"><div><h3>{{ item.role }} · {{ levelLabels[item.level] || item.level }}</h3><p class="tiny muted">{{ displayDate(item.createdAt) }} · {{ statusLabels[item.status] || '任务处理中' }}<span v-if="item.mock"> · Mock 测试</span></p></div><RouterLink :to="`/interviews/${encodeURIComponent(item.id)}`" class="btn btn-secondary">{{ item.status === 'REPORT_READY' ? '查看复盘' : '继续查看' }}</RouterLink></article></div>
    </section>
  </div>
</template>

<style scoped>
.interview-page .configure-aside .reading-list { margin-top: 16px; }
.interview-page .configure-aside h2 { font-size: 19px; }
.interview-links { display: flex; flex-wrap: wrap; gap: 8px 20px; margin-top: 12px; }
.interview-links a { display: inline-flex; align-items: center; min-height: 44px; }
.interview-history { padding-block: 0; }
.interview-history-row { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 14px; padding-block: 20px; border-bottom: 1px solid var(--border-subtle); }
.interview-history-row:last-child { border: 0; }
.interview-history-row p { margin-top: 8px; }
.interview-page .btn { min-height: 44px; }
.interview-page :is(a, button):focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
</style>
