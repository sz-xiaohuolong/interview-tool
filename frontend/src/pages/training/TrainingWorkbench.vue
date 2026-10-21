<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  PhArrowLeft,
  PhArrowRight,
  PhTrash,
  PhMagnifyingGlass,
  PhTarget,
  PhLightning,
  PhCheckCircle,
  PhClock,
  PhBookOpen,
  PhSparkle,
  PhFlame,
} from '@phosphor-icons/vue'
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
    <!-- 极简、通透的工作台紧凑标头 -->
    <header class="workbench-header-bar">
      <div class="header-main">
        <div class="title-with-badge">
          <h1>错题重练</h1>
          <span class="header-badge">真实问答攻坚</span>
        </div>
        <p class="header-caption">选一道题，重新作答，查看这次补齐了什么。</p>
      </div>
      <div v-if="rows.length" class="header-quick-stats">
        <span class="stat-pill"><PhClock :size="14" class="icon-amber" /> 待攻坚 {{ stats.todo }}</span>
        <span class="stat-pill"><PhLightning :size="14" class="icon-blue" /> 进行中 {{ stats.ongoing }}</span>
        <span class="stat-pill"><PhCheckCircle :size="14" class="icon-emerald" /> 已完成 {{ stats.done }}</span>
      </div>
    </header>

    <Notice :message="error" kind="error" />
    <Notice :message="notice" />
    <button v-if="error" class="btn btn-secondary" :disabled="loading" data-workbench-reload @click="load">
      重新读取记录
    </button>

    <div class="workbench-layout" :class="{ 'has-selection': hasSelection }">
      <!-- 左侧题目工作列表（更宽更舒展） -->
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
            <PhMagnifyingGlass :size="17" aria-hidden="true" />
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
                <PhTrash :size="14" aria-hidden="true" />
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

      <!-- 右侧工作台作答与复盘区 -->
      <section class="workbench-content" data-workbench-content aria-label="选中题目与作答">
        <div v-if="hasSelection" class="workbench-selection-bar">
          <button class="btn btn-secondary btn-sm workbench-back" @click="back">
            <PhArrowLeft :size="15" />
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
            <PhTrash :size="15" />
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

        <!-- 已选中的作答或复盘组件 -->
        <PracticeAnswer v-if="practiceId" :key="`case:${practiceId}`" embedded :case-id="practiceId" @changed="load" />
        <ReviewDetails v-else-if="reviewId" :key="`review:${reviewId}`" embedded :review-id="reviewId" @changed="load" />

        <!-- 未选中的欢迎看板：彻底消灭死白空洞，呈现智能攻坚中心 -->
        <div v-else class="workbench-welcome panel welcome-delight">
          <header class="welcome-header">
            <div class="welcome-icon-bubble">
              <PhTarget :size="28" weight="duotone" />
            </div>
            <div>
              <h2>{{ rows.length ? '选一道题，继续练习' : '从一次面试开始' }}</h2>
              <p>{{ rows.length ? '左侧选择题目，在这里查看原始问答、重新作答和比较变化。' : '完成面试后，从报告整理待练发现，或直接选择一道原题重新作答。' }}</p>
            </div>
          </header>

          <!-- 场景 1：已有错题时的「智能推荐与今日速览」 -->
          <div v-if="rows.length" class="welcome-dashboard-grid">
            <!-- 今日重点攻克卡片 -->
            <div v-if="recommendedRow" class="card-highlight">
              <div class="card-highlight-badge">
                <PhFlame :size="15" weight="fill" />
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
                  <PhArrowRight :size="15" />
                </button>
              </div>
            </div>

            <!-- 三步科学重练法 -->
            <div class="methodology-card">
              <h4>
                <PhSparkle :size="16" weight="fill" class="sparkle-icon" />
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

          <!-- 场景 2：题库全空态 -->
          <div v-if="!rows.length && !loading" class="empty-action-area">
            <RouterLink to="/configure/quick" class="btn btn-primary">
              开始快速练习 <PhArrowRight :size="16" />
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
.workbench-delight {
  max-width: 1260px;
  margin: 0 auto;
}

/* 紧凑优雅的标头 */
.workbench-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.title-with-badge {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title-with-badge h1 {
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
  letter-spacing: -0.02em;
}

.header-badge {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 6px;
  background: #eff6ff;
  color: #2563eb;
}

.header-caption {
  font-size: 13.5px;
  color: #64748b;
  margin-top: 4px;
  margin-bottom: 0;
}

.header-quick-stats {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stat-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 500;
  color: #475569;
  padding: 5px 12px;
  border-radius: 999px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.icon-amber { color: #d97706; }
.icon-blue { color: #2563eb; }
.icon-emerald { color: #059669; }

/* 整体网格布局（平衡左右宽度） */
.workbench-layout {
  display: grid;
  grid-template-columns: minmax(340px, 390px) minmax(0, 1fr);
  gap: 22px;
  align-items: start;
}

/* 左侧题目工作列表 */
.workbench-index {
  position: sticky;
  top: 24px;
  padding: 0;
  overflow: hidden;
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  box-shadow: 0 2px 10px rgba(32, 44, 64, 0.03);
}

.workbench-filters {
  padding: 18px 20px;
  border-bottom: 1px solid #e2e8f0;
  background: #fcfdfe;
}

.title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title-group h2 {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.count-badge {
  font-size: 12px;
  font-weight: 600;
  background: #f1f5f9;
  color: #475569;
  padding: 2px 8px;
  border-radius: 999px;
}

.workbench-filters .heading-row {
  margin-bottom: 14px;
}

/* 搜索框 */
.workbench-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  margin-bottom: 12px;
  transition: all 0.2s ease;
}

.workbench-search:focus-within {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.workbench-search svg {
  flex-shrink: 0;
  color: #94a3b8;
}

.workbench-search input {
  width: 100%;
  border: 0;
  outline: none;
  font-size: 13.5px;
  color: #1e293b;
  background: transparent;
  padding: 0;
}

.workbench-search input::placeholder {
  color: #94a3b8;
}

/* 筛选器 */
.filter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.filter-label {
  font-size: 12.5px;
  font-weight: 600;
  color: #64748b;
  flex-shrink: 0;
}

.select-wrapper {
  position: relative;
  flex: 1;
}

.select-wrapper select {
  width: 100%;
  padding: 6px 28px 6px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background-color: #ffffff;
  font-size: 13px;
  color: #334155;
  transition: all 0.15s ease;
  appearance: none;
  background-image: url("data:image/svg+xml;charset=UTF-8,%3csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3e%3cpolyline points='6 9 12 15 18 9'%3e%3c/polyline%3e%3c/svg%3e");
  background-repeat: no-repeat;
  background-position: right 8px center;
  background-size: 12px;
}

.select-wrapper select:focus {
  border-color: #3b82f6;
  outline: none;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.12);
}

/* 题目行列表 */
.workbench-rows {
  max-height: calc(100dvh - 310px);
  min-height: 240px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.workbench-row {
  position: relative;
  padding: 14px 18px;
  border-bottom: 1px solid #f1f5f9;
  transition: all 0.15s ease;
  background: #ffffff;
}

.workbench-row:last-of-type {
  border-bottom: 0;
}

.workbench-row:hover {
  background: #f8fafc;
}

.workbench-row.selected {
  background: #eff6ff;
  border-left: 3px solid #2563eb;
}

.workbench-open {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  color: #1e293b;
  cursor: pointer;
}

.workbench-open:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 2px;
  border-radius: 4px;
}

.workbench-row-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.origin-tag {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #475569;
}

.status-tag {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 4px;
}

.status-todo {
  background: #fff7ed;
  color: #c2410c;
}

.status-ongoing {
  background: #eff6ff;
  color: #1d4ed8;
}

.status-done {
  background: #ecfdf5;
  color: #047857;
}

.status-other {
  background: #f1f5f9;
  color: #64748b;
}

.workbench-question {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 14.5px;
  font-weight: 600;
  line-height: 1.55;
  color: #0f172a;
}

.workbench-summary {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 6px;
  padding-left: 8px;
  border-left: 2px solid #cbd5e1;
  font-size: 12.5px;
  line-height: 1.55;
  color: #64748b;
}

.summary-quote {
  font-weight: 600;
  color: #475569;
}

.workbench-row-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
}

.badge-mock-tag {
  font-size: 11px;
  color: #d97706;
  background: #fef3c7;
  padding: 1px 6px;
  border-radius: 4px;
}

.footer-hint {
  font-size: 12px;
  color: #94a3b8;
}

/* 优雅的删除按钮（微交互） */
.workbench-delete {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 28px;
  padding: 3px 8px;
  border: 0;
  background: transparent;
  color: #94a3b8;
  font-size: 12px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.workbench-row:hover .workbench-delete {
  color: #ef4444;
}

.workbench-delete:hover {
  background: #fef2f2;
  color: #dc2626;
}

.workbench-delete:disabled {
  opacity: 0.45;
  cursor: wait;
}

.workbench-empty {
  padding: 32px 20px;
  text-align: center;
  line-height: 1.6;
}

.workbench-more {
  display: block;
  width: calc(100% - 32px);
  margin: 16px auto;
  padding: 8px;
  font-size: 13px;
}

/* ================= 右侧工作台作答/复盘区 ================= */
.workbench-content {
  min-width: 0;
}

.workbench-selection-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 10px 16px;
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 10px;
}

.workbench-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.selection-origin-tag {
  font-size: 12.5px;
  color: #64748b;
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
}

.delete-selected-btn {
  margin-left: auto;
  color: #ef4444;
}

.workbench-history {
  margin-bottom: 16px;
  padding: 14px 18px;
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 10px;
}

.workbench-history summary {
  cursor: pointer;
  font-size: 13.5px;
  font-weight: 600;
  color: #334155;
}

.workbench-case {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 8px 0;
  border: 0;
  background: transparent;
  text-align: left;
  border-top: 1px solid #f1f5f9;
  cursor: pointer;
}

.case-state-badge {
  font-size: 12px;
  color: #2563eb;
  background: #eff6ff;
  padding: 2px 8px;
  border-radius: 4px;
  flex-shrink: 0;
}

/* 欢迎区 / 控制台（告别死白） */
.welcome-delight {
  padding: 28px 32px;
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  box-shadow: 0 2px 10px rgba(32, 44, 64, 0.03);
}

.welcome-header {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
}

.welcome-icon-bubble {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: #eff6ff;
  color: #2563eb;
  flex-shrink: 0;
}

.welcome-header h2 {
  font-size: 19px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.welcome-header p {
  font-size: 14px;
  color: #64748b;
  line-height: 1.6;
  margin-top: 4px;
  margin-bottom: 0;
}

/* 仪表盘两栏网格 */
.welcome-dashboard-grid {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 今日重点推荐卡片 */
.card-highlight {
  padding: 20px 22px;
  border-radius: 10px;
  background: linear-gradient(135deg, #f8fafc 0%, #eff6ff 100%);
  border: 1px solid #bfdbfe;
  position: relative;
}

.card-highlight-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 700;
  color: #b45309;
  background: #fef3c7;
  padding: 2px 8px;
  border-radius: 999px;
  margin-bottom: 10px;
}

.highlight-question {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.6;
  color: #0f172a;
  margin: 0;
}

.highlight-desc {
  font-size: 13px;
  line-height: 1.6;
  color: #475569;
  margin-top: 8px;
  margin-bottom: 0;
}

.highlight-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed #cbd5e1;
}

.quick-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

/* 方法论卡片 */
.methodology-card {
  padding: 20px 22px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.methodology-card h4 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14.5px;
  font-weight: 700;
  color: #1e293b;
  margin: 0 0 14px 0;
}

.sparkle-icon {
  color: #2563eb;
}

.method-steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.step-num {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #334155;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
  margin-top: 2px;
}

.step-item strong {
  display: block;
  font-size: 13.5px;
  color: #1e293b;
  margin-bottom: 4px;
}

.step-item p {
  font-size: 12.5px;
  line-height: 1.6;
  color: #64748b;
  margin: 0;
}

.workbench-scope {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #f1f5f9;
}

.empty-action-area {
  margin-top: 20px;
}

/* 嵌入页面透传样式 */
.workbench-content :deep(.page) {
  padding: 0;
  max-width: none;
}

.workbench-content :deep(.training-reading-layout) {
  display: flex !important;
  flex-direction: column !important;
  gap: 18px !important;
  width: 100% !important;
}

.workbench-content :deep(.training-actions) {
  order: -1;
  position: static;
  width: 100% !important;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  background: linear-gradient(135deg, #ffffff 0%, #eff6ff 100%);
  border: 1px solid #bfdbfe;
  border-radius: 12px;
  padding: 18px 24px;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.04);
}

.workbench-content :deep(.training-actions h2) {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.workbench-content :deep(.training-actions > p) {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

.workbench-content :deep(.training-flow.panel) {
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 10px rgba(32, 44, 64, 0.02);
}

.workbench-content :deep(pre) {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

/* 响应式断点 */
@media (max-width: 1080px) {
  .method-steps {
    grid-template-columns: 1fr;
    gap: 12px;
  }
}

@media (max-width: 900px) {
  .workbench-header-bar {
    flex-direction: column;
    align-items: flex-start;
  }
  .workbench-layout {
    grid-template-columns: minmax(280px, 320px) minmax(0, 1fr);
    gap: 16px;
  }
}

@media (max-width: 760px) {
  .workbench-layout {
    display: block;
  }
  .workbench-index {
    position: static;
  }
  .workbench-rows {
    max-height: none;
  }
  .workbench-content {
    display: none;
  }
  .workbench-layout.has-selection .workbench-index {
    display: none;
  }
  .workbench-layout.has-selection .workbench-content {
    display: block;
  }
  .welcome-delight {
    padding: 20px;
  }
  .workbench-selection-bar > .selection-origin-tag {
    display: none;
  }
}
</style>
