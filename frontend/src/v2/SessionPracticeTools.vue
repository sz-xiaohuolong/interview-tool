<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { request } from '../api'
import { writeV2 } from './api'
const props = defineProps<{ sessionId: string }>(), router = useRouter()
const questions = ref<{ id: string; content: string; followUp: boolean }[]>([]), selected = ref(''), busy = ref(false), error = ref('')
onMounted(async () => { try { const source = await request<{ messages: { id: string; role: string; content: string; followUp: boolean }[] }>(`/sessions/${props.sessionId}`); questions.value = source.messages.filter((m, i) => m.role === 'ASSISTANT' && source.messages[i + 1]?.role === 'USER'); selected.value = questions.value[0]?.id || '' } catch (e) { error.value = e instanceof Error ? e.message : '读取原问题失败' } })
async function create() { busy.value = true; try { const c = await writeV2<{ id: string }>('/practice-cases', { sourceKind: 'SESSION', sourceId: props.sessionId, messageId: selected.value }); await router.push(`/practice/${c.id}`) } catch (e) { error.value = e instanceof Error ? e.message : '建立案例失败' } finally { busy.value = false } }
</script>
<template>
  <section class="v2-context-tools report-practice-tools">
    <div><h2>原题重练</h2><p class="tiny muted">主问题和追问可分别选择；重练单独保存，原答不变。本报告已展示解析，重练时会保留这一记录。</p></div>
    <label :for="`practice-question-${sessionId}`">选择已有回答的问题</label>
    <select :id="`practice-question-${sessionId}`" v-model="selected"><option v-for="q in questions" :key="q.id" :value="q.id">{{ q.followUp ? '追问' : '主问题' }}：{{ q.content }}</option></select>
    <button class="btn btn-secondary" :disabled="busy || !selected" @click="create">建立重练案例</button>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>
