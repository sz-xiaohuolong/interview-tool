<script setup lang="ts">
import { PhWarningCircle, PhInfo } from '@phosphor-icons/vue'
defineProps<{ message?: string; kind?: 'error' | 'warning' | 'info' }>()
</script>
<template>
  <div v-if="message" class="notice" :class="kind" :role="kind === 'error' ? 'alert' : 'status'">
    <PhWarningCircle v-if="kind === 'error' || kind === 'warning'" :size="18" />
    <PhInfo v-else :size="18" />
    <div>{{ message }}<slot /></div>
  </div>
</template>
