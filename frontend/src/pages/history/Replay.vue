<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, ArrowUpRight, Bot, User } from 'lucide-vue-next'
import { request, errorMessage } from '../../api'
import type { Session } from '../../types'
import type { InterviewSession } from '../../models/interview'
import { displayDate, getSession, levelLabels } from '../../api/interviews'
import { modeNames } from '../../format'
import Notice from '../../components/common/Notice.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import LoadingState from '../../components/common/LoadingState.vue'

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
      <RouterLink to="/history" class="btn btn-secondary"><ArrowLeft :size="17" />返回面试历史</RouterLink>
    </div>
    <LoadingState v-if="loading" label="正在加载原始对话…" />
    <Notice v-else-if="error" :message="error" kind="error"><button class="btn btn-secondary btn-sm replay-retry" @click="reload++">重新加载</button></Notice>
    <template v-else-if="session">
      <div class="replay-workspace">
      <section class="panel replay-summary" aria-label="本场面试信息">
        <div><h2>{{ session.role }}</h2><p>{{ modeLabel }} · {{ levelLabels[session.level] || session.level }} · {{ displayDate(session.createdAt) }} · 已回答 {{ answerCount }} 次</p></div>
        <RouterLink v-if="reportHref" :to="reportHref" class="text-link">查看复盘<ArrowUpRight :size="16" /></RouterLink>
      </section>
      <Notice v-if="session.mock" kind="warning" message="Mock 测试面试：以下为合成对话记录。" />
      <div class="chat-panel">
        <EmptyState v-if="!session.messages.length" title="暂无对话记录" description="这场面试尚未保存任何提问或回答。" />
        <ol v-else class="replay-messages" aria-label="原始面试对话">
          <li v-for="message in session.messages" :key="message.id" class="message" :class="{ user: message.role === 'USER' }">
            <div class="avatar" aria-hidden="true"><User v-if="message.role === 'USER'" :size="16" /><Bot v-else :size="17" /></div>
            <div class="replay-message-body">
              <div class="message-meta"><span>{{ message.role === 'USER' ? '我的回答' : '面试官' }}</span><time :datetime="message.createdAt">{{ displayDate(message.createdAt) }}</time></div>
              <div class="message-content">{{ message.content }}</div>
            </div>
          </li>
        </ol>
      </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.replay-page { max-width:1320px; }.replay-heading { display:flex; align-items:center; justify-content:space-between; gap:20px; }.replay-heading .btn { flex-shrink:0; }.replay-workspace { display:grid; grid-template-columns:minmax(0,3fr) minmax(240px,2fr); border:1px solid var(--border-subtle); border-radius:8px; overflow:clip; align-items:start; }.replay-summary { grid-column:2; grid-row:1 / 3; border:0; border-radius:0; border-left:1px solid var(--border-subtle); padding:20px; background:var(--surface-soft); align-self:stretch; }.replay-summary h2 { font-size:16px; }.replay-summary p { margin:8px 0 20px; color:var(--text-muted); font-size:12px; font-variant-numeric:tabular-nums; }.replay-workspace > .chat-panel { grid-column:1; grid-row:2; border:0; border-radius:0; }.replay-workspace > .notice { grid-column:1; grid-row:1; margin:12px 16px 0; }.replay-messages { display:flex; flex-direction:column; gap:24px; list-style:none; margin:0; padding:20px 16px; }.replay-messages .message { max-width:96%; }.replay-message-body { min-width:0; }.replay-retry { display:flex; margin-top:12px; }
@media(max-width:800px) { .replay-workspace { display:flex; flex-direction:column; }.replay-summary { border-left:0; border-bottom:1px solid var(--border-subtle); }.replay-workspace > .chat-panel { width:100%; }.replay-heading { align-items:flex-start; flex-direction:column; gap:12px; }.replay-messages .message { max-width:100%; } }
</style>
