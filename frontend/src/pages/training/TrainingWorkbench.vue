<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Trash2, Search, Target, Zap, CircleCheck, Clock, BookOpen, Sparkles, Flame,  } from 'lucide-vue-next'
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

try {
  const saved = JSON.parse(sessionStorage.getItem(viewKey) || 'null')
  if (saved) {
    filter.value = saved.filter || 'all'
    search.value = saved.search || ''
    rememberedScroll = saved.scroll || 0
    rememberedPageScroll = saved.pageScroll || 0
  }
} catch {
  /* 无本页缓存时读取服务端记录。 */
}

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
    const group = cases.value.filter(c => related(c, r))
    group.forEach(c => attached.add(c.id))
    const progress = stateOf(group)
    const state = r.status === 'ARCHIVED' ? 'other' : progress !== 'todo' ? progress : r.status === 'RESOLVED' ? 'done' : 'todo'
    const uncertain = !r.findings.some(f => !f.disputed && f.kind !== 'uncertain')
    const effective = state === 'todo' && (r.status === 'WAITING_EVIDENCE' || uncertain) ? 'other' : state
    return {
      key: `review:${r.id}`,
      kind: 'review',
      id: r.id,
      revision: r.revision,
      question: r.question,
      origin: '面试复习',
      state: effective,
      label: r.status === 'ARCHIVED' ? '已归档' : effective === 'other' ? '待核实' : stateLabels[effective],
      cases: group,
      review: r,
    }
  })
  for (const c of cases.value) if (!attached.has(c.id)) {
    const origin = c.contextOrigin === 'REVIEW_GAP' ? '复盘补练' : c.contextOrigin === 'PROJECT_GAP' ? '项目补练' : c.sourceKind === 'STORY' ? '项目故事' : '原题重练'
    const state = stateOf([c])
    result.push({
      key: `case:${c.id}`,
      kind: 'case',
      id: c.id,
      revision: c.revision,
      question: c.question || '题目尚在准备',
      origin,
      state,
      label: stateLabels[state],
      cases: [c],
    })
  }
  return result
})

const practiceId = computed(() => route.path.toLowerCase().startsWith('/practice/') ? String(route.params.id || '') : '')
const reviewId = computed(() => route.path.toLowerCase().startsWith('/review') ? String(route.params.id || route.query.id || '') : '')
const hasSelection = computed(() => !!practiceId.value || !!reviewId.value)
const selected = computed(() => rows.value.find(r => practiceId.value ? r.cases.some(c => c.id === practiceId.value) : r.kind === 'review' && r.id === reviewId.value))
const filtered = computed(() => rows.value.filter(r => (filter.value === 'all' || r.state === filter.value) && `${r.question} ${r.origin}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const visible = computed(() => filtered.value.slice(0, visibleCount.value))

// 统计看板与智能推荐数据
const stats = computed(() => {
  const all = rows.value
  const todo = all.filter(r => r.state === 'todo').length
  const ongoing = all.filter(r => r.state === 'ongoing').length
  const done = all.filter(r => r.state === 'done').length
  return { total: all.length, todo, ongoing, done }
})

const recommendedRow = computed(() => {
  return rows.value.find(r => r.state === 'ongoing') || rows.value.find(r => r.state === 'todo') || rows.value[0] || null
})

function rememberView() {
  rememberedScroll = list.value?.scrollTop || 0
  if (route.meta.trainingWorkspace && !hasSelection.value) rememberedPageScroll = window.scrollY
  try {
    sessionStorage.setItem(viewKey, JSON.stringify({
      filter: filter.value,
      search: search.value,
      scroll: rememberedScroll,
      pageScroll: rememberedPageScroll,
    }))
  } catch {
    /* 服务端记录不依赖浏览器缓存。 */
  }
}

async function all<T>(path: string) {
  const result: T[] = []
  let cursor = ''
  do {
    const page = await training<Page<T>>(`${path}?limit=100&cursor=${encodeURIComponent(cursor)}`)
    result.push(...page.items)
    cursor = page.nextCursor || ''
  } while (cursor)
  return result
}

async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const [r, c] = await Promise.all([all<ReviewItem>('/review-items'), all<PracticeSummary>('/practice-cases')])
    if (current !== generation) return
    reviews.value = r
    cases.value = c
    await nextTick()
    if (list.value) list.value.scrollTop = rememberedScroll
    if (!hasSelection.value && rememberedPageScroll) window.scrollTo({ top: rememberedPageScroll })
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : '读取失败，请重新加载。'
  } finally {
    if (current === generation) loading.value = false
  }
}

async function open(row: Row) {
  rememberView()
  notice.value = ''
  if (row.kind === 'review') await router.push(`/review/${encodeURIComponent(row.id)}`)
  else await openCase(row.cases[0]!)
}

async function openCase(current: PracticeSummary) {
  rememberView()
  notice.value = ''
  await router.push({
    path: `/practice/${encodeURIComponent(current.id)}`,
    query: current.latestAttempt && current.latestAttempt.ordinal > 0 ? { attemptId: current.latestAttempt.id } : {},
  })
}

async function back() {
  rememberView()
  await router.push('/review')
  await nextTick()
  if (list.value) list.value.scrollTop = rememberedScroll
}

async function remove(row: Row) {
  if (deleting.value || loading.value) return
  if (!window.confirm(`确认删除“${row.question}”？将移除这条重练记录及关联练习入口，原面试报告和原始问答仍保留。`)) return
  deleting.value = row.key
  error.value = ''
  notice.value = ''
  try {
    await writeTraining(`/${row.kind === 'review' ? 'review-items' : 'practice-cases'}/${encodeURIComponent(row.id)}`, { expectedVersion: row.revision }, 'DELETE')
    if (selected.value?.key === row.key) await router.replace('/review')
    for (const c of row.cases) {
      try {
        if (c.latestAttempt) sessionStorage.removeItem(`v2:answer:${c.latestAttempt.id}`)
        sessionStorage.removeItem(`v2:practice-submission:${c.id}`)
      } catch {
        /* 已删除入口以服务端为准。 */
      }
    }
    await load()
    notice.value = '重练记录已删除，原面试报告与问答保留。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败，请重试。'
  } finally {
    deleting.value = ''
  }
}

watch([filter, search], () => {
  visibleCount.value = 30
  rememberView()
})

onMounted(load)
onBeforeUnmount(() => {
  generation++
  rememberView()
})
</script>

<template>
  <div class="page workbench-page workbench-delight">
    <header class="workbench-header-bar">
      <div class="header-main">
        <div class="title-with-badge">
          <h1>错题重练</h1>

        </div>
        <p class="header-caption">选一道题，重新作答，查看这次补齐了什么。</p>
      </div>
      <div v-if="rows.length" class="header-quick-stats">
        <span class="stat-pill"><Clock :size="14" class="icon-amber" /> 待攻坚 {{ stats.todo }}</span>
        <span class="stat-pill"><Zap :size="14" class="icon-blue" /> 进行中 {{ stats.ongoing }}</span>
        <span class="stat-pill"><CircleCheck :size="14" class="icon-emerald" /> 已完成 {{ stats.done }}</span>
      </div>
    </header>

    <Notice :message="error" kind="error" />
    <Notice :message="notice" />
    <button v-if="error" class="btn btn-secondary" :disabled="loading" data-workbench-reload @click="load">
      重新读取记录
    </button>

    <div class="workbench-layout" :class="{ 'has-selection': hasSelection }">
      <aside class="workbench-index panel" data-workbench-list aria-label="重练题目列表">
        <div class="workbench-filters">
          <div class="heading-row">
            <div class="title-group">
              <h2>我的题目</h2>
              <span class="count-badge">{{ rows.length }} 道</span>
            </div>
            <span v-if="filtered.length !== rows.length" class="tiny muted">筛选出 {{ filtered.length }} 道</span>
          </div>

          <label class="workbench-search" for="workbench-search">
            <Search :size="17" aria-hidden="true" />
            <input
              id="workbench-search"
              aria-label="搜索题目或来源"
              v-model="search"
              type="search"
              placeholder="搜索题目、技术考点或来源…"
            />
          </label>

          <div class="filter-row">
            <label for="workbench-filter" class="filter-label">练习状态</label>
            <div class="select-wrapper">
              <select id="workbench-filter" v-model="filter">
                <option value="all">全部题目（{{ rows.length }}）</option>
                <option value="todo">待练习（{{ stats.todo }}）</option>
                <option value="ongoing">练习中（{{ stats.ongoing }}）</option>
                <option value="done">已完成（{{ stats.done }}）</option>
                <option value="other">待核实 / 归档</option>
              </select>
            </div>
          </div>
        </div>

        <LoadingState v-if="loading && !rows.length" label="正在读取重练记录…" />

        <div ref="list" class="workbench-rows" @scroll.passive="rememberView">
          <article
            v-for="row in visible"
            :key="row.key"
            :data-workbench-row="row.key"
            class="workbench-row"
            :class="{ selected: selected?.key === row.key, [`state-${row.state}`]: true }"
          >
            <button
              class="workbench-open"
              :data-workbench-open="row.key"
              :aria-current="selected?.key === row.key ? 'true' : undefined"
              @click="open(row)"
            >
              <div class="workbench-row-meta">
                <span class="origin-tag" :class="`origin-${row.kind}`">{{ row.origin }}</span>
                <span class="status-tag" :class="`status-${row.state}`">{{ row.label }}</span>
              </div>
              <span class="workbench-question">{{ row.question }}</span>
              <span v-if="row.review" class="workbench-summary">
                <span class="summary-quote">原发现：</span>{{ row.review.findings.find(f => !f.disputed)?.text || '查看原题及历史记录' }}
              </span>
            </button>

            <div class="workbench-row-footer">
              <span v-if="row.review?.mock || row.cases[0]?.mock" class="tiny badge-mock-tag">Mock 演练</span>
              <span v-else class="tiny footer-hint">{{ row.cases.length ? '已有作答草稿' : '查看薄弱发现' }}</span>
              <button
                class="workbench-delete"
                :data-workbench-delete="row.key"
                :aria-label="`删除重练记录：${row.question}`"
                :disabled="!!deleting || loading"
                title="删除重练记录（原面试记录不受影响）"
                @click="remove(row)"
              >
                <Trash2 :size="14" aria-hidden="true" />
                <span>删除</span>
              </button>
            </div>
          </article>

          <div v-if="!loading && !visible.length" class="workbench-empty">
            <p class="muted">
              {{ rows.length ? '没有符合筛选条件的题目，请尝试重置搜索或状态。' : '还没有重练题目。完成面试后，可从报告中整理待练发现，或直接选择一道原题重新作答。' }}
            </p>
          </div>

          <button v-if="visibleCount < filtered.length" class="btn btn-secondary workbench-more" @click="visibleCount += 30">
            加载更多题目
          </button>
        </div>
      </aside>
      <section class="workbench-content" data-workbench-content aria-label="选中题目与作答">
        <div v-if="hasSelection" class="workbench-selection-bar">
          <button class="btn btn-secondary btn-sm workbench-back" @click="back">
            <ArrowLeft :size="15" />
            <span>返回题目列表</span>
          </button>
          <span class="selection-origin-tag">{{ selected?.origin || '历史练习记录' }}</span>
          <button
            v-if="selected"
            class="workbench-delete delete-selected-btn"
            data-workbench-delete-selected
            :disabled="!!deleting || loading"
            @click="remove(selected)"
          >
            <Trash2 :size="15" />
            <span>删除此项重练</span>
          </button>
        </div>

        <details v-if="selected?.kind === 'review' && selected.cases.length" class="workbench-history panel">
          <summary>同题作答记录（{{ selected.cases.length }}）</summary>
          <button
            v-for="c in selected.cases"
            :key="c.id"
            class="workbench-case text-link"
            :data-workbench-case="c.id"
            :aria-current="practiceId === c.id ? 'true' : undefined"
            @click="openCase(c)"
          >
            <span>{{ c.question }}</span>
            <span class="case-state-badge">{{ stateLabels[stateOf([c])] }}</span>
          </button>
        </details>
        <PracticeAnswer v-if="practiceId" :key="`case:${practiceId}`" embedded :case-id="practiceId" @changed="load" />
        <ReviewDetails v-else-if="reviewId" :key="`review:${reviewId}`" embedded :review-id="reviewId" @changed="load" />
        <div v-else class="workbench-welcome panel welcome-delight">
          <header class="welcome-header">
            <div class="welcome-icon-bubble">
              <Target :size="28" />
            </div>
            <div>
              <h2>{{ rows.length ? '选一道题，继续练习' : '从一次面试开始' }}</h2>
              <p>{{ rows.length ? '左侧选择题目，在这里查看原始问答、重新作答和比较变化。' : '完成面试后，从报告整理待练发现，或直接选择一道原题重新作答。' }}</p>
            </div>
          </header>
          <div v-if="rows.length" class="welcome-dashboard-grid">
            <div v-if="recommendedRow" class="card-highlight">
              <div class="card-highlight-badge">
                <Flame :size="15" />
                <span>智能推荐攻克</span>
              </div>
              <h3 class="highlight-question">{{ recommendedRow.question }}</h3>
              <p v-if="recommendedRow.review" class="highlight-desc">
                {{ recommendedRow.review.findings.find(f => !f.disputed)?.text || '针对原面试中的表达漏洞与关键细节进行专项补齐' }}
              </p>
              <div class="highlight-footer">
                <span class="origin-tag">{{ recommendedRow.origin }} · {{ recommendedRow.label }}</span>
                <button type="button" class="btn btn-primary btn-sm quick-action-btn" @click="open(recommendedRow)">
                  <span>{{ recommendedRow.state === 'ongoing' ? '继续上次作答' : '立即重新作答' }}</span>
                  <ArrowRight :size="15" />
                </button>
              </div>
            </div>
            <div class="methodology-card">
              <h4>
                <Sparkles :size="16" class="sparkle-icon" />
                科学重练三步法
              </h4>
              <div class="method-steps">
                <div class="step-item">
                  <span class="step-num">1</span>
                  <div>
                    <strong>溯源盲区</strong>
                    <p class="tiny muted">查看当时原答被指出的依据缺失、遗漏或表述模糊点。</p>
                  </div>
                </div>
                <div class="step-item">
                  <span class="step-num">2</span>
                  <div>
                    <strong>结构重构</strong>
                    <p class="tiny muted">用清晰工程逻辑与真实技术选型重新作答，不背固定模板。</p>
                  </div>
                </div>
                <div class="step-item">
                  <span class="step-num">3</span>
                  <div>
                    <strong>AI 差异对比</strong>
                    <p class="tiny muted">提交后对比两次回答的具体提升点，形成确定性肌肉记忆。</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="!rows.length && !loading" class="empty-action-area">
            <RouterLink to="/configure/quick" class="btn btn-primary">
              开始快速练习 <ArrowRight :size="16" />
            </RouterLink>
          </div>

          <p class="tiny muted workbench-scope">
            已完成表示本次作答和评估已保存，不代表已经掌握。待核实内容单独保留。
          </p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.workbench-page { max-width:none; }
.workbench-header-bar, .title-with-badge, .header-quick-stats, .stat-pill, .title-group { display:flex; align-items:center; gap:12px; }
.workbench-header-bar { justify-content:space-between; margin-bottom:20px; flex-wrap:wrap; }
.header-caption { color:var(--text-muted); margin-top:4px; }
.header-quick-stats { gap:16px; font:12px var(--font-mono); }.stat-pill { gap:6px; }.icon-amber { color:var(--warning); }.icon-blue { color:var(--accent); }.icon-emerald { color:var(--success); }
.workbench-layout { display:grid; grid-template-columns:minmax(290px, 38%) minmax(0,1fr); border:1px solid var(--border-subtle); border-radius:8px; min-height:580px; overflow:clip; }
.workbench-index { padding:0; border:0; border-right:1px solid var(--border-subtle); border-radius:0; background:var(--surface-soft); position:sticky; top:0; align-self:start; }
.workbench-filters { padding:14px; border-bottom:1px solid var(--border-subtle); }.workbench-filters .heading-row { margin-bottom:12px; }.title-group h2 { font-size:13px; }.count-badge { font:12px var(--font-mono); color:var(--text-muted); }
.workbench-search { display:flex; align-items:center; position:relative; margin-bottom:10px; }.workbench-search svg { position:absolute; left:10px; color:var(--text-muted); }.workbench-search input { padding-left:34px; font-size:13px; }
.filter-row { display:flex; align-items:center; gap:12px; }.filter-label { font-size:12px; font-weight:400; white-space:nowrap; margin:0; }.select-wrapper { flex:1; }.select-wrapper select { font-size:12px; }
.workbench-rows { max-height:65dvh; overflow:auto; }.workbench-row { display:flex; align-items:center; min-height:44px; border-bottom:1px solid var(--border-subtle); background:var(--surface); }.workbench-row:hover { background:var(--surface-soft); }.workbench-row.selected { background:var(--accent-soft); }
.workbench-open { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:4px 10px; min-width:0; flex:1; text-align:left; background:transparent; border:0; padding:10px 12px; color:var(--text-primary); }
.workbench-row-meta { display:flex; grid-column:1 / -1; gap:8px; align-items:center; }.origin-tag { font-size:11px; color:var(--text-muted); }.status-tag { font-size:11px; color:var(--warning); }.status-done { color:var(--success); }.status-ongoing { color:var(--accent); }
.workbench-question { display:block; grid-column:1 / -1; font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.workbench-summary { display:none; }
.workbench-row-footer { padding-right:8px; }.workbench-row-footer > .tiny { display:none; }.workbench-delete { border:0; background:transparent; color:var(--text-muted); display:flex; gap:6px; align-items:center; padding:8px; min-height:32px; font-size:12px; }.workbench-delete:hover { color:var(--danger); background:var(--danger-soft); border-radius:6px; }
.workbench-empty, .workbench-more { margin:16px; }.workbench-content { min-width:0; padding:20px; }.workbench-selection-bar { display:flex; align-items:center; gap:12px; padding-bottom:12px; border-bottom:1px solid var(--border-subtle); margin-bottom:16px; flex-wrap:wrap; }.selection-origin-tag { font-size:12px; color:var(--text-muted); }.delete-selected-btn { margin-left:auto; }.workbench-back { display:none; }
.workbench-history { padding:12px 0; border:0; border-bottom:1px solid var(--border-subtle); border-radius:0; margin-bottom:16px; }.workbench-case { display:flex; justify-content:space-between; width:100%; text-align:left; background:transparent; border:0; padding:8px 0; gap:12px; }.case-state-badge { flex-shrink:0; font-size:12px; }
.workbench-welcome { border:0; border-radius:0; padding:0; }.welcome-header { display:flex; gap:12px; align-items:flex-start; margin:4px 0 28px; }.welcome-icon-bubble { color:var(--text-muted); }.welcome-header h2 { font-size:16px; }.welcome-header p { color:var(--text-muted); margin-top:6px; max-width:60ch; }
.card-highlight { border-block:1px solid var(--border-subtle); padding:16px 0; }.card-highlight-badge { display:flex; align-items:center; gap:6px; font-size:12px; color:var(--text-muted); margin-bottom:10px; }.highlight-question { font-size:14px; }.highlight-desc { color:var(--text-muted); margin-top:8px; }.highlight-footer { display:flex; align-items:center; gap:12px; justify-content:space-between; flex-wrap:wrap; margin-top:16px; }
.methodology-card { padding-top:20px; }.methodology-card h4 { display:flex; align-items:center; gap:6px; font-size:12px; font-weight:500; margin:0 0 14px; color:var(--text-muted); }.method-steps { display:grid; gap:14px; }.step-item { display:flex; gap:12px; }.step-num { color:var(--text-muted); font:12px var(--font-mono); padding-top:3px; }.step-item strong { font-size:13px; font-weight:500; }.workbench-scope { margin-top:24px; padding-top:12px; border-top:1px solid var(--border-subtle); }.empty-action-area { margin:20px 0; }
.workbench-content :deep(.page) { padding:0; max-width:none; }
.workbench-content :deep(.panel) { border:0; border-radius:0; box-shadow:none; padding:12px 0; }
.workbench-content :deep(.training-reading-layout) { display:flex; flex-direction:column; gap:16px; }
.workbench-content :deep(.training-reading-layout > *) { width:100%; }
.workbench-content :deep(.training-actions) { order:-1; display:flex; flex-direction:row; align-items:center; flex-wrap:wrap; gap:10px; border-bottom:1px solid var(--border-subtle); padding:0 0 16px; }
.workbench-content :deep(.training-actions > .tiny) { flex-basis:100%; }
.workbench-content :deep(.training-actions h2) { font-size:13px; }
@media(max-width:1000px) { .workbench-layout { grid-template-columns:minmax(260px,40%) minmax(0,1fr); }.workbench-content { padding:16px; } }
@media(max-width:760px) { .workbench-layout { display:block; min-height:0; }.workbench-index { position:static; border:0; }.workbench-content { display:none; }.has-selection .workbench-index { display:none; }.has-selection .workbench-content { display:block; }.workbench-back { display:inline-flex; }.workbench-rows { max-height:65dvh; }.header-quick-stats { flex-wrap:wrap; gap:10px; }.workbench-delete { min-height:44px; }.workbench-open { min-height:60px; }.workbench-search input { font-size:16px; } }
</style>
