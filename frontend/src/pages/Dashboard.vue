<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { PhArrowUpRight, PhArrowRight, PhLightning, PhFileText, PhStack, PhChatsCircle, PhLockSimple, PhClock } from '@phosphor-icons/vue'
import { request, errorMessage } from '../api'
import type { Health, Material, Session } from '../types'
import { dateTime, modeNames, statusNames } from '../format'
import Notice from '../components/Notice.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
const sessions = ref<Session[]>([]), materials = ref<Material[]>([]), health = ref<Health | null>(null), loading = ref(true), error = ref('')
const completed = computed(() => sessions.value.filter(s => s.status === 'COMPLETED'))
const answers = computed(() => sessions.value.reduce((n, s) => n + s.messages.filter(m => m.role === 'USER').length, 0))
const recent = computed(() => sessions.value.slice(0, 4))
onMounted(async () => {
  try { [sessions.value, materials.value, health.value] = await Promise.all([request<Session[]>('/sessions'), request<Material[]>('/materials'), request<Health>('/health')]) }
  catch (e) { error.value = errorMessage(e) } finally { loading.value = false }
})
</script>
<template>
  <div class="page">
    <div class="page-heading"><p class="eyebrow" style="margin-top: 0">每一次练习，都是下一次的底气</p><h1>把你会的，讲得更好。</h1><p>从一道问题开始，练习技术深度、表达与临场应对。</p></div>
    <Notice :message="error" kind="error" />
    <Notice v-if="health && !health.configured" message="还没有配置模型。请填写项目根目录 .env.local 中的模型地址、密钥和模型名称，重启后端后开始练习。" kind="warning" />
    <Notice v-if="health?.provider === 'mock'" message="当前为 Mock 测试模式：可体验完整流程，题目和评分仅供功能验证。正式练习请配置真实模型。" kind="warning" />
    <LoadingState v-if="loading" />
    <template v-else>
      <div class="stat-strip"><div class="stat"><div class="stat-value">{{ completed.length }}<span class="tiny muted" style="margin-left: 7px">场</span></div><div class="stat-label">已完成面试</div></div><div class="stat"><div class="stat-value">{{ answers }}<span class="tiny muted" style="margin-left: 7px">次</span></div><div class="stat-label">已提交回答</div></div><div class="stat"><div class="stat-value">{{ materials.length }}<span class="tiny muted" style="margin-left: 7px">份</span></div><div class="stat-label">保存的个人资料</div></div></div>
      <div class="heading-row"><h2>今天，想练哪一种？</h2><span class="tiny muted">选择你的练习方式</span></div>
      <div class="mode-grid">
        <RouterLink to="/configure/quick" class="mode-card featured"><span class="mode-number">01</span><PhLightning class="mode-icon" :size="30" weight="light" /><h3>快速岗位面试</h3><p>不用准备资料。选一个目标岗位，马上进入面试状态。</p><span class="card-arrow">开始练习 <PhArrowUpRight :size="16" /></span></RouterLink>
        <RouterLink to="/configure/resume" class="mode-card"><span class="mode-number">02</span><PhFileText class="mode-icon" :size="30" weight="light" /><h3>简历针对押题</h3><p>从真实经历出发，提前准备最值得关注的问题。</p><span class="card-arrow">分析我的简历 <PhArrowUpRight :size="16" /></span></RouterLink>
        <RouterLink to="/configure/project" class="mode-card"><span class="mode-number">03</span><PhStack class="mode-icon" :size="30" weight="light" /><h3>项目深度面试</h3><p>从整体架构到技术权衡，讲透你真正做过的项目。</p><span class="card-arrow">深入聊一个项目 <PhArrowUpRight :size="16" /></span></RouterLink>
      </div>
      <div class="two-columns section">
        <section><div class="heading-row"><h2>最近的练习</h2><RouterLink to="/history" class="text-link">全部记录 <PhArrowRight :size="14" /></RouterLink></div>
          <div class="panel" style="padding-top: 4px; padding-bottom: 4px" v-if="recent.length"><RouterLink v-for="s in recent" :key="s.id" :to="s.status === 'COMPLETED' ? `/reports/${s.id}` : `/sessions/${s.id}`" class="list-row"><div class="list-icon"><PhChatsCircle :size="21" /></div><div class="list-body"><h3>{{ s.role }}</h3><p>{{ modeNames[s.mode] }} · {{ dateTime(s.createdAt) }}<span v-if="s.mock"> · 测试</span></p></div><span class="badge" :class="s.status === 'COMPLETED' ? '' : 'badge-orange'">{{ statusNames[s.status] }}</span><PhArrowUpRight :size="17" class="muted" /></RouterLink></div>
          <div v-else class="panel"><EmptyState title="第一场练习，从这里开始" description="完成面试后，你的对话和复盘会保存在这里。" /></div>
        </section>
        <section><div class="heading-row"><h2>我的资料</h2><RouterLink to="/materials" class="text-link">管理 <PhArrowRight :size="14" /></RouterLink></div><div class="panel" style="padding-top: 4px; padding-bottom: 4px" v-if="materials.length"><RouterLink v-for="m in materials.slice(0, 3)" :key="m.id" :to="`/materials?kind=${m.kind}`" class="list-row"><div class="list-icon"><PhFileText :size="20" /></div><div class="list-body"><h3>{{ m.name }}</h3><p>{{ { RESUME: '简历', PROJECT: '项目', JD: '目标岗位' }[m.kind] }} · {{ m.documents.length }} 份附件</p></div><PhArrowUpRight :size="16" class="muted" /></RouterLink></div><div class="panel" v-else><EmptyState title="让练习更贴近你" description="添加简历或项目，获得更有针对性的问题。"><RouterLink to="/materials" class="btn btn-secondary btn-sm">添加个人资料</RouterLink></EmptyState></div></section>
      </div>
      <div class="footer-note"><PhLockSimple :size="13" />资料与记录保存在本机 <span style="margin: 0 5px">·</span><PhClock :size="13" />按自己的节奏，持续练习</div>
    </template>
  </div>
</template>
