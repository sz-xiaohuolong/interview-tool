<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import { training, writeTraining } from '../../api/training'
import type { Page, ReviewItem } from '../../models/training'

// 旧复习地址直接接入同一作答页，保留既有案例、尝试和恢复参数。
const props = withDefaults(defineProps<{ embedded?: boolean; reviewId?: string }>(), { embedded: false })
const route = useRoute(), router = useRouter()
const error = ref(''), loading = ref(false)
let generation = 0
interface SourceCase { id: string; sourceKind: string; sourceSessionKind?: string; sourceId: string; questionIndex: number; messageId: string; mock: boolean }
async function open() {
  const current = ++generation
  loading.value = true; error.value = ''
  try {
    const id = String(props.reviewId || route.params.id || route.query.id || '')
    if (!id) { await router.replace('/review'); return }
    const entry = await training<ReviewItem>(`/review-items/${encodeURIComponent(id)}`)
    const question = entry.dialogue.find((m, i) => m.role === 'ASSISTANT' && !m.followUp && m.questionIndex === entry.questionIndex && entry.dialogue[i + 1]?.role === 'USER')
    if (!question) throw new Error('找不到已有回答的原问题，请返回题目列表核对来源。')
    const cases: SourceCase[] = []; let cursor = ''
    do {
      const page = await training<Page<SourceCase>>(`/practice-cases?sourceId=${encodeURIComponent(entry.sourceSessionId)}&limit=100&cursor=${encodeURIComponent(cursor)}`)
      cases.push(...page.items); cursor = page.nextCursor || ''
    } while (cursor)
    if (current !== generation) return
    const matches = cases.filter(c => c.sourceKind === 'SESSION' && (c.sourceSessionKind || 'SESSION') === 'SESSION' && c.sourceId === entry.sourceSessionId && c.questionIndex === entry.questionIndex && c.mock === entry.mock)
    let saved: SourceCase | undefined = matches.find(c => c.messageId === question.id) || matches[0]
    if (route.query.attemptId) {
      const attempt = await training<{ caseId: string }>(`/practice-attempts/${encodeURIComponent(String(route.query.attemptId))}`)
      saved = matches.find(c => c.id === attempt.caseId)
      if (!saved) throw new Error('这次作答不属于当前题目，请从题目列表重新打开。')
    }
    if (!saved) saved = await writeTraining<SourceCase>('/practice-cases', { sourceKind: 'SESSION', sourceId: entry.sourceSessionId, messageId: question.id })
    if (current === generation) await router.replace({ path: `/practice/${encodeURIComponent(saved.id)}`, query: route.query })
  } catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '准备题目失败，请重试。' }
  finally { if (current === generation) loading.value = false }
}
watch(() => props.reviewId || route.params.id || route.query.id, () => { if (route.path.startsWith('/review')) void open() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="training-page">
    <LoadingState v-if="loading" label="正在准备原题与作答…" />
    <Notice :message="error" kind="error" />
    <button v-if="error" class="btn btn-secondary" data-action="reload" @click="open">重新读取题目</button>
  </div>
</template>
