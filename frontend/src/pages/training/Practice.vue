<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import ModelRecovery from '../../components/training/ModelRecovery.vue'
import ComparisonResult from '../../components/training/ComparisonResult.vue'
import { training, writeTraining, ApiError } from '../../api/training'
import { CircleCheck, Lightbulb, TriangleAlert } from 'lucide-vue-next'
import type { Finding, Page } from '../../models/training'

interface Check { checkId: string; status: string; explanation: string; evidenceRefs: { kind: string; quote?: string; checkId?: string; start: number; end: number }[] }
interface Evaluation { id: string; status: string; operationId?: string; protocol?: { requestedModel: string; returnedModel?: string; mock: boolean; parameters: { reasoning_effort?: string } }; result?: { checks?: Check[]; suggestions?: string[] } }
interface Attempt { id: string; ordinal: number; revision: number; status: string; answerText: string; effectiveExposure: string; submittedAt?: string; evaluation?: Evaluation }
interface Case { id: string; sourceKind?: string; sourceId?: string; sourceSessionKind?: 'SESSION' | 'V3'; questionIndex?: number; revision: number; question: string; caseVersion: number; mock: boolean; context?: string; contextOrigin: string; contextTruncated?: boolean; checkpointSnapshot?: { reason: string }; priorDialogue: { id: string; role: string; content: string }[]; analysisExposure: string }
interface Comparison { id: string; leftAttemptId: string; rightAttemptId: string; status: string; comparability: string; scopeNotice?: string; leftAnswer?: string; rightAnswer?: string; operationId?: string; result?: Record<string, any> }

const props = withDefaults(defineProps<{ embedded?: boolean; caseId?: string }>(), { embedded: false })
const emit = defineEmits<{ changed: [] }>()
const route = useRoute(), router = useRouter(), id = computed(() => String(props.caseId || route.params.id || ''))
const source = ref<Case | null>(null), cases = ref<Case[]>([]), attempts = ref<Attempt[]>([]), active = ref<Attempt | null>(null)
const answer = ref(''), serverText = ref<string | null>(null), error = ref(''), notice = ref(''), recovery = ref<ApiError | null>(null)
const busy = ref(false), loading = ref(true), comparison = ref<Comparison | null>(null), comparisonFailed = ref(false), showServerVersion = ref(false)
const casesCursor = ref<string | null>(null), findings = ref<Pick<Finding, 'kind' | 'text' | 'disputed'>[]>([]), hintUnavailable = ref(false)
const labels: Record<string, string> = { DRAFT: '草稿', IMPORTED: '原始回答', EVALUATING: '评估中', READY: '评估完成', FAILED: '评估失败', INTERRUPTED: '评估中断', SUBMITTED: '已提交' }
const baseline = computed(() => attempts.value.filter(a => a.status === 'READY' && a.ordinal < (active.value?.ordinal ?? Infinity)).at(-1) || attempts.value.find(a => a.ordinal === 0))
const canCompare = computed(() => active.value?.status === 'READY' && baseline.value && baseline.value.id !== active.value.id)
const reviewGap = computed(() => source.value?.contextOrigin === 'REVIEW_GAP')
const projectGap = computed(() => ['PROJECT_GAP', 'REVIEW_GAP'].includes(source.value?.contextOrigin || ''))
const projectReportPath = computed(() => `${source.value?.sourceSessionKind === 'V3' ? '/interviews' : '/reports'}/${encodeURIComponent(source.value?.sourceId || '')}`)
const brief = (text: string) => text.match(/^.*?[。！？](?:[”」])?/)?.[0] || text
const hint = computed(() => {
  const checks = baseline.value?.evaluation?.result?.checks || []
  if (baseline.value?.ordinal && checks.length) {
    const gaps = checks.filter(c => c.status === 'GAP').slice(0, 2)
    return { uncertain: !gaps.length, text: gaps.length ? gaps.map(c => brief(c.explanation)).join('；') : '上轮没有明确的遗漏记录，可以继续完善思路与异常边界。' }
  }
  if (reviewGap.value) {
    try {
      const finding = JSON.parse(source.value?.context || '{}').finding
      const text = finding?.headline || finding?.observation
      if (typeof text === 'string' && text.trim()) return { uncertain: false, text: brief(text) }
    } catch { /* 缺少可读的来源快照时，使用通用作答提示。 */ }
  }
  if (projectGap.value && source.value?.checkpointSnapshot?.reason) return { uncertain: true, text: brief(source.value.checkpointSnapshot.reason) }
  const gaps = findings.value.filter(f => !f.disputed && ['missing', 'incorrect'].includes(f.kind)).slice(0, 2)
  return { uncertain: !gaps.length, text: gaps.length ? gaps.map(f => brief(f.text)).join('；') : findings.value.some(f => !f.disputed && f.kind === 'uncertain') ? '原报告依据不足，尚不能判断这部分是否答对。可以重新作答，补充具体思路与边界。' : hintUnavailable.value ? '上轮提示暂时无法读取，可以先重新作答。' : '暂无明确的薄弱点记录，可以从思路、核心方案和异常边界重新梳理。' }
})
const evaluationTips = computed(() => {
  const result = active.value?.evaluation?.result
  return [...new Set([...(result?.checks?.filter(c => c.status !== 'MET').map(c => c.explanation) || []), ...(result?.suggestions || [])])].slice(0, 2)
})
const strengths = computed(() => active.value?.evaluation?.result?.checks?.filter(c => c.status === 'MET').map(c => c.explanation).slice(0, 2) || [])
async function readHint() {
  if (!source.value || source.value.sourceKind !== 'SESSION' || projectGap.value || source.value.sourceSessionKind === 'V3') return
  try {
    const review = await training<{ id: string | null }>(`/review-item-for-source?sessionId=${encodeURIComponent(source.value.sourceId || '')}&questionIndex=${source.value.questionIndex}`)
    if (review.id) findings.value = (await training<{ findings: Finding[] }>(`/review-items/${encodeURIComponent(review.id)}`)).findings
    else if (source.value.analysisExposure === 'PROVIDED') {
      const report = await training<{ questions: { questionIndex: number; missing: string[]; incorrect: string[]; uncertain: string[] }[] }>(`/api/sessions/${encodeURIComponent(source.value.sourceId || '')}/report`)
      const question = report.questions.find(q => q.questionIndex === source.value!.questionIndex)
      if (question) findings.value = (['missing', 'incorrect', 'uncertain'] as const).flatMap(kind => (question[kind] || []).map(text => ({ kind, text, disputed: false })))
    }
  } catch { hintUnavailable.value = true }
}
watch(answer, value => {
  if (active.value?.status === 'DRAFT') try { sessionStorage.setItem(`v2:answer:${active.value.id}`, value) } catch { /* 服务端保存仍可用。 */ }
})
function failed(e: unknown) { error.value = e instanceof Error ? e.message : '操作失败，本页文本保留。'; recovery.value = e instanceof ApiError ? e : null }
function recoveryFor(message: string, operationId?: string) { recovery.value = operationId ? Object.assign(new ApiError(message), { operationId }) : null }
async function readAttempts() {
  const all: Attempt[] = []; let cursor = ''
  do {
    const page = await training<Page<Attempt>>(`/practice-cases/${id.value}/attempts?limit=100&cursor=${encodeURIComponent(cursor)}`)
    all.push(...page.items); cursor = page.nextCursor || ''
  } while (cursor)
  attempts.value = all.sort((a, b) => a.ordinal - b.ordinal)
}
function remember(a: Attempt) { attempts.value = [...attempts.value.filter(old => old.id !== a.id), a].sort((l, r) => l.ordinal - r.ordinal) }
async function select(a: Attempt) {
  const fresh = await training<Attempt>(`/practice-attempts/${a.id}`)
  active.value = fresh; remember(fresh); serverText.value = fresh.answerText; answer.value = fresh.answerText
  if (fresh.status === 'DRAFT') try { answer.value = sessionStorage.getItem(`v2:answer:${fresh.id}`) ?? fresh.answerText } catch { /* 无本页缓存时使用服务端草稿。 */ }
  if (['FAILED', 'INTERRUPTED', 'EVALUATING'].includes(fresh.status)) recoveryFor('回答已保存，评估尚未完成。', fresh.evaluation?.operationId)
}
async function prepareDraft() {
  const draft = attempts.value.filter(a => a.status === 'DRAFT').at(-1)
  const saved = draft || await writeTraining<Attempt>(`/practice-cases/${id.value}/attempts`, { exposureDeclaration: 'UNKNOWN' })
  await select(saved)
}
async function readComparisons() {
  const all: Comparison[] = []; let cursor = ''
  do {
    const page = await training<Page<Comparison>>(`/practice-comparisons?caseId=${encodeURIComponent(id.value)}&limit=100&cursor=${encodeURIComponent(cursor)}`)
    all.push(...page.items); cursor = page.nextCursor || ''
  } while (cursor)
  return all
}
async function reopenComparison(cid: string) {
  comparison.value = await training<Comparison>(`/practice-comparisons/${cid}`)
  if (['FAILED', 'INTERRUPTED', 'RUNNING'].includes(comparison.value.status)) recoveryFor('回答已保存，对比尚未完成。', comparison.value.operationId)
}
async function compare(createAllowed: boolean) {
  if (!canCompare.value) return
  const history = await readComparisons()
  const saved = history.find(c => c.leftAttemptId === baseline.value!.id && c.rightAttemptId === active.value!.id && c.comparability !== 'TEXT_ONLY')
  if (saved) { await reopenComparison(saved.id); return }
  if (!createAllowed) return
  // 历史原答的协议未知，直接用现有配对重评生成共同规则下的变化，保留原评估。
  comparison.value = await writeTraining<Comparison>('/practice-comparisons', { leftAttemptId: baseline.value!.id, rightAttemptId: active.value!.id, mode: 'REEVALUATE_PAIR' })
}
async function load(cursor = '') {
  loading.value = true; error.value = ''; recovery.value = null
  try {
    if (!id.value) {
      const page = await training<Page<Case>>(`/practice-cases?limit=20&cursor=${encodeURIComponent(cursor)}`)
      cases.value = cursor ? [...cases.value, ...page.items] : page.items; casesCursor.value = page.nextCursor
      return
    }
    source.value = await training<Case>(`/practice-cases/${id.value}`)
    await readAttempts()
    let pending = ''
    try { pending = sessionStorage.getItem(`v2:practice-submission:${id.value}`) || '' } catch { /* 没有本页存储时仍可读取明确的结果地址。 */ }
    const requested = String(route.query.attemptId || pending)
    if (requested) {
      const saved = attempts.value.find(a => a.id === requested)
      if (!saved) throw new Error('找不到这次作答，请返回错题重练重新打开。')
      await select(saved)
      if (saved.status === 'READY') await compare(false)
    } else {
      const latest = attempts.value.at(-1)
      const draft = attempts.value.find(a => a.status === 'DRAFT')
      if (!draft && latest && ['FAILED', 'INTERRUPTED', 'EVALUATING', 'SUBMITTED'].includes(latest.status)) await select(latest)
      else if (props.embedded && !draft && latest?.status === 'READY') { await select(latest); await compare(false) }
      else await prepareDraft()
    }
    await readHint()
  } catch (e) { failed(e) } finally { loading.value = false; if (props.embedded) emit('changed') }
}
async function keepResultRoute() {
  if (active.value && active.value.status !== 'DRAFT' && String(route.params.id || '') === id.value) {
    await router.replace({ path: route.path, query: { ...route.query, attemptId: active.value.id } })
    try { sessionStorage.removeItem(`v2:practice-submission:${id.value}`) } catch { /* 结果地址已保留作答 ID。 */ }
  }
}
async function save(submit: boolean) {
  if (!active.value || busy.value || active.value.status !== 'DRAFT') return
  const origin = document.activeElement instanceof HTMLButtonElement ? document.activeElement : null
  busy.value = true; error.value = ''; notice.value = ''; recovery.value = null; comparisonFailed.value = false; showServerVersion.value = false
  try {
    if (submit) try { sessionStorage.setItem(`v2:practice-submission:${id.value}`, active.value.id) } catch { /* 不影响服务端提交。 */ }
    const saved = await writeTraining<Attempt>(`/practice-attempts/${active.value.id}/${submit ? 'submit' : 'draft'}`, { answer: answer.value, expectedVersion: active.value.revision }, submit ? 'POST' : 'PUT')
    active.value = saved; remember(saved); serverText.value = saved.answerText
    if (submit) {
      answer.value = saved.answerText
      try { sessionStorage.removeItem(`v2:answer:${saved.id}`) } catch { /* 已封存答案以服务端为准。 */ }
      notice.value = '回答已保存。'
      if (saved.status === 'READY') {
        try { await compare(true) } catch (e) { comparisonFailed.value = true; failed(e) }
      } else recoveryFor('回答已保存，评估尚未完成。', saved.evaluation?.operationId)
      if (!comparisonFailed.value) await keepResultRoute()
    } else notice.value = '草稿已明确保存到本机服务。'
  } catch (e) {
    failed(e); showServerVersion.value = true
    try {
      const saved = await training<Attempt>(`/practice-attempts/${active.value.id}`)
      serverText.value = saved.answerText
      if (saved.status !== 'DRAFT') { active.value = saved; remember(saved); await keepResultRoute() }
      else if (submit) sessionStorage.removeItem(`v2:practice-submission:${id.value}`)
    } catch { /* 连接失败时仍保留本页输入。 */ }
  } finally {
    busy.value = false; emit('changed'); await nextTick()
    if (origin?.isConnected && !origin.disabled && (document.activeElement === document.body || document.activeElement === origin)) origin.focus()
  }
}
async function retryComparison() {
  if (busy.value) return
  busy.value = true; error.value = ''; recovery.value = null
  try { await compare(true); comparisonFailed.value = false; await keepResultRoute() } catch (e) { comparisonFailed.value = true; failed(e) } finally { busy.value = false }
}
async function recoverResult() {
  if (!active.value || busy.value) return
  busy.value = true; error.value = ''; recovery.value = null
  try {
    await select(active.value)
    if (comparison.value?.operationId) await reopenComparison(comparison.value.id)
    else if (active.value.status === 'READY') await compare(true)
  } catch (e) { failed(e) } finally { busy.value = false }
}
async function again() {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''; recovery.value = null
  try {
    await readAttempts(); await prepareDraft()
    comparison.value = null; comparisonFailed.value = false
    try { sessionStorage.removeItem(`v2:practice-submission:${id.value}`) } catch { /* 新草稿已保存。 */ }
    const { attemptId, ...query } = route.query
    await router.replace({ path: route.path, query })
    emit('changed'); await nextTick(); document.querySelector<HTMLTextAreaElement>('#new-answer')?.focus()
  } catch (e) { failed(e) } finally { busy.value = false }
}
watch(() => route.query.attemptId, value => {
  if (props.embedded && String(route.params.id || '') === id.value && !loading.value && !busy.value && value && value !== active.value?.id) void load()
})
watch(id, () => { source.value = null; active.value = null; comparison.value = null; findings.value = []; hintUnavailable.value = false; answer.value = ''; attempts.value = []; void load() }, { immediate: true })
</script>

<template>
  <div class="page training-page practice-page">
    <div v-if="!props.embedded" class="page-heading"><h1>{{ reviewGap ? '围绕本场回答，定向补练。' : projectGap ? '围绕项目缺口，定向补练。' : id ? '同一道题，再回答一次。' : '独立作答，保留每次变化。' }}</h1><p>{{ reviewGap ? '结合本场岗位与原答缺口练一道新题，结果单独保存，不修改原复盘。' : projectGap ? '结合本场主项目独立作答。补练结果单独保存，不修改原面试证据矩阵。' : id ? '写下新答案，直接查看这次补齐了什么。' : '选择一道题，继续练习。' }}</p><RouterLink v-if="projectGap" :to="projectReportPath" class="text-link">{{ reviewGap ? '返回原面试复盘' : '返回原面试证据矩阵' }}</RouterLink></div>
    <Notice :message="error" kind="error" /><Notice :message="notice" />
    <ModelRecovery :error="recovery" @recovered="recoverResult" />
    <button v-if="error && !active" class="btn btn-secondary" :disabled="loading" @click="load()">重新读取作答记录</button>
    <LoadingState v-if="loading" label="正在准备作答…" />
    <template v-if="!id && !loading">
      <div v-if="!cases.length" class="panel training-empty"><h2>还没有同题案例</h2><p>在报告或复习项中，选择一道已有回答的问题开始。</p><RouterLink to="/review" class="btn btn-primary">前往错题重练</RouterLink></div>
      <div v-else class="panel training-list"><article v-for="c in cases" :key="c.id" class="training-list-row"><div><span class="tiny muted">案例版本 {{ c.caseVersion }}</span><h2>{{ c.question }}</h2></div><RouterLink :to="`/practice/${c.id}`" class="btn btn-secondary">开始作答</RouterLink></article></div>
      <button v-if="casesCursor" class="btn btn-secondary" @click="load(casesCursor || '')">加载更多案例</button>
    </template>
    <template v-if="source && !loading">
      <section class="panel training-flow practice-question" aria-label="本次题目">
        <span v-if="source.mock" class="badge badge-orange">Mock · 仅验证流程</span>
        <h2>{{ source.question }}</h2>
        <div class="practice-hint" :class="{ 'hint-neutral': hint.uncertain }">
          <TriangleAlert :size="18" aria-hidden="true" />
          <p><strong>{{ hint.uncertain ? '作答提示' : '上轮回答待补强' }}</strong>{{ hint.text }}</p>
        </div>
        <details class="practice-reference">
          <summary>查看上轮原答</summary>
          <pre v-if="baseline">{{ baseline.answerText }}</pre>
          <p v-else class="muted">这是本题第一次作答，还没有历史回答。</p>
          <div v-for="m in source.priorDialogue" :key="m.id" class="training-quote"><strong>{{ m.role === 'USER' ? '先前回答' : '先前问题' }}</strong><pre>{{ m.content }}</pre></div>
        </details>
      </section>
      <section v-if="active" class="panel training-flow training-section practice-canvas" aria-label="本次作答">
        <div class="practice-answer-heading"><h2>{{ active.status === 'DRAFT' ? '重新回答一次' : '本次回答' }}</h2><span class="tiny muted" role="status">{{ labels[active.status] }}</span></div>
        <label for="new-answer" class="sr-only">我的新回答</label>
        <textarea id="new-answer" v-model="answer" maxlength="6000" :rows="active.status === 'DRAFT' ? 14 : 5" :readonly="active.status !== 'DRAFT'" :disabled="busy" placeholder="先说你的思路，再展开核心方案，最后补充异常情况与处理边界。" aria-describedby="answer-count" />
        <div class="practice-answer-footer">
          <span id="answer-count" class="tiny muted">{{ answer.length }} / 6000 字符</span>
          <div v-if="active.status === 'DRAFT'" class="practice-actions"><button class="btn btn-secondary" data-action="save-draft" :disabled="busy" @click="save(false)">暂存草稿</button><button class="btn btn-primary" data-action="submit" :disabled="busy || !answer.trim()" @click="save(true)">{{ busy ? '正在评估并对比…' : '提交并对比' }}</button></div>
        </div>
        <details v-if="showServerVersion && serverText !== null && serverText !== answer"><summary>服务端版本（本页文本保留）</summary><pre>{{ serverText }}</pre></details>
      </section>
      <section v-if="active && active.status !== 'DRAFT'" class="panel training-flow training-section practice-result" aria-label="本次对比结果" aria-live="polite">
        <h2>这次回答，有哪些变化？</h2>
        <template v-if="active.status === 'READY'">
          <ComparisonResult v-if="comparison?.result" :result="comparison.result" :before="baseline?.answerText" :after="active.answerText" />
          <template v-else>
            <p v-if="!baseline" class="tiny muted">这是首次作答，先看看本次反馈；下次可与这次回答对比。</p>
            <section><h3 class="feedback-heading"><CircleCheck :size="18" aria-hidden="true" />本次答得好的地方</h3><p v-for="text in strengths" :key="text">{{ text }}</p><p v-if="!strengths.length" class="muted">本次评估没有明确列出已满足的关键点。</p></section>
            <section v-if="evaluationTips.length"><h3 class="feedback-heading"><Lightbulb :size="18" aria-hidden="true" />仍可精进</h3><p v-for="tip in evaluationTips" :key="tip">{{ tip }}</p></section>
          </template>
          <button v-if="canCompare && !comparison?.result && !recovery?.operationId" class="btn btn-secondary" data-action="retry-comparison" :disabled="busy" @click="retryComparison">{{ comparisonFailed ? '重试对比' : '生成本次对比' }}</button>
          <p v-if="comparison?.status === 'READY'" class="tiny muted">仅比较两次回答中的变化，练习完成不代表已经掌握。</p>
          <div class="practice-actions practice-exits"><RouterLink to="/review" class="btn btn-primary">再练一题</RouterLink><button class="btn btn-secondary" data-action="again" :disabled="busy" @click="again">本题再答一次</button></div>
        </template>
        <p v-else class="muted">回答已保存，评估尚未完成。可通过上方操作查询或恢复结果。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.practice-page { max-width: 1040px; }
.practice-question h2 { font-size: 22px; line-height: 1.6; text-wrap: pretty; }
.practice-hint { display: flex; align-items: flex-start; gap: var(--space-3); padding: var(--space-4); background: var(--warning-soft); color: var(--warning); border-radius: var(--radius-control); }
.practice-hint svg { flex-shrink: 0; margin-top: 3px; }
.practice-hint p { margin: 0; line-height: 1.8; }
.practice-hint strong { display: block; margin-bottom: var(--space-1); font-size: 13px; }
.hint-neutral { color: var(--text-secondary); background: var(--surface-soft); }
.practice-reference summary { cursor: pointer; color: var(--text-muted); font-size: 13px; }
.practice-reference pre { margin-top: var(--space-3); }
.practice-answer-heading, .practice-actions, .practice-answer-footer { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.practice-answer-heading, .practice-answer-footer { justify-content: space-between; }
.practice-canvas textarea { min-height: 360px; line-height: 1.85; padding: var(--space-4); resize: vertical; caret-color: var(--accent); }
.practice-canvas textarea[readonly] { min-height: 180px; background: var(--surface-soft); }
.practice-result { border-top: 1px solid var(--border-subtle); }
.feedback-heading { display: flex; align-items: center; gap: var(--space-2); }
.practice-exits { margin-top: var(--space-4); }
@media (max-width: 600px) {
  .practice-question h2 { font-size: 19px; }
  .practice-canvas textarea { min-height: 340px; font-size: 16px; }
  .practice-answer-footer { align-items: stretch; }
  .practice-answer-footer .practice-actions { width: 100%; }
  .practice-actions .btn { flex: 1; }
}
</style>
