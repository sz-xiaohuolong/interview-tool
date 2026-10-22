<script setup lang="ts">
import { ref } from 'vue'
import { Upload } from 'lucide-vue-next'
defineProps<{ id: string; label: string; accept: string; multiple?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ files: [files: File[]] }>()
const input = ref<HTMLInputElement>()
const dragging = ref(false)
function choose(event: Event) {
  const target = event.target as HTMLInputElement
  emit('files', Array.from(target.files || []))
  target.value = ''
}
</script>
<template>
  <div class="file-dropzone" :class="{ dragging, disabled }" data-dropzone @dragover.prevent="dragging = !disabled" @dragleave.prevent="dragging = false" @drop.prevent="event => { dragging = false; if (!disabled) emit('files', Array.from(event.dataTransfer?.files || [])) }">
    <label class="sr-only" :for="id">{{ label }}</label>
    <input :id="id" ref="input" hidden type="file" :accept="accept" :multiple="multiple" :disabled="disabled" @change="choose" />
    <button type="button" class="dropzone-control" :disabled="disabled" :aria-describedby="`${id}-hint`" @click="input?.click()"><Upload :size="20" /><span>{{ label }}</span><small>点击选择，或将文件拖到这里</small></button>
    <p :id="`${id}-hint`" class="dropzone-hint"><slot />选择后仍需确认上传。</p>
  </div>
</template>
<style scoped>
.file-dropzone { border:1px dashed var(--border-control); border-radius:var(--radius-panel); background:var(--surface-soft); padding:var(--space-4); transition:border-color var(--duration-ui), background var(--duration-ui); }
.file-dropzone.dragging,.file-dropzone:focus-within { border-color:var(--accent); background:var(--accent-soft); }
.dropzone-control { display:flex; flex-direction:column; align-items:center; gap:var(--space-2); width:100%; border:0; border-radius:var(--radius-control); background:transparent; color:var(--text-secondary); padding:var(--space-3); min-height:44px; font-size:14px; }
.dropzone-control:hover:not(:disabled) { color:var(--accent-ink); }
.dropzone-control span { font-weight:600; }.dropzone-control small,.dropzone-hint { font-size:12px; line-height:1.75; color:var(--text-muted); }.dropzone-hint { text-align:center; margin-top:var(--space-2); }
.disabled { opacity:.7; }
@media(prefers-reduced-motion:reduce) { .file-dropzone { transition:none; } }
</style>
