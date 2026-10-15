<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { PhArrowUpRight, PhChatsCircle } from '@phosphor-icons/vue'
import { request, errorMessage } from '../api'
import type { Session } from '../types'
import { dateTime, modeNames, statusNames } from '../format'
import Notice from '../components/Notice.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
const sessions = ref<Session[]>([]), loading = ref(true), error = ref(''), filter = ref('all')
const shown = computed(() => sessions.value.filter(s => filter.value === 'all' || (filter.value === 'done' ? s.status === 'COMPLETED' : s.status !== 'COMPLETED')))
onMounted(async () => { try { sessions.value = await request<Session[]>('/sessions') } catch (e) { error.value = errorMessage(e) } finally { loading.value = false } })
</script>
<template><div class="page history-page"><div class="page-heading"><h1>每一次练习，都有迹可循。</h1><p>继续上次的对话，或回看你的表达与进步。</p></div><Notice :message="error" kind="error" /><div class="tabs" aria-label="面试记录筛选"><button v-for="tab in [{ id: 'all', name: '全部练习' }, { id: 'active', name: '待继续' }, { id: 'done', name: '已完成' }]" :key="tab.id" class="tab" :class="{ active: filter === tab.id }" @click="filter = tab.id">{{ tab.name }}</button></div><LoadingState v-if="loading" /><div class="panel" v-else><EmptyState v-if="!shown.length" title="这里还没有面试记录" description="开始一场面试，让每次思考都留下记录。"><RouterLink to="/configure/quick" class="btn btn-primary">开始第一场面试</RouterLink></EmptyState><RouterLink v-for="s in shown" :key="s.id" :to="s.status === 'COMPLETED' ? `/reports/${s.id}` : `/sessions/${s.id}`" class="list-row"><div class="list-icon"><PhChatsCircle :size="22" /></div><div class="list-body"><h3>{{ s.role }}</h3><p>{{ modeNames[s.mode] }} · {{ dateTime(s.createdAt) }} · 已回答 {{ s.messages.filter(m => m.role === 'USER').length }} 次<span v-if="s.mock"> · Mock 测试</span></p></div><span class="badge" :class="s.status === 'COMPLETED' ? '' : 'badge-orange'">{{ statusNames[s.status] }}</span><span class="text-link">{{ s.status === 'COMPLETED' ? '查看复盘' : '继续面试' }} <PhArrowUpRight :size="16" /></span></RouterLink></div></div></template>
