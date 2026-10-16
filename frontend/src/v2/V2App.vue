<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import { request } from '../api'
import type { Health } from '../types'
import ReportTools from './ReportTools.vue'
import LinkedConfigure from './LinkedConfigure.vue'
import SessionPracticeTools from './SessionPracticeTools.vue'
const route = useRoute(), health = ref<Health | null>(null), loaded = ref(false)
const label = computed(() => !loaded.value ? '正在连接' : !health.value ? '后端未连接' : health.value.provider === 'mock' ? '测试模式 · Mock' : health.value.configured ? `模型已配置 · ${health.value.model}` : '模型待配置')
onMounted(async () => { try { health.value = await request<Health>('/health') } catch { health.value = null } finally { loaded.value = true } })
</script>
<template><AppLayout :provider-label="label" :mock="health?.provider === 'mock'" :configured="health?.configured || false"><nav class="v2-subnav" aria-label="持续训练"><RouterLink to="/review">复习队列</RouterLink><RouterLink to="/skills">能力档案</RouterLink><RouterLink to="/practice">同题二答</RouterLink><RouterLink to="/jd-diagnostics">岗位差距与清单</RouterLink><RouterLink to="/stories">真实故事库</RouterLink><RouterLink to="/design-practice">分阶段设计</RouterLink></nav><ReportTools v-if="route.path.startsWith('/reports/')" :key="route.fullPath" :session-id="String(route.params.id)" /><SessionPracticeTools v-if="route.path.startsWith('/reports/')" :key="`practice-${route.fullPath}`" :session-id="String(route.params.id)" /><LinkedConfigure v-if="route.path === '/configure/quick' && typeof route.query.trainingLinkId === 'string'" :key="route.fullPath" /><RouterView v-else :key="$route.fullPath" /></AppLayout></template>
