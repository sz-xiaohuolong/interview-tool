<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PhArrowRight, PhArrowClockwise } from '@phosphor-icons/vue'
import Notice from '../components/Notice.vue'
import LoadingState from '../components/LoadingState.vue'
import EvidenceReturn from '../components/EvidenceReturn.vue'
import { training, writeTraining } from '../api/training'
import { reviewLabels, findingLabels, localDate } from '../models/training'
import type { ReviewItem, Page, Skill } from '../models/training'

const route = useRoute(), router = useRouter()
const id = computed(() => String(route.params.id || route.query.id || ''))
const item = ref<ReviewItem | null>(null), items = ref<ReviewItem[]>([]), skills = ref<Skill[]>([])
const selected = ref<string[]>([]), reason = ref(''), error = ref(''), message = ref(''), busy = ref(false), loading = ref(true)
const findingIds = ref<string[]>([]), history = ref<any[]>([]), historyCursor = ref<string | null>(null), historical = ref<any>(null)
function skillNames(ids: string[] = []) { return ids.length ? ids.map(id => skills.value.find(s => s.id === id)?.name || id).join('、') : '未归类' }
async function loadHistory(more = false) { try { const page = await training<Page<any>>(`/review-items/${id.value}/history?limit=20&cursor=${encodeURIComponent(more ? historyCursor.value || '' : '')}`); history.value = more ? [...history.value, ...page.items] : page.items; historyCursor.value = page.nextCursor } catch (e) { error.value = String(e) } }
async function openCase(entry: ReviewItem, messageId: string) {
  const existing = (await sourceCases(entry)).find(c => c.sourceKind === 'SESSION' && c.sourceId === entry.sourceSessionId && c.messageId === messageId && c.mock === entry.mock)
  const saved = existing || await writeTraining<{ id: string }>('/practice-cases', { sourceKind: 'SESSION', sourceId: entry.sourceSessionId, messageId })
  await router.push(`/practice/${saved.id}`)
}
async function caseFrom(m: { id: string }) {
  if (!item.value || busy.value) return
  busy.value = true; error.value = ''
  try { await openCase(item.value, m.id) } catch (e) { error.value = e instanceof Error ? e.message : '读取原题失败。' } finally { busy.value = false }
}
const tab = ref(route.query.status === 'RESOLVED' ? 'done' : 'todo'), visibleCount = ref(20), listReady = ref(false)
const moreOpen = ref(['ARCHIVED', 'WAITING_EVIDENCE'].includes(String(route.query.status || '')))
interface PracticeCase { id: string; sourceKind: string; sourceId: string; messageId: string; questionIndex: number; mock: boolean; createdAt?: string }
interface PracticeAttempt { id: string; ordinal: number; status: string; submittedAt?: string; updatedAt?: string }
interface Progress { path: string; done: boolean; updatedAt?: string; report?: boolean }
const progress = ref<Record<string, Progress>>({})
function primaryQuestion(entry: ReviewItem) {
  const question = entry.dialogue?.find((m, index) => m.role === 'ASSISTANT' && !m.followUp && m.questionIndex === entry.questionIndex && entry.dialogue[index + 1]?.role === 'USER')
  if (!entry.sourceSessionId || !question) throw new Error('找不到已有回答的原问题，请核对来源报告。')
  return question
}
async function allPages<T>(path: string) {
  const result: T[] = []; let cursor = ''
  do {
    const page = await training<Page<T>>(`${path}${path.includes('?') ? '&' : '?'}limit=100&cursor=${encodeURIComponent(cursor)}`)
    result.push(...page.items); cursor = page.nextCursor || ''
  } while (cursor)
  return result
}
async function sourceCases(entry: ReviewItem) {
  return allPages<PracticeCase>(`/practice-cases?sourceId=${encodeURIComponent(entry.sourceSessionId)}`)
}
async function loadProgress(entries: ReviewItem[]) {
  const next: Record<string, Progress> = {}
  const candidates = entries.filter(e => e.status !== 'ARCHIVED' && actionable(e))
  const bySource = new Map<string, PracticeCase[]>()
  await Promise.all([...new Set(candidates.map(e => e.sourceSessionId))].map(async sourceId => {
    bySource.set(sourceId, await sourceCases({ sourceSessionId: sourceId } as ReviewItem))
  }))
  await Promise.all(candidates.map(async entry => {
    const possible = (bySource.get(entry.sourceSessionId) || []).filter(c => c.sourceKind === 'SESSION' && c.questionIndex === entry.questionIndex && c.mock === entry.mock)
    if (!possible.length) return
    const detail = entry.dialogue ? entry : await training<ReviewItem>(`/review-items/${encodeURIComponent(entry.id)}`)
    const question = primaryQuestion(detail)
    const matching = possible.filter(c => c.messageId === question.id)
    const states = await Promise.all(matching.map(async c => {
      const attempts = await allPages<PracticeAttempt>(`/practice-cases/${c.id}/attempts`)
      const latestAnswer = attempts.filter(a => a.ordinal > 0).sort((a, b) => b.ordinal - a.ordinal)[0]
      const active = attempts.filter(a => a.status === 'DRAFT').sort((a, b) => b.ordinal - a.ordinal)[0] || (latestAnswer?.status !== 'READY' ? latestAnswer : undefined)
      const ready = latestAnswer?.status === 'READY' ? latestAnswer : undefined
      const latest = active || ready
      return latest ? { path: `/practice/${c.id}?attemptId=${latest.id}`, done: !active, updatedAt: latest.submittedAt || latest.updatedAt || c.createdAt } : null
    }))
    const saved = states.find(state => state && !state.done) || states.find(Boolean)
    if (saved) next[entry.id] = saved
  }))
  const legacy = candidates.filter(e => e.status === 'IN_PROGRESS' && !next[e.id])
  // 兼容旧训练会话，只读取已有的会话与来源关联。
  if (legacy.length) {
    const sessions = await training<{ id: string; status: string; reportStatus?: string; mock: boolean; updatedAt?: string; endedAt?: string }[]>('/api/sessions')
    const links = await Promise.all(sessions.map(async session => ({ session, links: await training<{ sourceKind: string; sourceId: string }[]>(`/training-links?sessionId=${encodeURIComponent(session.id)}`) })))
    for (const entry of legacy) {
      const related = links.filter(({ session, links }) => session.mock === entry.mock && links.some(link => link.sourceKind === 'REVIEW' && link.sourceId === entry.id))
      const found = related.find(x => x.session.status !== 'COMPLETED') || related[0]
      if (found) {
        const session = found.session
        next[entry.id] = { path: `/${session.status === 'COMPLETED' ? 'reports' : 'sessions'}/${session.id}`, done: session.status === 'COMPLETED' && session.reportStatus === 'READY', report: session.status === 'COMPLETED', updatedAt: session.endedAt || session.updatedAt }
      }
    }
  }
  progress.value = next
}
function resume(entry: ReviewItem) { const saved = progress.value[entry.id]; if (saved) router.push(saved.path) }
function resultPath(entry: ReviewItem) { return progress.value[entry.id]?.path }

function actionable(entry: ReviewItem) { return entry.findings.some(f => !f.disputed) }
function completed(entry: ReviewItem) { const saved = progress.value[entry.id]; return entry.status === 'RESOLVED' || !!(saved?.done && !(entry.status === 'DUE' && entry.nextReviewAt && entry.updatedAt && (!saved.updatedAt || entry.updatedAt > saved.updatedAt))) }
function secondary(entry: ReviewItem) { return entry.status === 'WAITING_EVIDENCE' || !entry.findings.some(f => !f.disputed && f.kind !== 'uncertain') }
function displayStatus(entry: ReviewItem) { return entry.status === 'ARCHIVED' ? '已归档' : completed(entry) ? '已完成' : secondary(entry) ? '待核实' : entry.status === 'IN_PROGRESS' ? '练习未完成' : entry.status === 'PENDING_CONFIRMATION' ? '新发现' : '待复习' }
const ongoing = computed(() => items.value.filter(e => e.status !== 'ARCHIVED' && progress.value[e.id] && !progress.value[e.id]!.done && actionable(e)))
const pending = computed(() => items.value.filter(e => e.status !== 'ARCHIVED' && !ongoing.value.includes(e) && !completed(e) && !secondary(e)))
const done = computed(() => items.value.filter(e => e.status !== 'ARCHIVED' && completed(e)))
const extra = computed(() => items.value.filter(e => e.status === 'ARCHIVED' || (!completed(e) && !ongoing.value.includes(e) && secondary(e))))
const visible = computed(() => (tab.value === 'todo' ? pending.value : done.value).slice(0, visibleCount.value))
function selectTab(value: string) { tab.value = value; visibleCount.value = 20 }
function moveTab(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  selectTab(event.key === 'Home' ? 'todo' : event.key === 'End' ? 'done' : tab.value === 'todo' ? 'done' : 'todo')
  ;(event.currentTarget as HTMLElement).querySelector<HTMLButtonElement>(`#review-tab-${tab.value}`)?.focus()
}
function remember(detail: ReviewItem) { if (item.value?.id === detail.id) item.value = detail; items.value = items.value.map(e => e.id === detail.id ? detail : e) }
function summary(entry: ReviewItem) { return entry.findings.find(f => !f.disputed && f.kind !== 'uncertain')?.text || entry.findings.find(f => !f.disputed)?.text || '当前没有待练内容，可查看原文或归档。' }
async function load(cursor = '') {
  loading.value = true; error.value = ''
  try {
    if (id.value) {
      const [detail, profile] = await Promise.all([training<ReviewItem>(`/review-items/${encodeURIComponent(id.value)}`), training<{ catalog: { skills: Skill[] } }>('/skills')])
      item.value = detail; findingIds.value = detail.findings.filter(f => !f.disputed).map(f => f.id); selected.value = [...detail.skillIds]; skills.value = profile.catalog.skills
      await loadProgress([detail])
    } else {
      const all: ReviewItem[] = []
      do {
        const page = await training<Page<ReviewItem>>(`/review-items?limit=100&cursor=${encodeURIComponent(cursor)}`)
        all.push(...page.items); cursor = page.nextCursor || ''
      } while (cursor)
      items.value = [...new Map(all.map(e => [e.id, e])).values()]
      await loadProgress(items.value); listReady.value = true
    }
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败，请重试。' } finally { loading.value = false }
}
async function action(name: string, extra: Record<string, unknown> = {}) {
  if (!item.value || busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    item.value = await writeTraining<ReviewItem>(`/review-items/${item.value.id}/actions`, { expectedVersion: item.value.revision, action: name, ...extra })
    message.value = name === 'ARCHIVE' ? '已移出待复习，原文保存在归档中。' : '已保存。'
  } catch (e) { error.value = e instanceof Error ? e.message : '保存失败，本页选择已保留。' } finally { busy.value = false }
}
async function practice(entry: ReviewItem, chosen?: string[]) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    let current = await training<ReviewItem>(`/review-items/${encodeURIComponent(entry.id)}`)
    if (current.status === 'ARCHIVED') throw new Error('这道题已归档，请先恢复。')
    const targets = current.findings.filter(f => !f.disputed && (!chosen || chosen.includes(f.id)))
    if (!targets.length || (chosen && targets.length !== chosen.length)) throw new Error('请选择有效的待练内容；内容有变化时请重新读取。')
    const question = primaryQuestion(current)
    const ids = targets.map(f => f.id)
    if (targets.some(f => !f.confirmed)) {
      current = await writeTraining<ReviewItem>(`/review-items/${current.id}/actions`, { expectedVersion: current.revision, action: 'CONFIRM', skillIds: current.skillIds, findingIds: ids })
      remember(current)
    }
    if (current.status === 'DUE') {
      current = await writeTraining<ReviewItem>(`/review-items/${current.id}/actions`, { expectedVersion: current.revision, action: 'START' })
      remember(current)
    }
    await openCase(current, question.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '开始失败，请重试。'
  } finally { busy.value = false }
}
watch(id, () => { item.value = null; listReady.value = false; progress.value = {}; void load() }, { immediate: true })
</script>

<template>
  <div class="page training-page">
    <div class="page-heading heading-row"><div><h1>{{ id ? '回到问题，安排下一次练习。' : '把报告，变成下一次练习。' }}</h1><p>{{ id ? '查看待练内容，或直接开始重练。' : '挑一道没掌握的题，再练一次。' }}</p></div><RouterLink v-if="id" to="/review" class="text-link">返回错题复习</RouterLink></div>
    <Notice :message="error" kind="error" /><Notice :message="message" />
    <button v-if="error" class="btn btn-secondary" data-action="reload" :disabled="loading" @click="load()"><PhArrowClockwise :size="16" />重新读取服务端记录</button>
    <LoadingState v-if="loading" label="正在读取本机复习记录…" />
    <template v-if="!id && !loading && listReady">
      <section v-if="ongoing.length" class="review-resume" aria-label="未完成的练习">
        <article v-for="entry in ongoing" :key="entry.id" class="review-resume-row">
          <div><p class="review-eyebrow">{{ progress[entry.id]?.report ? '练习已结束，复盘尚未完成' : '你有一道题尚未练完' }}</p><h2>{{ entry.question }}</h2></div>
          <button class="btn btn-primary" data-action="practice" :disabled="busy" @click="resume(entry)">{{ progress[entry.id]?.report ? '查看复盘' : '继续作答' }} <PhArrowRight :size="16" /></button>
        </article>
      </section>
      <div class="review-tabs" role="tablist" aria-label="复习进度" @keydown="moveTab">
        <button id="review-tab-todo" role="tab" :aria-selected="tab === 'todo'" aria-controls="review-items" :tabindex="tab === 'todo' ? 0 : -1" @click="selectTab('todo')">待复习 <span>{{ pending.length }}</span></button>
        <button id="review-tab-done" role="tab" :aria-selected="tab === 'done'" aria-controls="review-items" :tabindex="tab === 'done' ? 0 : -1" @click="selectTab('done')">已完成 <span>{{ done.length }}</span></button>
      </div>
      <div id="review-items" class="review-items" role="tabpanel" :aria-labelledby="`review-tab-${tab}`">
        <div v-if="!visible.length" class="panel training-empty"><h2>{{ !items.length ? '还没有复习项' : tab === 'done' ? '还没有完成的练习' : '暂时没有待复习的题目' }}</h2><p>{{ !items.length ? '完成一场面试后，在报告页整理错误、遗漏与未知项。' : tab === 'done' ? '提交原题回答后，就可以在这里回看结果。' : ongoing.length ? '可以先继续上方尚未完成的练习。' : '新发现会直接出现在这里；待核实内容和归档可在下方查看。' }}</p><RouterLink v-if="!items.length" to="/configure/quick" class="btn btn-primary">开始面试 <PhArrowRight :size="16" /></RouterLink></div>
        <div v-else class="panel training-list"><article v-for="entry in visible" :key="entry.id" class="training-list-row review-card">
          <div><span v-if="entry.status === 'PENDING_CONFIRMATION' && !completed(entry)" class="badge badge-gray">新发现</span><span v-if="entry.mock" class="badge badge-orange">Mock 测试记录</span><h2><RouterLink :to="`/review/${entry.id}`">{{ entry.question }}</RouterLink></h2><p class="muted review-summary">{{ summary(entry) }}</p><p v-if="completed(entry)" class="tiny muted">练习记录：{{ localDate(progress[entry.id]?.updatedAt || entry.updatedAt) }}</p><p v-else-if="entry.nextReviewAt" class="tiny muted">复习安排：{{ localDate(entry.nextReviewAt) }}</p></div>
          <div class="review-card-actions"><template v-if="completed(entry)"><RouterLink v-if="resultPath(entry)" :to="resultPath(entry)!" class="btn btn-secondary">查看本次结果 <PhArrowRight :size="16" /></RouterLink><RouterLink :to="`/review/${entry.id}`" class="text-link">查看练习记录</RouterLink></template><template v-else><button class="btn btn-primary" data-action="practice" :disabled="busy" @click="practice(entry)">{{ busy ? '正在准备…' : '开始重练' }} <PhArrowRight :size="16" /></button><RouterLink :to="`/review/${entry.id}`" class="text-link">异议 / 移除</RouterLink></template></div>
        </article></div>
        <button v-if="visible.length < (tab === 'todo' ? pending.length : done.length)" class="btn btn-secondary review-load-more" @click="visibleCount += 20">加载更多</button>
      </div>
      <p v-if="tab === 'done' && done.length" class="tiny muted review-completion-note">已完成表示回答与评估已保存，具体变化请查看本次结果。</p>
      <details v-if="extra.length" class="review-more" :open="moreOpen"><summary>更多：待核实与归档（{{ extra.length }}）</summary><div class="panel training-list"><article v-for="entry in extra" :key="entry.id" class="training-list-row"><div><span class="badge badge-gray">{{ entry.status === 'ARCHIVED' ? '已归档' : '待核实' }}</span><h2>{{ entry.question }}</h2><p class="muted">{{ entry.status === 'ARCHIVED' ? '原文和历史仍然保留。' : actionable(entry) ? '先补充资料或核对原文，不计为错题。' : '当前没有待练内容，可核对来源或归档。' }}</p></div><RouterLink :to="`/review/${entry.id}`" class="text-link">查看内容 <PhArrowRight :size="16" /></RouterLink></article></div></details>
    </template>
    <div v-if="item" class="training-reading-layout">
      <article class="panel training-flow"><div><span class="badge badge-gray">{{ displayStatus(item) }}</span><span v-if="item.mock" class="badge badge-orange">Mock · 仅验证流程</span><h2>{{ item.question }}</h2><p class="tiny muted">主问题 {{ item.questionIndex + 1 }} · 仅有整题依据</p><RouterLink :to="`/reports/${item.sourceSessionId}`" class="text-link">查看来源报告</RouterLink></div>
        <details><summary>查看原始问答</summary><div v-for="m in item.dialogue" :key="m.id" class="training-quote"><strong>{{ m.role === 'USER' ? '我的原始回答' : m.followUp ? '追问' : '原问题' }}</strong><pre>{{ m.content }}</pre><button v-if="m.role === 'ASSISTANT' && item.dialogue.some((x, i) => x.id === m.id && item!.dialogue[i + 1]?.role === 'USER')" class="text-link" :disabled="busy" @click="caseFrom(m)">只练这条原问题</button></div></details>
        <section><h3>报告中的发现</h3><p v-if="!item.findings.length" class="notice">这版报告没有待练发现。请核对来源版本；可归档保留历史，这不表示能力已提升。</p><p class="tiny muted">模型结论不是事实证明。异议会保留原文并排除当前自动支持。</p><div v-for="finding in item.findings" :key="finding.id" class="training-finding"><label v-if="!finding.disputed" class="training-inline-check"><input v-model="findingIds" type="checkbox" :value="finding.id" :disabled="busy || item.status === 'ARCHIVED'" />选择这条待练发现</label><span class="badge" :class="finding.kind === 'uncertain' ? 'badge-gray' : 'badge-orange'">{{ findingLabels[finding.kind] }}{{ finding.disputed ? ' · 有异议' : '' }}</span><p>{{ finding.text }}</p><p v-if="finding.disputed" class="tiny muted">异议：{{ finding.disputeReason }}</p><button v-else class="text-link" :disabled="busy || !reason.trim() || item.status === 'ARCHIVED'" @click="action('DISPUTE', { findingIds: [finding.id], reason })">对这条结论提出异议</button></div><label for="dispute-reason">异议说明</label><textarea id="dispute-reason" v-model="reason" maxlength="1000" placeholder="说明原回答中哪些内容支持你的判断。" /></section>
        <details><summary>技能标签</summary><p class="tiny muted">无法归类时可留空；标签不改变原报告判断。</p><div class="training-checks"><label v-for="skill in skills" :key="skill.id"><input v-model="selected" type="checkbox" :value="skill.id" :disabled="busy || item.status === 'ARCHIVED'" />{{ skill.name }}</label></div><button v-if="item.status !== 'ARCHIVED'" class="btn btn-secondary" :disabled="busy" @click="action('CLASSIFY', { skillIds: selected })">保存标签</button></details>
        <details v-if="item.sourceVersions?.length"><summary>查看来源报告版本（{{ item.sourceVersions.length }} 个旧版）</summary><section v-for="version in item.sourceVersions" :key="version.reportHash" class="training-quote"><h3>{{ version.question }}</h3><p class="tiny muted">保存时间：{{ localDate(version.updatedAt) }} · 当时状态：{{ reviewLabels[version.status] }}</p><p>当时标签：{{ skillNames(version.skillIds) }}</p><details><summary>版本标识与整题依据</summary><pre>{{ version.reportHash }}</pre><p>{{ version.sourceLimit }}</p><pre>{{ version.sourceReport?.answerOutline }}</pre><pre>{{ version.sourceReport?.knowledgeExplanation }}</pre></details><div v-for="m in version.dialogue" :key="m.id"><strong>{{ m.role === 'USER' ? '当时回答' : '当时问题' }}</strong><pre>{{ m.content }}</pre></div><p v-for="f in version.findings" :key="f.id">{{ findingLabels[f.kind] }}：{{ f.text }}{{ f.disputed ? '（有异议）' : '' }}</p><p v-if="!version.findings.length" class="tiny muted">这版报告没有待练发现。</p></section></details>
        <details v-if="item.findings.some(f => f.confirmed && !f.disputed) && item.status !== 'ARCHIVED'" :open="!!(route.query.attemptId || route.query.trainingLinkId)"><summary>核对练习结果与原发现</summary><EvidenceReturn :item="item" @saved="load()" /></details>
        <details @toggle="($event.target as HTMLDetailsElement).open && loadHistory()"><summary>查看确认、补证与安排历史</summary><div v-for="h in history" :key="h.id"><button class="text-link" @click="training<any>(`/review-items/${id}/history/${h.id}`).then(r => historical = r).catch(e => error = String(e))">查看 {{ localDate(h.createdAt) }} 的原文与判词快照</button></div><button v-if="historyCursor" class="text-link" @click="loadHistory(true)">继续读取历史</button><div v-if="historical"><p>当时状态：{{ reviewLabels[historical.status] }}</p><p>当时标签：{{ skillNames(historical.skillIds) }}</p><p v-for="f in historical.findings" :key="f.id">{{ findingLabels[f.kind] }}：{{ f.text }}{{ f.disputed ? '（有异议）' : '' }}</p><p v-for="b in historical.lastEvidence?.bindings" :key="b.findingId">{{ b.reportText || b.checkId }}；用户覆盖说明：{{ b.reason }}</p></div></details>
      </article>
      <aside class="panel training-flow training-actions"><h2>{{ completed(item) ? '练习记录' : '再练一次' }}</h2><p class="muted">{{ displayStatus(item) }}</p>
        <RouterLink v-if="completed(item) && resultPath(item)" :to="resultPath(item)!" class="btn btn-primary">查看本次结果 <PhArrowRight :size="16" /></RouterLink>
        <button v-else-if="item.status !== 'ARCHIVED' && progress[item.id] && !progress[item.id]!.done" class="btn btn-primary" :disabled="busy" @click="resume(item)">{{ progress[item.id]?.report ? '查看复盘' : '继续作答' }} <PhArrowRight :size="16" /></button>
        <button v-else-if="item.status !== 'ARCHIVED' && !completed(item) && actionable(item)" class="btn btn-primary" data-action="practice" :disabled="busy" @click="practice(item, findingIds)">{{ busy ? '正在准备…' : '开始重练' }} <PhArrowRight :size="16" /></button>
        <p v-if="!completed(item) && item.status !== 'ARCHIVED'" class="tiny muted">重新回答原题，提交后直接查看与原答的变化。</p>
        <details v-if="item.status === 'IN_PROGRESS' && item.lastEvidence"><summary>更多复习安排</summary><div class="training-flow"><button class="btn btn-secondary" :disabled="busy" @click="action('RESCHEDULE', { ...item.lastEvidence, findingIds: item.lastEvidence.bindings?.map((b: any) => b.findingId), intervalDays: 1 })">明日继续</button><button class="btn btn-secondary" :disabled="busy" @click="action('RESCHEDULE', { ...item.lastEvidence, findingIds: item.lastEvidence.bindings?.map((b: any) => b.findingId), intervalDays: 3 })">3 天后再练</button><button class="btn btn-secondary" :disabled="busy" @click="action('RESOLVE', { ...item.lastEvidence, findingIds: item.lastEvidence.bindings?.map((b: any) => b.findingId) })">结束本轮</button></div></details>
        <button class="btn btn-ghost" :disabled="busy" @click="action(item.status === 'ARCHIVED' ? 'RESTORE' : 'ARCHIVE')">{{ item.status === 'ARCHIVED' ? '恢复归档项' : '移出待复习' }}</button>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.review-tabs { display: flex; gap: var(--space-6); border-bottom: 1px solid var(--border-subtle); margin-bottom: var(--space-5); }
.review-tabs button { display: inline-flex; align-items: center; gap: var(--space-2); min-height: 48px; border: 0; border-bottom: 2px solid transparent; padding: var(--space-3) 0; background: transparent; color: var(--text-muted); font: inherit; cursor: pointer; }
.review-tabs button[aria-selected="true"] { border-bottom-color: var(--accent); color: var(--accent-ink); font-weight: 600; }
.review-tabs span { padding: 0 var(--space-2); border-radius: 5px; background: var(--surface-soft); font-size: 13px; font-variant-numeric: tabular-nums; }
.review-resume { margin-bottom: var(--space-6); padding: 0 var(--space-5); border: 1px solid var(--border-subtle); border-left: 3px solid var(--accent); border-radius: var(--radius-control); background: var(--accent-soft); }
.review-resume-row { display: flex; gap: var(--space-5); align-items: center; justify-content: space-between; padding-block: var(--space-4); }
.review-resume-row + .review-resume-row { border-top: 1px solid var(--border-subtle); }
.review-resume-row > div { min-width: 0; }
.review-resume-row h2 { margin-top: var(--space-1); font-size: 16px; }
.review-resume-row .btn { flex-shrink: 0; }
.review-eyebrow { font-size: 13px; color: var(--accent-ink); }
.review-card-actions { display: flex; flex-direction: column; align-items: flex-end; gap: var(--space-1); }
.review-card-actions .text-link { font-size: 13px; }
.review-card h2 { margin-top: var(--space-1); }
.review-summary { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 1; overflow: hidden; }
.review-more { margin-top: var(--space-6); color: var(--text-muted); }
.review-more summary { cursor: pointer; font-size: 14px; }
.review-more .panel { margin-top: var(--space-3); }
.review-load-more, .review-completion-note { margin-top: var(--space-4); }
@media (max-width: 600px) { .review-resume-row { align-items: flex-start; flex-direction: column; gap: var(--space-3); } .review-card-actions { flex-direction: row; align-items: center; justify-content: space-between; } }
</style>
