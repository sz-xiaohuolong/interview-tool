<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowUp, Square } from 'lucide-vue-next'
import { ApiError, errorMessage, request } from '../../api'
import { interviewNavigationKey } from '../../navigation'
import type { Material } from '../../types'
import Notice from '../../components/common/Notice.vue'
import SpeechInput from '../../components/interview/SpeechInput.vue'
import ProjectEvidenceMatrix from '../../components/material/ProjectEvidenceMatrix.vue'
import ConfigurationFields from '../../components/interview/InterviewConfigurationFields.vue'
import SourceCodeEvidence from '../../components/interview/SourceCodeEvidence.vue'
import ReportReview from '../../components/interview/InterviewReport.vue'
import KnowledgeSources from '../../components/interview/KnowledgeSources.vue'
import InterviewerAvatar from '../../components/interview/InterviewerAvatar.vue'
import CandidateAvatar from '../../components/interview/CandidateAvatar.vue'
import AiInterviewerVideoTile from '../../components/interview/AiInterviewerVideoTile.vue'
import CandidateVideoTile from '../../components/interview/CandidateVideoTile.vue'
import { displayDate, getSession, levelLabels, mutateSession, requestId, statusLabels } from '../../api/interviews'
import type { InterviewConfig, InterviewSession, MutationBody, ReviewFinding, SessionAction } from '../../models/interview'

const route = useRoute(), id = String(route.params.id)
const navigationContext = inject(interviewNavigationKey, null)
const session = ref<InterviewSession | null>(null), materials = ref<Material[]>([])
const state = reactive({ loading: true, error: '', pollError: '', busy: '' as SessionAction | '', sendingAnswer: false })
const correction = ref(''), editConfiguration = ref(false), clearJd = ref(false), answer = ref(''), openingProject = ref('')
const config = ref<InterviewConfig>({ role: '', level: '', resumeId: '', jdId: '', jd: '', durationMinutes: 10 })
const now = ref(Date.now()), answerStarted = ref(0), messagesPanel = ref<HTMLElement | null>(null)
const timingReceivedAt = ref(Date.now())
const speech = ref<InstanceType<typeof SpeechInput> | null>(null)
const quick = computed(() => session.value?.sessionType === 'QUICK')
const enhanced = computed(() => !quick.value && session.value?.experienceVersion === 1)
const remainingMs = computed(() => {
  const timing = session.value?.timing
  if (!timing?.enabled) return 0
  const passed = Math.max(0, now.value - timingReceivedAt.value)
  const waiting = Math.max(0, (timing.waitingUntil || 0) - timing.serverNow)
  const charged = timing.running ? passed : waiting > 0 ? Math.max(0, passed - waiting) : 0
  return Math.max(0, timing.remainingMs - charged)
})
const expired = computed(() => enhanced.value && !!session.value?.timing?.enabled && (session.value.timing.expired || remainingMs.value <= 0))
const clockLabel = computed(() => {
  const seconds = Math.ceil(remainingMs.value / 1000)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
})
const stageStatus = { NOT_STARTED: '未进行', IN_PROGRESS: '进行中', COMPLETED: '已完成', SKIPPED: '已跳过' }
const messages = computed(() => session.value?.messages || [])
const sources = computed(() => session.value?.sources || [])
const warningRecords = computed(() => {
  const records = new Map<string, number>()
  for (const warning of session.value?.warnings || []) {
    const message = warning.replaceAll('来源未获取', '本次未获取到参考文档').replaceAll('重新获取失败，使用此前有效缓存', '已使用本地缓存')
    records.set(message, (records.get(message) || 0) + 1)
  }
  return [...records].map(([message, count]) => ({ message, count }))
})
const currentQuestion = computed(() => [...messages.value].reverse().find(message => message.role === 'ASSISTANT' && message.kind !== 'REPLY'))
const hasStarted = computed(() => messages.value.some(message => message.role === 'ASSISTANT'))
const thinking = computed(() => state.sendingAnswer || session.value?.status === 'ANSWERING')
const waitingSeconds = computed(() => Math.max(0, Math.floor((now.value - answerStarted.value) / 1000)))
const beforeStart = computed(() => !!session.value && !hasStarted.value)
const editable = computed(() => !quick.value && beforeStart.value && ['READY', 'FAILED', 'INTERRUPTED'].includes(session.value?.status || ''))
const preparing = computed(() => !!session.value && !['READY', 'IN_PROGRESS', 'ANSWERING', 'REPORTING', 'REPORT_READY', 'COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED'].includes(session.value.status))
const reviewRunning = computed(() => session.value?.status === 'REPORTING')
const working = computed(() => preparing.value || thinking.value || reviewRunning.value)
const canAnswer = computed(() => session.value?.status === 'IN_PROGRESS' && !!currentQuestion.value?.questionId && !!answer.value.trim() && !state.busy)
const canSkip = computed(() => session.value?.status === 'INTERRUPTED' && session.value.allowedActions?.includes('next') === true)
const canSkipStage = computed(() => enhanced.value && !expired.value && session.value?.allowedActions?.includes('skip-stage') === true)
const nextStage = computed(() => {
  const stages = session.value?.stages || []
  const index = stages.findIndex(stage => stage.id === session.value?.stage)
  return index >= 0 ? stages[index + 1] : undefined
})
const canFinish = computed(() => hasStarted.value && (session.value?.allowedActions ? session.value.allowedActions.includes('finish') : ['IN_PROGRESS', 'COMPLETED'].includes(session.value?.status || '')))
const canRetry = computed(() => session.value?.allowedActions ? session.value.allowedActions.includes('retry') : ['FAILED', 'INTERRUPTED'].includes(session.value?.status || ''))
const summarySections = computed(() => [
  { title: '识别到的技术栈', items: session.value?.summary?.technicalStack || [] },
  { title: '个人核心职责', items: session.value?.summary?.responsibilities || [] },
  { title: '待澄清点', items: session.value?.summary?.clarifications || [] },
  { title: '本场考查方向', items: session.value?.summary?.focus || [] },
])
const stageIndex = computed(() => {
  const phase = session.value?.phase.toUpperCase() || ''
  if (/PLAN|QUESTION|准备问题/.test(phase)) return 3
  if (/RETRIEV|KNOWLEDGE|FETCH|获取|检索/.test(phase)) return 2
  if (/ANAL|RESUME|分析/.test(phase)) return 1
  return 0
})
const stageLabel = computed(() => reviewRunning.value ? '核对问答与来源，生成逐题复盘' : quick.value ? '正在准备岗位技术与工程场景问题' : ['排队等待', '分析简历与岗位', '检索相关知识', '准备本场题目'][stageIndex.value])
const callCount = computed(() => session.value?.usage?.calls ?? session.value?.usage?.attempts)
const hasTokenUsage = computed(() => typeof session.value?.usage?.promptTokens === 'number' || typeof session.value?.usage?.completionTokens === 'number')
const tokenCount = (value: number | undefined) => typeof value === 'number' ? value.toLocaleString('zh-CN') : '未返回'

let disposed = false, polling = false, pollTimer: ReturnType<typeof setTimeout> | undefined, clockTimer: ReturnType<typeof setInterval> | undefined
const pendingKey = `interview-v3:pending:${id}`, draftKey = `interview-v3:draft:${id}`
let pending: { action: SessionAction; fingerprint: string; body: MutationBody } | null = null
let draftQuestion = ''
let speechWrites = Promise.resolve()

function speechWaiting(waiting: boolean) {
  const value = session.value
  if (disposed || !enhanced.value || !value?.timing?.enabled || value.status !== 'IN_PROGRESS' || expired.value) return
  const questionId = currentQuestion.value?.questionId
  speechWrites = speechWrites.then(async () => {
    if (disposed || session.value?.status !== 'IN_PROGRESS' || currentQuestion.value?.questionId !== questionId) return
    const action = waiting ? 'transcription-wait' : 'transcription-resume'
    try {
      setSession(await mutateSession(id, action, { requestId: requestId(), expectedRevision: session.value.revision, expectedQuestionId: questionId }))
    } catch {
      // 通知失败后先恢复服务端版本，文字始终保留；时钟按服务端有界等待恢复。
      try { setSession(await getSession(id)) } catch { state.pollError = '暂未同步语音等待状态，文字已保留；请检查连接。' }
    }
  })
}

function store(key: string, value: unknown) {
  try { if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, JSON.stringify(value)) } catch { /* 存储不可用时仍保留当前页面草稿，服务端已提交记录不受影响。 */ }
}

function setSession(value: InterviewSession) {
  if (disposed || (session.value && value.revision < session.value.revision)) return
  if (session.value?.timing && value.timing && value.revision === session.value.revision && value.timing.serverNow < session.value.timing.serverNow) return
  timingReceivedAt.value = Date.now(); now.value = Date.now()
  session.value = value
  if (navigationContext) navigationContext.value = { id: value.id, sessionType: value.sessionType || 'COMPREHENSIVE' }
  if (value.status === 'ANSWERING' && !answerStarted.value) answerStarted.value = Date.now()
  if (value.status !== 'ANSWERING' && !state.sendingAnswer) answerStarted.value = 0
  const activeQuestion = [...(value.messages || [])].reverse().find(message => message.role === 'ASSISTANT' && message.kind !== 'REPLY')
  if (draftQuestion && activeQuestion?.questionId && activeQuestion.questionId !== draftQuestion) { answer.value = ''; draftQuestion = ''; store(draftKey, null) }
  if (!config.value.role) config.value = { role: value.role, level: value.level, resumeId: value.resumeId || '', jdId: value.jdId || '', jd: '', durationMinutes: value.durationMinutes || 10, interviewMode: value.interviewMode || 'TRAINING', timeLimitMinutes: value.timeLimitMinutes || 30, projectId: value.projectId || '', projectBrief: value.mainProject?.hypothetical ? '' : value.mainProject?.brief || '' }
}

function schedulePoll(delay?: number) {
  clearTimeout(pollTimer)
  const timedAnswer = session.value?.status === 'IN_PROGRESS' && session.value.timing?.enabled
  if (disposed || !session.value || (!working.value && !state.sendingAnswer && !timedAnswer)) return
  pollTimer = setTimeout(() => void load(), delay ?? (thinking.value ? 250 : timedAnswer ? 5000 : 1000))
}

async function load() {
  if (disposed || polling) return
  polling = true
  try { setSession(await getSession(id)); state.pollError = '' }
  catch (error) { if (!disposed) state.pollError = errorMessage(error) }
  finally { polling = false; state.loading = false; schedulePoll() }
}

function intent(action: SessionAction, extra: Record<string, unknown>) {
  const fingerprint = JSON.stringify({ action, extra })
  if (!pending || pending.action !== action || pending.fingerprint !== fingerprint) {
    pending = { action, fingerprint, body: { requestId: requestId(), expectedRevision: session.value!.revision, ...extra } }
    store(pendingKey, pending)
  }
  return pending.body
}

async function mutate(action: SessionAction, extra: Record<string, unknown> = {}) {
  if (!session.value || state.busy) return
  speech.value?.cancel()
  state.busy = action; state.error = ''
  await speechWrites
  if (disposed) return
  const body = intent(action, extra)
  try {
    let result: InterviewSession
    try { result = await mutateSession(id, action, body) }
    catch (error) {
      if (action !== 'skip-stage' || !(error instanceof ApiError) || error.status !== 409) throw error
      const latest = await getSession(id)
      setSession(latest)
      // 追问和用量可能刚更新版本；只重试原阶段一次，绝不顺带跳过新阶段。
      if (session.value?.stage !== extra.expectedStage || !session.value?.allowedActions?.includes('skip-stage')) throw error
      pending = null; store(pendingKey, null)
      result = await mutateSession(id, action, intent(action, extra))
    }
    setSession(result)
    pending = null; store(pendingKey, null)
    if (action === 'answers' || action === 'skip-stage') { answer.value = ''; draftQuestion = ''; store(draftKey, null) }
    if (action === 'corrections') { correction.value = ''; editConfiguration.value = false; clearJd.value = false }
  } catch (error) {
    state.error = errorMessage(error)
    if (error instanceof ApiError && error.status === 409) {
      pending = null; store(pendingKey, null)
      try { setSession(await getSession(id)); state.pollError = '' }
      catch (refreshError) { state.pollError = errorMessage(refreshError) }
    } else void load()
  } finally {
    state.busy = ''; state.sendingAnswer = false
    if (session.value?.status !== 'ANSWERING') answerStarted.value = 0
    schedulePoll()
  }
}

function submitAnswer() {
  if (!canAnswer.value) return
  state.sendingAnswer = true; answerStarted.value = Date.now(); now.value = Date.now()
  draftQuestion = currentQuestion.value!.questionId!
  schedulePoll(250)
  void mutate('answers', { expectedQuestionId: currentQuestion.value!.questionId, content: answer.value.trim() })
}

function confirm() {
  void mutate('confirm', enhanced.value && openingProject.value.trim() ? { projectBrief: openingProject.value.trim() } : {})
}

function skipStage() {
  if (!canSkipStage.value || state.busy) return
  const current = session.value?.stages?.find(stage => stage.id === session.value?.stage)?.label || '当前阶段'
  const target = nextStage.value ? `进入“${nextStage.value.label}”` : '结束本场问答'
  if (!window.confirm(`跳过“${current}”，${target}？已提交回答会保留，未提交草稿不会提交。`)) return
  void mutate('skip-stage', { expectedStage: session.value!.stage })
}

function correct() {
  if ((!correction.value.trim() && !editConfiguration.value) || !editable.value || state.busy) return
  const extra: Record<string, unknown> = { correction: correction.value.trim() || '已调整本场岗位或资料，请按新配置重新准备。' }
  if (editConfiguration.value) {
    extra.role = config.value.role; extra.level = config.value.level
    extra.resumeId = config.value.resumeId; extra.jdId = config.value.jdId
    if (enhanced.value) { extra.projectId = config.value.projectId || ''; extra.projectBrief = config.value.projectBrief || '' }
    if (config.value.jd.trim() || clearJd.value || (session.value?.jdId && !config.value.jdId)) extra.jd = clearJd.value ? '' : config.value.jd.trim()
  }
  void mutate('corrections', extra)
}

function sourceIds(finding: ReviewFinding) { return (finding.sourceIds || []).filter(sourceId => sources.value.some(source => source.id === sourceId)) }
function findingStatus(finding: ReviewFinding) {
  const status = finding.status.toUpperCase()
  if (['FACT_ERROR', 'INCORRECT', 'ERROR', 'CORRECT', 'SUPPORTED', 'VERIFIED'].includes(status) && !sourceIds(finding).length) return '待核实 · 依据不足'
  return ({ FACT_ERROR: '技术事实有误', INCORRECT: '技术事实有误', ERROR: '技术事实有误', CORRECT: '有依据支持', SUPPORTED: '有依据支持', VERIFIED: '已核实', ANSWER_GAP: '回答有待补充', GAP: '回答有待补充', INCOMPLETE: '回答有待补充', INSUFFICIENT: '待核实 · 依据不足', CONFLICT: '来源存在冲突', CONFLICTING: '来源存在冲突', UNCERTAIN: '待核实' } as Record<string, string>)[status] || '待核实'
}

watch(answer, value => {
  if (value) { draftQuestion = currentQuestion.value?.questionId || draftQuestion; store(draftKey, { questionId: draftQuestion, content: value }) }
  else store(draftKey, null)
})
watch(expired, value => { if (value) speech.value?.stopAndKeepDraft() }, { flush: 'sync' })
watch(() => messages.value.length, async () => {
  await nextTick()
  // 新问题与输入框同时就绪，避免平滑滚动期间仍停留在旧问题。
  if (messagesPanel.value) messagesPanel.value.scrollTop = messagesPanel.value.scrollHeight
})

onMounted(async () => {
  try {
    const rawPending = sessionStorage.getItem(pendingKey)
    if (rawPending) { const value = JSON.parse(rawPending); if (value?.body?.requestId && typeof value.fingerprint === 'string') pending = value }
    const rawDraft = sessionStorage.getItem(draftKey)
    if (rawDraft) { const value = JSON.parse(rawDraft); if (typeof value.content === 'string') { answer.value = value.content; draftQuestion = value.questionId || '' } }
  } catch { /* 损坏的本地草稿不影响读取服务端已保存的会话。 */ }
  clockTimer = setInterval(() => { if (thinking.value || session.value?.timing?.enabled) now.value = Date.now() }, 250)
  await load()
  if (!quick.value) void request<Material[]>('/materials').then(value => { if (!disposed) materials.value = value }).catch(() => {})
})
onUnmounted(() => { disposed = true; clearTimeout(pollTimer); clearInterval(clockTimer); if (navigationContext?.value?.id === id) navigationContext.value = null })
</script>

<template>
  <div class="page interview-page interview-session-page">
    <header class="page-heading"><nav class="interview-actions" :aria-label="quick ? '快速练习导航' : '综合面试导航'"><RouterLink :to="quick ? '/configure/quick' : '/interviews'" class="text-link">← {{ quick ? '快速练习' : '综合面试与记录' }}</RouterLink><RouterLink to="/" class="text-link">返回练习概览</RouterLink></nav><h1>{{ session?.role || '综合面试' }}</h1><p v-if="session">{{ levelLabels[session.level] || session.level }} · {{ quick ? '快速练习' : enhanced ? session.interviewMode === 'TIMED' ? `${session.timeLimitMinutes} 分钟计时实战` : '不限时训练' : `${session.durationMinutes} 分钟目标` }}<span v-if="session.plannedQuestionCount"> · {{ quick ? '' : '计划 ' }}{{ session.plannedQuestionCount }} 道主问题{{ quick ? '，追问另计' : '，实际轮数灵活调整' }}</span> · {{ displayDate(session.createdAt) }}</p></header>
    <Notice :message="state.error" kind="error" />
    <Notice :message="state.pollError" kind="warning" />
    <div v-if="state.pollError || state.error" class="interview-actions"><button class="btn btn-secondary" :disabled="state.busy !== ''" data-action="reload" @click="load">刷新已保存状态</button></div>
    <p v-if="state.loading" role="status" class="muted">正在恢复本场面试…</p>
    <template v-if="session">
      <SourceCodeEvidence v-if="!hasStarted" :source="session.sourceCode" />
      <div class="interview-meta"><span class="badge">{{ statusLabels[session.status] || '任务处理中' }}</span><span v-if="session.mock" class="badge badge-orange">Mock 测试 · 非真实模型评估</span><span class="tiny muted">离开或刷新后可继续查看</span></div>
      <section v-if="session.status === 'FAILED' || session.status === 'INTERRUPTED'" class="interview-recovery" aria-labelledby="interview-recovery-title">
        <Notice v-if="session.error" :message="session.error" kind="error" />
        <h2 id="interview-recovery-title">已保留输入与已提交的回答</h2><p class="form-help">恢复由你明确发起，不会自动重复调用模型。重试会计入本场调用上限。</p><div class="interview-actions"><button v-if="canRetry" class="btn btn-primary" :disabled="!!state.busy" data-action="retry" @click="mutate('retry')">{{ state.busy === 'retry' ? '正在恢复…' : '重试本次任务' }}</button><button v-if="session.allowedActions?.includes('continue-without-source')" class="btn btn-secondary" :disabled="!!state.busy" @click="mutate('continue-without-source')">仅使用项目资料继续</button><button v-if="canSkip" class="btn btn-secondary" :disabled="!!state.busy" data-action="next" @click="mutate('next')">跳至下一道准备问题</button><button class="btn btn-ghost" :disabled="!!state.busy" @click="load">刷新已保存状态</button></div>
      </section>
      <Notice v-else-if="session.error" :message="session.error" kind="error" />
      <details v-if="warningRecords.length" class="interview-session-notices"><summary><span>本场提示记录</span><span class="interview-notice-count">{{ warningRecords.length }} 项提示 · {{ session.warnings?.length }} 条记录</span></summary><ul><li v-for="warning in warningRecords" :key="warning.message"><span class="interview-notice-text">{{ warning.message }}</span><span v-if="warning.count > 1" class="interview-notice-count">记录 {{ warning.count }} 次</span></li></ul></details>
      <p v-if="!quick && !session.resumeId && session.report?.version !== 2" class="interview-note">本场未选择简历，不会推断你没有提供的项目经历。</p>

      <section v-if="enhanced && hasStarted && session.report?.version !== 2" class="panel interview-round-progress" aria-label="本场面试进度">
        <div class="heading-row"><h2>本场进度</h2><span v-if="session.timing?.enabled" class="interview-clock" data-interview-clock :class="{ urgent: remainingMs < 180000 }">剩余 {{ clockLabel }}</span><span v-else class="badge">不限时训练</span></div>
        <ol v-if="session.stages?.length" class="interview-rounds" aria-label="面试四阶段"><li v-for="item in session.stages" :key="item.id" :aria-current="session.stage === item.id && item.status === 'IN_PROGRESS' ? 'step' : undefined" :class="{ active: item.status === 'IN_PROGRESS', complete: item.status === 'COMPLETED' }"><strong>{{ item.label }}</strong><span>{{ stageStatus[item.status] }}</span></li></ol>
        <div v-if="canSkipStage" class="interview-actions"><button class="btn btn-ghost" :disabled="!!state.busy" data-action="skip-stage" @click="skipStage">{{ state.busy === 'skip-stage' ? '正在跳过…' : nextStage ? '跳过当前阶段' : '跳过反问，结束面试' }}</button><span v-if="nextStage" class="tiny muted">进入{{ nextStage.label }}，已有回答保留。</span></div>
        <p v-if="session.timing?.enabled && !expired" class="form-help">阅读、思考和作答计时；模型生成与系统等待不扣时。接近尾声会减少追问，为后续阶段留出时间。</p>
        <p v-if="expired && session.status === 'IN_PROGRESS'" class="interview-expired" role="status">时间已到，已停止新题与语音输入。草稿已保留，请校对最后一答后手动提交；也可直接结束面试。</p>
        <p v-if="session.report && session.stages?.some(item => item.status !== 'COMPLETED')" class="form-help">本场存在未完成阶段，仅表示本场未完成对应流程，不算答错。</p>
      </section>

      <section v-if="preparing || reviewRunning" class="panel interview-preparation" aria-labelledby="interview-progress-title" aria-live="polite" :data-phase="session.phase">
        <div class="heading-row"><h2 id="interview-progress-title">{{ reviewRunning ? '正在查证复盘' : quick ? '正在准备本场问题' : '正在为你深度准备' }}</h2><span class="spinner" aria-hidden="true"></span></div>
        <p role="status">{{ stageLabel }}</p><p class="tiny muted">{{ session.phase }}</p>
        <ol v-if="preparing && !quick" class="interview-steps" aria-label="准备阶段"><li v-for="(label, index) in ['排队', '分析简历与岗位', '检索知识', '准备题目']" :key="label" :aria-current="index === stageIndex ? 'step' : undefined" :class="{ active: index === stageIndex, passed: index < stageIndex }"><span>{{ index + 1 }}</span>{{ label }}</li></ol>
        <p class="tiny muted">{{ reviewRunning ? '通常约 3 分钟。逐题核实依据，缺失或冲突会明确标记。' : quick ? '题目准备好后直接开始。可以离开页面，稍后从统一历史继续。' : '通常需要 1～3 分钟。任务在后台执行，可以先离开，回来后从记录继续。' }}</p>
        <div class="interview-actions"><RouterLink to="/history" class="btn btn-secondary">返回记录，稍后再看</RouterLink><button class="btn btn-ghost" :disabled="!!state.busy" @click="mutate('cancel')">取消本场任务</button></div>
      </section>

      <section v-if="session.summary && Object.keys(session.summary).length > 0 && editable" class="panel interview-summary" aria-labelledby="interview-summary-title">
        <div class="heading-row"><h2 id="interview-summary-title">开考前，确认我们理解了你。</h2><span class="badge">仅用于本场</span></div>
        <p class="muted">重点确认个人职责与待澄清点；这里不会提前展示面试题和参考答案。</p>
        <div v-if="enhanced && session.mainProject" class="interview-main-project"><h3>本场主项目：{{ session.mainProject.name }}</h3><p>{{ session.mainProject.brief || '尚未提供真实项目，可在下方补充后重新准备。' }}</p><p v-if="session.mainProject.hypothetical" class="form-help">本场暂用假设场景；没有真实项目时不生成经历证据矩阵。</p></div>
        <div v-if="enhanced && session.mainProject?.hypothetical" class="form-field"><label for="interview-opening-project">开场前简述一个真实项目 <span class="muted">（可选）</span></label><textarea id="interview-opening-project" v-model="openingProject" maxlength="5000" :disabled="!!state.busy" placeholder="例如：我参与订单系统，负责支付回调与幂等处理。"></textarea><p class="form-help">随确认开考保存，面试从澄清你的实际职责开始；留空则继续假设场景。</p></div>
        <div class="interview-summary-grid"><section v-for="part in summarySections" :key="part.title"><h3>{{ part.title }}</h3><ul v-if="part.items.length" class="reading-list"><li v-for="(item, index) in part.items" :key="index">{{ item }}</li></ul><p v-else class="tiny muted">暂无明确内容，面试中进一步澄清。</p></section></div>
        <div v-if="session.status === 'READY'" class="interview-actions"><button class="btn btn-primary" :disabled="!!state.busy" data-action="confirm" @click="confirm">{{ state.busy === 'confirm' ? '正在开考…' : '确认无误，直接开始' }}</button><button class="btn btn-secondary" :disabled="!!state.busy" data-action="refresh" @click="mutate('refresh')">刷新知识并重新准备</button></div>
      </section>

      <section v-if="editable" class="panel interview-correction" aria-labelledby="interview-correction-title">
        <h2 id="interview-correction-title">纠正本场理解</h2><p class="form-help">例如：“架构由同事设计，我负责接入和故障处理”。更正会重新准备，只影响本场，不修改原简历。</p>
        <label for="interview-correction">需要纠正或补充的事实</label><textarea id="interview-correction" v-model="correction" maxlength="5000" :disabled="!!state.busy" placeholder="指出职责、业务背景或技能理解中的偏差。"></textarea>
        <label class="interview-check"><input v-model="editConfiguration" type="checkbox" :disabled="!!state.busy" />同时更换本场岗位、简历或 JD<span v-if="enhanced">、主项目</span></label>
        <template v-if="editConfiguration"><ConfigurationFields v-model="config" :materials="materials" :disabled="!!state.busy" :legacy="!enhanced" correction /><label class="interview-check"><input v-model="clearJd" type="checkbox" :disabled="!!state.busy" />清除本场补充 JD</label></template>
        <button class="btn btn-secondary" data-action="correct" :disabled="!!state.busy || (!correction.trim() && !editConfiguration) || (editConfiguration && (!config.role || !config.level))" @click="correct">{{ state.busy === 'corrections' ? '正在保存更正…' : '保存更正，重新准备' }}</button>
      </section>

      <ReportReview v-if="session.report?.version === 2" :session="session" />
      <div class="interview-workspace" :class="{ 'is-live': hasStarted && !session.report }">
        <!-- macOS 风格沉浸式模拟面试窗口栏 (复刻图2) -->
        <div v-if="hasStarted && !session.report" class="immersive-window-bar">
          <div class="window-left">
            <div class="window-dots" aria-hidden="true">
              <span class="window-dot dot-red" />
              <span class="window-dot dot-yellow" />
              <span class="window-dot dot-green" />
            </div>
            <h2 class="window-title">沉浸式模拟面试 - {{ session.role }}</h2>
          </div>
          <div class="window-right">
            <span v-if="session.timing?.enabled" class="interview-clock window-clock" data-interview-clock :class="{ urgent: remainingMs < 180000 }">
              剩余 {{ clockLabel }}
            </span>
            <span v-else class="window-mode-badge">不限时训练</span>
            <div v-if="canFinish" class="interview-actions interview-finish header-finish">
              <button class="btn btn-secondary btn-sm finish-btn" :disabled="!!state.busy" data-action="finish" @click="mutate('finish')">
                {{ state.busy === 'finish' ? '正在创建复盘任务…' : session.status === 'COMPLETED' ? '生成查证复盘' : '结束面试，查证复盘' }}
              </button>
            </div>
          </div>
        </div>

        <div class="workspace-columns">
          <section v-if="hasStarted && session.report?.version !== 2" class="panel chat-panel interview-dialogue" aria-labelledby="interview-dialogue-title">
            <header class="chat-header">
              <h2 id="interview-dialogue-title">{{ session.report ? '本场原始问答' : quick ? '围绕技术，讲清思路' : '围绕经历，逐层深入' }}</h2>
              <span class="tiny muted">每道主问题最多追问两次</span>
            </header>
            <div ref="messagesPanel" class="chat-messages interview-messages" role="log" aria-label="面试对话" aria-live="polite">
              <article v-for="message in messages" :key="message.id" class="message" :class="{ user: message.role === 'USER', assistant: message.role === 'ASSISTANT' }">
                <span class="avatar" aria-hidden="true">
                  <CandidateAvatar v-if="message.role === 'USER'" />
                  <InterviewerAvatar v-else />
                </span>
                <div class="message-body">
                  <p class="message-meta">
                    <span>{{ message.role === 'USER' ? '你的回答' : message.kind === 'REPLY' ? '面试官回应' : '面试官' }}</span>
                    <span v-if="message.fallback" class="badge badge-orange">切换为已准备的相关备用问题</span>
                  </p>
                  <div class="message-content">{{ message.content }}</div>
                  <p v-if="message.hasCodeEvidence" class="tiny muted">本题有源码依据 · 复盘中查看</p>
                </div>
              </article>
              <div v-if="thinking" class="interview-thinking" data-thinking role="status" aria-live="polite">
                <span class="avatar thinking-avatar" aria-hidden="true">
                  <InterviewerAvatar />
                </span>
                <div class="thinking-body">
                  <div class="thinking-header">
                    <span class="spinner" aria-hidden="true"></span>
                    <strong>面试官思考中</strong>
                  </div>
                  <p class="tiny">{{ state.sendingAnswer ? '正在保存并处理本次回答，请勿重复提交。' : '回答已保存，正在针对你的表述组织追问。' }}<span v-if="waitingSeconds"> 已等待 {{ waitingSeconds }} 秒。</span></p>
                  <template v-if="waitingSeconds >= 8 && !state.sendingAnswer">
                    <p class="tiny">仍在生成追问，回答已保存。页面会自动更新，也可刷新查看最新状态。</p>
                    <button class="btn btn-secondary btn-sm" :disabled="!!state.busy" data-action="reload" @click="load">刷新已保存状态</button>
                  </template>
                </div>
              </div>
            </div>
            <form v-if="['IN_PROGRESS', 'ANSWERING'].includes(session.status) || state.sendingAnswer" class="composer modern-composer" @submit.prevent="submitAnswer">
              <div class="composer-pill-card">
                <label for="interview-answer" class="sr-only">你的回答</label>
                <textarea id="interview-answer" v-model="answer" maxlength="12000" rows="2" :disabled="session.status !== 'IN_PROGRESS' || !!state.busy" placeholder="请输入您的回答（结合实际负责的部分，说明场景、决策、异常和边界）…" @keydown.ctrl.enter.prevent="submitAnswer" @keydown.meta.enter.prevent="submitAnswer"></textarea>
                <div class="composer-action-area">
                  <SpeechInput ref="speech" v-model="answer" :context-key="currentQuestion?.questionId" :max-length="12000" :disabled="session.status !== 'IN_PROGRESS' || !!state.busy || expired" @waiting="speechWaiting" />
                  <button class="composer-send-btn" type="button" data-action="answer" :disabled="!canAnswer" :aria-label="state.sendingAnswer ? '正在提交…' : '发送回答'" :title="canAnswer ? '发送回答 (Ctrl / ⌘ + Enter)' : '请输入回答'" @click="submitAnswer">
                    <span v-if="state.sendingAnswer" class="spinner composer-spinner" />
                    <ArrowUp v-else :size="18" stroke-width="2.5" />
                  </button>
                </div>
              </div>
              <div class="composer-footer"><span class="tiny muted">Ctrl / ⌘ + Enter 提交 · Shift + Enter 换行 · 已提交记录自动保存</span></div>
            </form>
          </section>

          <div v-if="canFinish && (!hasStarted || session.report)" class="interview-actions interview-finish"><button class="btn btn-secondary" :disabled="!!state.busy" data-action="finish" @click="mutate('finish')">{{ state.busy === 'finish' ? '正在创建复盘任务…' : session.status === 'COMPLETED' ? '生成查证复盘' : '结束面试，查证复盘' }}</button><span class="tiny muted">只评价实际问过且已回答的内容。</span></div>
          <p v-if="session.status === 'CANCELLED'" class="panel">本场已取消，已保存的输入与问答仍可在这里查看。</p>

          <section v-if="session.report && session.report.version !== 2" class="interview-report" aria-labelledby="interview-report-title"><div class="heading-row"><h2 id="interview-report-title">有依据的逐题复盘</h2><span class="badge">保留不同合理方案</span></div><p class="panel interview-report-summary">{{ session.report.summary }}</p><article v-for="(finding, index) in session.report.findings || []" :key="`${finding.questionId}-${index}`" class="panel interview-finding"><h3>{{ messages.find(message => message.role === 'ASSISTANT' && message.questionId === finding.questionId)?.content || `已答问题 ${index + 1}` }}</h3><span class="badge" data-finding-status>{{ findingStatus(finding) }}</span><p>{{ finding.assessment }}</p><p v-if="finding.sourceQuote && sourceIds(finding).length" class="interview-source-quote" data-source-quote><strong>核对原文：</strong>{{ finding.sourceQuote }}</p><p v-if="finding.suggestion"><strong>改进建议：</strong>{{ finding.suggestion }}</p><div v-if="sourceIds(finding).length" class="interview-actions"><span class="tiny muted">具体依据：</span><a v-for="sourceId in sourceIds(finding)" :key="sourceId" :href="`#interview-source-${sourceId}`" class="text-link">{{ sources.find(source => source.id === sourceId)?.title }}</a></div><p v-else class="tiny muted">没有可回查的技术来源时，不作为已核实的技术判错。</p></article><p v-if="!session.report.findings?.length" class="muted">样本不足，未生成逐题能力判断。</p></section>

          <ProjectEvidenceMatrix v-if="enhanced && session.report?.matrix && session.report.version !== 2" :matrix="session.report.matrix" source-kind="V3" :source-id="session.id" />
          
          <aside class="interview-inspector" aria-label="面试上下文与依据">
            <!-- 右侧双人视频席位 (复刻图2) -->
            <div v-if="hasStarted && !session.report" class="video-participants-grid">
              <AiInterviewerVideoTile :thinking="thinking" />
              <CandidateVideoTile />
            </div>

            <template v-if="hasStarted && !session.report">
              <div class="inspector-card">
                <div class="inspector-heading"><h2>本场上下文</h2><span class="tiny muted">只读</span></div>
                <section class="inspector-context"><h3>{{ session.role }}</h3><p class="form-help">{{ levelLabels[session.level] || session.level }} · {{ quick ? '快速练习' : '综合面试' }}</p><dl class="context-data"><div><dt>已提交回答</dt><dd>{{ messages.filter(m => m.role === 'USER').length }}</dd></div><div v-if="session.plannedQuestionCount"><dt>计划主问题</dt><dd>{{ session.plannedQuestionCount }}</dd></div></dl><template v-if="session.mainProject"><h3>{{ session.mainProject.name }}</h3><p>{{ session.mainProject.brief }}</p></template><p class="form-help">正式作答时不展示参考答案。完成复盘后，可回查回答与技术依据。</p></section>
                <SourceCodeEvidence :source="session.sourceCode" />
              </div>
            </template>
            <KnowledgeSources v-if="sources.length || session.agentTopics?.length || session.knowledgeRetrieval?.length" :sources="sources" :retrieval="session.knowledgeRetrieval" :agent-topics="session.agentTopics" :planned-question-count="session.plannedQuestionCount" />
          </aside>
        </div>
      </div>
      <footer class="footer-note mono"><span v-if="callCount !== undefined">本场模型尝试 {{ callCount }}<template v-if="session.usage?.maxCalls"> / {{ session.usage.maxCalls }}</template> 次。</span><span v-if="hasTokenUsage" data-token-usage>已统计 Token：输入 {{ tokenCount(session.usage?.promptTokens) }} · 输出 {{ tokenCount(session.usage?.completionTokens) }} · 合计 {{ tokenCount(session.usage?.totalTokens) }}<template v-if="session.usage?.usageIncomplete">；部分调用未返回用量，统计不完整。</template></span><span v-else data-token-usage>暂未取得 Token 用量。</span></footer>
    </template>
  </div>
</template>

<style scoped>
.interview-page .btn { min-height: 32px; }
.interview-page .panel { margin-bottom: 16px; }
.interview-page :is(button, a, summary, textarea, input):focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.interview-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 20px; margin: 18px 0; }
.interview-actions .text-link { display: inline-flex; align-items: center; min-height: 32px; }
.interview-page .page-heading .interview-actions { margin: 0 0 14px; }
.interview-meta { display: flex; flex-wrap: wrap; gap: 10px 16px; align-items: center; margin-bottom: 20px; }
.interview-session-notices { margin-bottom: 16px; border-block: 1px solid var(--border-subtle); color: var(--text-secondary); font-size: 13px; line-height: 1.8; }
.interview-session-notices summary { min-height: 32px; padding-block: 8px; cursor: pointer; overflow-wrap: anywhere; }
.interview-session-notices summary:hover { color: var(--accent-ink); }
.interview-session-notices summary > span + span { margin-left: 12px; }
.interview-notice-count { color: var(--text-muted); font-size: 14px; font-variant-numeric: tabular-nums; }
.interview-session-notices ul { list-style: none; margin: 0; padding: 0 0 12px; }
.interview-session-notices li { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 16px; padding-block: 12px; }
.interview-session-notices li + li { border-top: 1px solid var(--border-subtle); }
.interview-notice-text { min-width: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.interview-recovery { margin-bottom: 16px; }
.interview-recovery h2 { font-size: 14px; line-height: 1.6; }
.interview-recovery .form-help { font-size: 14px; }
.interview-recovery .interview-actions { margin-bottom: 0; }
.interview-recovery :deep(.notice) { font-size: 13px; }
.interview-note { color: var(--text-muted); font-size: 13px; margin-bottom: 20px; }
.interview-preparation > p { margin-top: 12px; }
.interview-steps { padding: 0; list-style: none; display: flex; gap: 12px; margin-block: 24px; flex-wrap: wrap; }
.interview-steps li { flex: 1 1 150px; min-height: 60px; display: flex; align-items: center; gap: 10px; padding: 12px; background: var(--surface-soft); color: var(--text-muted); border: 1px solid var(--border-subtle); border-radius: var(--radius-control); font-size: 13px; }
.interview-steps li.active { color: var(--accent-ink); border-color: var(--accent); background: var(--accent-soft); }
.interview-steps li.passed { color: var(--text-primary); }
.interview-steps li span { font-variant-numeric: tabular-nums; }
.interview-summary > p { margin-bottom: 16px; }
.interview-summary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px 32px; }
.interview-summary-grid h3 { margin-bottom: 10px; font-size: 13px; }
.interview-summary-grid section { min-width: 0; }
.interview-main-project { margin-block: 22px; padding: 18px; background: var(--surface-soft); border-left: 1px solid var(--accent); }
.interview-main-project > p { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 10px; }
.interview-clock { font-size: 21px; font-weight: 600; font-variant-numeric: tabular-nums; }
.interview-clock.urgent { color: var(--accent-ink); }
.interview-rounds { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; list-style: none; padding: 0; margin: 20px 0 12px; }
.interview-rounds li { display: flex; flex-direction: column; gap: 6px; padding: 12px; border-top: 1px solid var(--border-subtle); color: var(--text-muted); }
.interview-rounds li.active { border-color: var(--accent); background: var(--accent-soft); color: var(--accent-ink); }
.interview-rounds li.complete { color: var(--text-primary); }
.interview-rounds strong { font-size: 14px; line-height: 1.6; }
.interview-rounds span { font-size: 13px; }
.interview-expired { line-height: 1.8; padding: 14px; background: var(--accent-soft); color: var(--accent-ink); }
@media (max-width: 700px) { .interview-rounds { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; } }
.interview-correction h2 + p { margin: 12px 0 20px; }
.interview-check { display: flex; align-items: center; gap: 10px; min-height: 32px; margin: 14px 0; font-weight: 400; }
.interview-check input { width: 18px; height: 18px; accent-color: var(--accent); }
.interview-dialogue { padding: 0; }
.interview-dialogue .chat-header { padding: 20px 24px; border-bottom: 1px solid var(--border-subtle); }
.interview-dialogue h2 { font-size: 14px; }
.interview-dialogue .chat-header > .tiny, .interview-dialogue .message-meta { font-size: 14px; line-height: 1.8; }
.interview-dialogue .message > div { min-width: 0; }
.interview-dialogue .message-content { font-size: 14px; line-height: 1.8; max-width: 72ch; }
.interview-messages { max-height: 65dvh; min-height: 120px; scroll-behavior: auto; }
.interview-thinking { display: flex; align-items: flex-start; gap: 12px; padding: 18px 24px; background: var(--accent-soft); color: var(--accent-ink); border-top: 1px solid var(--border-subtle); }
.interview-thinking strong { font-size: 14px; }.interview-thinking p { margin-top: 6px; }
.interview-thinking .spinner { margin-top: 3px; flex: 0 0 auto; }
.interview-thinking .btn { margin-top: 14px; }
.interview-page .composer label { font-size: 14px; line-height: 1.8; }
.interview-page .composer textarea { min-height: 88px; font-size: 14px; line-height: 1.8; }
.interview-page .composer textarea::placeholder { font-size: 13px; }
.interview-page .composer .btn { font-size: 13px; }
.interview-page .composer-footer .tiny, .interview-finish .tiny, .interview-dialogue .interview-thinking .tiny { font-size: 14px; line-height: 1.8; }
.interview-page .composer :deep(.speech-input .form-help) { font-size: 14px; line-height: 1.8; }
.interview-page .composer :deep(.speech-input .btn) { font-size: 13px; }
.interview-finish { margin-bottom: 28px; }
.interview-report-summary, .interview-finding p { white-space: pre-wrap; overflow-wrap: anywhere; }
.interview-finding h3 { font-size: 17px; line-height: 1.7; margin-bottom: 14px; }
.interview-finding p { margin-top: 14px; max-width: 80ch; }
.interview-source-quote { border-left: 2px solid var(--accent); padding-left: 14px; color: var(--text-secondary); }
.interview-finding .interview-actions { margin-bottom: 0; }
@media (max-width: 700px) { .interview-summary-grid { grid-template-columns: minmax(0, 1fr); }.interview-dialogue .chat-header { padding: 16px; }.interview-thinking { padding: 16px; }.interview-steps { gap: 8px; }.interview-steps li { flex-basis: 120px; } }

.interview-session-page { max-width:none; padding:0 24px 20px; }
.interview-session-page .page-heading { display:grid; grid-template-columns:1fr auto; gap:4px 16px; padding:12px 0; border-bottom:1px solid var(--border-subtle); margin-bottom:12px; }
.interview-session-page .page-heading h1 { font-size:18px; grid-row:1; }
.interview-session-page .page-heading .interview-actions { grid-column:2; grid-row:1; margin:0; }
.interview-session-page .page-heading p { grid-column:1/-1; margin:0; font-size:12px; }
.interview-meta { margin-bottom:12px; }.interview-meta .badge { padding:2px 6px; }
.interview-round-progress { border:0; border-bottom:1px solid var(--border-subtle); border-radius:0; padding:0 0 8px; }
.interview-round-progress .heading-row { margin:0; }.interview-round-progress h2 { font-size:12px; }
.interview-rounds { margin:8px 0; gap:8px; }.interview-rounds li { flex-direction:row; justify-content:space-between; padding:8px; }.interview-rounds strong, .interview-rounds span { font-size:12px; }
.interview-round-progress .interview-actions { margin:0; }.interview-clock { font:600 14px var(--font-mono); }

/* 沉浸式模拟面试工作台 (复刻图2布局与大字号) */
.interview-workspace.is-live {
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border: 1px solid var(--border-subtle);
  border-radius: 16px;
  overflow: clip;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.05);
  margin-top: 8px;
}

/* macOS 风格窗口顶部条 */
.immersive-window-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: #ffffff;
  border-bottom: 1px solid var(--border-subtle);
}

.window-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.window-dots {
  display: flex;
  align-items: center;
  gap: 7px;
}

.window-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  display: inline-block;
}

.dot-red { background: #ff5f56; }
.dot-yellow { background: #ffbd2e; }
.dot-green { background: #27c93f; }

.window-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: #0f172a;
  letter-spacing: -0.015em;
}

.window-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.window-clock {
  font-size: 13px;
  font-weight: 600;
  font-family: var(--font-mono, monospace);
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--surface-soft);
  color: var(--text-primary);
}

.window-clock.urgent {
  color: #ef4444;
  background: #fef2f2;
}

.window-mode-badge {
  font-size: 12px;
  color: var(--text-muted);
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--surface-soft);
}

.header-finish {
  margin: 0;
}

.finish-btn {
  font-size: 13px;
  font-weight: 500;
  border-radius: 8px;
}

/* 7:3 核心布局比例划分 */
.is-live .workspace-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  min-height: calc(100dvh - 220px);
  background: #ffffff;
}

@media (min-width: 1280px) {
  .is-live .workspace-columns {
    grid-template-columns: minmax(0, 1fr) 320px;
  }
}

/* 左侧对话流栏 */
.is-live .interview-dialogue {
  margin: 0;
  border: 0;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  min-width: 0;
}

.interview-dialogue .chat-header {
  padding: 12px 24px;
  min-height: 44px;
  border-bottom: 1px solid var(--border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.interview-dialogue h2 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
}

.is-live .interview-messages {
  flex: 1 1 auto;
  min-height: 420px;
  height: clamp(420px, calc(100dvh - 360px), 720px);
  max-height: none;
  overflow-y: auto;
  padding: 24px 28px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/* 消息项与大字号样式 */
.is-live .message {
  display: flex;
  gap: 14px;
  max-width: 88%;
  min-width: 0;
  align-items: flex-start;
}

.is-live .message.assistant {
  align-self: flex-start;
}

.is-live .message.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.is-live .message .avatar {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 50%;
  overflow: hidden;
  background: transparent;
  display: grid;
  place-items: center;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.is-live .message-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.is-live .message.user .message-body {
  align-items: flex-end;
}

.is-live .message-meta {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.is-live .message-content {
  font-size: 16px;
  line-height: 1.8;
  color: #0f172a;
  text-wrap: pretty;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

/* 候选人作答气泡卡片 (复刻图2气泡质感) */
.is-live .message.user .message-content {
  background: #f1f5f9;
  border-radius: 18px 4px 18px 18px;
  padding: 16px 20px;
  color: #1e293b;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

/* 思考中状态 */
.is-live .interview-thinking {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 16px 20px;
  border-radius: 14px;
  background: var(--surface-soft);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
  max-width: 75%;
}

.is-live .thinking-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
}

.is-live .thinking-content {
  min-width: 0;
}

.is-live .thinking-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--accent-ink);
}

/* 底部现代胶囊输入栏 (复刻图2) */
.is-live .modern-composer {
  padding: 12px 24px 18px;
  border-top: 1px solid var(--border-subtle);
  background: #ffffff;
}

.composer-pill-card {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 10px 14px 10px 18px;
  border: 1px solid var(--border-control);
  border-radius: 24px;
  background: #ffffff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.composer-pill-card:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.composer-pill-card textarea {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  padding: 6px 0;
  font-size: 15px;
  line-height: 1.6;
  resize: none;
  max-height: 160px;
  field-sizing: content;
}

.composer-pill-card textarea::placeholder {
  color: #94a3b8;
  font-size: 14px;
}

.composer-action-area {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-bottom: 2px;
}

.composer-send-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: #0f172a;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.composer-send-btn:hover:not(:disabled) {
  background: #334155;
  transform: scale(1.05);
}

.composer-send-btn:disabled {
  background: #e2e8f0;
  color: #94a3b8;
  cursor: not-allowed;
}

.composer-spinner {
  width: 14px;
  height: 14px;
  border-width: 2px;
}

.is-live .composer-footer {
  margin-top: 8px;
  padding: 0 10px;
  display: flex;
  justify-content: space-between;
  color: var(--text-muted);
  font-size: 12px;
}

/* 右侧视频与面板栏 (~28%) */
.is-live .interview-inspector {
  border-left: 1px solid var(--border-subtle);
  background: #f8fafc;
  padding: 16px;
  max-height: calc(100dvh - 220px);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.video-participants-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.inspector-card {
  background: #ffffff;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  overflow: hidden;
}

.inspector-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 40px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border-subtle);
}

.inspector-heading h2 {
  font-size: 13px;
  font-weight: 600;
}

.inspector-context {
  padding: 14px;
}

.inspector-context p {
  font-size: 13px;
}

.context-data {
  display: flex;
  gap: 24px;
  margin: 12px 0;
  padding-block: 10px;
  border-block: 1px solid var(--border-subtle);
}

.context-data dt {
  font-size: 12px;
  color: var(--text-muted);
}

.context-data dd {
  margin: 4px 0 0;
  font: 600 18px var(--font-mono, monospace);
}

.interview-inspector :deep(.source-code) {
  margin: 0;
  border: 0;
  border-top: 1px solid var(--border-subtle);
  border-radius: 0;
  background: transparent;
}

.interview-inspector :deep(.interview-sources) {
  margin: 0;
  padding: 0;
  border: 0;
}

.interview-inspector :deep(.interview-sources-disclosure) {
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: #ffffff;
  overflow: hidden;
}

@media (max-width: 1000px) {
  .is-live .workspace-columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .is-live .interview-inspector {
    border-left: 0;
    border-top: 1px solid var(--border-subtle);
    max-height: none;
  }
  .interview-session-page .page-heading {
    display: block;
  }
  .interview-session-page .page-heading h1 {
    margin: 8px 0 4px;
  }
}
@media(max-width:600px) { .interview-session-page { padding:0 12px 20px; }.interview-page .btn,.interview-actions .text-link { min-height:44px; }.interview-rounds { grid-template-columns:repeat(2,minmax(0,1fr)); }.interview-dialogue .chat-header { flex-wrap:wrap; }.interview-page .composer textarea { font-size:16px; }.interview-rounds li { flex-wrap:wrap; } }
</style>
