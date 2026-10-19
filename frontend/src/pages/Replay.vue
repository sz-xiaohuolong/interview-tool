<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { PhArrowLeft, PhArrowUpRight, PhRobot, PhUser } from '@phosphor-icons/vue'
import { request, errorMessage } from '../api'
import type { Session } from '../types'
import type { InterviewSession } from '../models/interview'
import { displayDate, getSession, levelLabels } from '../api/interviews'
import { modeNames } from '../format'
import Notice from '../components/Notice.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'

const props = withDefaults(defineProps<{ source?: 'legacy' | 'interview' }>(), { source: 'legacy' })
const route = useRoute()
const session = ref<Session | InterviewSession | null>(null)
const loading = ref(true), error = ref(''), reload = ref(0)
const answerCount = computed(() => session.value?.messages.filter(message => message.role === 'USER').length || 0)
const modeLabel = computed(() => session.value && 'mode' in session.value ? modeNames[session.value.mode] : session.value?.sessionType === 'QUICK' ? '快速练习' : '综合面试')
const reportHref = computed(() => {
  if (!session.value) return ''
  const id = encodeURIComponent(session.value.id)
  return props.source === 'interview'
    ? session.value.status === 'REPORT_READY' ? `/interviews/${id}` : ''
    : session.value.status === 'COMPLETED' ? `/reports/${id}` : ''
})

watch([() => route.params.id, () => props.source, reload], async ([id, source], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  session.value = null
  loading.value = true
  error.value = ''
  try {
    const result = source === 'interview' ? await getSession(String(id)) : await request<Session>(`/sessions/${encodeURIComponent(String(id))}`)
    if (current) session.value = result
  } catch (e) {
    if (current) error.value = errorMessage(e)
  } finally {
    if (current) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <div class="page replay-page">
    <div class="page-heading replay-heading">
      <div><h1>对话回放</h1><p>回看当时的提问与回答。</p></div>
      <RouterLink to="/history" class="btn btn-secondary"><PhArrowLeft :size="17" />返回面试历史</RouterLink>
    </div>
    <LoadingState v-if="loading" label="正在加载原始对话…" />
    <Notice v-else-if="error" :message="error" kind="error"><button class="btn btn-secondary btn-sm replay-retry" @click="reload++">重新加载</button></Notice>
    <template v-else-if="session">
      <section class="panel replay-summary" aria-label="本场面试信息">
        <div><h2>{{ session.role }}</h2><p>{{ modeLabel }} · {{ levelLabels[session.level] || session.level }} · {{ displayDate(session.createdAt) }} · 已回答 {{ answerCount }} 次</p></div>
        <RouterLink v-if="reportHref" :to="reportHref" class="text-link">查看复盘<PhArrowUpRight :size="16" /></RouterLink>
      </section>
      <Notice v-if="session.mock" kind="warning" message="Mock 测试面试：以下为合成对话记录。" />
      <div class="chat-panel">
        <EmptyState v-if="!session.messages.length" title="暂无对话记录" description="这场面试尚未保存任何提问或回答。" />
        <ol v-else class="replay-messages" aria-label="原始面试对话">
          <li v-for="message in session.messages" :key="message.id" class="message" :class="{ user: message.role === 'USER' }">
            <div class="avatar" aria-hidden="true"><PhUser v-if="message.role === 'USER'" :size="16" /><PhRobot v-else :size="17" /></div>
            <div class="replay-message-body">
              <div class="message-meta"><span>{{ message.role === 'USER' ? '我的回答' : '面试官' }}</span><time :datetime="message.createdAt">{{ displayDate(message.createdAt) }}</time></div>
              <div class="message-content">{{ message.content }}</div>
            </div>
          </li>
        </ol>
      </div>
    </template>
  </div>
</template>

<style scoped>
.replay-page { max-width: 1080px; }
.replay-heading, .replay-summary { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.replay-heading .btn, .replay-summary .text-link { flex-shrink: 0; }
.replay-summary { margin-bottom: 20px; }
.replay-summary h2 { font-size: 20px; overflow-wrap: anywhere; }
.replay-summary p { margin: 6px 0 0; color: var(--text-muted); font-size: 13px; }
.replay-messages { display: flex; flex-direction: column; gap: 32px; list-style: none; margin: 0; padding: 32px 24px; }
.replay-messages .message { max-width: 92%; }
.replay-message-body { min-width: 0; }
.replay-retry { display: flex; margin-top: 12px; }
@media (max-width: 600px) {
  .replay-heading, .replay-summary { align-items: flex-start; flex-direction: column; gap: 16px; }
  .replay-summary .text-link { min-height: 44px; }
  .replay-messages { padding: 24px 14px; gap: 28px; }
  .replay-messages .message { max-width: 100%; }
}
</style>
