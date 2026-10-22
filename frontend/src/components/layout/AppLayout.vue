<script setup lang="ts">
import { MessagesSquare, LayoutDashboard, Library, History, ChevronRight, Zap } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { computed, provide, ref } from 'vue'
import ModelSettingsDialog from '../model/ModelSettingsDialog.vue'
import { notifyModelConfigurationChanged, type ModelConfiguration } from '../../model-settings'
import { interviewNavigationKey, navigationFor, type InterviewNavigationContext } from '../../navigation'
import { PanelLeftClose, PanelLeftOpen, Settings2 } from 'lucide-vue-next'
import { cn } from '../../utils/classes'
import DailyQuote from './DailyQuote.vue'
const props = defineProps<{ providerLabel: string; mock: boolean; configured: boolean; demo?: boolean }>()
const emit = defineEmits<{ 'configuration-changed': [] }>()
const collapsed = ref(false)
const settingsOpen = ref(false), savedSettings = ref<ModelConfiguration | null>(null)
const label = computed(() => !savedSettings.value ? props.providerLabel : savedSettings.value.provider === 'mock' ? '测试模式 · Mock' : savedSettings.value.configured ? `模型已配置 · ${savedSettings.value.model}` : '模型待配置')
const isMock = computed(() => savedSettings.value ? savedSettings.value.provider === 'mock' : props.mock)
const isConfigured = computed(() => savedSettings.value ? savedSettings.value.configured : props.configured)
function settingsSaved(value: ModelConfiguration) {
  if (!savedSettings.value || value.revision >= savedSettings.value.revision) savedSettings.value = value
  notifyModelConfigurationChanged()
  emit('configuration-changed')
}
const route = useRoute()
const currentInterview = ref<InterviewNavigationContext | null>(null)
provide(interviewNavigationKey, currentInterview)
const location = computed(() => navigationFor(route.path, typeof route.meta.title === 'string' ? route.meta.title : '', currentInterview.value?.id === String(route.params.id) ? currentInterview.value.sessionType : undefined))
const navigation = [
  { to: '/', title: '练习概览', section: 'overview', icon: LayoutDashboard },
  { to: '/configure/quick', title: '快速练习', section: 'quick', icon: Zap },
  { to: '/interviews', title: '综合面试', section: 'interview', icon: MessagesSquare },
  { to: '/materials', title: '个人资料库', section: 'materials', icon: Library },
  { to: '/history', title: '面试历史', section: 'history', icon: History },
]
</script>
<template>
  <div :class="cn('app-shell', collapsed && 'sidebar-collapsed')">
    <aside class="sidebar">
      <RouterLink to="/" class="brand" aria-label="面试练习室首页">
        <span class="brand-mark"><MessagesSquare :size="23" /></span>
        <div class="brand-copy"><strong>面试练习室</strong><small>把每一次表达练得更好</small></div>
      </RouterLink>
      <div class="sidebar-navigation"><p class="nav-label">我的训练空间</p><nav aria-label="主导航">
        <template v-for="item in navigation" :key="item.to">
          <RouterLink :to="item.to" custom v-slot="{ href, navigate }">
            <a :href="href" @click="navigate" :aria-label="item.title" :title="item.title" class="nav-link" :class="{ 'router-link-active': location.primary === item.section }" :aria-current="location.primary === item.section ? 'page' : undefined">
              <component :is="item.icon" :size="16" aria-hidden="true" /><span class="nav-text">{{ item.title }}</span>
            </a>
          </RouterLink>
          <slot v-if="item.section === 'interview'" name="training-navigation" />
        </template>
      </nav></div>
      <div class="sidebar-footer"><button type="button" class="model-settings-trigger" data-model-settings aria-label="打开模型配置" :class="{ 'badge-orange': isMock, 'badge-red': !isConfigured }" @click="settingsOpen = true"><Settings2 :size="16" aria-hidden="true" /><span class="model-label">{{ label }}</span><span class="connection-dot" :class="{ unavailable: !isConfigured, mock: isMock }" aria-hidden="true" /></button></div>
    </aside>
    <div class="main-area">
      <header class="topbar">
        <button class="icon-button sidebar-toggle" type="button" data-sidebar-toggle :aria-expanded="!collapsed" :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'" @click="collapsed = !collapsed"><PanelLeftOpen v-if="collapsed" :size="16" /><PanelLeftClose v-else :size="16" /></button>
        <nav class="topbar-path" aria-label="当前位置"><ol>
          <li v-for="(step, index) in location.breadcrumbs" :key="index">
            <ChevronRight v-if="index" :size="11" aria-hidden="true" />
            <RouterLink v-if="step.to" :to="step.to">{{ step.title }}</RouterLink>
            <span v-else aria-current="page">{{ step.title }}</span>
          </li>
        </ol></nav>
        <div class="topbar-meta"><span class="local-status"><span class="connection-dot" aria-hidden="true" />{{ demo ? '演示工作空间' : '本地工作空间' }}</span><span class="topbar-date">{{ demo ? '演示版' : '个人版' }}</span></div>
      </header>
      <main id="main-content"><p v-if="demo" data-demo-notice class="demo-notice" role="note">演示工作空间 · 预置内容为虚构样例；新建练习使用单独配置的真实模型。</p><slot /></main>
      <DailyQuote />
    </div>
    <ModelSettingsDialog v-model:open="settingsOpen" @saved="settingsSaved" />
  </div>
</template>
<style scoped>
.demo-notice { margin: 0 0 20px; padding-bottom: 12px; border-bottom: 1px solid var(--border-subtle); color: var(--text-secondary); font-size: 14px; line-height: 1.6; }
</style>
