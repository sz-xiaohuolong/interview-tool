<script setup lang="ts">
import { computed, ref } from 'vue'
import { RefreshCw, ChevronDown, LockKeyhole } from 'lucide-vue-next'
import { DAILY_QUOTES, getTodayQuote } from '../../utils/dailyQuotes'

const offset = ref(0)
const currentQuote = computed(() => getTodayQuote(offset.value))
function nextQuote() { offset.value = (offset.value + 1) % DAILY_QUOTES.length }
</script>

<template>
  <section class="daily-quote" aria-labelledby="daily-quote-title">
    <div class="daily-quote-header">
      <h2 id="daily-quote-title">每日一句</h2>
      <button type="button" class="daily-quote-refresh" aria-label="换一句面试提醒" @click="nextQuote">
        <RefreshCw :size="14" aria-hidden="true" />换一句
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
      <summary title="展开查看资料与模型服务说明"><LockKeyhole :size="14" aria-hidden="true" />本地私人空间<ChevronDown :size="12" class="privacy-caret" aria-hidden="true" /></summary>
      <p>资料与记录保存在本机。真实练习使用你配置的模型服务。</p>
    </details>
  </section>
</template>

<style scoped>
.daily-quote { display:flex; align-items:center; gap:16px; padding:4px 20px; border-top:1px solid var(--border-subtle); background:var(--surface-soft); color:var(--text-muted); min-height:32px; font-size:12px; }
.daily-quote-header { display:contents; }
.daily-quote-header h2, .daily-quote-category { display:none; }
.daily-quote-content { min-width:0; flex:1; order:-1; }
.daily-quote-text { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px; line-height:1.6; }
.daily-quote-refresh { display:flex; align-items:center; gap:4px; border:0; background:transparent; padding:2px 4px; white-space:nowrap; }
.daily-quote-refresh:hover { color:var(--accent); }
.daily-quote-privacy { position:relative; }
.daily-quote-privacy summary { display:flex; align-items:center; gap:4px; white-space:nowrap; list-style:none; }
.daily-quote-privacy p { position:absolute; bottom:28px; right:0; width:240px; padding:12px; border:1px solid var(--border-subtle); border-radius:6px; background:white; z-index:10; }
@media(max-width:600px) { .daily-quote { gap:8px; padding:4px 12px; } .daily-quote-refresh, .daily-quote-privacy summary { min-height:44px; } .daily-quote-privacy summary { font-size:0; gap:0; } .daily-quote-privacy svg { width:16px; } }
</style>
