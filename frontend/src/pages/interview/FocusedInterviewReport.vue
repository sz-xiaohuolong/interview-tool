<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowRight, RefreshCw, MessagesSquare, CircleCheck } from 'lucide-vue-next'
import { request, post, errorMessage } from '../../api'
import type { Report, Session } from '../../types'
import { shortDate, modeNames, levelNames } from '../../format'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import SourceCodeEvidence from '../../components/interview/SourceCodeEvidence.vue'
import ProjectEvidenceMatrix from '../../components/material/ProjectEvidenceMatrix.vue'
const route = useRoute(), id = String(route.params.id)
const session = ref<Session | null>(null), report = ref<Report | null>(null), error = ref(''), loading = ref(true), busy = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
async function load() {
  try {
    session.value = await request<Session>(`/sessions/${id}`)
    if (session.value.reportStatus === 'READY') { report.value = await request<Report>(`/sessions/${id}/report`); error.value = '' }
    else if (session.value.reportStatus === 'GENERATING' && !disposed) timer = setTimeout(() => { void load() }, 1500)
  } catch (e) { error.value = errorMessage(e) } finally { loading.value = false }
}
onMounted(load)
onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
async function regenerate() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { report.value = await post<Report>(`/sessions/${id}/report`); await load() }
  catch (e) { error.value = errorMessage(e); await load() } finally { busy.value = false }
}
function locateQuestion(index: number) { const node = document.getElementById(`report-question-${index}`); node?.scrollIntoView({ block: 'start' }); node?.focus({ preventScroll: true }) }
const categories = [{ key: 'correct', title: '回答中正确的内容' }, { key: 'incorrect', title: '明确答错的内容' }, { key: 'missing', title: '没有提到的关键点' }, { key: 'uncertain', title: '表述存疑 / 建议补充技术细节' }] as const
</script>
<template>
  <div class="page report-page">
    <div v-if="report && session" class="report-metric-strip" aria-label="本场复盘指标"><div><span>已提交回答</span><strong>{{ session.messages.filter(m => m.role === 'USER').length }}</strong></div><div><span>已复盘主问题</span><strong>{{ report.questions.length }}</strong></div><div><span>评分维度</span><strong>{{ report.dimensions.length }}</strong></div><div><span>待练方向</span><strong>{{ report.weaknesses.length }}</strong></div></div>
    <nav v-if="report?.questions.length" class="report-question-index" aria-label="复盘题目索引"><h2>本场问题</h2><button v-for="q in report.questions" :key="q.questionIndex" @click="locateQuestion(q.questionIndex)"><span class="mono">{{ String(q.questionIndex + 1).padStart(2, '0') }}</span><span>{{ q.question }}</span></button></nav>
    <div class="report-reading">
      <header class="page-heading report-heading">
        <RouterLink to="/history" class="text-link report-back">返回历史</RouterLink>
        <h1>面试复盘</h1>
        <p>回到你的原始回答，看清优势、缺口与下一步。</p>
      </header>
      <Notice :message="error" kind="error" />
      <LoadingState v-if="loading" label="正在加载复盘…" />
      <template v-else-if="session">
        <Notice v-if="session.mock" kind="warning" message="这是一份 Mock 测试报告。评分与建议仅为流程示意，不是对你实际能力的评价。" />
        <div v-if="session.status !== 'COMPLETED'" class="panel report-status">
          <h2>本场面试还没有结束</h2>
          <p class="muted">结束后统一生成复盘，避免影响正式面试过程。</p>
          <RouterLink :to="`/sessions/${id}`" class="btn btn-primary">继续面试 <ArrowRight :size="16" /></RouterLink>
        </div>
        <div v-else-if="!report" class="panel report-status">
          <LoadingState v-if="session.reportStatus === 'GENERATING' || busy" label="正在分析完整问答并生成复盘…" />
          <template v-else>
            <h2>对话已保存，报告尚未生成</h2>
            <Notice :message="session.error" kind="error" />
            <p class="muted">可以重试生成；你的原始回答会保留。</p>
            <button class="btn btn-primary" :disabled="busy" @click="regenerate"><RefreshCw :size="17" />重试生成报告</button>
          </template>
        </div>
        <template v-if="report">
          <SourceCodeEvidence :source="report.sourceCode" :questions="report.sourceQuestions" review />
          <ProjectEvidenceMatrix v-if="report.matrix" :matrix="report.matrix" source-kind="SESSION" :source-id="id" />
          <h2 class="report-section-heading">逐题回看，找到可改进的细节</h2>
          <p v-if="!report.questions.length" class="report-empty muted">本次报告没有可展示的逐题分析，原始对话仍可查看。</p>
          <article v-for="q in report.questions" :key="q.questionIndex" class="panel question-analysis" :id="`report-question-${q.questionIndex}`" tabindex="-1">
            <div class="prediction-question"><span class="question-number">{{ String(q.questionIndex + 1).padStart(2, '0') }}</span><h3>{{ q.question }}</h3></div>
            <details open>
              <summary>查看这一题的原始问答</summary>
              <div class="raw-dialogue">
                <div v-for="m in session.messages.filter(m => m.questionIndex === q.questionIndex)" :key="m.id" class="report-utterance">
                  <strong class="tiny" :class="m.role === 'USER' ? '' : 'muted'">{{ m.role === 'USER' ? '我的回答' : m.followUp ? '面试官追问' : '面试官主问题' }}</strong>
                  <pre class="tiny">{{ m.content }}</pre>
                </div>
              </div>
            </details>
            <div class="analysis-grid">
              <section v-for="category in categories" :key="category.key" class="analysis-block" :class="`analysis-block-${category.key}`">
                <h4>{{ category.title }}</h4>
                <p v-if="category.key === 'uncertain' && q[category.key].length" class="report-category-intro">你表达的核心方向正确，但以下语句较为口语化，面试官可能产生技术怀疑：</p>
                <ul v-if="q[category.key].length"><li v-for="(text, i) in q[category.key]" :key="i">{{ text }}</li></ul>
                <p v-else class="tiny muted">本项没有列出内容。</p>
              </section>
            </div>
            <div class="form-section report-explanation">
              <section class="report-answer-outline"><h3>推荐回答思路</h3><pre class="tiny">{{ q.answerOutline }}</pre></section>
              <section class="report-knowledge">
                <h3>必要的知识点解释</h3><pre class="tiny">{{ q.knowledgeExplanation }}</pre>
                <div v-if="q.evidence.length" class="evidence"><strong>资料或回答依据</strong><p v-for="(item, i) in q.evidence" :key="i">{{ item }}</p></div>
              </section>
            </div>
          </article>
          <div class="two-columns section report-next-steps">
            <section class="panel">
              <h2>下一次，重点练这些</h2>
              <p v-if="!report.weaknesses.length" class="tiny muted">目前没有足够证据列出薄弱项。可以继续完整练习。</p>
              <div v-for="(weakness, i) in report.weaknesses" :key="i" class="list-row">
                <div class="list-body"><p>{{ weakness }}</p></div>
                <RouterLink :to="{ path: '/interviews', query: { focus: weakness, role: session.role, level: session.level } }" class="btn btn-secondary btn-sm">专项练习 <ArrowRight :size="13" /></RouterLink>
              </div>
            </section>
            <section class="panel">
              <CircleCheck :size="24" class="report-next-icon" /><h2>后续练习建议</h2>
              <p v-if="!report.suggestions.length" class="report-empty muted">本次报告没有列出练习建议。</p>
              <ul class="reading-list"><li v-for="(suggestion, i) in report.suggestions" :key="i">{{ suggestion }}</li></ul>
            </section>
          </div>
        </template>
      </template>
    </div>
    <aside v-if="!loading && session && report" class="report-overview" aria-label="复盘概览与评分">
      <div class="report-intro">
        <h2>{{ session.role }}</h2>
        <div class="report-metadata">
          <span class="badge">{{ modeNames[session.mode] }}</span><span class="badge badge-gray">{{ levelNames[session.level] }}</span>
          <span class="badge badge-gray">{{ shortDate(session.createdAt) }}</span>
          <span class="badge badge-gray">{{ new Set(session.messages.filter(m => m.role === 'ASSISTANT' && !m.followUp).map(m => m.questionIndex)).size }} 道主问题 · {{ session.messages.filter(m => m.role === 'USER').length }} 次回答</span>
        </div>
        <h3>报告概览</h3><p>{{ report.summary }}</p>
      </div>
      <div class="report-dimensions-heading"><h2>四个维度，看清表现</h2><p class="tiny muted">0～4 分 · 每一项都有依据</p></div>
      <div class="dimension-grid">
        <article v-for="d in report.dimensions" :key="d.name" class="dimension"><h3>{{ d.name }}</h3><strong>{{ d.score }} <small>/ 4</small></strong><p>{{ d.reason }}</p></article>
      </div>
      <p v-if="!report.dimensions.length" class="report-empty muted">本次报告没有可展示的维度评分，不能据此推断能力。</p>
      <details class="tiny muted report-score-guide"><summary>了解评分标准</summary><p>0：没有可确认的有效表现；1：明显薄弱；2：基本掌握但有关键缺口；3：准确完整且能解释；4：深入严谨并讨论边界。没有足够回答时，以各维度说明为准，不能据此推断能力。</p></details>
    </aside>
    <slot name="report-tools" />
    <template v-if="!loading && session">
      <details class="panel section report-transcript">
        <summary><MessagesSquare :size="20" />查看完整原始对话（{{ session.messages.length }} 条）</summary>
        <div class="report-transcript-body">
          <div v-for="m in session.messages" :key="m.id" class="report-transcript-message">
            <span class="badge" :class="m.role === 'USER' ? 'badge-orange' : ''">{{ m.role === 'USER' ? '我的回答' : '面试官' }} · 第 {{ m.questionIndex + 1 }} 题{{ m.followUp ? '追问' : '' }}</span><pre>{{ m.content }}</pre>
          </div>
          <p v-if="!session.messages.length" class="muted tiny">本场没有生成对话。</p>
        </div>
      </details>
    </template>
  </div>
</template>

<style src="../../styles/report.css"></style>
