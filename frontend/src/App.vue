<script setup lang="ts">
import { onModelConfigurationChanged } from './model-settings'

import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from './components/AppLayout.vue'
import { request } from './api'
import type { Health } from './types'
import ReportTools from './components/ReportTools.vue'
import LinkedConfigure from './components/LinkedConfigure.vue'
import SessionPracticeTools from './components/SessionPracticeTools.vue'
import { PhChartBar } from '@phosphor-icons/vue'
import { navigationFor, trainingNavigation } from './navigation'
const props = withDefaults(defineProps<{ trainingEnabled?: boolean }>(), { trainingEnabled: true })
const route = useRoute(), health = ref<Health | null>(null), loaded = ref(false)
const location = computed(() => navigationFor(route.path))
const label = computed(() => !loaded.value ? '正在连接' : !health.value ? '后端未连接' : health.value.provider === 'mock' ? '测试模式 · Mock' : health.value.configured ? `模型已配置 · ${health.value.model}` : '模型待配置')
async function refreshHealth() { try { health.value = await request<Health>('/health') } catch { health.value = null } finally { loaded.value = true } }
onMounted(refreshHealth)
onModelConfigurationChanged(refreshHealth)
</script>
<template>
  <AppLayout :provider-label="label" :mock="health?.provider === 'mock'" :configured="health?.configured || false">
    <template v-if="props.trainingEnabled" #training-navigation>
      <RouterLink to="/review" custom v-slot="{ href, navigate }">
        <a :href="href" @click="navigate" class="nav-link" :class="{ 'router-link-active': location.primary === 'training' }" :aria-current="location.primary === 'training' ? 'page' : undefined"><PhChartBar :size="20" weight="regular" />专项训练</a>
      </RouterLink>
    </template>
    <section v-if="props.trainingEnabled && location.training" class="training-training-navigation" aria-label="专项训练模块">
      <h2>专项训练</h2>
      <nav class="training-subnav" aria-label="持续训练">
        <RouterLink v-for="item in trainingNavigation" :key="item.to" :to="item.to" custom v-slot="{ href, navigate }">
          <a :href="href" @click="navigate" :class="{ 'router-link-active': location.training.to === item.to }" :aria-current="location.training.to === item.to ? 'page' : undefined">{{ item.title }}</a>
        </RouterLink>
      </nav>
    </section>
    <div class="training-route-surface">
      <LinkedConfigure v-if="props.trainingEnabled && route.path === '/configure/quick' && typeof route.query.trainingLinkId === 'string'" :key="route.fullPath" />
      <RouterView v-else :key="$route.fullPath" v-slot="{ Component }">
        <component :is="Component">
          <template #report-tools>
            <ReportTools v-if="props.trainingEnabled && route.path.startsWith('/reports/')" :key="route.fullPath" :session-id="String(route.params.id)" />
            <SessionPracticeTools v-if="props.trainingEnabled && route.path.startsWith('/reports/')" :key="`practice-${route.fullPath}`" :session-id="String(route.params.id)" />
          </template>
        </component>
      </RouterView>
    </div>
  </AppLayout>
</template>
