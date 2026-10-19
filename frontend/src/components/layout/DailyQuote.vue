<script setup lang="ts">
import { computed, ref } from 'vue'
import { PhArrowClockwise, PhCaretDown, PhLockSimple } from '@phosphor-icons/vue'
import { DAILY_QUOTES, getTodayQuote } from '../../utils/dailyQuotes'

const offset = ref(0)
const currentQuote = computed(() => getTodayQuote(offset.value))
function nextQuote() { offset.value = (offset.value + 1) % DAILY_QUOTES.length }
</script>

<template>
  <section class="sidebar-bottom daily-quote" aria-labelledby="daily-quote-title">
    <div class="daily-quote-header">
      <h2 id="daily-quote-title">每日一句</h2>
      <button type="button" class="daily-quote-refresh" aria-label="换一句面试提醒" @click="nextQuote">
        <PhArrowClockwise :size="14" aria-hidden="true" />换一句
      </button>
    </div>
    <div class="daily-quote-content" aria-live="polite" aria-atomic="true">
      <Transition name="quote-fade" mode="out-in">
        <div :key="currentQuote.text">
          <p class="daily-quote-text">{{ currentQuote.text }}</p>
          <p class="daily-quote-category">{{ currentQuote.category }}</p>
        </div>
      </Transition>
    </div>
    <details class="daily-quote-privacy">
      <summary title="展开查看资料与模型服务说明"><PhLockSimple :size="14" aria-hidden="true" />本地私人空间<PhCaretDown :size="12" class="privacy-caret" aria-hidden="true" /></summary>
      <p>资料与记录保存在本机。真实练习使用你配置的模型服务。</p>
    </details>
  </section>
</template>

<style scoped>
.daily-quote { padding-top: 12px; }
.daily-quote-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.daily-quote-header h2 { font-size: 13px; font-weight: 600; color: var(--text-secondary); letter-spacing: 0; }
.daily-quote-refresh { display: inline-flex; align-items: center; justify-content: center; gap: 4px; min-height: 44px; padding: 4px 6px; margin-right: -6px; border: 0; border-radius: var(--radius-control); background: transparent; color: var(--text-muted); font-size: 12px; white-space: nowrap; transition: color var(--duration-ui) var(--ease-ui), background var(--duration-ui) var(--ease-ui); }
.daily-quote-refresh:hover { color: var(--accent-ink); background: var(--accent-soft); }
.daily-quote-refresh :deep(svg) { color: inherit; }
.daily-quote-content { min-height: 9.5em; font-size: 15px; }
.daily-quote-text { color: var(--text-secondary); font-size: 15px; line-height: 1.85; }
.daily-quote-category { margin-top: 12px; color: var(--text-muted); font-size: 12px; line-height: 1.7; }
.daily-quote-privacy { margin-top: 12px; border-top: 1px solid var(--border-subtle); font-size: 12px; }
.daily-quote-privacy summary { display: flex; align-items: center; gap: 6px; min-height: 44px; list-style: none; }
.daily-quote-privacy summary::-webkit-details-marker { display: none; }
.privacy-caret { margin-left: auto; transition: transform var(--duration-ui) var(--ease-ui); }
.daily-quote-privacy[open] .privacy-caret { transform: rotate(180deg); }
.daily-quote-privacy p { padding-bottom: 8px; line-height: 1.8; }
.quote-fade-enter-active, .quote-fade-leave-active { transition: opacity var(--duration-ui) var(--ease-ui); }
.quote-fade-enter-from, .quote-fade-leave-to { opacity: 0; }
@media (prefers-reduced-motion: reduce) {
  .quote-fade-enter-active, .quote-fade-leave-active, .daily-quote-refresh, .privacy-caret { transition: none; }
}
</style>
