<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Trash2, Search, Target, CircleCheck, BookOpen, Layers3, GitCompareArrows, Flame, X, ChevronDown } from 'lucide-vue-next'
import { training, writeTraining } from '../../api/training'
import type { Page, ReviewItem, Skill } from '../../models/training'
import { trainingNavigation } from '../../navigation'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import ReviewDetails from './Review.vue'
import PracticeAnswer from './Practice.vue'

interface AttemptSummary { id: string; ordinal: number; status: string; updatedAt?: string }
interface PracticeSummary { id: string; revision: number; question: string; sourceKind: string; sourceId: string; sourceSessionKind?: string; questionIndex?: number; mock: boolean; contextOrigin?: string; latestAttempt?: AttemptSummary }
type State = 'todo' | 'ongoing' | 'done'
interface Row { key: string; kind: 'review' | 'case'; id: string; revision: number; question: string; origin: string; state: State; label: string; cases: PracticeSummary[]; review?: ReviewItem }

const route = useRoute(), router = useRouter()
const reviews = ref<ReviewItem[]>([]), cases = ref<PracticeSummary[]>([]), loading = ref(true), error = ref(''), notice = ref(''), deleting = ref('')
const list = ref<HTMLElement | null>(null), filter = ref('all'), search = ref(''), visibleCount = ref(30)
const searchInput = ref<HTMLInputElement | null>(null), skills = ref<Skill[]>([])
const activeMethod = ref('trace')
const methods = [
  { id: 'trace', title: '盲区溯源', caption: '找到上次遗漏的关键点', icon: BookOpen, tip: '先回看上次薄弱点与原答，找出一个需要补齐的细节。没有明确发现时，从题目要求重新梳理。' },
  { id: 'rebuild', title: '结构重构', caption: '把思路组织成完整回答', icon: Layers3, tip: '先说结论，再展开方案与取舍，最后补充异常边界。用自己的项目细节说明原因，不背固定模板。' },
  { id: 'compare', title: 'AI 差异对比', caption: '看见两次作答的变化', icon: GitCompareArrows, tip: '提交后查看反馈与两次回答的差异，核对这次补齐了什么。首次作答先建立基线，评估完成不等于已经掌握。' },
]
const methodTip = computed(() => methods.find(m => m.id === activeMethod.value)?.tip)
const viewKey = 'training:workbench:view'
let generation = 0, rememberedScroll = 0, rememberedPageScroll = 0

try {
  const saved = JSON.parse(sessionStorage.getItem(viewKey) || 'null')
  if (saved) {
    filter.value = ['all', 'todo', 'ongoing', 'done'].includes(saved.filter) ? saved.filter : 'all'
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

const stateLabels: Record<State, string> = { todo: '待练习', ongoing: '练习中', done: '已完成' }

const rows = computed<Row[]>(() => {
  const attached = new Set<string>()
  const result: Row[] = reviews.value.map(r => {
    const group = cases.value.filter(c => related(c, r))
    group.forEach(c => attached.add(c.id))
    const progress = stateOf(group)
    const state = progress !== 'todo' ? progress : r.status === 'RESOLVED' ? 'done' : 'todo'
    return {
      key: `review:${r.id}`,
      kind: 'review',
      id: r.id,
      revision: r.revision,
      question: r.question,
      origin: '面试复习',
      state,
      label: stateLabels[state],
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
const stateFilters = computed(() => [
  { value: 'all', label: '全部', count: stats.value.total },
  { value: 'todo', label: '待攻坚', count: stats.value.todo },
  { value: 'ongoing', label: '练习中', count: stats.value.ongoing },
  { value: 'done', label: '已攻克', count: stats.value.done },
])
const recommendedDomain = computed(() => {
  const ids = recommendedRow.value?.review?.skillIds || []
  return skills.value.filter(s => ids.includes(s.id)).map(s => s.name).join(' / ') || '领域待标注'
})
function finding(row: Row) {
  return row.review?.findings.find(f => !f.disputed)?.text || ''
}
async function startRecommended() {
  const row = recommendedRow.value
  if (!row) return
  const ongoing = row.cases.find(c => stateOf([c]) === 'ongoing')
  if (ongoing) await openCase(ongoing)
  else await open(row)
}
async function focusSearch(event: KeyboardEvent) {
  if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'k' || event.altKey || event.isComposing) return
  event.preventDefault()
  if (hasSelection.value && window.matchMedia?.('(max-width: 760px)').matches) await back()
  searchInput.value?.focus()
}
async function loadSkills() {
  try { skills.value = (await training<{ items?: Skill[] }>('/skills')).items || [] }
  catch { /* 领域标签不可用时明确显示待标注，不影响题目与作答。 */ }
}

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

onMounted(() => {
  void load()
  void loadSkills()
  window.addEventListener('keydown', focusSearch)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', focusSearch)
  generation++
  rememberView()
})
</script>

<template>
  <div class="page workbench-page">
    <header class="workbench-header-bar">
      <div class="header-main">
        <Target :size="19" aria-hidden="true" />
        <nav class="workbench-tabs" aria-label="持续训练">
          <RouterLink v-for="item in trainingNavigation" :key="item.to" :to="item.to" :class="{ active: item.to === '/review' }" :aria-current="item.to === '/review' ? 'page' : undefined">
            <h1 v-if="item.to === '/review'">{{ item.title }}</h1>
            <span v-else>{{ item.title }}</span>
          </RouterLink>
        </nav>
      </div>
      <div class="header-quick-stats" aria-label="练习进度">
        <span class="stat-pill">待攻坚 <b>{{ stats.todo }}</b></span>
        <span class="stat-pill stat-ongoing"><i :class="{ breathing: stats.ongoing > 0 }" aria-hidden="true" />进行中 <b>{{ stats.ongoing }}</b></span>
        <span class="stat-pill stat-done"><CircleCheck :size="12" aria-hidden="true" />已攻克 <b>{{ stats.done }}</b></span>
      </div>
    </header>

    <Notice :message="error" kind="error" />
    <Notice :message="notice" />
    <button v-if="error" class="btn btn-secondary" :disabled="loading" data-workbench-reload @click="load">重新读取记录</button>

    <div class="workbench-layout" :class="{ 'has-selection': hasSelection }">
      <aside class="workbench-index" data-workbench-list aria-label="重练题目列表">
        <div class="workbench-filters">
          <div class="heading-row">
            <h2>我的题目 <span>{{ rows.length }}</span></h2>
            <span v-if="filtered.length !== rows.length" class="filter-result" aria-live="polite">找到 {{ filtered.length }} 道</span>
          </div>
          <div class="workbench-search">
            <Search :size="16" aria-hidden="true" />
            <input id="workbench-search" ref="searchInput" v-model="search" aria-label="搜索题目或来源" aria-keyshortcuts="Meta+K Control+K" type="search" placeholder="搜索题目或来源…" />
            <button v-if="search" type="button" class="clear-search" aria-label="清空搜索" data-workbench-clear-search @click="search = ''; searchInput?.focus()"><X :size="14" aria-hidden="true" /></button>
            <kbd v-else aria-hidden="true">⌘K</kbd>
          </div>
          <!-- 保留原生 change 和选项契约，交互入口由分段按钮提供。 -->
          <select id="workbench-filter" v-model="filter" class="compat-filter" tabindex="-1" aria-hidden="true">
            <option value="all">全部题目（{{ rows.length }}）</option>
            <option value="todo">待练习（{{ stats.todo }}）</option>
            <option value="ongoing">练习中（{{ stats.ongoing }}）</option>
            <option value="done">已完成（{{ stats.done }}）</option>
          </select>
          <div class="segmented-filter" role="group" aria-label="练习状态">
            <button v-for="item in stateFilters" :key="item.value" type="button" :data-workbench-state="item.value" :aria-pressed="filter === item.value" @click="filter = item.value">
              <span>{{ item.label }}</span> <span class="filter-count">{{ item.count }}</span>
            </button>
          </div>
        </div>
        <LoadingState v-if="loading && !rows.length" label="正在读取重练记录…" />
        <div ref="list" class="workbench-rows" @scroll.passive="rememberView">
          <article v-for="row in visible" :key="row.key" :data-workbench-row="row.key" class="workbench-row" :class="{ selected: selected?.key === row.key }">
            <button class="workbench-open" :data-workbench-open="row.key" :aria-current="selected?.key === row.key ? 'true' : undefined" @click="open(row)">
              <span class="workbench-row-meta">
                <span class="origin-tag">{{ row.origin }}</span>
                <span class="status-tag" :class="`status-${row.state}`"><i aria-hidden="true" />{{ row.label }}</span>
                <span v-if="row.review?.mock || row.cases[0]?.mock" class="mock-tag">Mock</span>
              </span>
              <span class="workbench-question">{{ row.question }}</span>
              <span class="workbench-summary">{{ finding(row) || '暂无薄弱点记录，先梳理思路与边界。' }}</span>
            </button>
            <button class="workbench-delete row-delete" :data-workbench-delete="row.key" :aria-label="`删除重练记录：${row.question}`" :disabled="!!deleting || loading" title="删除重练记录（原面试记录不受影响）" @click="remove(row)"><Trash2 :size="14" aria-hidden="true" /></button>
            <details v-if="row.kind === 'review' && row.cases.length" class="workbench-history">
              <summary>同题作答记录（{{ row.cases.length }}）</summary>
              <button v-for="c in row.cases" :key="c.id" class="workbench-case text-link" :data-workbench-case="c.id" :aria-current="practiceId === c.id ? 'true' : undefined" @click="openCase(c)">
                <span>{{ c.question }}</span><span class="case-state-badge">{{ stateLabels[stateOf([c])] }}</span>
              </button>
            </details>
          </article>
          <div v-if="!loading && !visible.length" class="workbench-empty">
            <Search :size="22" aria-hidden="true" />
            <p>{{ rows.length ? '没有符合筛选条件的题目，请尝试重置搜索或状态。' : '还没有重练题目。完成面试后，可从报告中整理待练发现，或直接选择一道原题重新作答。' }}</p>
            <button v-if="rows.length" class="text-link" @click="search = ''; filter = 'all'">重置筛选</button>
          </div>
          <button v-if="visibleCount < filtered.length" class="btn btn-secondary workbench-more" @click="visibleCount += 30">加载更多题目</button>
        </div>
        <div class="index-footer"><BookOpen :size="13" aria-hidden="true" /><span>保留每次作答，回看每一步变化</span></div>
      </aside>

      <section class="workbench-content" data-workbench-content aria-label="选中题目与作答">
        <div v-if="hasSelection" class="workbench-selection-bar">
          <button class="workbench-back" @click="back"><ArrowLeft :size="15" aria-hidden="true" /><span>返回题目列表</span></button>
          <span class="selection-origin-tag">{{ selected?.origin || '历史练习记录' }}</span>
          <button v-if="selected" class="workbench-delete delete-selected-btn" data-workbench-delete-selected :disabled="!!deleting || loading" @click="remove(selected)"><Trash2 :size="14" aria-hidden="true" /><span>删除此项重练</span></button>
        </div>
        <PracticeAnswer v-if="practiceId" :key="`case:${practiceId}`" embedded :case-id="practiceId" @changed="load" />
        <ReviewDetails v-else-if="reviewId" :key="`review:${reviewId}`" embedded :review-id="reviewId" />
        <div v-else class="workbench-welcome">
          <header class="welcome-header">
            <span class="hub-caption"><Target :size="15" aria-hidden="true" />错题攻坚控制台</span>
            <h2>{{ rows.length ? '选一道题，继续练习' : loading ? '正在整理你的练习' : '从一次面试开始' }}</h2>
            <p>{{ rows.length ? '把上次没讲清楚的地方，这次讲透。' : '完成面试后，从报告选一道原题，开始有针对性的重练。' }}</p>
          </header>
          <article v-if="recommendedRow" class="recommendation" data-workbench-recommendation>
            <div class="recommendation-topline">
              <span class="recommendation-label"><Flame :size="16" aria-hidden="true" />{{ recommendedRow.state === 'done' ? '巩固已完成的练习' : '推荐攻坚' }}</span>
              <span class="recommendation-reason">{{ recommendedRow.state === 'ongoing' ? '优先继续未完成的作答' : recommendedRow.state === 'todo' ? '从一道待练题开始' : '回看已保存的回答与评估' }}</span>
            </div>
            <div class="recommendation-context"><span>{{ recommendedDomain }}</span><span>{{ recommendedRow.origin }}</span><span v-if="recommendedRow.review?.mock || recommendedRow.cases[0]?.mock">Mock 演练</span></div>
            <h3>{{ recommendedRow.question }}</h3>
            <div class="recommendation-gap">
              <span>{{ finding(recommendedRow) ? '上次失分盲区' : '本次练习重点' }}</span>
              <p>{{ finding(recommendedRow) || '暂无明确的薄弱点记录。重新组织思路，讲清方案、取舍与异常边界。' }}</p>
            </div>
            <div class="recommendation-footer">
              <span><CircleCheck :size="14" aria-hidden="true" />{{ recommendedRow.state === 'ongoing' ? '接着写，上次草稿会保留' : '原题与历史回答可随时回看' }}</span>
              <button type="button" class="btn btn-primary start-recommended" data-workbench-start :disabled="loading || !!deleting" @click="startRecommended"><span>{{ recommendedRow.state === 'done' ? '回看本次结果' : '立即攻克此题' }}</span><ArrowRight :size="17" aria-hidden="true" /></button>
            </div>
          </article>
          <div v-if="!rows.length && !loading" class="empty-action-area"><RouterLink to="/configure/quick" class="btn btn-primary">开始快速练习 <ArrowRight :size="16" aria-hidden="true" /></RouterLink></div>
          <section class="methodology" aria-label="科学重练三步法">
            <div class="method-heading"><h3>让每次重练都有收获</h3><span>溯源 → 重构 → 对比</span></div>
            <div class="method-grid">
              <button v-for="method in methods" :key="method.id" type="button" class="method-card" :data-workbench-method="method.id" :aria-expanded="activeMethod === method.id" aria-controls="workbench-method-tip" @click="activeMethod = activeMethod === method.id ? '' : method.id">
                <span class="method-icon"><component :is="method.icon" :size="19" aria-hidden="true" /><ChevronDown :size="13" aria-hidden="true" /></span>
                <strong>{{ method.title }}</strong><span>{{ method.caption }}</span>
              </button>
            </div>
            <p v-if="methodTip" id="workbench-method-tip" class="method-tip" aria-live="polite">{{ methodTip }}</p>
          </section>
          <p class="workbench-scope"><CircleCheck :size="14" aria-hidden="true" /><span>“已攻克”沿用已完成状态：已完成表示本次作答和评估已保存，不代表已经掌握。</span></p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.workbench-page {
  --workbench-line: #e2e8f0cc;
  --workbench-soft: #f6f8fa;
  max-width: none;
  padding: 0 24px;
  color: var(--text-primary);
  background: #fff;
}
.workbench-header-bar, .header-main, .header-quick-stats, .stat-pill, .workbench-tabs { display: flex; align-items: center; }
.workbench-header-bar { min-height: 68px; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--workbench-line); }
.header-main { gap: 14px; color: #64748b; }
.workbench-tabs { gap: 24px; align-self: stretch; }
.workbench-tabs a { min-height: 68px; display: flex; align-items: center; border-bottom: 2px solid transparent; margin-bottom: -1px; font-size: 13px; }
.workbench-tabs a.active { color: #0f172a; border-bottom-color: #2563eb; }
.workbench-tabs h1 { font-size: 16px; font-weight: 600; line-height: 1.4; letter-spacing: 0; }
.header-quick-stats { gap: 8px; flex-wrap: wrap; }
.stat-pill { gap: 6px; border-radius: 6px; padding: 4px 8px; font-size: 11px; line-height: 1.5; color: #475569; background: #f1f5f9; white-space: nowrap; }
.stat-pill b { font-family: var(--font-mono); font-weight: 600; font-variant-numeric: tabular-nums; }
.stat-ongoing { background: #eff6ff; color: #1d4ed8; }
.stat-done { background: #ecfdf5; color: #047857; }
.stat-ongoing i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.breathing { animation: status-breathe 2.8s ease-in-out infinite; }
@keyframes status-breathe { 50% { opacity: .4; } }
.workbench-layout { display: grid; grid-template-columns: 380px minmax(0, 1fr); min-height: 600px; height: calc(100dvh - 112px); }
.workbench-index { display: flex; flex-direction: column; min-width: 0; min-height: 0; border-right: 1px solid var(--workbench-line); background: #fff; }
.workbench-filters { padding: 20px 16px 16px 0; border-bottom: 1px solid var(--workbench-line); }
.heading-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.heading-row h2 { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; }
.heading-row h2 span { color: #64748b; font: 11px var(--font-mono); }
.filter-result { font-size: 11px; color: #64748b; }
.workbench-search { display: flex; align-items: center; gap: 8px; min-height: 36px; padding: 0 10px; background: var(--workbench-soft); border-radius: 7px; color: #64748b; transition: box-shadow .15s ease, background .15s ease; }
.workbench-search:focus-within { background: #fff; box-shadow: 0 0 0 3px #2563eb18, 0 0 0 1px #93b4f3; }
.workbench-search input { flex: 1; width: 0; padding: 8px 0; border: 0; background: transparent; font-size: 12px; outline: none; box-shadow: none; }
.workbench-search input::-webkit-search-cancel-button { display: none; }
.workbench-search kbd { padding: 1px 4px; border: 1px solid #e2e8f0; border-radius: 4px; font: 11px var(--font-mono); color: #64748b; background: #fff; }
.clear-search { display: grid; place-items: center; border: 0; padding: 4px; color: #64748b; background: transparent; }
.compat-filter { display: none; }
.segmented-filter { display: flex; gap: 2px; padding: 3px; margin-top: 12px; border-radius: 7px; background: var(--workbench-soft); }
.segmented-filter button { flex: 1; display: flex; align-items: center; justify-content: center; gap: 5px; min-width: 0; padding: 6px 3px; border: 1px solid transparent; border-radius: 5px; background: transparent; color: #64748b; font-size: 11px; white-space: nowrap; transition: color .15s ease, background .15s ease, border-color .15s ease; }
.segmented-filter button[aria-pressed="true"] { color: #0f172a; border-color: #e2e8f0; background: #fff; font-weight: 600; }
.filter-count { font: 11px var(--font-mono); color: #64748b; padding: 0 3px; border-radius: 3px; background: #e9edf2; }
.workbench-rows { flex: 1; min-height: 0; overflow-y: auto; scrollbar-width: thin; scrollbar-color: #cbd5e1 transparent; }
.workbench-row { position: relative; border-bottom: 1px solid var(--workbench-line); border-left: 2px solid transparent; transition: background .15s ease, border-color .15s ease; }
.workbench-row:hover { background: #f8fafc; }
.workbench-row.selected { border-left-color: #2563eb; background: #eff6ff66; }
.workbench-open { display: flex; flex-direction: column; gap: 7px; width: 100%; min-width: 0; min-height: 86px; padding: 14px 34px 14px 12px; text-align: left; border: 0; background: transparent; color: inherit; }
.workbench-open:focus-visible { outline-offset: -3px; }
.workbench-row-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 11px; }
.origin-tag { color: #64748b; font: 11px var(--font-mono); }
.status-tag { display: inline-flex; align-items: center; gap: 4px; color: #64748b; font-size: 11px; line-height: 1.4; padding: 2px 5px; border-radius: 4px; background: #f1f5f9; }
.status-tag i { width: 5px; height: 5px; border: 1px solid currentColor; border-radius: 50%; }
.status-ongoing { color: #1d4ed8; background: #eff6ff; }
.status-done { color: #047857; background: #ecfdf5; }
.status-done i { background: currentColor; }
.mock-tag { color: #64748b; font-size: 11px; }
.workbench-question { width: 100%; font-size: 13px; font-weight: 600; line-height: 1.6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workbench-summary { width: 100%; padding-left: 8px; border-left: 2px solid #dbe2ea; color: #64748b; font-size: 11px; line-height: 1.6; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.workbench-delete { display: inline-flex; align-items: center; justify-content: center; gap: 6px; border: 0; border-radius: 5px; background: transparent; color: #64748b; min-height: 32px; padding: 6px; font-size: 11px; transition: opacity .15s ease, color .15s ease, background .15s ease; }
.workbench-delete:hover { color: var(--danger); background: var(--danger-soft); }
.row-delete { position: absolute; top: 12px; right: 6px; opacity: 0; }
.workbench-row:hover .row-delete, .workbench-row:focus-within .row-delete { opacity: 1; }
.workbench-history { padding: 0 12px 12px; color: #64748b; font-size: 11px; }
.workbench-history summary { cursor: pointer; }
.workbench-case { display: flex; align-items: start; justify-content: space-between; width: 100%; gap: 12px; padding: 8px 0; border: 0; background: transparent; font-size: 12px; text-align: left; }
.workbench-case span:first-child { overflow-wrap: anywhere; }
.case-state-badge { flex-shrink: 0; font-size: 11px; }
.workbench-empty { display: grid; justify-items: start; gap: 12px; margin: 28px 18px 28px 0; color: #64748b; font-size: 12px; }
.workbench-more { margin: 16px; }
.index-footer { display: flex; align-items: center; gap: 7px; border-top: 1px solid var(--workbench-line); padding: 14px 8px; color: #64748b; font-size: 11px; }
.workbench-content { min-width: 0; min-height: 0; overflow-y: auto; scrollbar-width: thin; }
.workbench-welcome { max-width: 1000px; margin: 0 auto; padding: 32px clamp(24px, 3vw, 52px); }
.welcome-header { margin-bottom: 28px; }
.hub-caption { display: flex; align-items: center; gap: 7px; color: #64748b; font-size: 12px; margin-bottom: 14px; }
.welcome-header h2 { font-size: clamp(20px, 1.8vw, 26px); font-weight: 600; line-height: 1.4; letter-spacing: -.6px; }
.welcome-header p { color: #64748b; font-size: 13px; margin-top: 8px; }
.recommendation { position: relative; overflow: hidden; border: 1px solid #d6e3fa; border-radius: 12px; padding: 25px 26px 22px; background: #f8fbff; }
.recommendation::before { content: ''; position: absolute; inset: 0 0 auto; height: 3px; background: linear-gradient(90deg, #2563eb, #a3c4ff 72%, #e5eeff); }
.recommendation-topline { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 22px; }
.recommendation-label { display: flex; align-items: center; gap: 7px; font-size: 12px; font-weight: 600; color: #1d4ed8; }
.recommendation-reason { font-size: 11px; color: #64748b; }
.recommendation-context { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; font-size: 11px; color: #475569; }
.recommendation-context span { padding: 2px 6px; border-radius: 4px; background: #eaf1fc; }
.recommendation h3 { max-width: 72ch; font-size: clamp(17px, 1.5vw, 21px); font-weight: 600; line-height: 1.7; overflow-wrap: anywhere; text-wrap: pretty; }
.recommendation-gap { margin-top: 20px; padding-left: 12px; border-left: 2px solid #b8cef5; }
.recommendation-gap > span { font-size: 11px; color: #64748b; }
.recommendation-gap p { margin-top: 5px; font-size: 13px; line-height: 1.8; color: #334155; max-width: 72ch; overflow-wrap: anywhere; }
.recommendation-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; margin-top: 26px; }
.recommendation-footer > span { display: flex; gap: 6px; align-items: center; color: #64748b; font-size: 11px; }
.start-recommended { min-height: 42px; border-radius: 9px; padding: 10px 16px; font-size: 13px; gap: 14px; }
.methodology { margin-top: 30px; }
.method-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 12px; }
.method-heading h3 { font-size: 13px; font-weight: 600; }
.method-heading > span { color: #64748b; font-size: 11px; }
.method-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.method-card { display: flex; flex-direction: column; align-items: stretch; gap: 6px; padding: 16px 14px; border: 1px solid var(--workbench-line); border-radius: 8px; text-align: left; background: #fff; color: #475569; transition: border-color .15s ease, background .15s ease; }
.method-card:hover, .method-card[aria-expanded="true"] { background: #f8fafc; border-color: #cbd5e1; }
.method-icon { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; color: #64748b; }
.method-icon svg:last-child { transition: transform .15s ease; }
.method-card[aria-expanded="true"] .method-icon svg:last-child { transform: rotate(180deg); }
.method-card strong { font-size: 13px; font-weight: 600; color: #334155; }
.method-card > span:last-child { font-size: 11px; line-height: 1.6; }
.method-tip { min-height: 44px; margin-top: 12px; font-size: 12px; line-height: 1.8; color: #64748b; max-width: 72ch; }
.workbench-scope { display: flex; align-items: flex-start; gap: 7px; border-top: 1px solid var(--workbench-line); padding-top: 18px; margin-top: 26px; color: #64748b; font-size: 11px; line-height: 1.8; }
.workbench-scope svg { flex-shrink: 0; margin-top: 3px; }
.empty-action-area { margin: 24px 0; }
.workbench-selection-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; min-height: 52px; padding: 8px 24px; border-bottom: 1px solid var(--workbench-line); }
.workbench-back { display: flex; align-items: center; gap: 6px; border: 0; border-radius: 4px; padding: 6px 0; background: transparent; font-size: 12px; color: #475569; }
.selection-origin-tag { color: #64748b; font-size: 11px; }
.delete-selected-btn { margin-left: auto; }
.workbench-content :deep(.page) { max-width: none; padding: 0; }
.workbench-content :deep(.panel) { border: 0; border-radius: 0; box-shadow: none; padding: 24px; }
.workbench-content :deep(.practice-question) { border-bottom: 1px solid var(--workbench-line); }
.workbench-content :deep(.practice-question h2) { font-size: 20px; max-width: 72ch; }
.workbench-content :deep(.practice-hint) { background: #f8fafc; color: #475569; border-left: 2px solid #cbd5e1; border-radius: 0; }
.workbench-content :deep(.practice-canvas) { margin: 0; padding-bottom: 0; }
.workbench-content :deep(.practice-canvas textarea) { min-height: 320px; border-color: #e2e8f0; max-width: 100%; }
.workbench-content :deep(.practice-answer-footer) { position: sticky; bottom: 0; z-index: 2; margin: 0 -24px; padding: 12px 24px; border-top: 1px solid var(--workbench-line); background: #fff; }
.workbench-content :deep(.practice-answer-heading h2) { font-size: 14px; }
.workbench-content :deep(.practice-result) { margin: 0; }
.workbench-content :deep(.practice-exits) { position: sticky; bottom: 0; padding: 12px 0; background: #fff; }
@media (hover: none) { .row-delete { opacity: 1; min-width: 36px; min-height: 36px; } }
@media (max-width: 1100px) {
  .workbench-layout { grid-template-columns: 360px minmax(0, 1fr); }
  .workbench-welcome { padding: 26px 24px; }
  .recommendation { padding: 22px 20px; }
  .recommendation-footer > span { width: 100%; }
  .method-grid { gap: 6px; }
  .method-card { padding: 12px 10px; }
  .method-heading > span { display: none; }
}
@media (max-width: 900px) and (min-width: 761px) {
  .workbench-page { padding: 0 16px; }
  .workbench-layout { grid-template-columns: 320px minmax(0, 1fr); }
  .method-grid { grid-template-columns: 1fr; }
  .method-card { display: grid; grid-template-columns: 24px 1fr; gap: 4px 10px; }
  .method-icon { grid-row: span 2; margin: 0; }
  .method-icon svg:last-child { display: none; }
  .header-main { gap: 8px; }
  .workbench-tabs { gap: 16px; }
}
@media (max-width: 760px) {
  .workbench-page { padding: 0 16px; }
  .workbench-header-bar { flex-wrap: wrap; gap: 0; padding-bottom: 12px; }
  .workbench-tabs a { min-height: 54px; }
  .header-quick-stats { width: 100%; margin-top: 6px; }
  .workbench-layout { display: flex; flex-direction: column; height: auto; min-height: 0; }
  .workbench-index { border-right: 0; }
  .workbench-filters { padding-right: 0; }
  .workbench-rows { overflow: visible; }
  .workbench-content { overflow: visible; order: -1; }
  .workbench-welcome { padding: 24px 0; border-bottom: 1px solid var(--workbench-line); }
  .welcome-header { margin-bottom: 20px; }
  .welcome-header h2 { font-size: 22px; }
  .method-grid { gap: 6px; }
  .method-card { padding: 12px 8px; }
  .method-card > span:last-child { display: none; }
  .method-card strong { font-size: 12px; }
  .method-heading > span { display: none; }
  .method-icon { margin-bottom: 6px; }
  .workbench-scope { margin-top: 18px; }
  .has-selection .workbench-index { display: none; }
  .workbench-selection-bar { padding: 10px 0; gap: 8px; }
  .selection-origin-tag { display: none; }
  .workbench-back, .workbench-delete { min-height: 44px; }
  .row-delete { opacity: 1; }
  .workbench-search { min-height: 44px; }
  .workbench-search input { font-size: 16px; }
  .segmented-filter button { min-height: 40px; }
  .recommendation { padding: 22px 18px; }
  .recommendation-footer { margin-top: 20px; gap: 14px; }
  .start-recommended { width: 100%; justify-content: space-between; }
  .workbench-content :deep(.panel) { padding: 20px 0; }
  .workbench-content :deep(.practice-canvas) { padding-bottom: 0; }
  .workbench-content :deep(.practice-question h2) { font-size: 18px; }
  .workbench-content :deep(.practice-answer-footer) { margin: 0 -16px; padding: 12px 16px max(12px, env(safe-area-inset-bottom)); }
  .workbench-content :deep(.practice-canvas textarea) { font-size: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  .workbench-page *, .workbench-page *::before { animation: none !important; transition: none !important; }
}
</style>
