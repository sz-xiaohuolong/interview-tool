<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PhPaperPlaneRight, PhStop, PhArrowClockwise, PhChatsCircle, PhArrowRight, PhUser, PhRobot } from '@phosphor-icons/vue'
import { request, post, errorMessage } from '../../api'
import type { Session } from '../../types'
import { modeNames, levelNames, styleNames } from '../../format'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import Modal from '../../components/common/Modal.vue'
import SpeechInput from '../../components/interview/SpeechInput.vue'
const route = useRoute(), router = useRouter(), id = String(route.params.id)
const session = ref<Session | null>(null), loading = ref(true), busy = ref(false), error = ref(''), endDialog = ref(false), chat = ref<HTMLElement | null>(null)
const draftKey = `interview-draft-${id}`, pendingKey = `interview-pending-${id}`
const draft = ref(sessionStorage.getItem(draftKey) || '')
const speechInput = ref<{ cancel: () => void } | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const last = computed(() => session.value?.messages.at(-1))
const canAnswer = computed(() => session.value?.status === 'IN_PROGRESS' && !session.value.pendingAction && last.value?.role === 'ASSISTANT')
const failed = computed(() => ['INTERRUPTED', 'FAILED'].includes(session.value?.status || ''))
const generating = computed(() => !failed.value && !!session.value?.pendingAction && session.value.status !== 'COMPLETED')
watch(draft, value => sessionStorage.setItem(draftKey, value))
async function scrollChat() { await nextTick(); if (chat.value) chat.value.scrollTop = chat.value.scrollHeight }
function reconcile(s: Session) {
  session.value = s
  const stored = sessionStorage.getItem(pendingKey)
  if (stored) {
    try {
      const pending = JSON.parse(stored)
      const questionAt = s.messages.findIndex(m => m.id === pending.expectedMessageId)
      const savedAnswer = questionAt >= 0 ? s.messages[questionAt + 1] : undefined
      if (savedAnswer?.role === 'USER') {
        if (savedAnswer.content.trim() === pending.content.trim()) draft.value = ''
        else if (!error.value) error.value = '该问题已在其他页面回答。你的草稿已保留，请核对当前问题后再提交。'
        sessionStorage.removeItem(pendingKey)
      }
    } catch { sessionStorage.removeItem(pendingKey) }
  }
  void scrollChat()
  clearTimeout(timer)
  if (!disposed && s.pendingAction && ['CREATED', 'IN_PROGRESS'].includes(s.status)) timer = setTimeout(() => { void refresh() }, 1400)
}
async function refresh() {
  try { reconcile(await request<Session>(`/sessions/${id}`)) } catch (e) { error.value = errorMessage(e) }
}
onMounted(async () => { await refresh(); loading.value = false })
onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
async function send() {
  const content = draft.value.trim()
  if (busy.value || !canAnswer.value || !content || !last.value) return
  speechInput.value?.cancel()
  busy.value = true; error.value = ''
  let payload = { content, requestId: crypto.randomUUID(), expectedMessageId: last.value.id }
  try {
    const saved = JSON.parse(sessionStorage.getItem(pendingKey) || 'null')
    if (saved?.content === content && saved?.expectedMessageId === last.value.id) payload = saved
  } catch { /* 本地草稿损坏时重新生成请求标识。 */ }
  sessionStorage.setItem(pendingKey, JSON.stringify(payload))
  try {
    const s = await post<Session>(`/sessions/${id}/answers`, payload); reconcile(s)
    if (s.status === 'COMPLETED') await router.push(`/reports/${id}`)
  } catch (e) { error.value = errorMessage(e); await refresh() }
  finally { busy.value = false }
}
async function retry() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { const s = await post<Session>(`/sessions/${id}/retry`); reconcile(s); if (s.status === 'COMPLETED') await router.push(`/reports/${id}`) }
  catch (e) { error.value = errorMessage(e); await refresh() } finally { busy.value = false }
}
async function finish() {
  if (busy.value) return
  speechInput.value?.cancel()
  endDialog.value = false; busy.value = true; error.value = ''
  try { const s = await post<Session>(`/sessions/${id}/finish`); reconcile(s); await router.push(`/reports/${id}`) }
  catch (e) { error.value = errorMessage(e); await refresh() } finally { busy.value = false }
}
function shortcut(event: KeyboardEvent) { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) { event.preventDefault(); void send() } }
</script>
<template>
  <div class="page session-page">
    <div class="page-heading"><h1>专注此刻，把思路讲清楚。</h1><p>不必追求一次完美回答。说出你的判断、依据，以及不确定的地方。</p></div>
    <Notice :message="error" kind="error" />
    <LoadingState v-if="loading" label="正在恢复面试记录…" />
    <template v-else-if="session">
      <Notice v-if="session.mock" kind="warning" message="Mock 测试面试：用于验证流程，问题和评分不代表真实模型能力。" />
      <Notice v-if="session.contextTruncated" kind="warning" message="本场资料上下文已截断，模型仅使用保留部分。" />
      <div class="session-layout">
        <div class="chat-panel"><div class="chat-header"><span style="display: flex; align-items: center; gap: 9px; font-size: 14px"><PhChatsCircle :size="20" />面试对话</span><span class="tiny muted">{{ session.status === 'COMPLETED' ? '本场面试已结束' : '一次只讨论一个问题' }}</span></div>
          <div ref="chat" class="chat-messages" aria-live="polite" aria-label="面试对话记录"><div v-for="m in session.messages" :key="m.id" class="message" :class="{ user: m.role === 'USER' }"><div class="avatar"><PhUser v-if="m.role === 'USER'" :size="16" /><PhRobot v-else :size="17" /></div><div style="min-width: 0"><div class="message-meta"><span>{{ m.role === 'USER' ? '我的回答' : '面试官' }}</span><span v-if="m.role === 'ASSISTANT'">{{ m.followUp ? '继续追问' : `第 ${m.questionIndex + 1} 题` }}</span></div><div class="message-content">{{ m.content }}</div></div></div><div v-if="busy || generating" class="message"><div class="avatar"><PhRobot :size="17" /></div><div class="tiny muted" role="status" style="padding-top: 7px"><span class="spinner" style="width: 12px; height: 12px; margin-right: 8px"></span>正在思考与保存，请稍候…</div></div></div>
          <div class="composer">
            <template v-if="failed"><Notice :message="session.error || '本次生成中断，已经提交的回答仍然保留。'" kind="error" /><button class="btn btn-dark" :disabled="busy" @click="retry"><PhArrowClockwise :size="16" />重试生成</button><span class="tiny muted" style="margin-left: 12px">不会重复提交你的回答</span></template>
            <template v-else-if="session.status === 'COMPLETED'"><p class="muted" style="font-size: 13px">对话已保存。现在可以查看逐题分析与后续练习建议。</p><RouterLink :to="`/reports/${id}`" class="btn btn-primary" style="margin-top: 13px">查看面试复盘 <PhArrowRight :size="16" /></RouterLink></template>
            <form v-else @submit.prevent="send"><label for="answer" class="sr-only">你的回答</label><textarea id="answer" v-model="draft" placeholder="在这里组织你的回答。可以分段描述背景、思路、实现和取舍…" maxlength="6000" :disabled="busy || !canAnswer" @keydown="shortcut"></textarea><SpeechInput ref="speechInput" v-model="draft" :disabled="busy || !canAnswer" :context-key="last?.id" /><div class="composer-footer"><span class="tiny muted">{{ draft.length }} / 6000 · ⌘ / Ctrl + Enter 发送<br />Enter 换行 · 面试中不展示参考答案</span><button class="btn btn-primary" type="submit" :disabled="busy || !canAnswer || !draft.trim()"><PhPaperPlaneRight :size="17" />发送回答</button></div></form>
          </div>
        </div>
        <aside class="session-meta"><div class="panel"><span class="badge">{{ modeNames[session.mode] }}</span><h3 style="margin-top: 17px">{{ session.role }}</h3><dl><div><dt>岗位级别</dt><dd>{{ levelNames[session.level] }}</dd></div><div><dt>面试风格</dt><dd>{{ styleNames[session.style] }}</dd></div><div><dt>当前进度</dt><dd>主问题 {{ Math.min(session.questionIndex + 1, session.totalQuestions) }} / {{ session.totalQuestions }}<br /><span class="tiny muted">本题已追问 {{ session.followUpCount }} / 2 次</span></dd></div><div><dt>考查方向</dt><dd>{{ session.currentTopic || '正在规划问题' }}</dd></div><div><dt>目标时长</dt><dd>{{ session.durationMinutes }} 分钟 · 不限时</dd></div></dl><button v-if="session.status !== 'COMPLETED'" class="btn btn-secondary btn-sm" :disabled="busy || generating" style="width: 100%" @click="endDialog = true"><PhStop :size="14" />结束并复盘</button><RouterLink v-else :to="`/reports/${id}`" class="btn btn-primary btn-sm">查看复盘 <PhArrowRight :size="14" /></RouterLink></div><p class="form-help" style="margin-top: 13px">对话自动保存。可随时离开，在面试历史中继续。</p></aside>
      </div>
    </template>
    <div v-else-if="!loading" class="panel"><p class="muted">无法加载本场面试。</p><RouterLink to="/history" class="btn btn-secondary" style="margin-top: 15px">返回面试历史</RouterLink></div>
    <Modal v-model:open="endDialog" title="结束这场面试？" description="已提交的回答都会保存。结束后生成复盘，未回答的问题不会用于评分。"><div class="dialog-actions"><button class="btn btn-secondary" @click="endDialog = false">继续回答</button><button class="btn btn-primary" @click="finish">确认结束并复盘</button></div></Modal>
  </div>
</template>
