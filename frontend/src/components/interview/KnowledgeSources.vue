<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { PhCaretRight } from '@phosphor-icons/vue'
import { agentTopicLabels, displayDate, publicSourceUrl } from '../../api/interviews'
import type { KnowledgeSource, KnowledgeRetrieval } from '../../models/interview'

const props = defineProps<{ sources: KnowledgeSource[]; retrieval?: KnowledgeRetrieval[]; agentTopics?: string[]; plannedQuestionCount?: number }>()
const disclosure = ref<HTMLDetailsElement | null>(null)
const retrievalDetails = computed(() => props.retrieval || props.sources.flatMap(source => source.retrieval ? [source.retrieval] : []))
const selectedAgentTopics = computed(() => [...new Set(props.agentTopics || [])]
  .filter(id => Object.hasOwn(agentTopicLabels, id)).map(id => ({ id, label: agentTopicLabels[id] })))

function revealSource() {
  let targetId: string
  try { targetId = decodeURIComponent(window.location.hash.slice(1)).replace(/^v3-source-/, 'interview-source-') } catch { return false }
  const target = Array.from(disclosure.value?.querySelectorAll<HTMLDetailsElement>('[data-source]') || []).find(source => source.id === targetId)
  if (!disclosure.value || !target) return false
  disclosure.value.open = true
  target.open = true
  target.querySelector('summary')?.focus({ preventScroll: true })
  target.scrollIntoView?.({ block: 'start' })
  return true
}

function revealRepeatedSource(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>('a[href]') : null
  if (!link?.hash || link.target || link.hasAttribute('download')) return
  if (link.origin === location.origin && link.pathname === location.pathname && link.search === location.search && link.hash === location.hash && revealSource()) event.preventDefault()
}

onMounted(() => { window.addEventListener('hashchange', revealSource); document.addEventListener('click', revealRepeatedSource); revealSource() })
onUnmounted(() => { window.removeEventListener('hashchange', revealSource); document.removeEventListener('click', revealRepeatedSource) })
watch(() => JSON.stringify(props.sources.map(source => source.id)), revealSource, { flush: 'post' })
</script>

<template>
  <section class="interview-sources" aria-labelledby="interview-sources-title">
    <details ref="disclosure" class="interview-sources-disclosure">
      <summary class="interview-sources-summary">
        <h2 id="interview-sources-title">
          <PhCaretRight class="interview-sources-caret" aria-hidden="true" />
          <span class="interview-sources-heading">本场知识依据 <span class="interview-sources-count">{{ sources.length }} 份资料</span></span>
          <span class="interview-sources-toggle" aria-hidden="true"><span class="when-closed">展开查看</span><span class="when-open">收起</span></span>
        </h2>
      </summary>
      <div class="interview-sources-list">
        <div v-if="selectedAgentTopics.length" class="interview-agent-scope" data-agent-practice-scope>
          <h3>本场 Agent 练习范围</h3>
          <ul aria-label="已选 Agent 主题"><li v-for="topic in selectedAgentTopics" :key="topic.id" :data-agent-topic="topic.id" class="badge badge-gray">{{ topic.label }}</li></ul>
          <p class="form-help">已选 {{ selectedAgentTopics.length }} 类主题<template v-if="plannedQuestionCount">，计划 {{ plannedQuestionCount }} 道主问题</template>。按题量从所选范围抽查，不保证每类都考到；具体考点覆盖在复盘中查看。</p>
        </div>
        <p class="form-help interview-sources-help">下方列出本场获取的参考资料，份数不代表已选主题数或实际考点覆盖数。题库用于发现考点，技术结论结合官方资料核对。来源不足或冲突会明确标注。</p>
        <p v-if="!sources.length" class="form-help interview-sources-help">本场尚无可展示的参考资料，技术结论仍需核实。</p>
        <details v-if="retrievalDetails.length" data-retrieval-details class="interview-source">
          <summary>参考文档获取详情</summary>
          <ul><li v-for="item in retrievalDetails" :key="item.sourceId" class="interview-source-meta">
            <strong>{{ item.title }}</strong>：{{ item.message }}（{{ item.status }}<template v-if="item.httpStatus"> · HTTP {{ item.httpStatus }}</template>）
            <span v-if="item.cacheUsed"> · 已使用本地缓存{{ item.stale ? '（已过期，技术依据需复核）' : '（有效）' }}</span>
            <span v-else-if="item.status !== 'FETCHED'"> · 本次未获取到参考文档</span>
          </li></ul>
        </details>
        <details v-for="source in sources" :id="`interview-source-${source.id}`" :key="source.id" :data-source="source.id" class="interview-source">
          <summary><PhCaretRight class="interview-source-caret" aria-hidden="true" /><span>{{ source.title }}<span class="interview-source-kind">{{ source.kind === 'OFFICIAL' ? '官方文档' : '公开资料' }}</span></span></summary>
          <div class="interview-source-content">
            <p class="interview-source-meta">版本：{{ source.version || '未标明' }} · 获取：{{ displayDate(source.fetchedAt) }}</p>
            <h3>来源摘录</h3>
            <p class="interview-source-excerpt">{{ source.excerpt }}</p>
            <a v-if="publicSourceUrl(source.url)" :href="publicSourceUrl(source.url)" target="_blank" rel="noopener noreferrer" class="text-link">打开原始来源 ↗</a>
            <p v-else class="interview-source-meta">此来源没有可打开的公开链接。</p>
            <p v-if="source.sha256" class="interview-hash"><span>片段摘要</span><code>{{ source.sha256 }}</code></p>
          </div>
        </details>
      </div>
    </details>
  </section>
</template>

<style scoped>
.interview-sources { min-width: 0; margin-top: 32px; border-top: 1px solid var(--border-subtle); padding-top: 24px; }
.interview-sources-disclosure { border: 1px solid var(--border-subtle); border-radius: var(--radius-panel); background: var(--surface); }
summary { cursor: pointer; list-style: none; }
summary::-webkit-details-marker { display: none; }
summary:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; border-radius: var(--radius-control); }
.interview-sources-summary { padding: 18px 20px; min-height: 64px; border-radius: var(--radius-panel); transition: background-color var(--duration-ui) var(--ease-ui); }
.interview-sources-summary:hover { background: var(--surface-soft); }
.interview-sources-summary h2 { display: flex; align-items: center; gap: 12px; margin: 0; font-size: 18px; font-weight: 600; line-height: 1.6; letter-spacing: -.02em; }
.interview-sources-heading { flex: 1; display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; min-width: 0; }
.interview-sources-count { color: var(--text-secondary); font-size: 14px; font-weight: 400; letter-spacing: normal; font-variant-numeric: tabular-nums; white-space: nowrap; }
.interview-sources-toggle { color: var(--accent); font-size: 14px; font-weight: 500; letter-spacing: normal; white-space: nowrap; }
.interview-sources-caret, .interview-source-caret { width: 16px; height: 16px; flex-shrink: 0; transition: transform var(--duration-ui) var(--ease-ui); }
.when-open, .interview-sources-disclosure[open] > summary .when-closed { display: none; }
.interview-sources-disclosure[open] > summary .when-open { display: inline; }
.interview-sources-disclosure[open] > summary .interview-sources-caret, .interview-source[open] > summary .interview-source-caret { transform: rotate(90deg); }
.interview-sources-disclosure[open] > summary { border-bottom: 1px solid var(--border-subtle); border-radius: var(--radius-panel) var(--radius-panel) 0 0; }
.interview-sources-list { padding: 0 20px; }
.interview-agent-scope { padding-top: 20px; }
.interview-agent-scope h3 { font-size: 16px; font-weight: 600; }
.interview-agent-scope ul { display: flex; flex-wrap: wrap; gap: 8px; list-style: none; padding: 0; margin: 12px 0; }
.interview-agent-scope .form-help { line-height: 1.8; }
.interview-sources-help { max-width: 72ch; margin: 20px 0 8px; font-size: 14px; line-height: 1.8; color: var(--text-secondary); }
.interview-source { padding: 20px 0; scroll-margin-top: 24px; }
.interview-source + .interview-source { border-top: 1px solid var(--border-subtle); }
.interview-source:target { outline: 2px solid var(--accent); outline-offset: 5px; border-radius: var(--radius-control); }
.interview-source > summary { display: flex; align-items: flex-start; gap: 12px; min-height: 44px; font-size: 16px; font-weight: 600; line-height: 1.8; overflow-wrap: anywhere; }
.interview-source > summary > span { min-width: 0; }
.interview-source-caret { margin-top: 6px; }
.interview-source-kind { display: block; color: var(--text-secondary); font-size: 14px; font-weight: 400; line-height: 1.8; margin-top: 4px; }
.interview-source-content { margin-left: 28px; max-width: 72ch; min-width: 0; }
.interview-source-meta { font-size: 14px; line-height: 1.8; color: var(--text-secondary); margin-top: 12px; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.interview-source-content h3 { font-size: 16px; line-height: 1.8; font-weight: 600; margin: 24px 0 8px; }
.interview-source-excerpt { font-size: 16px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
.interview-source .text-link { display: inline-flex; align-items: center; min-height: 44px; font-size: 16px; line-height: 1.8; margin-top: 16px; }
.interview-hash { color: var(--text-secondary); margin: 16px 0 0; }
.interview-hash > span { display: block; font-size: 14px; line-height: 1.8; margin-bottom: 6px; }
.interview-hash code { display: block; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; white-space: normal; }
@media (max-width: 700px) {
  .interview-sources-summary { padding: 16px; }
  .interview-sources-summary h2 { gap: 8px; }
  .interview-sources-heading { gap: 2px 8px; }
  .interview-sources-list { padding-inline: 16px; }
  .interview-source-content { margin-left: 0; }
}
@media (prefers-reduced-motion: reduce) { .interview-sources-summary, .interview-sources-caret, .interview-source-caret { transition: none; } }
</style>
