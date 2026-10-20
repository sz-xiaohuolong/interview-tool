<script setup lang="ts">
import { computed } from 'vue'
import type { SourceCodeSnapshot, SourceQuestion, CodeEvidence } from '../../source-code'
const props = defineProps<{ source?: SourceCodeSnapshot | null; review?: boolean; questions?: SourceQuestion[] }>()
const labels: Record<string, string> = { PENDING: '等待读取', READING: '正在读取', READY: '读取成功', PARTIAL: '部分可用', FAILED: '读取失败', SKIPPED: '仅使用项目资料' }
const rows = computed(() => (props.questions || []).map(question => ({ ...question, evidence: (props.source?.evidence || []).filter(item => item.verified && question.codeEvidenceIds.includes(item.id)) })).filter(question => question.evidence.length))
function link(item: CodeEvidence) {
  const source = props.source
  if (!source?.commitSha || !/^[a-f0-9]{40}$/.test(source.commitSha) || !/^[a-zA-Z0-9-]+\/[a-zA-Z0-9_.-]+$/.test(source.repository)) return undefined
  if (item.path.split('/').some(part => !part || part === '.' || part === '..') || /[\\\u0000-\u001f]/.test(item.path)) return undefined
  return `https://github.com/${source.repository}/blob/${source.commitSha}/${item.path.split('/').map(encodeURIComponent).join('/')}#L${item.startLine}-L${item.endLine}`
}
</script>
<template>
  <section v-if="source" class="source-code panel" aria-label="本场源码依据">
    <div class="heading-row"><h3>{{ review ? '源码依据' : '本场仓库' }}</h3><span class="badge">{{ labels[source.status] || '状态待确认' }}</span></div>
    <p class="tiny source-identity">{{ source.repository }}<span v-if="source.commitSha"> · 代码版本 {{ source.commitSha.slice(0, 12) }}</span></p>
    <p v-if="source.error" class="form-help">{{ source.error }}</p>
    <p v-for="warning in source.warnings || []" :key="warning" class="form-help">{{ warning }}</p>
    <p v-if="!review && ['READY', 'PARTIAL'].includes(source.status)" class="form-help">本场已锁定代码版本，源码依据在复盘中查看。</p>
    <template v-if="review">
      <details v-for="(row, index) in rows" :key="index" class="source-question"><summary>{{ row.question }}</summary>
        <article v-for="item in row.evidence" :key="item.id" class="source-fragment">
          <a v-if="link(item)" :href="link(item)" target="_blank" rel="noopener noreferrer" class="text-link">{{ item.path }} · 第 {{ item.startLine }}～{{ item.endLine }} 行</a>
          <pre>{{ item.snippet }}</pre>
        </article>
      </details>
      <p v-if="!rows.length" class="form-help">本场没有已展示问题的源码引用。</p>
    </template>
  </section>
</template>
<style scoped>
.source-code { margin-block: 16px; min-width: 0; }
.source-identity, .source-question summary, .source-fragment a { overflow-wrap: anywhere; }
.source-question { border-top: 1px solid var(--border-subtle); margin-top: 16px; padding-top: 14px; }
.source-question summary { cursor: pointer; line-height: 1.65; }
.source-fragment { margin-top: 14px; min-width: 0; }
.source-fragment pre { white-space: pre-wrap; overflow-wrap: anywhere; background: var(--bg-subtle, #f6f5f1); padding: 14px; font-size: 12px; line-height: 1.7; margin-top: 10px; }
</style>
