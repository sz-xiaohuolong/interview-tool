<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { PhArrowRight, PhArrowClockwise, PhChatsCircle, PhCheckCircle } from '@phosphor-icons/vue'
import { request, post, errorMessage } from '../api'
import type { Report, Session } from '../types'
import { shortDate, modeNames, levelNames } from '../format'
import Notice from '../components/Notice.vue'
import LoadingState from '../components/LoadingState.vue'
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
const categories = [{ key: 'correct', title: '回答中正确的内容' }, { key: 'incorrect', title: '明确答错的内容' }, { key: 'missing', title: '没有提到的关键点' }, { key: 'uncertain', title: '资料不足，无法判断' }] as const
</script>
<template>
  <div class="page report-page"><div class="page-heading"><div class="heading-row"><div><h1>把一次面试，变成下一次的进步。</h1><p>回到你的原始回答，看清优势、缺口与下一步。</p></div><RouterLink to="/history" class="text-link">返回历史</RouterLink></div></div><Notice :message="error" kind="error" /><LoadingState v-if="loading" label="正在加载复盘…" />
    <template v-else-if="session">
      <Notice v-if="session.mock" kind="warning" message="这是一份 Mock 测试报告。评分与建议仅为流程示意，不是对你实际能力的评价。" />
      <div v-if="session.status !== 'COMPLETED'" class="panel"><h2>本场面试还没有结束</h2><p class="muted" style="margin-top: 12px">结束后统一生成复盘，避免影响正式面试过程。</p><RouterLink :to="`/sessions/${id}`" class="btn btn-primary" style="margin-top: 20px">继续面试 <PhArrowRight :size="16" /></RouterLink></div>
      <div v-else-if="!report" class="panel"><LoadingState v-if="session.reportStatus === 'GENERATING' || busy" label="正在分析完整问答并生成复盘…" /><template v-else><h2>对话已保存，报告尚未生成</h2><Notice :message="session.error" kind="error" /><p class="muted" style="margin-top: 12px">可以重试生成；你的原始回答会保留。</p><button class="btn btn-primary" :disabled="busy" style="margin-top: 20px" @click="regenerate"><PhArrowClockwise :size="17" />重试生成报告</button></template></div>
      <template v-if="report">
        <div class="report-intro"><div style="display: flex; gap: 9px; flex-wrap: wrap"><span class="badge">{{ modeNames[session.mode] }}</span><span class="badge badge-gray">{{ levelNames[session.level] }}</span><span class="badge badge-gray">{{ shortDate(session.createdAt) }}</span><span class="badge badge-gray">{{ new Set(session.messages.filter(m => m.role === 'ASSISTANT' && !m.followUp).map(m => m.questionIndex)).size }} 道主问题 · {{ session.messages.filter(m => m.role === 'USER').length }} 次回答</span></div><h2 style="margin-top: 19px">{{ session.role }}</h2><p style="white-space: pre-wrap">{{ report.summary }}</p></div>
        <div class="heading-row"><h2>四个维度，看清表现</h2><span class="tiny muted">0～4 分 · 每一项都有依据</span></div><div class="dimension-grid"><article v-for="d in report.dimensions" :key="d.name" class="dimension"><h3 style="font-size: 13px">{{ d.name }}</h3><strong>{{ d.score }} <small>/ 4</small></strong><p>{{ d.reason }}</p></article></div>
        <details class="tiny muted" style="margin-bottom: 30px"><summary style="cursor: pointer">了解评分标准</summary><p style="margin-top: 8px">0：没有可确认的有效表现；1：明显薄弱；2：基本掌握但有关键缺口；3：准确完整且能解释；4：深入严谨并讨论边界。没有足够回答时，以各维度说明为准，不能据此推断能力。</p></details>
        <h2 style="margin-bottom: 20px">逐题回看，找到可改进的细节</h2>
        <article v-for="q in report.questions" :key="q.questionIndex" class="panel question-analysis"><div class="prediction-question"><span class="question-number">{{ String(q.questionIndex + 1).padStart(2, '0') }}</span><h3>{{ q.question }}</h3></div><details open><summary>查看这一题的原始问答</summary><div class="raw-dialogue"><div v-for="m in session.messages.filter(m => m.questionIndex === q.questionIndex)" :key="m.id" style="margin-bottom: 15px"><strong class="tiny" :class="m.role === 'USER' ? '' : 'muted'">{{ m.role === 'USER' ? '我的回答' : m.followUp ? '面试官追问' : '面试官主问题' }}</strong><pre class="tiny" style="margin-top: 5px">{{ m.content }}</pre></div></div></details><div class="analysis-grid"><section v-for="category in categories" :key="category.key" class="analysis-block"><h4>{{ category.title }}</h4><ul v-if="q[category.key].length"><li v-for="(text, i) in q[category.key]" :key="i">{{ text }}</li></ul><p v-else class="tiny muted">本项没有列出内容。</p></section></div><div class="form-section" style="margin-top: 22px; padding-top: 19px"><h3 style="font-size: 13px">推荐回答思路</h3><pre class="tiny" style="margin-top: 9px">{{ q.answerOutline }}</pre><h3 style="font-size: 13px; margin-top: 18px">必要的知识点解释</h3><pre class="tiny" style="margin-top: 9px">{{ q.knowledgeExplanation }}</pre><div v-if="q.evidence.length" class="evidence"><strong>资料或回答依据</strong><p v-for="(item, i) in q.evidence" :key="i">{{ item }}</p></div></div></article>
        <div class="two-columns section"><section class="panel"><h2>下一次，重点练这些</h2><p v-if="!report.weaknesses.length" class="tiny muted" style="margin-top: 15px">目前没有足够证据列出薄弱项。可以继续完整练习。</p><div v-for="(weakness, i) in report.weaknesses" :key="i" class="list-row"><div class="list-body"><p style="color: var(--text-secondary); margin: 0">{{ weakness }}</p></div><RouterLink :to="{ path: '/configure/quick', query: { focus: weakness, role: session.role, level: session.level } }" class="btn btn-secondary btn-sm">专项练习 <PhArrowRight :size="13" /></RouterLink></div></section><section class="panel"><PhCheckCircle :size="24" weight="light" style="margin-bottom: 15px; color: var(--accent-ink)" /><h2>后续练习建议</h2><ul class="reading-list" style="margin-top: 17px"><li v-for="(suggestion, i) in report.suggestions" :key="i">{{ suggestion }}</li></ul></section></div>
      </template>
      <details class="panel section"><summary style="cursor: pointer; display: flex; align-items: center; gap: 9px; font-weight: 600"><PhChatsCircle :size="20" />查看完整原始对话（{{ session.messages.length }} 条）</summary><div style="margin-top: 24px"><div v-for="m in session.messages" :key="m.id" style="margin-bottom: 19px"><span class="badge" :class="m.role === 'USER' ? 'badge-orange' : ''">{{ m.role === 'USER' ? '我的回答' : '面试官' }} · 第 {{ m.questionIndex + 1 }} 题{{ m.followUp ? '追问' : '' }}</span><pre style="font-size: 13px; margin-top: 9px">{{ m.content }}</pre></div><p v-if="!session.messages.length" class="muted tiny">本场没有生成对话。</p></div></details>
    </template>
  </div>
</template>
