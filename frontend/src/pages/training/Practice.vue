<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import ModelRecovery from '../../components/training/ModelRecovery.vue'
import ComparisonResult from '../../components/training/ComparisonResult.vue'
import { training, writeTraining, ApiError } from '../../api/training'
import { localDate } from '../../models/training'
import type { Page } from '../../models/training'

interface Check { checkId: string; status: string; explanation: string; evidenceRefs: { kind: string; quote?: string; checkId?: string; start: number; end: number }[] }
interface Evaluation { id: string; status: string; operationId?: string; protocol?: { requestedModel: string; returnedModel?: string; mock: boolean; parameters: { reasoning_effort?: string } }; result?: { checks?: Check[]; suggestions?: string[] } }
interface Attempt { id: string; ordinal: number; revision: number; status: string; answerText: string; effectiveExposure: string; submittedAt?: string; evaluation?: Evaluation }
interface Case { id: string; sourceKind?: string; sourceId?: string; sourceSessionKind?: 'SESSION' | 'V3'; questionIndex?: number; revision: number; question: string; caseVersion: number; mock: boolean; context?: string; contextOrigin: string; contextTruncated?: boolean; priorDialogue: { id: string; role: string; content: string }[]; analysisExposure: string }
interface Comparison { id: string; leftAttemptId: string; rightAttemptId: string; status: string; comparability: string; scopeNotice?: string; leftAnswer?: string; rightAnswer?: string; operationId?: string; result?: Record<string, any> }

const route = useRoute(), router = useRouter(), id = computed(() => String(route.params.id || ''))
const source = ref<Case | null>(null), cases = ref<Case[]>([]), attempts = ref<Attempt[]>([]), active = ref<Attempt | null>(null)
const answer = ref(''), serverText = ref<string | null>(null), error = ref(''), notice = ref(''), recovery = ref<ApiError | null>(null)
const busy = ref(false), loading = ref(true), comparison = ref<Comparison | null>(null), comparisonFailed = ref(false)
const casesCursor = ref<string | null>(null), reviewId = ref(''), reveal = ref<Record<string, any> | null>(null), referenceLoading = ref(false)
const groups = ref<Evaluation[]>([]), groupCursor = ref<string | null>(null), comparisonHistory = ref<Comparison[]>([]), comparisonCursor = ref<string | null>(null), displayedGroup = ref<Evaluation | null>(null)
const labels: Record<string, string> = { DRAFT: '草稿', IMPORTED: '原始回答', EVALUATING: '评估中', READY: '评估完成', FAILED: '评估失败', INTERRUPTED: '评估中断', SUBMITTED: '已提交' }
const exposure: Record<string, string> = { PROVIDED: '解析已提供 · 辅助准备', SEEN: '用户声明看过解析', NOT_SEEN: '用户声明未看解析', UNKNOWN: '解析接触情况未知' }
const baseline = computed(() => attempts.value.find(a => a.ordinal === 0) || attempts.value.filter(a => a.status === 'READY' && a.ordinal < (active.value?.ordinal ?? Infinity)).at(-1))
const canCompare = computed(() => active.value?.status === 'READY' && baseline.value && baseline.value.id !== active.value.id)
const reviewGap = computed(() => source.value?.contextOrigin === 'REVIEW_GAP')
const projectGap = computed(() => ['PROJECT_GAP', 'REVIEW_GAP'].includes(source.value?.contextOrigin || ''))
const projectReportPath = computed(() => `${source.value?.sourceSessionKind === 'V3' ? '/interviews' : '/reports'}/${encodeURIComponent(source.value?.sourceId || '')}`)
const contextText = computed(() => {
  if (source.value?.contextOrigin !== 'CONFIRMED_STORY') return source.value?.context || ''
  try { return Object.values(JSON.parse(source.value.context || '{}')).map((f: any) => f.confirmation?.notApplicable ? `不适用：${f.confirmation.reason}` : f.text).join('\n\n') } catch { return '故事上下文无法读取，请查看来源版本。' }
})
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
      if (!saved) throw new Error('找不到这次作答，请返回错题复习重新打开。')
      await select(saved)
      if (saved.status === 'READY') await compare(false)
    } else {
      const latest = attempts.value.at(-1)
      const draft = attempts.value.find(a => a.status === 'DRAFT')
      if (!draft && latest && ['FAILED', 'INTERRUPTED', 'EVALUATING', 'SUBMITTED'].includes(latest.status)) await select(latest)
      else await prepareDraft()
    }
    if (source.value.sourceKind === 'SESSION' && !projectGap.value) {
      const review = await training<{ id: string | null }>(`/review-item-for-source?sessionId=${encodeURIComponent(source.value.sourceId || '')}&questionIndex=${source.value.questionIndex}`)
      reviewId.value = review.id || ''
    }
  } catch (e) { failed(e) } finally { loading.value = false }
}
async function keepResultRoute() {
  if (active.value && active.value.status !== 'DRAFT') {
    await router.replace({ path: route.path, query: { ...route.query, attemptId: active.value.id } })
    try { sessionStorage.removeItem(`v2:practice-submission:${id.value}`) } catch { /* 结果地址已保留作答 ID。 */ }
  }
}
async function save(submit: boolean) {
  if (!active.value || busy.value || active.value.status !== 'DRAFT') return
  const origin = document.activeElement instanceof HTMLButtonElement ? document.activeElement : null
  busy.value = true; error.value = ''; notice.value = ''; recovery.value = null; comparisonFailed.value = false
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
    failed(e)
    try {
      const saved = await training<Attempt>(`/practice-attempts/${active.value.id}`)
      serverText.value = saved.answerText
      if (saved.status !== 'DRAFT') { active.value = saved; remember(saved); await keepResultRoute() }
      else if (submit) sessionStorage.removeItem(`v2:practice-submission:${id.value}`)
    } catch { /* 连接失败时仍保留本页输入。 */ }
  } finally {
    busy.value = false; await nextTick()
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
    await nextTick(); document.querySelector<HTMLTextAreaElement>('#new-answer')?.focus()
  } catch (e) { failed(e) } finally { busy.value = false }
}
async function revealAnalysis(event: Event) {
  if (!(event.target as HTMLDetailsElement).open || reveal.value || referenceLoading.value || !source.value) return
  referenceLoading.value = true
  try {
    const fresh = await training<Case>(`/practice-cases/${id.value}`)
    const saved = await writeTraining<{ content?: Record<string, any> }>(`/practice-cases/${id.value}/reveal`, { revealKind: 'SOURCE_ANALYSIS', expectedVersion: fresh.revision })
    reveal.value = saved.content || {}; source.value.analysisExposure = 'PROVIDED'
  } catch (e) { failed(e) } finally { referenceLoading.value = false }
}
async function histories(more = false) {
  try {
    const page = await training<Page<Comparison>>(`/practice-comparisons?caseId=${encodeURIComponent(id.value)}&limit=20&cursor=${encodeURIComponent(more ? comparisonCursor.value || '' : '')}`)
    comparisonHistory.value = more ? [...comparisonHistory.value, ...page.items] : page.items; comparisonCursor.value = page.nextCursor
  } catch (e) { failed(e) }
}
async function loadGroups(more = false) {
  try {
    const page = await training<Page<Evaluation>>(`/practice-cases/${id.value}/evaluation-groups?limit=20&cursor=${encodeURIComponent(more ? groupCursor.value || '' : '')}`)
    groups.value = more ? [...groups.value, ...page.items] : page.items; groupCursor.value = page.nextCursor
  } catch (e) { failed(e) }
}
async function newGroup() {
  if (!active.value || busy.value) return
  busy.value = true
  try { await writeTraining(`/practice-cases/${id.value}/evaluation-groups`, { attemptId: active.value.id }); await loadGroups() } catch (e) { failed(e) } finally { busy.value = false }
}
watch(id, () => { source.value = null; active.value = null; comparison.value = null; reveal.value = null; reviewId.value = ''; answer.value = ''; attempts.value = []; void load() }, { immediate: true })
</script>

<template>
  <div class="page training-page practice-page">
    <div class="page-heading"><h1>{{ reviewGap ? '围绕本场回答，定向补练。' : projectGap ? '围绕项目缺口，定向补练。' : id ? '同一道题，再回答一次。' : '独立作答，保留每次变化。' }}</h1><p>{{ reviewGap ? '结合本场岗位与原答缺口练一道新题，结果单独保存，不修改原复盘。' : projectGap ? '结合本场主项目独立作答。补练结果单独保存，不修改原面试证据矩阵。' : id ? '写下新答案，直接查看这次补齐了什么。' : '选择一道题，继续练习。' }}</p><RouterLink v-if="projectGap" :to="projectReportPath" class="text-link">{{ reviewGap ? '返回原面试复盘' : '返回原面试证据矩阵' }}</RouterLink></div>
    <Notice :message="error" kind="error" /><Notice :message="notice" />
    <ModelRecovery :error="recovery" @recovered="recoverResult" />
    <button v-if="error && !active" class="btn btn-secondary" :disabled="loading" @click="load()">重新读取作答记录</button>
    <LoadingState v-if="loading" label="正在准备作答…" />
    <template v-if="!id && !loading">
      <div v-if="!cases.length" class="panel training-empty"><h2>还没有同题案例</h2><p>在报告或复习项中，选择一道已有回答的问题开始。</p><RouterLink to="/review" class="btn btn-primary">前往错题复习</RouterLink></div>
      <div v-else class="panel training-list"><article v-for="c in cases" :key="c.id" class="training-list-row"><div><span class="tiny muted">案例版本 {{ c.caseVersion }}</span><h2>{{ c.question }}</h2></div><RouterLink :to="`/practice/${c.id}`" class="btn btn-secondary">开始作答</RouterLink></article></div>
      <button v-if="casesCursor" class="btn btn-secondary" @click="load(casesCursor || '')">加载更多案例</button>
    </template>
    <template v-if="source && !loading">
      <section class="panel training-flow practice-question" aria-label="本次题目">
        <span v-if="source.mock" class="badge badge-orange">Mock · 仅验证流程</span>
        <h2>{{ source.question }}</h2>
        <details v-if="source.priorDialogue.length"><summary>查看这道追问的前文</summary><div v-for="m in source.priorDialogue" :key="m.id" class="training-quote"><strong>{{ m.role === 'USER' ? '先前回答' : '先前问题' }}</strong><pre>{{ m.content }}</pre></div></details>
        <details class="practice-reference" @toggle="revealAnalysis">
          <summary>查看上一次回答及原报告诊断</summary>
          <div class="training-flow"><section v-if="baseline"><h3>{{ baseline.ordinal === 0 ? '原始回答' : '上一次回答' }}</h3><pre>{{ baseline.answerText }}</pre></section><p v-else class="muted">这是本题第一次作答，还没有历史回答。</p>
            <LoadingState v-if="referenceLoading" label="正在读取原报告诊断…" />
            <template v-if="reveal"><section v-for="(label, key) in { correct: '原报告正确点', incorrect: '原报告错误', missing: '原报告遗漏', uncertain: '原报告未知', suggestions: '原建议' }" :key="key"><template v-if="reveal[key]?.length"><h3>{{ label }}</h3><p v-for="(text, index) in reveal[key]" :key="index">{{ text }}</p></template></section><p v-if="!Object.keys(reveal).length" class="muted">本题没有来源报告诊断。</p></template>
          </div>
        </details>
      </section>
      <section v-if="active" class="panel training-flow training-section" aria-label="本次作答">
        <div class="practice-answer-heading"><h2>{{ active.status === 'DRAFT' ? '写下你的新回答' : '本次回答' }}</h2><span class="tiny muted">{{ labels[active.status] }}</span></div>
        <label for="new-answer">我的新回答</label><textarea id="new-answer" v-model="answer" maxlength="6000" rows="12" :readonly="active.status !== 'DRAFT'" :disabled="busy" placeholder="从思路、关键步骤和异常边界开始。" />
        <p v-if="active.status === 'DRAFT'" class="tiny muted">{{ answer.length }} / 6000 字符。本页输入暂存；保存草稿后可从服务端找回。</p>
        <details v-if="serverText !== null && serverText !== answer"><summary>服务端版本（本页文本保留）</summary><pre>{{ serverText }}</pre></details>
        <div v-if="active.status === 'DRAFT'" class="practice-actions"><button class="btn btn-primary" data-action="submit" :disabled="busy || !answer.trim()" @click="save(true)">{{ busy ? '正在评估并对比…' : '提交回答并对比' }}</button><button class="btn btn-secondary" data-action="save-draft" :disabled="busy" @click="save(false)">保存草稿到本机服务</button></div>
        <template v-else-if="active.status === 'READY' && !comparison?.result"><div v-for="check in active.evaluation?.result?.checks" :key="check.checkId" class="training-finding"><strong>{{ { MET: '满足', GAP: '有缺口', UNCERTAIN: '无法判断' }[check.status] }}</strong><p>{{ check.explanation }}</p></div><p v-for="tip in active.evaluation?.result?.suggestions" :key="tip">{{ tip }}</p></template>
      </section>
      <section v-if="comparison?.result" class="panel training-flow training-section practice-result" aria-label="本次对比结果" aria-live="polite"><h2>这次回答，有哪些变化？</h2><ComparisonResult :result="comparison.result" /><p v-if="comparison.scopeNotice" class="tiny muted">{{ comparison.scopeNotice }}</p></section>
      <button v-if="canCompare && !comparison?.result && !recovery?.operationId" class="btn btn-secondary training-section" data-action="retry-comparison" :disabled="busy" @click="retryComparison">{{ comparisonFailed ? '重试对比' : '生成本次对比' }}</button>
      <div v-if="active && active.status !== 'DRAFT'" class="practice-actions practice-exits"><button class="btn btn-primary" data-action="again" :disabled="busy" @click="again">再练一次</button><RouterLink :to="projectGap ? projectReportPath : '/review'" class="btn btn-secondary">{{ reviewGap ? '返回原面试复盘' : projectGap ? '返回原面试证据矩阵' : '返回错题复习' }}</RouterLink></div>
      <details class="practice-debug training-section"><summary>历史记录与评估详情</summary><div class="training-flow">
        <p class="tiny muted">案例版本 {{ source.caseVersion }} · {{ exposure[source.analysisExposure] }}。历史原答的评分协议保留未知，配对重评另存结果。</p>
        <p v-if="source.contextTruncated" class="tiny muted">原资料已截断，不能据此断言内容缺失。</p>
        <details><summary>查看冻结的资料上下文</summary><pre>{{ contextText || '没有补充资料上下文' }}</pre></details>
        <details><summary>查看历次回答（{{ attempts.length }}）</summary><section v-for="a in attempts" :key="a.id" class="training-quote"><h3>{{ a.ordinal === 0 ? '原始回答' : `第 ${a.ordinal} 次新作答` }} · {{ labels[a.status] }}</h3><pre>{{ a.answerText || '草稿尚未填写' }}</pre><p class="tiny muted">{{ exposure[a.effectiveExposure] }} · {{ localDate(a.submittedAt) }}</p></section></details>
        <details v-if="active?.evaluation"><summary>查看本次评估协议与依据</summary><p v-if="active.evaluation.protocol" class="tiny muted">请求模型：{{ active.evaluation.protocol.requestedModel }}；返回模型：{{ active.evaluation.protocol.returnedModel || '服务未提供' }}；思考强度：{{ active.evaluation.protocol.parameters.reasoning_effort || '服务默认' }}</p><div v-for="check in active.evaluation.result?.checks" :key="check.checkId"><p>{{ check.explanation }}</p><p v-for="(evidence, index) in check.evidenceRefs" :key="index" class="tiny muted">{{ evidence.kind === 'QUOTE' ? `回答原文：${evidence.quote}` : `全文检查：${evidence.checkId}，范围 ${evidence.start}–${evidence.end}` }}</p></div></details>
        <RouterLink v-if="reviewId && active?.status === 'READY'" :to="{ path: `/review/${reviewId}`, query: { attemptId: active.id } }" class="text-link">返回复习项，逐条确认本次观察</RouterLink>
        <details @toggle="($event.target as HTMLDetailsElement).open && histories()"><summary>查看已保存对比历史</summary><button v-for="h in comparisonHistory" :key="h.id" class="text-link" :disabled="busy" @click="reopenComparison(h.id).catch(failed)">查看对比 · {{ h.status === 'READY' ? '已完成' : h.comparability === 'TEXT_ONLY' ? '仅原文' : '待完成' }}</button><button v-if="comparisonCursor" class="text-link" @click="histories(true)">加载更多对比</button></details>
        <details @toggle="($event.target as HTMLDetailsElement).open && loadGroups()"><summary>查看评估组历史与协议</summary><div v-for="g in groups" :key="g.id"><button class="text-link" @click="training<Evaluation>(`/practice-evaluation-groups/${g.id}`).then(saved => displayedGroup = saved).catch(failed)">查看评估组 · {{ labels[g.status] || g.status }}</button></div><button v-if="groupCursor" class="text-link" @click="loadGroups(true)">加载更多评估组</button><div v-if="displayedGroup"><p>请求模型：{{ displayedGroup.protocol?.requestedModel }} · 返回模型：{{ displayedGroup.protocol?.returnedModel || '服务未提供' }}</p><pre>{{ displayedGroup.result }}</pre></div><button v-if="active && ['READY', 'FAILED', 'INTERRUPTED'].includes(active.status)" class="text-link" :disabled="busy" @click="newGroup">按当前协议另存评估（调用模型）</button></details>
      </div></details>
    </template>
  </div>
</template>

<style scoped>
.practice-page { max-width: 1040px; }
.practice-question h2 { font-size: 22px; line-height: 1.7; }
.practice-reference { border-top: 1px solid var(--border-subtle); padding-top: var(--space-4); }
.practice-reference > div, .practice-debug > div { margin-top: var(--space-4); }
.practice-answer-heading, .practice-actions { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.practice-answer-heading { justify-content: space-between; }
.practice-page textarea { line-height: 1.85; }
.practice-result { border-top: 3px solid var(--accent); }
.practice-exits { margin-block: var(--space-6); }
.practice-debug { color: var(--text-muted); }
.practice-debug summary, .practice-reference summary { cursor: pointer; }
@media (max-width: 600px) { .practice-question h2 { font-size: 20px; } .practice-actions .btn { width: 100%; } }
</style>
