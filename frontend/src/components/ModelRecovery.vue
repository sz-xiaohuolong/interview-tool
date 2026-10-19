<script setup lang="ts">
import { ref } from 'vue'
import { training, writeTraining, ApiError } from '../api/training'
const props = defineProps<{ error: ApiError | null }>(), emit = defineEmits<{ recovered: [] }>()
const operation = ref<{ id: string; state: string; generation: number } | null>(null), busy = ref(false), message = ref('')
async function inspect() { if (!props.error?.operationId) return; busy.value = true; try { operation.value = await training(`/operations/${props.error.operationId}`); if (operation.value?.state === 'SUCCEEDED') { message.value = '已找到保存的结果，正在载入。'; emit('recovered') } } catch (e) { message.value = e instanceof Error ? e.message : '读取操作失败' } finally { busy.value = false } }
async function retry() { if (!operation.value) return; busy.value = true; try { await writeTraining(`/operations/${operation.value.id}/retry`, { expectedGeneration: operation.value.generation }); message.value = '评估已恢复，请查看保存的结果。'; emit('recovered') } catch (e) { message.value = e instanceof Error ? e.message : '重试失败'; await inspect() } finally { busy.value = false } }
</script>
<template><section v-if="error?.operationId" class="panel training-flow" aria-label="模型操作恢复"><h3>提交已保存，查看评估状态</h3><p>重试可能再次调用模型服务并产生费用。不会修改已封存的原文。</p><button class="btn btn-secondary" :disabled="busy" @click="inspect">查看本次操作</button><template v-if="operation"><p>{{ { RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败', INTERRUPTED: '中断' }[operation.state] || operation.state }} · 第 {{ operation.generation + 1 }} 次执行</p><button v-if="['FAILED', 'INTERRUPTED'].includes(operation.state)" class="btn btn-primary" :disabled="busy" @click="retry">确认重试未完成的评估</button></template><p v-if="message" role="status">{{ message }}</p></section></template>
