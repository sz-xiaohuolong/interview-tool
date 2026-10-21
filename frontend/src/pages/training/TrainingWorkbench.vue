<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PhArrowLeft, PhArrowRight, PhTrash, PhMagnifyingGlass } from '@phosphor-icons/vue'
import { training, writeTraining } from '../../api/training'
import type { Page, ReviewItem } from '../../models/training'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import ReviewDetails from './Review.vue'
import PracticeAnswer from './Practice.vue'

interface AttemptSummary { id: string; ordinal: number; status: string; updatedAt?: string }
interface PracticeSummary { id: string; revision: number; question: string; sourceKind: string; sourceId: string; sourceSessionKind?: string; questionIndex?: number; mock: boolean; contextOrigin?: string; latestAttempt?: AttemptSummary }
type State = 'todo' | 'ongoing' | 'done' | 'other'
interface Row { key: string; kind: 'review' | 'case'; id: string; revision: number; question: string; origin: string; state: State; label: string; cases: PracticeSummary[]; review?: ReviewItem }
const route = useRoute(), router = useRouter()
const reviews = ref<ReviewItem[]>([]), cases = ref<PracticeSummary[]>([]), loading = ref(true), error = ref(''), notice = ref(''), deleting = ref('')
const list = ref<HTMLElement | null>(null), filter = ref('all'), search = ref(''), visibleCount = ref(30)
const viewKey = 'training:workbench:view'
let generation = 0, rememberedScroll = 0, rememberedPageScroll = 0
try { const saved = JSON.parse(sessionStorage.getItem(viewKey) || 'null'); if (saved) { filter.value = saved.filter || 'all'; search.value = saved.search || ''; rememberedScroll = saved.scroll || 0; rememberedPageScroll = saved.pageScroll || 0 } } catch { /* 无本页缓存时读取服务端记录。 */ }
if (route.query.status === 'RESOLVED') filter.value = 'done'
function related(c: PracticeSummary, r: ReviewItem) {
  return c.sourceKind === 'SESSION' && (c.sourceSessionKind || 'SESSION') === 'SESSION' && c.sourceId === r.sourceSessionId && c.questionIndex === r.questionIndex && c.mock === r.mock
}
function stateOf(group: PracticeSummary[]): State {
  if (group.some(c => c.latestAttempt && c.latestAttempt.ordinal > 0 && c.latestAttempt.status !== 'READY')) return 'ongoing'
  return group.some(c => c.latestAttempt?.status === 'READY') ? 'done' : 'todo'
}
const stateLabels: Record<State, string> = { todo: '待练习', ongoing: '练习中', done: '已完成', other: '待核实 / 归档' }
const rows = computed<Row[]>(() => {
  const attached = new Set<string>()
  const result: Row[] = reviews.value.map(r => {
    const group = cases.value.filter(c => related(c, r)); group.forEach(c => attached.add(c.id))
    const progress = stateOf(group)
    const state = r.status === 'ARCHIVED' ? 'other' : progress !== 'todo' ? progress : r.status === 'RESOLVED' ? 'done' : 'todo'
    const uncertain = !r.findings.some(f => !f.disputed && f.kind !== 'uncertain')
    const effective = state === 'todo' && (r.status === 'WAITING_EVIDENCE' || uncertain) ? 'other' : state
    return { key: `review:${r.id}`, kind: 'review', id: r.id, revision: r.revision, question: r.question, origin: '面试复习', state: effective, label: r.status === 'ARCHIVED' ? '已归档' : effective === 'other' ? '待核实' : stateLabels[effective], cases: group, review: r }
  })
  for (const c of cases.value) if (!attached.has(c.id)) {
    const origin = c.contextOrigin === 'REVIEW_GAP' ? '复盘补练' : c.contextOrigin === 'PROJECT_GAP' ? '项目补练' : c.sourceKind === 'STORY' ? '项目故事' : '原题重练'
    const state = stateOf([c])
    result.push({ key: `case:${c.id}`, kind: 'case', id: c.id, revision: c.revision, question: c.question || '题目尚在准备', origin, state, label: stateLabels[state], cases: [c] })
  }
  return result
})
const practiceId = computed(() => route.path.toLowerCase().startsWith('/practice/') ? String(route.params.id || '') : '')
const reviewId = computed(() => route.path.toLowerCase().startsWith('/review') ? String(route.params.id || route.query.id || '') : '')
const hasSelection = computed(() => !!practiceId.value || !!reviewId.value)
const selected = computed(() => rows.value.find(r => practiceId.value ? r.cases.some(c => c.id === practiceId.value) : r.kind === 'review' && r.id === reviewId.value))
const filtered = computed(() => rows.value.filter(r => (filter.value === 'all' || r.state === filter.value) && `${r.question} ${r.origin}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const visible = computed(() => filtered.value.slice(0, visibleCount.value))
function rememberView() {
  rememberedScroll = list.value?.scrollTop || 0
  if (route.meta.trainingWorkspace && !hasSelection.value) rememberedPageScroll = window.scrollY
  try { sessionStorage.setItem(viewKey, JSON.stringify({ filter: filter.value, search: search.value, scroll: rememberedScroll, pageScroll: rememberedPageScroll })) } catch { /* 服务端记录不依赖浏览器缓存。 */ }
}
async function all<T>(path: string) {
  const result: T[] = []; let cursor = ''
  do { const page = await training<Page<T>>(`${path}?limit=100&cursor=${encodeURIComponent(cursor)}`); result.push(...page.items); cursor = page.nextCursor || '' } while (cursor)
  return result
}
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const [r, c] = await Promise.all([all<ReviewItem>('/review-items'), all<PracticeSummary>('/practice-cases')])
    if (current !== generation) return
    reviews.value = r; cases.value = c
    await nextTick(); if (list.value) list.value.scrollTop = rememberedScroll
    if (!hasSelection.value && rememberedPageScroll) window.scrollTo({ top: rememberedPageScroll })
  } catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '读取失败，请重新加载。' }
  finally { if (current === generation) loading.value = false }
}
async function open(row: Row) {
  rememberView(); notice.value = ''
  if (row.kind === 'review') await router.push(`/review/${encodeURIComponent(row.id)}`)
  else await openCase(row.cases[0]!)
}
async function openCase(current: PracticeSummary) {
  rememberView(); notice.value = ''
  await router.push({ path: `/practice/${encodeURIComponent(current.id)}`, query: current.latestAttempt && current.latestAttempt.ordinal > 0 ? { attemptId: current.latestAttempt.id } : {} })
}
async function back() { rememberView(); await router.push('/review'); await nextTick(); if (list.value) list.value.scrollTop = rememberedScroll }
async function remove(row: Row) {
  if (deleting.value || loading.value) return
  if (!window.confirm(`确认删除“${row.question}”？将移除这条重练记录及关联练习入口，原面试报告和原始问答仍保留。`)) return
  deleting.value = row.key; error.value = ''; notice.value = ''
  try {
    await writeTraining(`/${row.kind === 'review' ? 'review-items' : 'practice-cases'}/${encodeURIComponent(row.id)}`, { expectedVersion: row.revision }, 'DELETE')
    if (selected.value?.key === row.key) await router.replace('/review')
    for (const c of row.cases) try { if (c.latestAttempt) sessionStorage.removeItem(`v2:answer:${c.latestAttempt.id}`); sessionStorage.removeItem(`v2:practice-submission:${c.id}`) } catch { /* 已删除入口以服务端为准。 */ }
    await load(); notice.value = '重练记录已删除，原面试报告与问答保留。'
  } catch (e) { error.value = e instanceof Error ? e.message : '删除失败，请重试。' }
  finally { deleting.value = '' }
}
watch([filter, search], () => { visibleCount.value = 30; rememberView() })
onMounted(load)
onBeforeUnmount(() => { generation++; rememberView() })
</script>

<template>
  <div class="page workbench-page">
    <div class="page-heading"><h1>错题重练</h1><p>选一道题，重新作答，查看这次补齐了什么。</p></div>
    <Notice :message="error" kind="error" /><Notice :message="notice" />
    <button v-if="error" class="btn btn-secondary" :disabled="loading" data-workbench-reload @click="load">重新读取记录</button>
    <div class="workbench-layout" :class="{ 'has-selection': hasSelection }">
      <aside class="workbench-index panel" data-workbench-list aria-label="重练题目列表">
        <div class="workbench-filters">
          <div class="heading-row"><h2>我的题目</h2><span class="tiny muted">{{ rows.length }} 道</span></div>
          <label class="workbench-search" for="workbench-search"><PhMagnifyingGlass :size="18" aria-hidden="true" /><input id="workbench-search" aria-label="搜索题目或来源" v-model="search" type="search" placeholder="搜索题目或来源" /></label>
          <label for="workbench-filter" class="tiny muted">练习状态</label>
          <select id="workbench-filter" v-model="filter"><option value="all">全部题目</option><option value="todo">待练习</option><option value="ongoing">练习中</option><option value="done">已完成</option><option value="other">待核实 / 归档</option></select>
        </div>
        <LoadingState v-if="loading && !rows.length" label="正在读取重练记录…" />
        <div ref="list" class="workbench-rows" @scroll.passive="rememberView">
          <article v-for="row in visible" :key="row.key" :data-workbench-row="row.key" class="workbench-row" :class="{ selected: selected?.key === row.key }">
            <button class="workbench-open" :data-workbench-open="row.key" :aria-current="selected?.key === row.key ? 'true' : undefined" @click="open(row)">
              <span class="workbench-row-meta"><span>{{ row.origin }}</span><span>{{ row.label }}</span></span>
              <span class="workbench-question">{{ row.question }}</span>
              <span v-if="row.review" class="workbench-summary">{{ row.review.findings.find(f => !f.disputed)?.text || '查看原题及历史记录' }}</span>
            </button>
            <div class="workbench-row-footer"><span v-if="row.review?.mock || row.cases[0]?.mock" class="tiny muted">Mock 测试记录</span><span v-else class="tiny muted">{{ row.cases.length ? '已有作答入口' : '查看待练发现' }}</span><button class="workbench-delete" :data-workbench-delete="row.key" :aria-label="`删除重练记录：${row.question}`" :disabled="!!deleting || loading" @click="remove(row)"><PhTrash :size="16" aria-hidden="true" />删除</button></div>
          </article>
          <p v-if="!loading && !visible.length" class="workbench-empty tiny muted">{{ rows.length ? '没有符合筛选条件的题目。' : '还没有重练题目。可以从面试报告中选择原题或补练。' }}</p>
          <button v-if="visibleCount < filtered.length" class="btn btn-secondary workbench-more" @click="visibleCount += 30">加载更多</button>
        </div>
      </aside>
      <section class="workbench-content" data-workbench-content aria-label="选中题目与作答">
        <div v-if="hasSelection" class="workbench-selection-bar"><button class="text-link workbench-back" @click="back"><PhArrowLeft :size="16" />返回题目列表</button><span class="tiny muted">{{ selected?.origin || '历史练习记录' }}</span><button v-if="selected" class="workbench-delete" data-workbench-delete-selected :disabled="!!deleting || loading" @click="remove(selected)"><PhTrash :size="16" />删除此项</button></div>
        <details v-if="selected?.kind === 'review' && selected.cases.length" class="workbench-history panel">
          <summary>同题作答记录（{{ selected.cases.length }}）</summary>
          <button v-for="c in selected.cases" :key="c.id" class="workbench-case text-link" :data-workbench-case="c.id" :aria-current="practiceId === c.id ? 'true' : undefined" @click="openCase(c)">{{ c.question }} <span class="tiny muted">{{ stateLabels[stateOf([c])] }}</span></button>
        </details>
        <PracticeAnswer v-if="practiceId" :key="`case:${practiceId}`" embedded :case-id="practiceId" @changed="load" />
        <ReviewDetails v-else-if="reviewId" :key="`review:${reviewId}`" embedded :review-id="reviewId" @changed="load" />
        <div v-else class="workbench-welcome panel"><h2>{{ rows.length ? '选一道题，继续练习' : '从一次面试开始' }}</h2><p>{{ rows.length ? '左侧选择题目，在这里查看原始问答、重新作答和比较变化。' : '完成面试后，从报告整理待练发现，或直接选择一道原题重新作答。' }}</p><RouterLink v-if="!rows.length && !loading" to="/configure/quick" class="btn btn-primary">开始快速练习 <PhArrowRight :size="16" /></RouterLink><p class="tiny muted workbench-scope">已完成表示本次作答和评估已保存，不代表已经掌握。待核实内容单独保留。</p></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.workbench-layout { display: grid; grid-template-columns: minmax(270px, 330px) minmax(0, 1fr); gap: 24px; align-items: start; }
.workbench-index { position: sticky; top: 24px; padding: 0; overflow: hidden; }
.workbench-filters { padding: 20px; border-bottom: 1px solid var(--border-subtle); }
.workbench-filters h2 { font-size: 18px; }
.workbench-filters .heading-row { margin-bottom: 16px; }
.workbench-search { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
.workbench-search svg { flex-shrink: 0; color: var(--text-muted); }
.workbench-search input { min-width: 0; }
.workbench-filters select { width: 100%; margin-top: 6px; }
.workbench-rows { max-height: calc(100dvh - 360px); min-height: 180px; overflow-y: auto; overscroll-behavior: contain; }
.workbench-row { padding: 8px 16px 8px 20px; border-bottom: 1px solid var(--border-subtle); }
.workbench-row:last-of-type { border-bottom: 0; }
.workbench-row.selected { background: var(--accent-soft); }
.workbench-open { display: block; width: 100%; padding: 10px 0; border: 0; background: transparent; text-align: left; color: var(--text-primary); font: inherit; cursor: pointer; }
.workbench-open:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; border-radius: 4px; }
.workbench-row-meta { display: flex; justify-content: space-between; gap: 8px; color: var(--text-secondary); font-size: 12px; }
.workbench-question { display: block; margin-top: 10px; font-size: 15px; font-weight: 600; line-height: 1.7; overflow-wrap: anywhere; }
.workbench-summary { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-top: 8px; font-size: 13px; line-height: 1.7; color: var(--text-secondary); }
.workbench-row-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.workbench-delete { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 44px; padding: 8px; border: 0; background: transparent; color: var(--danger); font: inherit; font-size: 13px; cursor: pointer; }
.workbench-delete:disabled { opacity: .55; cursor: wait; }
.workbench-content { min-width: 0; }
.workbench-history { margin-bottom: 16px; padding: 16px 20px; }
.workbench-history summary { cursor: pointer; font-size: 14px; }
.workbench-case { display: flex; justify-content: space-between; gap: 12px; width: 100%; min-height: 44px; margin-top: 8px; text-align: left; overflow-wrap: anywhere; }
.workbench-case span { flex-shrink: 0; }
.workbench-selection-bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 16px; }
.workbench-selection-bar .workbench-delete { margin-left: auto; }
.workbench-back { display: inline-flex; gap: 6px; align-items: center; min-height: 44px; }
.workbench-welcome { padding: 36px; }
.workbench-welcome h2 { font-size: 22px; margin-bottom: 12px; }
.workbench-welcome p { max-width: 44em; line-height: 1.8; }
.workbench-welcome .btn { margin-top: 20px; }
.workbench-scope { margin-top: 36px; }
.workbench-empty { padding: 24px 20px; line-height: 1.8; }
.workbench-more { margin: 16px; }
.workbench-content :deep(.page) { padding: 0; max-width: none; }
.workbench-content :deep(.training-reading-layout) { display: flex; flex-direction: column; gap: 20px; }
.workbench-content :deep(.training-actions) { order: -1; position: static; }
.workbench-content :deep(pre) { overflow-wrap: anywhere; white-space: pre-wrap; }
@media (max-width: 1000px) {
  .workbench-layout { grid-template-columns: minmax(230px, 280px) minmax(0, 1fr); gap: 16px; }
  .workbench-filters { padding: 16px; }
}
@media (max-width: 760px) {
  .workbench-layout { display: block; }
  .workbench-index { position: static; }
  .workbench-rows { max-height: none; }
  .workbench-content { display: none; }
  .workbench-layout.has-selection .workbench-index { display: none; }
  .workbench-layout.has-selection .workbench-content { display: block; }
  .workbench-welcome { padding: 24px; }
  .workbench-selection-bar > .tiny { display: none; }
}
</style>
