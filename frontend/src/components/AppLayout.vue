<script setup lang="ts">
import { PhChatsCircle, PhSquaresFour, PhBooks, PhClockCounterClockwise, PhCaretRight, PhLockSimple, PhLightning } from '@phosphor-icons/vue'
import { useRoute } from 'vue-router'
import { computed, ref } from 'vue'
import ModelSettingsDialog from './ModelSettingsDialog.vue'
import { notifyModelConfigurationChanged, type ModelConfiguration } from '../model-settings'
import { navigationFor } from '../navigation'
const props = defineProps<{ providerLabel: string; mock: boolean; configured: boolean }>()
const emit = defineEmits<{ 'configuration-changed': [] }>()
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
const location = computed(() => navigationFor(route.path, typeof route.meta.title === 'string' ? route.meta.title : ''))
const navigation = [
  { to: '/', title: '练习概览', section: 'overview', icon: PhSquaresFour },
  { to: '/configure/quick', title: '开始面试', section: 'interview', icon: PhChatsCircle },
  { to: '/materials', title: '个人资料库', section: 'materials', icon: PhBooks },
  { to: '/history', title: '面试历史', section: 'history', icon: PhClockCounterClockwise },
]
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand" aria-label="面试练习室首页">
        <span class="brand-mark"><PhChatsCircle :size="23" weight="duotone" /></span>
        <div><strong>面试练习室</strong><small>把每一次表达练得更好</small></div>
      </RouterLink>
      <div><p class="nav-label">我的训练空间</p><nav aria-label="主导航">
        <template v-for="item in navigation" :key="item.to">
          <RouterLink :to="item.to" custom v-slot="{ href, navigate }">
            <a :href="href" @click="navigate" class="nav-link" :class="{ 'router-link-active': location.primary === item.section }" :aria-current="location.primary === item.section ? 'page' : undefined">
              <component :is="item.icon" :size="20" weight="regular" />{{ item.title }}
            </a>
          </RouterLink>
          <slot v-if="item.section === 'interview'" name="training-navigation" />
        </template>
      </nav></div>
      <div class="sidebar-bottom"><PhLockSimple :size="18" style="margin-bottom: 10px" /><strong>你的私人练习空间</strong>资料与记录保存在本机。<br />真实练习使用你配置的模型服务。</div>
    </aside>
    <div class="main-area">
      <header class="topbar">
        <nav class="topbar-path" aria-label="当前位置"><ol>
          <li v-for="(step, index) in location.breadcrumbs" :key="index">
            <PhCaretRight v-if="index" :size="11" aria-hidden="true" />
            <RouterLink v-if="step.to" :to="step.to">{{ step.title }}</RouterLink>
            <span v-else aria-current="page">{{ step.title }}</span>
          </li>
        </ol></nav>
        <div class="topbar-meta"><button type="button" class="badge model-settings-trigger" data-model-settings aria-label="打开模型配置" :class="{ 'badge-orange': isMock, 'badge-red': !isConfigured }" @click="settingsOpen = true"><PhLightning :size="12" />{{ label }}</button><span class="topbar-date">个人版</span></div>
      </header>
      <main id="main-content"><slot /></main>
    </div>
    <ModelSettingsDialog v-model:open="settingsOpen" @saved="settingsSaved" />
  </div>
</template>
<style scoped>
.model-settings-trigger { cursor: pointer; font: inherit; font-size: 12px; max-width: min(44vw, 360px); overflow-wrap: anywhere; text-align: left; }
.model-settings-trigger:hover { filter: brightness(.96); }
.model-settings-trigger:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
</style>
