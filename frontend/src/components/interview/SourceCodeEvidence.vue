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
function tokens(line: string) {
  return line.split(/(\b(?:public|private|protected|class|interface|return|throw|new|void|if|else|try|catch|const|let|function|async|await|import|from|export|def|None|true|false|null)\b|"[^"\n]*"|'[^'\n]*')/g).map((text, index) => ({ text, highlighted: index % 2 === 1 }))
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
          <pre class="source-code-block"><code><span v-for="(line, index) in item.snippet.split('\n')" :key="index" class="code-line" :data-line="item.startLine + index"><span v-for="(token, part) in tokens(line)" :key="part" :class="{ 'code-keyword': token.highlighted }">{{ token.text }}</span>{{ index < item.snippet.split('\n').length - 1 ? '\n' : '' }}</span></code></pre>
        </article>
      </details>
      <p v-if="!rows.length" class="form-help">本场没有已展示问题的源码引用。</p>
    </template>
  </section>
</template>
<style scoped>
.source-code { margin-block:12px; min-width:0; }.source-code .heading-row { margin-bottom:8px; }.source-identity,.source-fragment a { font-family:var(--font-mono); overflow-wrap:anywhere; }.source-question { border-top:1px solid var(--border-subtle); margin-top:12px; padding-top:8px; }.source-question summary { min-height:32px; line-height:1.6; }.source-fragment { margin-top:12px; min-width:0; }.source-fragment a { font-size:12px; background:var(--surface-soft); padding:4px 8px; border-radius:6px; }.source-fragment pre { background:#f8fafc; border:1px solid var(--border-subtle); border-radius:6px; padding:12px 8px; font:12px/1.8 var(--font-mono); margin-top:8px; overflow:auto; white-space:pre-wrap; }.code-line { display:block; padding-left:42px; position:relative; min-height:1.8em; white-space:pre-wrap; overflow-wrap:anywhere; }.code-line::before { content:attr(data-line); position:absolute; left:0; width:30px; text-align:right; color:var(--text-muted); border-right:1px solid var(--border-subtle); padding-right:8px; user-select:none; }.code-keyword { color:var(--accent-ink); font-weight:600; }
</style>
