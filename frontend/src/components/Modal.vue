<script setup lang="ts">
import { DialogRoot, DialogPortal, DialogOverlay, DialogContent, DialogTitle, DialogDescription, DialogClose } from 'reka-ui'
import { PhX } from '@phosphor-icons/vue'
defineProps<{ open: boolean; title: string; description?: string }>()
defineEmits<{ 'update:open': [value: boolean] }>()
</script>
<template>
  <DialogRoot :open="open" @update:open="$emit('update:open', $event)">
    <DialogPortal><DialogOverlay class="dialog-overlay" /><DialogContent class="dialog-content">
      <div class="heading-row" style="margin-bottom: 0"><DialogTitle class="dialog-title">{{ title }}</DialogTitle><DialogClose class="icon-button" aria-label="关闭弹窗"><PhX :size="19" /></DialogClose></div>
      <DialogDescription class="dialog-description">{{ description || '填写以下信息，保存到本机。' }}</DialogDescription>
      <slot />
    </DialogContent></DialogPortal>
  </DialogRoot>
</template>
