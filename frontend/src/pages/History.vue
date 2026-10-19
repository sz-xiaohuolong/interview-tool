<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowUpRight, PhChatsCircle, PhPlay, PhTrash } from '@phosphor-icons/vue'
import { request, errorMessage } from '../api'
import type { Session } from '../types'
import { dateTime } from '../format'
import { interviewRecords } from '../interview-records'
import { deleteSession as deleteComprehensiveSession, listSessions } from '../api/interviews'
import type { InterviewSessionSummary } from '../models/interview'
import Notice from '../components/Notice.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
const sessions = ref<Session[]>([]), loading = ref(true), error = ref(''), filter = ref('all'), deletingKey = ref('')
const router = useRouter(), comprehensiveSessions = ref<InterviewSessionSummary[]>([])
const shown = computed(() => interviewRecords(sessions.value, comprehensiveSessions.value)
  .filter(session => filter.value === 'all' || (filter.value === 'done' ? session.done : !session.done)))
onMounted(async () => {
  // 综合面试 启动时先注册路由；旧入口继续只加载旧接口。
  const enabled = router.getRoutes().some(route => route.path === '/interviews/:id')
  const results = await Promise.allSettled([
    request<Session[]>('/sessions').then(value => { sessions.value = value }),
    ...(enabled ? [listSessions().then(value => { comprehensiveSessions.value = value })] : []),
  ])
  error.value = results.flatMap((result, index) => result.status === 'rejected'
    ? [`${index === 0 ? '旧版面试' : '快速练习与综合面试'}记录加载失败：${errorMessage(result.reason)}`] : []).join('；')
  loading.value = false
})
async function deleteSession(session: { id: string; role: string; source: 'legacy' | 'interview'; key: string }) {
  if (deletingKey.value || !window.confirm(`删除“${session.role}”这条面试记录？删除后无法继续面试、回放或查看复盘，已整理的错题和独立练习记录会保留。`)) return
  deletingKey.value = session.key
  error.value = ''
  try {
    if (session.source === 'interview') {
      await deleteComprehensiveSession(session.id)
      comprehensiveSessions.value = comprehensiveSessions.value.filter(item => item.id !== session.id)
    } else {
      await request(`/sessions/${encodeURIComponent(session.id)}`, { method: 'DELETE' })
      sessions.value = sessions.value.filter(item => item.id !== session.id)
    }
  } catch (e) { error.value = errorMessage(e) }
  finally { deletingKey.value = '' }
}
</script>

<template>
  <div class="page history-page">
    <div class="page-heading"><h1>每一次练习，都有迹可循。</h1><p>继续上次的对话，或回看你的表达与进步。</p></div>
    <Notice :message="error" kind="error" />
    <div class="tabs" aria-label="面试记录筛选">
      <button v-for="tab in [{ id: 'all', name: '全部练习' }, { id: 'active', name: '待继续' }, { id: 'done', name: '已完成' }]" :key="tab.id" class="tab" :class="{ active: filter === tab.id }" @click="filter = tab.id">{{ tab.name }}</button>
    </div>
    <LoadingState v-if="loading" />
    <div v-else class="panel">
      <EmptyState v-if="!shown.length" title="这里还没有面试记录" description="开始一场面试，让每次思考都留下记录。"><RouterLink to="/configure/quick" class="btn btn-primary">开始第一场面试</RouterLink></EmptyState>
      <div v-for="s in shown" :key="s.key" class="list-row history-row">
        <RouterLink :to="s.href" class="history-entry">
          <div class="list-icon"><PhChatsCircle :size="22" /></div>
          <div class="list-body"><h3>{{ s.role }}</h3><p>{{ s.modeLabel }} · {{ dateTime(s.createdAt) }}<span v-if="s.answerCount !== undefined"> · 已回答 {{ s.answerCount }} 次</span><span v-if="s.mock"> · Mock 测试</span></p></div>
          <span class="badge" :class="s.done ? '' : 'badge-orange'">{{ s.statusLabel }}</span>
        </RouterLink>
        <div class="history-actions">
          <RouterLink v-if="s.done" :to="s.replayHref" class="btn btn-secondary btn-sm"><PhPlay :size="16" />回放</RouterLink>
          <RouterLink :to="s.href" class="text-link">{{ s.actionLabel }} <PhArrowUpRight :size="16" /></RouterLink>
          <button type="button" class="btn btn-ghost btn-sm history-delete" :disabled="!!deletingKey" :aria-label="`删除${s.role}的面试记录`" @click="deleteSession(s)"><PhTrash :size="16" />{{ deletingKey === s.key ? '删除中…' : '删除' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.history-entry { display: flex; flex: 1; align-items: center; gap: 14px; min-width: 0; }
.history-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 16px; }
.history-actions .text-link { white-space: nowrap; }
.history-delete { color: var(--danger); flex-shrink: 0; }
@media (max-width: 600px) {
  .history-entry { flex-wrap: wrap; flex-basis: 100%; gap: 12px; }
  .history-actions { width: 100%; gap: 12px; }
  .history-actions > a, .history-delete { min-height: 44px; }
}
</style>
