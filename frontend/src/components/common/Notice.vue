<script setup lang="ts">
import { CircleAlert, Info } from 'lucide-vue-next'
defineProps<{ message?: string; kind?: 'error' | 'warning' | 'info' }>()
</script>
<template>
  <div v-if="message" class="notice text-[13px]" :class="kind" :role="kind === 'error' ? 'alert' : 'status'">
    <CircleAlert v-if="kind === 'error' || kind === 'warning'" :size="18" />
    <Info v-else :size="18" />
    <div>{{ message }}<slot /></div>
  </div>
</template>
