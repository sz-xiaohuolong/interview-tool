<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { PhArrowUpRight, PhChatsCircle } from '@phosphor-icons/vue'
import { request, errorMessage } from '../api'
import type { Session } from '../types'
import { dateTime, modeNames, statusNames } from '../format'
import { listSessions, statusLabels } from '../v3/api'
import type { InterviewSessionSummary } from '../v3/types'
import Notice from '../components/Notice.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
const sessions = ref<Session[]>([]), loading = ref(true), error = ref(''), filter = ref('all')
const router = useRouter(), comprehensiveSessions = ref<InterviewSessionSummary[]>([])
const shown = computed(() => [
  ...sessions.value.map(s => ({
    ...s, key: `legacy:${s.id}`, source: 'legacy', done: s.status === 'COMPLETED',
    modeLabel: modeNames[s.mode], statusLabel: statusNames[s.status],
    answerCount: s.messages.filter(m => m.role === 'USER').length,
    href: s.status === 'COMPLETED' ? `/reports/${s.id}` : `/sessions/${s.id}`,
    actionLabel: s.status === 'COMPLETED' ? '查看复盘' : '继续面试',
  })),
  ...comprehensiveSessions.value.map(s => ({
    ...s, key: `v3:${s.id}`, source: 'v3', done: ['COMPLETED', 'REPORTING', 'REPORT_READY', 'CANCELLED'].includes(s.status),
    modeLabel: '综合面试', statusLabel: statusLabels[s.status] || '任务处理中', answerCount: undefined,
    href: `/interview-v3/${encodeURIComponent(s.id)}`,
    actionLabel: ({ COMPLETED: '生成复盘', REPORTING: '查看进度', REPORT_READY: '查看复盘', CANCELLED: '查看记录', IN_PROGRESS: '继续面试', ANSWERING: '继续面试' } as Record<string, string>)[s.status] || '继续查看',
  })),
].filter(s => filter.value === 'all' || (filter.value === 'done' ? s.done : !s.done))
  .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)))
onMounted(async () => {
  // V3 启动时先注册路由；旧入口继续只加载旧接口。
  const enabled = router.getRoutes().some(route => route.path === '/interview-v3/:id')
  const results = await Promise.allSettled([
    request<Session[]>('/sessions').then(value => { sessions.value = value }),
    ...(enabled ? [listSessions().then(value => { comprehensiveSessions.value = value })] : []),
  ])
  error.value = results.flatMap((result, index) => result.status === 'rejected'
    ? [`${index === 0 ? '旧版面试' : '综合面试'}记录加载失败：${errorMessage(result.reason)}`] : []).join('；')
  loading.value = false
})
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
          <span class="text-link">{{ s.actionLabel }} <PhArrowUpRight :size="16" /></span>
        </RouterLink>
      </div>
    </div>
  </div>
</template>

<style scoped>
.history-entry { display: flex; flex: 1; align-items: center; gap: 14px; min-width: 0; }
@media (max-width: 600px) {
  .history-entry { flex-wrap: wrap; flex-basis: 100%; gap: 12px; }
  .history-entry .text-link { margin-left: 52px; }
}
</style>
