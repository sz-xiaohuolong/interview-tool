<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheck, Lightbulb } from 'lucide-vue-next'
const props = defineProps<{ result: Record<string, any>; before?: string; after?: string }>()
const result = computed(() => props.result.comparison || props.result)
const suggestions = computed<string[]>(() => [...new Set<string>([
  ...(result.value.newIssues || []).map((issue: { description: string }) => issue.description),
  ...(result.value.remaining || []).map((issue: { description: string }) => issue.description),
  ...(result.value.suggestions || []),
])].slice(0, 2))
</script>

<template>
  <div class="training-flow instant-feedback">
    <div v-if="before !== undefined && after !== undefined" class="training-compare-grid answer-comparison">
      <section><h3>上轮回答</h3><pre>{{ before }}</pre></section>
      <section><h3>本次回答</h3><pre>{{ after }}</pre></section>
    </div>
    <section class="feedback-fixed">
      <h3><CircleCheck :size="18" aria-hidden="true" />这次补齐了什么</h3>
      <p v-for="(issue, i) in result.fixed" :key="i">{{ issue.description }}</p>
      <p v-if="!result.fixed?.length" class="muted">本次对比尚未确认新增补齐的关键点。</p>
    </section>
    <section v-if="suggestions.length">
      <h3><Lightbulb :size="18" aria-hidden="true" />仍可精进</h3>
      <p v-for="tip in suggestions" :key="tip">{{ tip }}</p>
    </section>
  </div>
</template>

<style scoped>
.instant-feedback h3 { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-3); }
.instant-feedback h3 svg { flex-shrink: 0; }
.feedback-fixed h3 { color: var(--success); }
.answer-comparison { gap: var(--space-4); }
.answer-comparison section { min-width: 0; padding: var(--space-4); background: var(--surface-soft); border-radius: var(--radius-control); }
.answer-comparison section:last-child { background: var(--accent-soft); color: var(--accent-ink); }
.answer-comparison pre { max-height: 220px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.8; }
</style>
