<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ result: Record<string, any> }>()
const result = computed(() => props.result.comparison || props.result)
const labels: Record<string, string> = { fixed: '文本中已修正的问题', remaining: '仍需补充的内容', newIssues: '本次新增的问题' }
</script>
<template><div class="training-flow"><section v-for="(label, kind) in labels" :key="kind"><h3>{{ label }}</h3><p v-if="!result[kind]?.length" class="muted">本次没有可确认的条目。</p><article v-for="issue in result[kind]" :key="issue.checkId" class="training-quote"><p>{{ issue.description }}</p><div class="training-compare-grid"><section v-for="side in ['left', 'right']" :key="side"><strong>{{ side === 'left' ? '较早回答依据' : '本次回答依据' }}</strong><p v-for="(r, i) in issue[`${side}EvidenceRefs`]" :key="i" class="tiny muted">{{ r.kind === 'QUOTE' ? `回答原文：${r.quote}` : `检查点 ${r.checkId}：检查全文 ${r.start}–${r.end}，不是缺失原文引用` }}</p></section></div></article></section><section><h3>下一步建议</h3><p v-for="tip in result.suggestions" :key="tip">{{ tip }}</p></section></div></template>
