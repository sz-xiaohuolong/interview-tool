<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { training } from '../api/training'
import type { Page } from '../models/training'
const props = withDefaults(defineProps<{ modelValue: string[]; kinds?: string[] }>(), { kinds: () => ['RESUME', 'PROJECT'] })
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()
interface Material { id: string; kind: string; name: string }
const items = ref<Material[]>([]), cursor = ref<string | null>(null), error = ref(''), busy = ref(false)
async function load(more = false) { busy.value = true; try { const p = await training<Page<Material>>(`/available-materials?limit=20&cursor=${encodeURIComponent(more ? cursor.value || '' : '')}`); items.value = more ? [...items.value, ...p.items] : p.items; cursor.value = p.nextCursor } catch (e) { error.value = e instanceof Error ? e.message : '资料读取失败' } finally { busy.value = false } }
function toggle(id: string, checked: boolean) { emit('update:modelValue', checked ? [...props.modelValue, id] : props.modelValue.filter(x => x !== id)) }
onMounted(() => load())
</script>
<template><fieldset class="training-fieldset"><legend>选择本机资料（最多 5 份）</legend><p class="tiny muted">只发送随后预览的文本快照。没有资料也可继续，缺失事实保持未知。</p><p v-if="error" role="alert">{{ error }}</p><div class="training-checks"><label v-for="m in items.filter(m => kinds.includes(m.kind))" :key="m.id"><input type="checkbox" :value="m.id" :checked="modelValue.includes(m.id)" :disabled="!modelValue.includes(m.id) && modelValue.length >= 5" @change="toggle(m.id, ($event.target as HTMLInputElement).checked)" />{{ m.name }} · {{ { RESUME: '简历', PROJECT: '项目', JD: 'JD' }[m.kind] }}</label></div><p v-if="!items.length && !busy" class="muted">没有可选资料，可在资料库中添加。</p><button v-if="cursor" type="button" class="text-link" :disabled="busy" @click="load(true)">继续读取资料</button></fieldset></template>
