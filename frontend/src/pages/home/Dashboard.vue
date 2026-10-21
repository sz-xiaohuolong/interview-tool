<script setup lang="ts">
import { onModelConfigurationChanged } from '../../model-settings'
import { computed, onMounted, ref } from 'vue'
import { ArrowUpRight, ArrowRight, Zap, FileText, Layers, MessagesSquare, LockKeyhole, Clock } from 'lucide-vue-next'
import { request, errorMessage } from '../../api'
import type { Health, Material, Session } from '../../types'
import { dateTime } from '../../format'
import { listSessions } from '../../api/interviews'
import type { InterviewSessionSummary } from '../../models/interview'
import { interviewRecords } from '../../interview-records'
import Notice from '../../components/common/Notice.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import LoadingState from '../../components/common/LoadingState.vue'
const sessions = ref<Session[]>([]), materials = ref<Material[]>([]), health = ref<Health | null>(null), loading = ref(true), error = ref('')
const currentSessions = ref<InterviewSessionSummary[]>([]), recordsIncomplete = ref(false), materialsUnavailable = ref(false)
const records = computed(() => interviewRecords(sessions.value, currentSessions.value))
const completed = computed(() => records.value.filter(session => session.completed))
const knownAnswers = computed(() => records.value.filter(session => session.answerCount !== undefined))
const missingAnswerCounts = computed(() => records.value.length - knownAnswers.value.length)
const answers = computed(() => knownAnswers.value.length ? knownAnswers.value.reduce((sum, session) => sum + session.answerCount!, 0) : missingAnswerCounts.value ? '未提供' : 0)
const recent = computed(() => records.value.slice(0, 4))
onModelConfigurationChanged(async () => { try { health.value = await request<Health>('/health') } catch { health.value = null } })
onMounted(async () => {
  const results = await Promise.allSettled([request<Session[]>('/sessions'), listSessions(), request<Material[]>('/materials'), request<Health>('/health')])
  if (results[0].status === 'fulfilled') sessions.value = results[0].value
  if (results[1].status === 'fulfilled') currentSessions.value = results[1].value
  if (results[2].status === 'fulfilled') materials.value = results[2].value
  else materialsUnavailable.value = true
  if (results[3].status === 'fulfilled') health.value = results[3].value
  recordsIncomplete.value = results[0].status === 'rejected' || results[1].status === 'rejected'
  const names = ['原有面试记录', '快速练习与综合面试记录', '资料库', '模型状态']
  error.value = results.flatMap((result, index) => result.status === 'rejected' ? [`${names[index]}加载失败：${errorMessage(result.reason)}`] : []).join('；')
  loading.value = false
})
</script>
<template>
  <div class="page dashboard-page">
    <div class="page-heading"><h1>把你会的，讲得更好。</h1><p>从一道问题开始，练习技术深度、表达与临场应对。</p></div>
    <Notice :message="error" kind="error" />
    <Notice v-if="health && !health.configured" message="还没有配置模型。请打开模型配置，填写服务地址、密钥和模型名称，然后开始练习。" kind="warning" />
    <Notice v-if="health?.provider === 'mock'" message="当前为 Mock 测试模式：可体验完整流程，题目和评分仅供功能验证。正式练习请配置真实模型。" kind="warning" />
    <LoadingState v-if="loading" />
    <template v-else>
      <div class="heading-row"><h2>今天，想练哪一种？</h2><span class="tiny muted">选择你的练习方式</span></div>
      <div class="mode-grid">
        <RouterLink to="/configure/quick" class="mode-card featured"><Zap class="mode-icon" :size="20" /><h3>快速练习</h3><p>选择岗位和题量，从技术原理与工程场景直接开始。</p><span class="card-arrow">开始练习 <ArrowUpRight :size="16" /></span></RouterLink>
        <RouterLink to="/interviews" class="mode-card"><MessagesSquare class="mode-icon" :size="20" /><h3>综合面试</h3><p>结合岗位与个人经历，完整经历四阶段面试。</p><span class="card-arrow">准备综合面试 <ArrowUpRight :size="16" /></span></RouterLink>
        <RouterLink to="/configure/resume" class="mode-card"><FileText class="mode-icon" :size="20" /><h3>简历针对押题</h3><p>从真实经历出发，提前准备最值得关注的问题。</p><span class="card-arrow">分析我的简历 <ArrowUpRight :size="16" /></span></RouterLink>
        <RouterLink to="/configure/project" class="mode-card"><Layers class="mode-icon" :size="20" /><h3>项目深度面试</h3><p>从整体架构到技术权衡，讲透你真正做过的项目。</p><span class="card-arrow">深入聊一个项目 <ArrowUpRight :size="16" /></span></RouterLink>
      </div>
      <div class="stat-strip"><div class="stat"><div class="stat-value">{{ completed.length }}<span class="tiny muted" style="margin-left: 7px">场</span></div><div class="stat-label">已完成面试</div></div><div class="stat"><div class="stat-value">{{ answers }}<span v-if="typeof answers === 'number'" class="tiny muted" style="margin-left: 7px">次</span></div><div class="stat-label">已提交回答</div></div><div class="stat"><div class="stat-value">{{ materialsUnavailable ? '未读取' : materials.length }}<span v-if="!materialsUnavailable" class="tiny muted" style="margin-left: 7px">份</span></div><div class="stat-label">保存的个人资料</div></div></div>
      <p v-if="recordsIncomplete" class="form-help">统计不完整：仅汇总已成功读取的面试记录。</p>
      <p v-if="missingAnswerCounts" class="form-help">{{ missingAnswerCounts }} 场记录未提供回答次数，已从回答次数统计中排除。</p>
      <div class="two-columns section">
        <section><div class="heading-row"><h2>最近的练习</h2><RouterLink to="/history" class="text-link">全部记录 <ArrowRight :size="14" /></RouterLink></div>
          <div class="panel" style="padding-top: 4px; padding-bottom: 4px" v-if="recent.length"><RouterLink v-for="s in recent" :key="s.key" :to="s.href" class="list-row"><div class="list-icon"><MessagesSquare :size="21" /></div><div class="list-body"><h3>{{ s.role }}</h3><p>{{ s.modeLabel }} · {{ dateTime(s.createdAt) }}<span v-if="s.mock"> · 测试</span></p></div><span class="badge" :class="s.done ? '' : 'badge-orange'">{{ s.statusLabel }}</span><ArrowUpRight :size="17" class="muted" /></RouterLink></div>
          <div v-else class="panel"><EmptyState title="第一场练习，从这里开始" description="完成面试后，你的对话和复盘会保存在这里。" /></div>
        </section>
        <section><div class="heading-row"><h2>我的资料</h2><RouterLink to="/materials" class="text-link">管理 <ArrowRight :size="14" /></RouterLink></div><div class="panel" style="padding-top: 4px; padding-bottom: 4px" v-if="materials.length"><RouterLink v-for="m in materials.slice(0, 3)" :key="m.id" :to="`/materials?kind=${m.kind}`" class="list-row"><div class="list-icon"><FileText :size="20" /></div><div class="list-body"><h3>{{ m.name }}</h3><p>{{ { RESUME: '简历', PROJECT: '项目', JD: '目标岗位' }[m.kind] }} · {{ m.documents.length }} 份附件</p></div><ArrowUpRight :size="16" class="muted" /></RouterLink></div><div class="panel" v-else><EmptyState title="让练习更贴近你" description="添加简历或项目，获得更有针对性的问题。"><RouterLink to="/materials" class="btn btn-secondary btn-sm">添加个人资料</RouterLink></EmptyState></div></section>
      </div>
      <div class="footer-note"><LockKeyhole :size="13" />资料与记录保存在本机 <span style="margin: 0 5px">·</span><Clock :size="13" />按自己的节奏，持续练习</div>
    </template>
  </div>
</template>

<style scoped>
.dashboard-page { max-width:1200px; }
.dashboard-page h1 { font-size:24px; }
.mode-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0; border-block:1px solid var(--border-subtle); }
.mode-card { display:grid; grid-template-columns:24px minmax(0,1fr) auto; align-items:center; column-gap:12px; padding:18px 16px; border:0; border-radius:0; background:var(--surface); }
.mode-card:nth-child(odd) { border-right:1px solid var(--border-subtle); }
.mode-card:nth-child(n+3) { border-top:1px solid var(--border-subtle); }
.mode-card:hover { background:var(--surface-soft); }
.mode-icon { grid-row:1 / 3; color:var(--text-secondary); }
.mode-card h3 { grid-column:2; font-size:14px; }
.mode-card p { grid-column:2; margin-top:4px; color:var(--text-muted); font-size:12px; }
.mode-card .card-arrow { grid-column:3; grid-row:1 / 3; display:flex; gap:6px; align-items:center; color:var(--accent); font-size:12px; }
.featured .mode-icon, .featured h3 { color:var(--accent); }
.dashboard-page .panel { border:0; border-radius:0; padding:0; }
@media(max-width:1100px) { .mode-card { grid-template-columns:24px minmax(0,1fr); }.mode-card .card-arrow { grid-column:2; grid-row:3; margin-top:8px; } }
@media(max-width:700px) { .mode-grid { grid-template-columns:1fr; }.mode-card:nth-child(odd) { border-right:0; }.mode-card + .mode-card { border-top:1px solid var(--border-subtle); }.mode-card { padding-inline:0; } }
</style>
