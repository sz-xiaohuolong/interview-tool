<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { training, writeTraining, ApiError } from '../../api/training'
import ModelRecovery from '../training/ModelRecovery.vue'
import Notice from '../common/Notice.vue'
import type { ProjectEvidenceMatrix, ProjectGapPractice, ProjectCheckpointId } from '../../project-evidence'

const props = defineProps<{ matrix: ProjectEvidenceMatrix; sourceKind: 'SESSION' | 'V3'; sourceId: string }>()
const router = useRouter(), gaps = ref<ProjectGapPractice[]>([]), busy = ref(''), error = ref(''), recovery = ref<ApiError | null>(null)
const base = computed(() => `/api/project-evidence/${encodeURIComponent(props.sourceKind)}/${encodeURIComponent(props.sourceId)}/gaps`)
const labels = { CLEAR: '已讲清', PARTIAL: '待补强', UNCOVERED: '未覆盖' }
const gapLabels: Record<string, string> = { RUNNING: '补练问题生成中', FAILED: '补练问题生成失败', INTERRUPTED: '补练问题生成中断' }
let readGeneration = 0
function gap(id: ProjectCheckpointId) { return gaps.value.find(item => item.checkpointId === id) }
function pending(item?: ProjectGapPractice) { return !!item && item.status !== 'READY' }
function showRecovery(item?: ProjectGapPractice) {
  if (item?.operationId) recovery.value = Object.assign(new ApiError(gapLabels[item.status] || '请查看已保存的操作'), { operationId: item.operationId })
}
async function load() {
  const generation = ++readGeneration
  try {
    const result = await training<{ items: ProjectGapPractice[] }>(base.value)
    if (generation !== readGeneration) return
    gaps.value = result.items || []
  } catch (e) { if (generation === readGeneration) error.value = e instanceof Error ? e.message : '补练记录暂时无法读取，原矩阵仍可查看。' }
}
async function create(checkpointId: ProjectCheckpointId) {
  if (busy.value) return
  busy.value = checkpointId; error.value = ''; recovery.value = null
  try {
    const saved = await writeTraining<ProjectGapPractice>(`${base.value}/${checkpointId}`, {})
    gaps.value = [...gaps.value.filter(item => item.checkpointId !== checkpointId), saved]
    if (saved.status === 'READY' && saved.caseId) await router.push(`/practice/${encodeURIComponent(saved.caseId)}`)
    else showRecovery(saved)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '补练尚未完成，原报告保持。'
    recovery.value = e instanceof ApiError ? e : null
    await load()
  } finally { busy.value = '' }
}
async function recovered() { recovery.value = null; error.value = ''; await load() }
watch(() => [props.sourceKind, props.sourceId], () => { gaps.value = []; recovery.value = null; error.value = ''; void load() }, { immediate: true })
</script>

<template>
  <section class="project-evidence" aria-labelledby="project-evidence-title">
    <header class="project-evidence-heading">
      <h2 id="project-evidence-title">项目证据覆盖与缺口</h2>
      <p><strong>{{ matrix.project.name }}</strong> · 本场主项目</p>
      <p class="muted">{{ matrix.notice || '仅反映本场已提交文字；未覆盖不代表能力不足，已讲清不等于外部真实性认证。' }}</p>
    </header>
    <div class="project-evidence-grid">
      <article v-for="item in matrix.items" :key="item.checkpointId" class="panel project-evidence-item" :data-checkpoint="item.checkpointId">
        <div class="project-evidence-title"><h3>{{ item.title }}</h3><span class="badge" :class="{ 'badge-orange': item.status === 'PARTIAL', 'badge-gray': item.status === 'UNCOVERED' }">{{ labels[item.status] }}</span></div>
        <p>{{ item.reason }}</p>
        <details v-if="item.evidence.length" class="project-evidence-quotes">
          <summary>查看本场问答证据</summary>
          <div v-for="(quote, index) in item.evidence" :key="`${quote.questionMessageId}-${quote.answerMessageId}-${index}`">
            <p><strong>面试官：</strong>{{ quote.questionQuote }}</p>
            <blockquote><strong>你的原答：</strong>{{ quote.answerQuote }}</blockquote>
          </div>
        </details>
        <p v-else class="tiny muted">尚无本项已提交回答证据；未问或未答都不记为能力不足。</p>
        <div v-if="item.status !== 'CLEAR'" class="project-evidence-actions">
          <template v-if="gap(item.checkpointId)?.status === 'READY' && gap(item.checkpointId)?.caseId">
            <RouterLink :to="`/practice/${encodeURIComponent(gap(item.checkpointId)!.caseId!)}`" class="btn btn-secondary" :data-gap-continue="item.checkpointId">继续定向补练</RouterLink>
            <RouterLink v-if="gap(item.checkpointId)?.latestAttempt" :to="{ path: `/practice/${encodeURIComponent(gap(item.checkpointId)!.caseId!)}`, query: { attemptId: gap(item.checkpointId)!.latestAttempt!.id } }" class="text-link" :data-gap-result="item.checkpointId">查看补练结果</RouterLink>
          </template>
          <template v-else-if="pending(gap(item.checkpointId))">
            <span role="status">{{ gapLabels[gap(item.checkpointId)!.status] || '补练尚未就绪' }}</span>
            <button class="btn btn-secondary" @click="showRecovery(gap(item.checkpointId))">查看已保存的操作</button>
          </template>
          <button v-else class="btn btn-secondary" :disabled="!!busy" :data-gap-create="item.checkpointId" @click="create(item.checkpointId)">{{ busy === item.checkpointId ? '正在生成定向问题…' : '定向补练' }}</button>
        </div>
      </article>
    </div>
    <p class="tiny muted">补练会结合本场主项目生成一道新问题，继续使用单题练习；补练结果单独保存，原矩阵保持本场记录。</p>
    <Notice :message="error" kind="error" />
    <button v-if="error" class="btn btn-secondary" @click="recovered">重新读取补练记录</button>
    <ModelRecovery :error="recovery" @recovered="recovered" />
  </section>
</template>

<style scoped>
.project-evidence { margin:24px 0; }.project-evidence-heading { margin-bottom:12px; }.project-evidence-heading p { margin-top:6px; font-size:13px; }.project-evidence-grid { border-top:1px solid var(--border-subtle); margin-bottom:12px; }.project-evidence-item { display:grid; grid-template-columns:minmax(140px, .8fr) minmax(0,2fr) auto; column-gap:20px; padding:12px 0; border:0; border-bottom:1px solid var(--border-subtle); border-radius:0; }.project-evidence-title { display:flex; gap:8px; align-items:center; flex-wrap:wrap; align-content:start; }.project-evidence-title h3 { font-size:13px; }.project-evidence-item > p { grid-column:2; font-size:13px; }.project-evidence-quotes { grid-column:2; margin-top:8px; }.project-evidence-quotes summary { color:var(--accent-ink); font-size:12px; }.project-evidence-quotes p, .project-evidence-quotes blockquote { margin:10px 0; white-space:pre-wrap; overflow-wrap:anywhere; }.project-evidence-quotes blockquote { padding-left:12px; border-left:1px solid var(--border-control); }.project-evidence-actions { grid-column:3; grid-row:1 / 4; display:flex; align-items:flex-start; gap:8px; flex-wrap:wrap; max-width:170px; }.project-evidence-actions .btn { font-size:12px; min-height:32px; }
@media(max-width:800px) { .project-evidence-item { grid-template-columns:minmax(0,1fr); gap:8px; }.project-evidence-item > p, .project-evidence-quotes, .project-evidence-actions { grid-column:1; grid-row:auto; }.project-evidence-actions { max-width:none; }.project-evidence-actions .btn { min-height:44px; } }
</style>
