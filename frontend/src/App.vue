<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppLayout from './components/AppLayout.vue'
import { request } from './api'
import type { Health } from './types'
const health = ref<Health | null>(null)
const loaded = ref(false)
const providerLabel = computed(() => !loaded.value ? '正在连接' : !health.value ? '后端未连接' : health.value.provider === 'mock' ? '测试模式 · Mock' : health.value.configured ? `模型已配置 · ${health.value.model}` : '模型待配置')
onMounted(async () => { try { health.value = await request<Health>('/health') } catch { health.value = null } finally { loaded.value = true } })
</script>
<template><AppLayout :provider-label="providerLabel" :mock="health?.provider === 'mock'" :configured="health?.configured || false"><RouterView :key="$route.fullPath" /></AppLayout></template>
