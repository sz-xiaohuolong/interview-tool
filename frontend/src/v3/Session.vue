<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, errorMessage, request } from '../api'
import type { Material } from '../types'
import Notice from '../components/Notice.vue'
import SpeechInput from '../components/SpeechInput.vue'
import ProjectEvidenceMatrix from '../components/ProjectEvidenceMatrix.vue'
import ConfigurationFields from './ConfigurationFields.vue'
import ReportReview from './ReportReview.vue'
import { displayDate, getSession, levelLabels, mutateSession, publicSourceUrl, requestId, statusLabels } from './api'
import type { InterviewConfig, InterviewSession, MutationBody, ReviewFinding, SessionAction } from './types'

const route = useRoute(), id = String(route.params.id)
const session = ref<InterviewSession | null>(null), materials = ref<Material[]>([])
const state = reactive({ loading: true, error: '', pollError: '', busy: '' as SessionAction | '', sendingAnswer: false })
const correction = ref(''), editConfiguration = ref(false), clearJd = ref(false), answer = ref(''), openingProject = ref('')
const config = ref<InterviewConfig>({ role: '', level: '', resumeId: '', jdId: '', jd: '', durationMinutes: 10 })
const now = ref(Date.now()), answerStarted = ref(0), messagesPanel = ref<HTMLElement | null>(null)
const timingReceivedAt = ref(Date.now())
const speech = ref<InstanceType<typeof SpeechInput> | null>(null)
const enhanced = computed(() => session.value?.experienceVersion === 1)
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
const stageStatus = { NOT_STARTED: '未进行', IN_PROGRESS: '进行中', COMPLETED: '已完成' }
const messages = computed(() => session.value?.messages || [])
const sources = computed(() => session.value?.sources || [])
const currentQuestion = computed(() => [...messages.value].reverse().find(message => message.role === 'ASSISTANT' && message.kind !== 'REPLY'))
const hasStarted = computed(() => messages.value.some(message => message.role === 'ASSISTANT'))
const thinking = computed(() => state.sendingAnswer || session.value?.status === 'ANSWERING')
const waitingSeconds = computed(() => Math.max(0, Math.floor((now.value - answerStarted.value) / 1000)))
const beforeStart = computed(() => !!session.value && !hasStarted.value)
const editable = computed(() => beforeStart.value && ['READY', 'FAILED', 'INTERRUPTED'].includes(session.value?.status || ''))
const preparing = computed(() => !!session.value && !['READY', 'IN_PROGRESS', 'ANSWERING', 'REPORTING', 'REPORT_READY', 'COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED'].includes(session.value.status))
const reviewRunning = computed(() => session.value?.status === 'REPORTING')
const working = computed(() => preparing.value || thinking.value || reviewRunning.value)
const canAnswer = computed(() => session.value?.status === 'IN_PROGRESS' && !!currentQuestion.value?.questionId && !!answer.value.trim() && !state.busy)
const canSkip = computed(() => session.value?.status === 'INTERRUPTED' && session.value.allowedActions?.includes('next') === true)
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
const stageLabel = computed(() => reviewRunning.value ? '核对问答与来源，生成逐题复盘' : ['排队等待', '分析简历与岗位', '检索相关知识', '准备本场题目'][stageIndex.value])
const callCount = computed(() => session.value?.usage?.calls ?? session.value?.usage?.attempts)

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
    setSession(await mutateSession(id, action, body))
    pending = null; store(pendingKey, null)
    if (action === 'answers') { answer.value = ''; draftQuestion = ''; store(draftKey, null) }
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
  void request<Material[]>('/materials').then(value => { if (!disposed) materials.value = value }).catch(() => {})
  await load()
})
onUnmounted(() => { disposed = true; clearTimeout(pollTimer); clearInterval(clockTimer) })
</script>

<template>
  <div class="page v3-page">
    <header class="page-heading"><nav class="v3-actions" aria-label="综合面试导航"><RouterLink to="/interview-v3" class="text-link">← 综合面试与记录</RouterLink><RouterLink to="/" class="text-link">返回练习概览</RouterLink></nav><h1>{{ session?.role || '综合面试' }}</h1><p v-if="session">{{ levelLabels[session.level] || session.level }} · {{ enhanced ? session.interviewMode === 'TIMED' ? `${session.timeLimitMinutes} 分钟计时实战` : '不限时训练' : `${session.durationMinutes} 分钟目标` }}<span v-if="session.plannedQuestionCount"> · 计划 {{ session.plannedQuestionCount }} 道主问题，实际轮数灵活调整</span> · {{ displayDate(session.createdAt) }}</p></header>
    <Notice :message="state.error" kind="error" />
    <Notice :message="state.pollError" kind="warning" />
    <div v-if="state.pollError || state.error" class="v3-actions"><button class="btn btn-secondary" :disabled="state.busy !== ''" data-action="reload" @click="load">刷新已保存状态</button></div>
    <p v-if="state.loading" role="status" class="muted">正在恢复本场面试…</p>
    <template v-if="session">
      <div class="v3-meta"><span class="badge">{{ statusLabels[session.status] || '任务处理中' }}</span><span v-if="session.mock" class="badge badge-orange">Mock 测试 · 非真实模型评估</span><span class="tiny muted">离开或刷新后可继续查看</span></div>
      <details v-if="session.report?.version === 2 && session.warnings?.length" class="panel v3-report-warnings"><summary>查看本场准备与查证提示（{{ session.warnings.length }} 条）</summary><Notice v-for="(warning, index) in session.warnings" :key="index" :message="warning" kind="warning" /></details>
      <template v-else><Notice v-for="(warning, index) in session.warnings || []" :key="index" :message="warning" kind="warning" /></template>
      <Notice v-if="session.error" :message="session.error" kind="error" />
      <p v-if="!session.resumeId && session.report?.version !== 2" class="v3-note">本场未选择简历，不会推断你没有提供的项目经历。</p>

      <section v-if="enhanced && hasStarted && session.report?.version !== 2" class="panel v3-round-progress" aria-label="本场面试进度">
        <div class="heading-row"><h2>本场进度</h2><span v-if="session.timing?.enabled" class="v3-clock" data-interview-clock :class="{ urgent: remainingMs < 180000 }">剩余 {{ clockLabel }}</span><span v-else class="badge">不限时训练</span></div>
        <ol v-if="session.stages?.length" class="v3-rounds" aria-label="面试四阶段"><li v-for="item in session.stages" :key="item.id" :aria-current="session.stage === item.id && item.status === 'IN_PROGRESS' ? 'step' : undefined" :class="{ active: item.status === 'IN_PROGRESS', complete: item.status === 'COMPLETED' }"><strong>{{ item.label }}</strong><span>{{ stageStatus[item.status] }}</span></li></ol>
        <p v-if="session.timing?.enabled && !expired" class="form-help">阅读、思考和作答计时；模型生成与系统等待不扣时。接近尾声会减少追问，为后续阶段留出时间。</p>
        <p v-if="expired && session.status === 'IN_PROGRESS'" class="v3-expired" role="status">时间已到，已停止新题与语音输入。草稿已保留，请校对最后一答后手动提交；也可直接结束面试。</p>
        <p v-if="session.report && session.stages?.some(item => item.status !== 'COMPLETED')" class="form-help">本场存在未完成阶段，仅表示本场未完成对应流程，不算答错。</p>
      </section>

      <section v-if="preparing || reviewRunning" class="panel v3-preparation" aria-labelledby="v3-progress-title" aria-live="polite" :data-phase="session.phase">
        <div class="heading-row"><h2 id="v3-progress-title">{{ reviewRunning ? '正在查证复盘' : '正在为你深度准备' }}</h2><span class="spinner" aria-hidden="true"></span></div>
        <p role="status">{{ stageLabel }}</p><p class="tiny muted">{{ session.phase }}</p>
        <ol v-if="preparing" class="v3-steps" aria-label="准备阶段"><li v-for="(label, index) in ['排队', '分析简历与岗位', '检索知识', '准备题目']" :key="label" :aria-current="index === stageIndex ? 'step' : undefined" :class="{ active: index === stageIndex, passed: index < stageIndex }"><span>{{ index + 1 }}</span>{{ label }}</li></ol>
        <p class="tiny muted">{{ reviewRunning ? '通常约 3 分钟。逐题核实依据，缺失或冲突会明确标记。' : '通常需要 1～3 分钟。任务在后台执行，可以先离开，回来后从记录继续。' }}</p>
        <div class="v3-actions"><RouterLink to="/interview-v3" class="btn btn-secondary">返回记录，稍后再看</RouterLink><button class="btn btn-ghost" :disabled="!!state.busy" @click="mutate('cancel')">取消本场任务</button></div>
      </section>

      <section v-if="session.summary && editable" class="panel v3-summary" aria-labelledby="v3-summary-title">
        <div class="heading-row"><h2 id="v3-summary-title">开考前，确认我们理解了你。</h2><span class="badge">仅用于本场</span></div>
        <p class="muted">重点确认个人职责与待澄清点；这里不会提前展示面试题和参考答案。</p>
        <div v-if="enhanced && session.mainProject" class="v3-main-project"><h3>本场主项目：{{ session.mainProject.name }}</h3><p>{{ session.mainProject.brief || '尚未提供真实项目，可在下方补充后重新准备。' }}</p><p v-if="session.mainProject.hypothetical" class="form-help">本场暂用假设场景；没有真实项目时不生成经历证据矩阵。</p></div>
        <div v-if="enhanced && session.mainProject?.hypothetical" class="form-field"><label for="v3-opening-project">开场前简述一个真实项目 <span class="muted">（可选）</span></label><textarea id="v3-opening-project" v-model="openingProject" maxlength="5000" :disabled="!!state.busy" placeholder="例如：我参与订单系统，负责支付回调与幂等处理。"></textarea><p class="form-help">随确认开考保存，面试从澄清你的实际职责开始；留空则继续假设场景。</p></div>
        <div class="v3-summary-grid"><section v-for="part in summarySections" :key="part.title"><h3>{{ part.title }}</h3><ul v-if="part.items.length" class="reading-list"><li v-for="(item, index) in part.items" :key="index">{{ item }}</li></ul><p v-else class="tiny muted">暂无明确内容，面试中进一步澄清。</p></section></div>
        <div v-if="session.status === 'READY'" class="v3-actions"><button class="btn btn-primary" :disabled="!!state.busy" data-action="confirm" @click="confirm">{{ state.busy === 'confirm' ? '正在开考…' : '确认无误，直接开始' }}</button><button class="btn btn-secondary" :disabled="!!state.busy" data-action="refresh" @click="mutate('refresh')">刷新知识并重新准备</button></div>
      </section>

      <section v-if="editable" class="panel v3-correction" aria-labelledby="v3-correction-title">
        <h2 id="v3-correction-title">纠正本场理解</h2><p class="form-help">例如：“架构由同事设计，我负责接入和故障处理”。更正会重新准备，只影响本场，不修改原简历。</p>
        <label for="v3-correction">需要纠正或补充的事实</label><textarea id="v3-correction" v-model="correction" maxlength="5000" :disabled="!!state.busy" placeholder="指出职责、业务背景或技能理解中的偏差。"></textarea>
        <label class="v3-check"><input v-model="editConfiguration" type="checkbox" :disabled="!!state.busy" />同时更换本场岗位、简历或 JD<span v-if="enhanced">、主项目</span></label>
        <template v-if="editConfiguration"><ConfigurationFields v-model="config" :materials="materials" :disabled="!!state.busy" :legacy="!enhanced" correction /><label class="v3-check"><input v-model="clearJd" type="checkbox" :disabled="!!state.busy" />清除本场补充 JD</label></template>
        <button class="btn btn-secondary" data-action="correct" :disabled="!!state.busy || (!correction.trim() && !editConfiguration) || (editConfiguration && (!config.role || !config.level))" @click="correct">{{ state.busy === 'corrections' ? '正在保存更正…' : '保存更正，重新准备' }}</button>
      </section>

      <ReportReview v-if="session.report?.version === 2" :session="session" />
      <section v-if="hasStarted && session.report?.version !== 2" class="panel chat-panel v3-dialogue" aria-labelledby="v3-dialogue-title">
        <header class="chat-header"><h2 id="v3-dialogue-title">{{ session.report ? '本场原始问答' : '围绕经历，逐层深入' }}</h2><span class="tiny muted">每道主问题最多追问两次</span></header>
        <div ref="messagesPanel" class="chat-messages v3-messages" role="log" aria-label="面试对话" aria-live="polite">
          <article v-for="message in messages" :key="message.id" class="message" :class="{ user: message.role === 'USER' }"><span class="avatar" aria-hidden="true">{{ message.role === 'USER' ? '你' : message.kind === 'REPLY' ? '答' : '问' }}</span><div><p class="message-meta">{{ message.role === 'USER' ? '你的回答' : message.kind === 'REPLY' ? '面试官回应' : '面试官' }}<span v-if="message.fallback" class="badge badge-orange">切换为已准备的相关备用问题</span></p><p class="message-content">{{ message.content }}</p></div></article>
        </div>
        <div v-if="thinking" class="v3-thinking" data-thinking role="status" aria-live="polite"><span class="spinner" aria-hidden="true"></span><div><strong>面试官思考中</strong><p class="tiny">{{ state.sendingAnswer ? '正在保存并处理本次回答，请勿重复提交。' : '回答已保存，正在针对你的表述组织追问。' }}<span v-if="waitingSeconds"> 已等待 {{ waitingSeconds }} 秒。</span></p><template v-if="waitingSeconds >= 8"><p class="tiny">暂未同步到新问题。请刷新已保存状态，系统会显示备用问题或恢复操作。</p><button class="btn btn-secondary" :disabled="!!state.busy" @click="load">刷新已保存状态</button></template></div></div>
        <form v-if="['IN_PROGRESS', 'ANSWERING'].includes(session.status) || state.sendingAnswer" class="composer" @submit.prevent="submitAnswer">
          <label for="v3-answer">你的回答</label><textarea id="v3-answer" v-model="answer" maxlength="12000" :disabled="session.status !== 'IN_PROGRESS' || !!state.busy" placeholder="结合你实际负责的部分，说明场景、决策、异常和边界。" @keydown.ctrl.enter.prevent="submitAnswer" @keydown.meta.enter.prevent="submitAnswer"></textarea>
          <SpeechInput ref="speech" v-model="answer" :context-key="currentQuestion?.questionId" :max-length="12000" :disabled="session.status !== 'IN_PROGRESS' || !!state.busy || expired" @waiting="speechWaiting" />
          <div class="composer-footer"><span class="tiny muted">Ctrl / ⌘ + Enter 提交 · 已提交记录自动保存</span><button class="btn btn-primary" type="button" data-action="answer" :disabled="!canAnswer" @click="submitAnswer">{{ state.sendingAnswer ? '正在提交…' : '提交回答' }}</button></div>
        </form>
      </section>

      <section v-if="session.status === 'FAILED' || session.status === 'INTERRUPTED'" class="panel v3-recovery" aria-labelledby="v3-recovery-title">
        <h2 id="v3-recovery-title">已保留输入与已提交的回答</h2><p class="form-help">恢复由你明确发起，不会自动重复调用模型。重试会计入本场调用上限。</p><div class="v3-actions"><button v-if="canRetry" class="btn btn-primary" :disabled="!!state.busy" data-action="retry" @click="mutate('retry')">{{ state.busy === 'retry' ? '正在恢复…' : '重试本次任务' }}</button><button v-if="canSkip" class="btn btn-secondary" :disabled="!!state.busy" data-action="next" @click="mutate('next')">跳至下一道准备问题</button><button class="btn btn-ghost" :disabled="!!state.busy" @click="load">刷新已保存状态</button></div>
      </section>

      <div v-if="canFinish" class="v3-actions v3-finish"><button class="btn btn-secondary" :disabled="!!state.busy" data-action="finish" @click="mutate('finish')">{{ state.busy === 'finish' ? '正在创建复盘任务…' : session.status === 'COMPLETED' ? '生成查证复盘' : '结束面试，查证复盘' }}</button><span class="tiny muted">只评价实际问过且已回答的内容。</span></div>
      <p v-if="session.status === 'CANCELLED'" class="panel">本场已取消，已保存的输入与问答仍可在这里查看。</p>

      <section v-if="session.report && session.report.version !== 2" class="v3-report" aria-labelledby="v3-report-title"><div class="heading-row"><h2 id="v3-report-title">有依据的逐题复盘</h2><span class="badge">保留不同合理方案</span></div><p class="panel v3-report-summary">{{ session.report.summary }}</p><article v-for="(finding, index) in session.report.findings || []" :key="`${finding.questionId}-${index}`" class="panel v3-finding"><h3>{{ messages.find(message => message.role === 'ASSISTANT' && message.questionId === finding.questionId)?.content || `已答问题 ${index + 1}` }}</h3><span class="badge" data-finding-status>{{ findingStatus(finding) }}</span><p>{{ finding.assessment }}</p><p v-if="finding.sourceQuote && sourceIds(finding).length" class="v3-source-quote" data-source-quote><strong>核对原文：</strong>{{ finding.sourceQuote }}</p><p v-if="finding.suggestion"><strong>改进建议：</strong>{{ finding.suggestion }}</p><div v-if="sourceIds(finding).length" class="v3-actions"><span class="tiny muted">具体依据：</span><a v-for="sourceId in sourceIds(finding)" :key="sourceId" :href="`#v3-source-${sourceId}`" class="text-link">{{ sources.find(source => source.id === sourceId)?.title }}</a></div><p v-else class="tiny muted">没有可回查的技术来源时，不作为已核实的技术判错。</p></article><p v-if="!session.report.findings?.length" class="muted">样本不足，未生成逐题能力判断。</p></section>

      <ProjectEvidenceMatrix v-if="enhanced && session.report?.matrix && session.report.version !== 2" :matrix="session.report.matrix" source-kind="V3" :source-id="session.id" />
      <section v-if="sources.length" class="v3-sources" aria-labelledby="v3-sources-title"><h2 id="v3-sources-title">本场可回查的知识依据</h2><p class="form-help">题库用于发现考点，技术结论结合官方资料核对。来源不足或冲突会明确标注。</p><details v-for="source in sources" :id="`v3-source-${source.id}`" :key="source.id" :data-source="source.id" class="panel v3-source"><summary>{{ source.title }}<span class="tiny muted"> · {{ source.kind === 'OFFICIAL' ? '官方文档' : '公开资料' }}</span></summary><p class="tiny muted">版本：{{ source.version || '未标明' }} · 获取：{{ displayDate(source.fetchedAt) }}</p><p class="v3-source-excerpt">{{ source.excerpt }}</p><a v-if="publicSourceUrl(source.url)" :href="publicSourceUrl(source.url)" target="_blank" rel="noopener noreferrer" class="text-link">打开原始来源 ↗</a><p v-else class="tiny muted">此来源没有可打开的公开链接。</p><p v-if="source.sha256" class="tiny muted v3-hash">片段摘要：{{ source.sha256 }}</p></details></section>
      <footer class="footer-note"><span v-if="callCount !== undefined">本场模型尝试 {{ callCount }} / {{ session.usage?.maxCalls || 32 }} 次。</span><span>实际费用未知，以模型服务账单为准；输出上限不代表实际用量或金额。</span></footer>
    </template>
  </div>
</template>

<style scoped>
.v3-page .btn { min-height: 44px; }
.v3-page .panel { margin-bottom: 24px; }
.v3-page :is(button, a, summary, textarea, input):focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.v3-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 20px; margin: 18px 0; }
.v3-actions .text-link { display: inline-flex; align-items: center; min-height: 44px; }
.v3-page .page-heading .v3-actions { margin: 0 0 14px; }
.v3-meta { display: flex; flex-wrap: wrap; gap: 10px 16px; align-items: center; margin-bottom: 20px; }
.v3-report-warnings summary { min-height: 44px; cursor: pointer; font-size: 16px; line-height: 1.8; }
.v3-note { color: var(--text-muted); font-size: 13px; margin-bottom: 20px; }
.v3-preparation > p { margin-top: 12px; }
.v3-steps { padding: 0; list-style: none; display: flex; gap: 12px; margin-block: 24px; flex-wrap: wrap; }
.v3-steps li { flex: 1 1 150px; min-height: 60px; display: flex; align-items: center; gap: 10px; padding: 12px; background: var(--surface-soft); color: var(--text-muted); border: 1px solid var(--border-subtle); border-radius: var(--radius-control); font-size: 13px; }
.v3-steps li.active { color: var(--accent-ink); border-color: var(--accent); background: var(--accent-soft); }
.v3-steps li.passed { color: var(--text-primary); }
.v3-steps li span { font-variant-numeric: tabular-nums; }
.v3-summary > p { margin-bottom: 24px; }
.v3-summary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px 32px; }
.v3-summary-grid h3 { margin-bottom: 10px; font-size: 15px; }
.v3-summary-grid section { min-width: 0; }
.v3-main-project { margin-block: 22px; padding: 18px; background: var(--surface-soft); border-left: 3px solid var(--accent); }
.v3-main-project > p { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 10px; }
.v3-clock { font-size: 21px; font-weight: 600; font-variant-numeric: tabular-nums; }
.v3-clock.urgent { color: var(--accent-ink); }
.v3-rounds { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; list-style: none; padding: 0; margin: 20px 0 12px; }
.v3-rounds li { display: flex; flex-direction: column; gap: 6px; padding: 12px; border-top: 3px solid var(--border-subtle); color: var(--text-muted); }
.v3-rounds li.active { border-color: var(--accent); background: var(--accent-soft); color: var(--accent-ink); }
.v3-rounds li.complete { color: var(--text-primary); }
.v3-rounds strong { font-size: 14px; line-height: 1.6; }
.v3-rounds span { font-size: 13px; }
.v3-expired { line-height: 1.8; padding: 14px; background: var(--accent-soft); color: var(--accent-ink); }
@media (max-width: 700px) { .v3-rounds { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; } }
.v3-correction h2 + p { margin: 12px 0 20px; }
.v3-check { display: flex; align-items: center; gap: 10px; min-height: 44px; margin: 14px 0; font-weight: 400; }
.v3-check input { width: 18px; height: 18px; accent-color: var(--accent); }
.v3-dialogue { padding: 0; }
.v3-dialogue .chat-header { padding: 20px 24px; border-bottom: 1px solid var(--border-subtle); }
.v3-dialogue h2 { font-size: 19px; }
.v3-messages { max-height: 65dvh; min-height: 120px; }
.v3-thinking { display: flex; align-items: flex-start; gap: 12px; padding: 18px 24px; background: var(--accent-soft); color: var(--accent-ink); border-top: 1px solid var(--border-subtle); }
.v3-thinking strong { font-size: 14px; }.v3-thinking p { margin-top: 6px; }
.v3-thinking .spinner { margin-top: 3px; flex: 0 0 auto; }
.v3-thinking .btn { margin-top: 14px; }
.v3-page .composer textarea { min-height: 130px; }
.v3-finish { margin-bottom: 28px; }
.v3-report-summary, .v3-finding p, .v3-source-excerpt { white-space: pre-wrap; overflow-wrap: anywhere; }
.v3-finding h3 { font-size: 17px; line-height: 1.7; margin-bottom: 14px; }
.v3-finding p { margin-top: 14px; max-width: 80ch; }
.v3-source-quote { border-left: 2px solid var(--accent); padding-left: 14px; color: var(--text-secondary); }
.v3-finding .v3-actions { margin-bottom: 0; }
.v3-sources { margin-top: 32px; }
.v3-source { scroll-margin-top: 24px; }.v3-source:target { outline: 2px solid var(--accent); }
.v3-source summary { font-weight: 600; cursor: pointer; min-height: 44px; line-height: 1.8; overflow-wrap: anywhere; }
.v3-source summary .tiny { font-weight: 400; }
.v3-source > p { margin-block: 12px; }
.v3-source .text-link { display: inline-flex; min-height: 44px; align-items: center; }
.v3-hash { overflow-wrap: anywhere; font-size: 11px; }
@media (max-width: 700px) { .v3-summary-grid { grid-template-columns: minmax(0, 1fr); }.v3-dialogue .chat-header { padding: 16px; }.v3-thinking { padding: 16px; }.v3-steps { gap: 8px; }.v3-steps li { flex-basis: 120px; } }
</style>
