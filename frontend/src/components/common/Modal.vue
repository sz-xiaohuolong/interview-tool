<script setup lang="ts">
import { DialogRoot, DialogPortal, DialogOverlay, DialogContent, DialogTitle, DialogDescription, DialogClose } from 'reka-ui'
import { X } from 'lucide-vue-next'
defineProps<{ open: boolean; title: string; description?: string; variant?: 'drawer'; busy?: boolean }>()
defineEmits<{ 'update:open': [value: boolean]; closeAutoFocus: [event: Event] }>()
</script>
<template>
  <DialogRoot :open="open" @update:open="$emit('update:open', $event)">
    <DialogPortal><DialogOverlay class="dialog-overlay backdrop-blur-xs bg-slate-950/40" /><DialogContent class="dialog-content rounded-lg border border-slate-200 shadow-lg" :class="{ 'dialog-drawer': variant === 'drawer' }" @close-auto-focus="$emit('closeAutoFocus', $event)" @escape-key-down="event => { if (busy) event.preventDefault() }" @interact-outside="event => { if (busy) event.preventDefault() }">
      <div class="dialog-header"><div class="heading-row" style="margin-bottom: 0"><DialogTitle class="dialog-title">{{ title }}</DialogTitle><DialogClose class="icon-button" :disabled="busy" aria-label="关闭弹窗"><X :size="19" /></DialogClose></div>
        <DialogDescription class="dialog-description">{{ description || '填写以下信息，保存到本机。' }}</DialogDescription>
      </div>
      <div v-if="variant === 'drawer'" class="dialog-drawer-body"><slot /></div><slot v-else />
      <div v-if="$slots.footer" class="dialog-drawer-footer"><slot name="footer" /></div>
    </DialogContent></DialogPortal>
  </DialogRoot>
</template>
<style scoped>
.dialog-header .heading-row { align-items:flex-start; }
.dialog-drawer { inset:0 0 0 auto; transform:none; width:min(760px, calc(100vw - 48px)); height:100dvh; max-height:100dvh; display:flex; flex-direction:column; padding:0; border-radius:0; overflow:hidden; }
.dialog-drawer .dialog-header { flex-shrink:0; padding:var(--space-6); border-bottom:1px solid var(--border-subtle); }
.dialog-drawer .dialog-description { margin:var(--space-2) 0 0; }
.dialog-drawer-body { flex:1; min-height:0; overflow-y:auto; overscroll-behavior:contain; padding:var(--space-6); }
.dialog-drawer-footer { flex-shrink:0; padding:var(--space-4) var(--space-6) max(var(--space-4), env(safe-area-inset-bottom)); border-top:1px solid var(--border-subtle); }
.dialog-drawer[data-state="open"] { animation:drawer-in var(--duration-ui) var(--ease-ui); }
@keyframes drawer-in { from { transform:translateX(24px); opacity:0; } to { transform:translateX(0); opacity:1; } }
@media(max-width:768px) { .dialog-drawer { width:100vw; }.dialog-drawer .dialog-header,.dialog-drawer-body { padding:var(--space-4); }.dialog-drawer-footer { padding-inline:var(--space-4); }.dialog-drawer .icon-button { min-width:44px; min-height:44px; } }
@media(prefers-reduced-motion:reduce) { .dialog-drawer[data-state="open"] { animation:none; } }
</style>
