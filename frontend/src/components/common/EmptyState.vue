<script setup lang="ts">
import { Inbox } from 'lucide-vue-next'
defineProps<{ title: string; description?: string }>()
</script>
<template><div class="empty-state"><Inbox :size="32" /><h3>{{ title }}</h3><p v-if="description">{{ description }}</p><slot /></div></template>
