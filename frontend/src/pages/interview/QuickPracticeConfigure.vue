<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Zap } from 'lucide-vue-next'
import Notice from '../../components/common/Notice.vue'
import { agentTopicLabels, createSession, getAgentPracticeOptions, getInterviewStatus, isAgentRole, requestId } from '../../api/interviews'
import { errorMessage } from '../../api'
import { onModelConfigurationChanged } from '../../model-settings'
import type { AgentPracticeOptions, AgentPracticePreference, InterviewConfig, InterviewStatus } from '../../models/interview'

const route = useRoute(), router = useRouter()
const roles = ['Java 后端工程师', '大模型应用 / Agent 开发工程师']
const initialRole = String(route.query.role || '')
const selectedRole = ref(!initialRole || roles.includes(initialRole) ? initialRole : 'CUSTOM')
const customRole = ref(roles.includes(initialRole) ? '' : initialRole)
const questionCount = ref(5), level = ref(['INTERN', 'JUNIOR', 'SENIOR'].includes(String(route.query.level)) ? String(route.query.level) : 'JUNIOR')
const role = computed(() => selectedRole.value === 'CUSTOM' ? customRole.value.trim() : selectedRole.value)
const agentRole = computed(() => isAgentRole(role.value))
const agentTopics = ref<string[]>([]), practicePreference = ref<AgentPracticePreference>('NEW_FIRST')
const config = computed<InterviewConfig>(() => ({
  sessionType: 'QUICK', role: role.value, level: level.value, plannedQuestionCount: questionCount.value, resumeId: '', jdId: '', jd: '',
  ...(agentRole.value ? { agentTopics: [...agentTopics.value], practicePreference: practicePreference.value } : {}),
}))
const health = ref<InterviewStatus | null>(null)
const state = reactive({ loading: true, busy: false, error: '' })
const reviewCheck = ref<{ fingerprint: string; options: AgentPracticeOptions } | null>(null)
const needsReviewAcknowledgement = computed(() => !!reviewCheck.value?.options.canStart && !!reviewCheck.value.options.requiresAcknowledgement)
let pending: { fingerprint: string; id: string } | null = null
let disposed = false
onUnmounted(() => { disposed = true })
watch([selectedRole, customRole, questionCount, level, () => agentTopics.value.join(','), practicePreference], () => {
  reviewCheck.value = null
  pending = null
}, { flush: 'sync' })
async function readStatus() {
  try { health.value = await getInterviewStatus() } catch (error) { state.error = errorMessage(error) }
  finally { state.loading = false }
}
onMounted(readStatus)
onModelConfigurationChanged(readStatus)
async function start() {
  if (!role.value || state.busy || state.loading || health.value?.configured === false) return
  const currentConfig = { ...config.value }
  const fingerprint = JSON.stringify(currentConfig)
  state.busy = true; state.error = ''
  try {
    if (agentRole.value && practicePreference.value === 'REVIEW') {
      if (reviewCheck.value?.fingerprint === fingerprint && needsReviewAcknowledgement.value) {
        currentConfig.acceptReviewFill = true
        currentConfig.expectedNewSupplementCount = reviewCheck.value.options.newSupplementCount
      } else {
        const options = await getAgentPracticeOptions({ role: currentConfig.role, level: currentConfig.level, plannedQuestionCount: questionCount.value, agentTopics: currentConfig.agentTopics || [] })
        if (disposed || JSON.stringify(config.value) !== fingerprint) return
        reviewCheck.value = { fingerprint, options }
        if (!options.eligible || !options.canStart || options.requiresAcknowledgement) return
      }
    }
    const creationFingerprint = JSON.stringify(currentConfig)
    if (pending?.fingerprint !== creationFingerprint) pending = { fingerprint: creationFingerprint, id: requestId() }
    const session = await createSession(currentConfig, pending.id)
    if (disposed) return
    pending = null
    await router.push(`/interviews/${encodeURIComponent(session.id)}`)
  } catch (error) { reviewCheck.value = null; state.error = errorMessage(error) } finally { state.busy = false }
}
</script>

<template>
  <div class="page quick-practice-page configure-page">
    <header class="page-heading"><h1>快速练习，从一道技术问题开始。</h1><p>选好岗位和题量，围绕技术原理与工程场景连续问答。</p></header>
    <Notice :message="state.error" kind="error" />
    <Notice v-if="health?.provider === 'mock'" kind="warning" message="当前为 Mock 测试模式，合成结果仅验证流程，不代表真实模型质量。" />
    <Notice v-if="health?.configured === false" kind="warning" message="模型服务尚未配置，请先通过模型配置入口配置服务再开始。" />
    <div class="two-columns configure-layout">
      <form class="panel" @submit.prevent="start">
        <div class="heading-row"><h2>本次想练什么？</h2><Zap :size="22" aria-hidden="true" /></div>
        <fieldset :disabled="state.busy" class="quick-fields"><legend class="sr-only">快速练习配置</legend>
          <div class="form-field"><label for="quick-role">目标岗位</label><select id="quick-role" v-model="selectedRole" required><option value="" disabled>请选择岗位方向</option><option v-for="item in roles" :key="item">{{ item }}</option><option value="CUSTOM">自定义岗位</option></select></div>
          <div v-if="selectedRole === 'CUSTOM'" class="form-field"><label for="quick-custom-role">岗位名称</label><input id="quick-custom-role" v-model="customRole" required maxlength="120" placeholder="例如：数据平台工程师" /></div>
          <div class="form-field"><label for="quick-question-count">主问题数量</label><select id="quick-question-count" v-model.number="questionCount"><option :value="3">3 题</option><option :value="5">5 题</option><option :value="8">8 题</option></select><p class="form-help">每道主问题最多追问两次，追问不计入主问题数；可随时结束并复盘。</p></div>
          <details class="quick-level"><summary>{{ agentRole ? '调整职级、主题与练习偏好' : '调整目标职级' }}</summary><div class="form-field"><label for="quick-level">目标职级</label><select id="quick-level" v-model="level"><option value="INTERN">实习生</option><option value="JUNIOR">初级</option><option value="SENIOR">中高级</option></select><p class="form-help">默认初级，面试官会结合你的实际回答选择追问深度。</p></div>
            <fieldset v-if="agentRole" class="quick-topics" data-agent-topics><legend>Agent 练习主题（可多选）</legend><p class="form-help">不选时自动综合抽查；选中后从所选范围安排问题。题量少于主题数时轮换抽查，全选也不保证一场覆盖全部主题；实际考点覆盖在复盘中查看。</p><div class="quick-topic-options"><label v-for="(label, id) in agentTopicLabels" :key="id" :for="`quick-topic-${id}`"><input :id="`quick-topic-${id}`" v-model="agentTopics" type="checkbox" name="agentTopics" :value="id" />{{ label }}</label></div></fieldset>
            <div v-if="agentRole" class="form-field"><label for="quick-practice-preference">练习偏好</label><select id="quick-practice-preference" v-model="practicePreference"><option value="NEW_FIRST">未练优先</option><option value="REVIEW">复习已练考点</option></select><p class="form-help">默认优先选择未练考点；复习会先检查当前主题的已练记录。</p></div>
          </details>
        </fieldset>
        <div v-if="reviewCheck" class="quick-review-options" data-review-options role="status" aria-live="polite"><p v-if="reviewCheck.options.message">{{ reviewCheck.options.message }}</p><p v-if="needsReviewAcknowledgement">已有 {{ reviewCheck.options.reviewAvailable }} 道复习考点，将补充 {{ reviewCheck.options.newSupplementCount }} 道新考点。请确认后开始。</p><button v-if="!reviewCheck.options.canStart" type="button" class="btn btn-secondary" data-switch-new-first :disabled="state.busy" @click="practicePreference = 'NEW_FIRST'">切换为未练优先</button></div>
        <button type="submit" class="btn btn-primary quick-start" :disabled="!role || state.busy || state.loading || health?.configured === false"><span v-if="state.busy || state.loading" class="spinner"></span><ArrowRight v-else :size="17" />{{ state.busy ? '正在准备练习…' : state.loading ? '正在检查模型配置…' : needsReviewAcknowledgement ? '确认补新并开始练习' : '开始快速练习' }}</button>
      </form>
      <aside class="configure-aside"><div class="panel"><h2>把技术思路讲清楚。</h2><ul class="reading-list"><li>无需上传资料，也无需准备自我介绍。</li><li>从岗位技术与工程场景切入，每次回答一个问题。</li><li>练习中保持面试节奏，结束后统一查看反馈与具体依据。</li><li>自动保存对话，可从统一历史继续或回看复盘。</li></ul><nav class="quick-links" aria-label="其他面试方式"><RouterLink to="/interviews" class="text-link">综合面试</RouterLink><RouterLink to="/configure/project" class="text-link">项目深挖</RouterLink><RouterLink to="/configure/resume" class="text-link">简历押题</RouterLink><RouterLink to="/history" class="text-link">全部历史</RouterLink></nav></div></aside>
    </div>
  </div>
</template>

<style scoped>
.quick-fields { border: 0; padding: 0; min-width: 0; }
.quick-level { margin-top: 24px; }
.quick-level summary { cursor: pointer; min-height: 44px; font-weight: 600; }
.quick-level .form-field { margin-top: 12px; }
.quick-topics { border: 0; padding: 0; margin: 24px 0 0; min-width: 0; }
.quick-topics legend { font-weight: 600; }
.quick-topic-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 16px; margin-top: 8px; }
.quick-topic-options label { display: flex; gap: 10px; align-items: center; min-height: 44px; cursor: pointer; overflow-wrap: anywhere; }
.quick-topic-options input { width: 18px; height: 18px; padding: 0; margin: 0; flex-shrink: 0; accent-color: var(--accent); }
.quick-review-options { margin-top: 24px; padding: 16px; border: 1px solid var(--border-subtle); border-radius: var(--radius-control); background: var(--surface-soft); }
.quick-review-options p { margin: 0 0 12px; line-height: 1.7; }
.quick-start { margin-top: 24px; width: 100%; }
.quick-links { display: flex; flex-wrap: wrap; gap: 12px 20px; margin-top: 24px; }
.quick-links a { min-height: 44px; display: inline-flex; align-items: center; }
.quick-practice-page :is(button, a, input, select, summary):focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media (max-width: 480px) { .quick-topic-options { grid-template-columns: minmax(0, 1fr); } }
</style>
