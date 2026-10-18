<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { v2, writeV2 } from './api'
import Notice from '../components/Notice.vue'
import type { ReviewItem } from './types'
const props = defineProps<{ sessionId: string }>()
const busy = ref(false), error = ref(''), items = ref<ReviewItem[]>([])
const links = ref<{ id: string; sourceKind: string; sourceId: string; sprintId?: string; actionId?: string }[]>([])
onMounted(async () => { try { links.value = await v2(`/training-links?sessionId=${encodeURIComponent(props.sessionId)}`) } catch { /* 普通 V1 报告仍照常显示。 */ } })
async function collect() {
  busy.value = true; error.value = ''
  try {
    const preview = await v2<{ reportHash: string }>(`/review-import-preview?sessionId=${encodeURIComponent(props.sessionId)}`)
    items.value = (await writeV2<{ items: ReviewItem[] }>('/review-imports', { sessionId: props.sessionId, expectedReportHash: preview.reportHash })).items
    if (!items.value.length) error.value = '本报告没有可导入的错误、遗漏或未知项。原报告仍可查看。'
  } catch (e) { error.value = e instanceof Error ? e.message : '整理失败，请重试。' } finally { busy.value = false }
}
</script>
<template>
  <section class="v2-context-tools report-collect-tools">
    <div><h2>错题复习</h2><p class="tiny muted">整理本次报告中的错误、遗漏和待核实内容，保留原分类与整题依据，不调用模型改写历史。</p></div>
    <button class="btn btn-primary" :disabled="busy" @click="collect">{{ busy ? '正在整理…' : '整理为复习项' }}</button>
    <RouterLink v-for="link in links" :key="link.id" :to="link.sourceKind === 'REVIEW' ? { path: `/review/${link.sourceId}`, query: { trainingLinkId: link.id } } : link.sprintId ? { path: `/sprints/${link.sprintId}`, query: { trainingLinkId: link.id, actionId: link.actionId } } : `/jd-diagnostics/${link.sourceId}`" class="btn btn-primary">返回训练来源，核对本次报告证据</RouterLink>
    <Notice :message="error" kind="error" />
    <div v-if="items.length" class="v2-context-results"><RouterLink to="/review" class="btn btn-secondary">已加入复习本，去练习</RouterLink><RouterLink v-for="item in items" :key="item.id" :to="`/review/${item.id}`" class="text-link">第 {{ item.questionIndex + 1 }} 题：查看待练内容</RouterLink></div>
  </section>
</template>
