<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PhCheckCircle, PhWarningCircle } from '@phosphor-icons/vue'
import type { InterviewSession, ReviewFinding } from '../models/interview'
import ProjectEvidenceMatrix from './ProjectEvidenceMatrix.vue'
import Notice from './Notice.vue'
import ModelRecovery from './ModelRecovery.vue'
import { training, writeTraining, ApiError } from '../api/training'

const props = defineProps<{ session: InterviewSession }>()
const router = useRouter(), container = ref<HTMLElement | null>(null), expanded = ref<Record<string, boolean>>({})
interface Gap { questionId: string; caseId: string; status: string; operationId?: string; latestAttempt?: { id: string; status: string } }
const gaps = ref<Gap[]>([]), busy = ref(''), error = ref(''), recovery = ref<ApiError | null>(null)
const report = computed(() => props.session.report!)
const diagnostics = computed(() => props.session.status === 'REPORT_READY' ? (report.value.diagnostics || []).filter(item => item.diagnosis && props.session.messages.some(message => message.id === item.answerMessageId && message.role === 'USER' && message.questionId === item.questionId)) : [])
const diagnosisLabels = { SUFFICIENT: '当时观察：回答较充分', INCOMPLETE: '内容待补充', INCORRECT: '技术表述待核对', UNCERTAIN: '判断待核实', CONTRADICTORY: '表述存在矛盾', NO_EVIDENCE: '缺少实现依据' }
const assessment = computed(() => report.value.detailedAssessment)
const assessmentSections = computed(() => assessment.value ? [
  { title: '亮点分析', items: assessment.value.strengths, empty: '尚无足够证据提炼亮点，可结合后续回答继续验证。' },
  { title: '可提升空间', items: assessment.value.improvements, empty: '暂无可确认的改进项，未覆盖内容仍需后续验证。' },
] : [])
const findings = computed(() => report.value.findings || [])
const strengths = computed(() => findings.value.filter(f => f.answerStatus === 'CLEAR' && f.answerQuote).slice(0, 3))
const improvements = computed(() => findings.value.filter(f => f.answerStatus === 'PARTIAL' && f.answerQuote).sort((a, b) => (a.priority || 3) - (b.priority || 3)).slice(0, 3))
const gapTraining = computed(() => findings.value.filter(f => f.answerStatus === 'PARTIAL'))
const groups = computed(() => {
  const result: { id: string; title: string; entries: { question: InterviewSession['messages'][number]; answer?: InterviewSession['messages'][number]; finding?: ReviewFinding }[] }[] = []
  for (let i = 0; i < props.session.messages.length; i++) {
    const q = props.session.messages[i]
    if (q.role !== 'ASSISTANT' || !q.questionId) continue
    const root = q.rootQuestionId || q.questionId
    let group = result.find(item => item.id === root)
    if (!group) { group = { id: root, title: q.content, entries: [] }; result.push(group) }
    const next = props.session.messages[i + 1]
    group.entries.push({ question: q, answer: next?.role === 'USER' && next.questionId === q.questionId ? next : undefined, finding: findings.value.find(f => f.questionId === q.questionId) })
  }
  return result
})
const stageLabels = { NOT_STARTED: '未进行', IN_PROGRESS: '未完成', COMPLETED: '已完成', SKIPPED: '已跳过' }
const contentLabels = { CLEAR: '已讲清', PARTIAL: '待补强', UNVERIFIED: '尚未验证' }
const endLabels: Record<string, string> = { USER_FINISHED: '主动结束', TIME_LIMIT: '实战时间到达', QUESTIONS_COMPLETED: '题目流程结束', USER_SKIPPED_FINAL_STAGE: '跳过反问后结束', SYSTEM_INTERRUPTED: '系统中断后结束' }
function sourceLabel(f: ReviewFinding) { return f.status === 'SUPPORTED' ? '技术核实：有依据支持该判断' : f.status === 'CONFLICTING' ? '技术核实：来源存在冲突' : '技术核实：依据不足，待核实' }
function sourceIds(f: ReviewFinding) { return f.sourceIds.filter(id => props.session.sources.some(s => s.id === id)) }
async function locate(qid: string) {
  const group = groups.value.find(item => item.entries.some(entry => entry.question.questionId === qid))
  if (!group) return
  expanded.value[group.id] = true
  await nextTick()
  const element = Array.from(container.value?.querySelectorAll<HTMLElement>('[data-answer]') || []).find(item => item.dataset.answer === qid)
  element?.scrollIntoView?.({ block: 'center' }); element?.focus({ preventScroll: true })
}
const base = computed(() => `/api/review-gaps/V3/${encodeURIComponent(props.session.id)}`)
function gap(qid: string) { return gaps.value.find(item => item.questionId === qid) }
let generation = 0, disposed = false
onUnmounted(() => { disposed = true; generation++ })
async function load() {
  const current = ++generation
  try { const result = await training<{ items: Gap[] }>(base.value); if (current === generation) gaps.value = result.items || [] }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '补练记录暂时无法读取，可继续查看原复盘。' }
}
function recover(item: Gap) {
  if (item.operationId) recovery.value = Object.assign(new ApiError('补练尚未就绪，请查看已保存的任务并显式恢复。'), { operationId: item.operationId })
}
async function create(qid: string) {
  if (busy.value) return
  const sourceId = props.session.id
  busy.value = qid; error.value = ''; recovery.value = null
  try {
    const saved = await writeTraining<Gap>(`${base.value}/${encodeURIComponent(qid)}`, {})
    if (disposed || props.session.id !== sourceId) return
    gaps.value = [...gaps.value.filter(item => item.questionId !== qid), saved]
    if (saved.status === 'READY' && saved.caseId) await router.push(`/practice/${encodeURIComponent(saved.caseId)}`)
    else recover(saved)
  } catch (e) { if (disposed || props.session.id !== sourceId) return; error.value = e instanceof Error ? e.message : '补练尚未完成，原复盘已保留。'; recovery.value = e instanceof ApiError ? e : null; await load() }
  finally { if (!disposed && props.session.id === sourceId) busy.value = '' }
}
async function recovered() { recovery.value = null; error.value = ''; await load() }
watch(() => props.session.id, () => { busy.value = ''; gaps.value = []; expanded.value = {}; recovery.value = null; error.value = ''; void load() }, { immediate: true })
</script>

<template>
  <div ref="container" class="review-reading">
    <div class="review-highlights">
      <section class="panel review-strengths" data-strengths aria-labelledby="review-strengths-title">
        <h2 id="review-strengths-title"><PhCheckCircle :size="20" aria-hidden="true" />本场已讲清的内容</h2>
        <article v-for="f in strengths" :key="f.questionId" data-strength-item class="diagnosis-item">
          <h3>{{ f.headline }}</h3><p>{{ f.observation }}</p>
          <button class="text-link" :data-locate="f.questionId" @click="locate(f.questionId)">核对原回答</button>
        </article>
        <p v-if="!strengths.length" class="review-help">尚无足够证据提炼优势，可从逐题原答继续核对；不强凑结论。</p>
      </section>
      <section class="panel review-improvements" data-improvements aria-labelledby="review-improvements-title">
        <h2 id="review-improvements-title"><PhWarningCircle :size="20" aria-hidden="true" />待补强</h2>
        <article v-for="(f, index) in improvements" :key="f.questionId" data-improvement-item class="diagnosis-item">
          <h3>{{ index + 1 }}. {{ f.headline }}</h3><p>{{ f.observation }}</p><p><strong>下一步：</strong>{{ f.suggestion }}</p>
          <button class="text-link" :data-locate="f.questionId" @click="locate(f.questionId)">定位原回答</button>
        </article>
        <p v-if="!improvements.length" class="review-help">本场没有形成可引用的优先补强结论。尚未验证的内容留待后续训练。</p>
      </section>
    </div>
    <section class="panel review-assessment" data-overview aria-labelledby="review-overview-title">
      <h2 id="review-overview-title">详细评估</h2>
      <div v-if="assessment" data-detailed-assessment class="detailed-assessment">
        <section>
          <h3>整体印象</h3><p>{{ assessment.overall.text }}</p>
          <div v-if="assessment.overall.questionIds.length" class="review-actions"><button v-for="(qid, index) in assessment.overall.questionIds" :key="qid" class="text-link" :data-assessment-locate="qid" @click="locate(qid)">查看依据 {{ index + 1 }}</button></div>
        </section>
        <section v-for="section in assessmentSections" :key="section.title">
          <h3>{{ section.title }}</h3>
          <article v-for="(item, index) in section.items" :key="index" class="assessment-paragraph">
            <p>{{ item.text }}</p><div class="review-actions"><button v-for="(qid, evidenceIndex) in item.questionIds" :key="qid" class="text-link" :data-assessment-locate="qid" @click="locate(qid)">查看依据 {{ evidenceIndex + 1 }}</button></div>
          </article><p v-if="!section.items.length" class="review-help">{{ section.empty }}</p>
        </section>
        <section>
          <h3>下一步行动</h3>
          <ol v-if="assessment.nextSteps.length" class="assessment-actions"><li v-for="(item, index) in assessment.nextSteps" :key="index"><p>{{ item.text }}</p><div class="review-actions"><button v-for="(qid, evidenceIndex) in item.questionIds" :key="qid" class="text-link" :data-assessment-locate="qid" @click="locate(qid)">查看依据 {{ evidenceIndex + 1 }}</button></div></li></ol>
          <p v-else class="review-help">本场尚无证据充分的定向行动，可继续作答后再复盘。</p>
        </section>
      </div>
      <p v-else class="review-assessment-body">{{ report.summary }}</p>
      <p v-if="report.overview" class="review-meta">{{ report.overview.answeredCount }} 次作答 · {{ report.overview.mainQuestionCount }} 道主问题、{{ report.overview.followUpCount }} 次追问 · {{ endLabels[report.overview.endReason] || '结束原因未记录' }}<span v-if="session.plannedQuestionCount"> · 原计划 {{ session.plannedQuestionCount }} 道主问题</span></p>
      <ol v-if="session.sessionType !== 'QUICK' && report.stages?.length" class="review-stages" aria-label="本场四阶段完成情况"><li v-for="stage in report.stages || []" :key="stage.id"><strong>{{ stage.label }}</strong><span>{{ stageLabels[stage.status] }} · {{ stage.answeredCount }} 次作答</span></li></ol>
      <p class="review-help">未进行或未作答表示本场尚未验证，不算答错。以下仅评价已提交文字；引用不代表经历真实性认证。</p>
    </section>
    <ProjectEvidenceMatrix v-if="report.matrix" class="review-matrix" :matrix="report.matrix" source-kind="V3" :source-id="session.id" />
    <section class="review-groups" aria-labelledby="review-groups-title">
      <h2 id="review-groups-title">按主问题回顾本场</h2><p class="review-help">展开后逐题查看原问、原答、内容观察与技术依据；主问题和追问分别保留判断。</p>
      <details v-for="(group, index) in groups" :key="group.id" class="panel question-group" data-question-group :open="expanded[group.id]" @toggle="expanded[group.id] = ($event.target as HTMLDetailsElement).open">
        <summary>{{ index + 1 }}. {{ group.title }}<span class="review-help"> · {{ group.entries.length - 1 }} 次追问</span></summary>
        <article v-for="entry in group.entries" :key="entry.question.questionId" :data-answer="entry.question.questionId" tabindex="-1" class="review-entry">
          <h3>{{ entry.question.questionKind === 'FOLLOW_UP' ? '追问' : '主问题' }}：{{ entry.question.content }}</h3>
          <p v-if="entry.question.fallback" class="review-help">本题采用准备阶段的相关备用问题。</p>
          <blockquote v-if="entry.answer"><strong>你的原答：</strong>{{ entry.answer.content }}</blockquote><p v-else>未作答，本题尚未验证。</p>
          <template v-if="entry.finding">
            <p><span class="badge">回答内容：{{ contentLabels[entry.finding.answerStatus || 'UNVERIFIED'] }}</span></p><p>{{ entry.finding.observation }}</p>
            <blockquote v-if="entry.finding.answerQuote"><strong>原答摘录：</strong>“{{ entry.finding.answerQuote }}”</blockquote>
            <p class="review-help">{{ sourceLabel(entry.finding) }}。有来源支持不代表回答正确。</p><p>{{ entry.finding.assessment }}</p>
            <blockquote v-if="entry.finding.sourceQuote"><strong>官方原文：</strong>{{ entry.finding.sourceQuote }}</blockquote>
            <p><strong>改进建议：</strong>{{ entry.finding.suggestion }}</p>
            <dl v-if="entry.finding.terms?.length" class="review-terms"><template v-for="term in entry.finding.terms" :key="term.term"><dt>{{ term.term }}</dt><dd>{{ term.explanation }}</dd></template></dl>
            <div v-if="sourceIds(entry.finding).length" class="review-actions"><a v-for="sid in sourceIds(entry.finding)" :key="sid" class="text-link" :href="`#interview-source-${sid}`">{{ session.sources.find(s => s.id === sid)?.title }}</a></div>
          </template>
        </article>
      </details>
      <p v-if="!groups.length" class="review-help">本场尚无已问问题，未生成逐题能力判断。</p>
    </section>
    <details v-if="diagnostics.length" class="panel review-disclosure" data-followup-diagnostics>
      <summary>查看追问依据</summary><p class="review-help">以下是当时的追问观察，请结合对应原答与最终逐题复盘核对。</p>
      <article v-for="(item, index) in diagnostics" :key="`${item.answerMessageId}-${index}`" class="diagnosis-item">
        <h3>{{ diagnosisLabels[item.diagnosis.category] }}</h3>
        <blockquote v-if="item.diagnosis.answerQuote"><strong>原答摘录：</strong>“{{ item.diagnosis.answerQuote }}”</blockquote>
        <p v-if="item.diagnosis.coveredPoints.length"><strong>当时识别的要点：</strong>{{ item.diagnosis.coveredPoints.join('；') }}</p>
        <p v-if="item.diagnosis.gap"><strong>待核对：</strong>{{ item.diagnosis.gap }}</p>
        <p v-if="item.diagnosis.nextGoal"><strong>后续考查目标：</strong>{{ item.diagnosis.nextGoal }}</p>
        <p>{{ item.diagnosis.reason }}</p><button class="text-link" :data-diagnosis-locate="item.questionId" @click="locate(item.questionId)">核对对应原答</button>
      </article>
    </details>
    <details :key="session.id" class="panel review-disclosure" data-review-training>
      <summary>定向补练</summary><p class="review-help">结合本场岗位、项目与原回答生成一道新题。练习结果单独保存，原复盘保持本场记录。</p>
      <div v-for="f in gapTraining" :key="f.questionId" class="diagnosis-item">
        <h3>{{ f.headline }}</h3>
        <div class="review-actions"><template v-if="gap(f.questionId)?.status === 'READY'">
          <RouterLink :to="`/practice/${encodeURIComponent(gap(f.questionId)!.caseId)}`" class="btn btn-secondary" :data-review-gap-continue="f.questionId">继续定向补练</RouterLink>
          <RouterLink v-if="gap(f.questionId)?.latestAttempt" :to="{ path: `/practice/${encodeURIComponent(gap(f.questionId)!.caseId)}`, query: { attemptId: gap(f.questionId)!.latestAttempt!.id } }" class="text-link">查看补练结果</RouterLink>
        </template><button v-else-if="gap(f.questionId)" class="btn btn-secondary" @click="recover(gap(f.questionId)!)">查看已保存的补练任务</button><button v-else class="btn btn-secondary" :disabled="!!busy" :data-review-gap-create="f.questionId" @click="create(f.questionId)">{{ busy === f.questionId ? '正在生成定向问题…' : '定向补练一道新题' }}</button></div>
      </div>
      <p v-if="!gapTraining.length" class="review-help">当前没有已确认的回答内容缺口，可通过下一场面试继续验证。</p>
      <Notice :message="error" kind="error" /><button v-if="error" class="btn btn-secondary" @click="recovered">重新读取补练记录</button><ModelRecovery :key="recovery?.operationId" :error="recovery" @recovered="recovered" />
    </details>
    <details class="panel review-disclosure"><summary>查看完整原始对话</summary><article v-for="m in session.messages" :key="m.id" class="review-entry"><strong>{{ m.role === 'USER' ? '你' : m.kind === 'REPLY' ? '面试官回应' : '面试官' }}</strong><p>{{ m.content }}</p></article></details>
  </div>
</template>

<style scoped>
.review-reading { min-width: 0; display: grid; gap: 20px; }
.detailed-assessment { display: grid; gap: 26px; }
.assessment-paragraph + .assessment-paragraph { margin-top: 18px; }
.assessment-actions { list-style: decimal; padding-left: 24px; }
.assessment-actions li + li { margin-top: 16px; }
.detailed-assessment .review-actions { margin-top: 8px; }
.review-reading :is(p, blockquote, dd) { font-size: 16px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 10px; }
.review-reading h2 { font-size: 18px; margin-bottom: 12px; }
.review-reading h3 { font-size: 17px; line-height: 1.7; overflow-wrap: anywhere; }
.review-reading .review-help, .review-reading .review-meta { font-size: 14px; color: var(--text-secondary); }
.review-reading blockquote { border-left: 3px solid var(--accent); padding-left: 14px; margin-inline: 0; }
.review-reading summary { font-size: 16px; line-height: 1.7; cursor: pointer; min-height: 44px; overflow-wrap: anywhere; }
.review-highlights { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; align-items: stretch; }
.review-highlights h2 { display: flex; align-items: center; gap: 8px; }
.review-highlights h2 svg { flex-shrink: 0; }
.review-reading .review-strengths { background: var(--success-soft); border-color: color-mix(in srgb, var(--success) 28%, var(--surface)); }
.review-strengths h2 { color: var(--success); }
.review-reading .review-improvements { background: var(--warning-soft); border-color: color-mix(in srgb, var(--warning) 28%, var(--surface)); }
.review-improvements h2 { color: var(--warning); }
.diagnosis-item + .diagnosis-item { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--border-subtle); }
.diagnosis-item p { margin-top: 4px; }
.review-reading .review-assessment-body { font-size: 18px; max-width: 72ch; }
.review-stages { list-style: none; padding: 16px 0 0; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 16px 0 0; border-top: 1px solid var(--border-subtle); }
.review-stages li { display: flex; flex-direction: column; gap: 4px; font-size: 14px; }
.review-stages li span { color: var(--text-secondary); }
.review-groups > .review-help { margin: 0 0 12px; }
.review-entry { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--border-subtle); }
.review-entry:focus { outline: 2px solid var(--accent); outline-offset: 6px; }
.review-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 12px; }
.review-reading :is(button, a) { min-height: 44px; display: inline-flex; align-items: center; font-size: 14px; }
.review-reading .panel { margin: 0; padding: 20px 24px; }
.review-reading .review-matrix { margin: 0; }
.review-reading .question-group + .question-group { margin-top: 10px; }
.review-disclosure > summary { font-weight: 600; }
.review-disclosure .diagnosis-item { margin-top: 16px; }
.review-terms dt { font-weight: 600; font-size: 14px; margin-top: 12px; }
.review-terms dd { margin-left: 0; font-size: 16px; }
@media (max-width: 700px) {
  .review-highlights { grid-template-columns: minmax(0, 1fr); }
  .review-stages { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .review-reading .panel { padding: 16px; }
}
@media (max-width: 360px) { .review-stages { grid-template-columns: minmax(0, 1fr); } }
</style>
