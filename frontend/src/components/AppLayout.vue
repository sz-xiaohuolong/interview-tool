<script setup lang="ts">
import { PhChatsCircle, PhSquaresFour, PhBooks, PhClockCounterClockwise, PhCaretRight, PhLockSimple, PhLightning } from '@phosphor-icons/vue'
import { useRoute } from 'vue-router'
defineProps<{ providerLabel: string; mock: boolean; configured: boolean }>()
const route = useRoute()
const navigation = [
  { to: '/', title: '练习概览', icon: PhSquaresFour },
  { to: '/configure/quick', title: '开始面试', icon: PhChatsCircle },
  { to: '/materials', title: '个人资料库', icon: PhBooks },
  { to: '/history', title: '面试历史', icon: PhClockCounterClockwise },
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
        <RouterLink v-for="item in navigation" :key="item.to" :to="item.to" class="nav-link" :class="item.to === '/' && route.path !== '/' ? 'not-active' : ''" :exact-active-class="'router-link-active'" :active-class="item.to === '/' ? '' : 'router-link-active'">
          <component :is="item.icon" :size="20" weight="regular" />{{ item.title }}
        </RouterLink>
      </nav></div>
      <div class="sidebar-bottom"><PhLockSimple :size="18" style="margin-bottom: 10px" /><strong>你的私人练习空间</strong>资料与记录保存在本机。<br />真实练习使用你配置的模型服务。</div>
    </aside>
    <div class="main-area">
      <header class="topbar">
        <div class="topbar-path"><span>我的空间</span><PhCaretRight :size="11" /><span>{{ route.meta.title || '练习概览' }}</span></div>
        <div class="topbar-meta"><span class="badge" :class="{ 'badge-orange': mock, 'badge-red': !configured }"><PhLightning :size="12" />{{ providerLabel }}</span><span class="topbar-date">个人版</span></div>
      </header>
      <main id="main-content"><slot /></main>
    </div>
  </div>
</template>
