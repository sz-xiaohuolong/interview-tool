<script setup lang="ts">
import { PhTray } from '@phosphor-icons/vue'
defineProps<{ title: string; description?: string }>()
</script>
<template><div class="empty-state"><PhTray :size="32" weight="light" /><h3>{{ title }}</h3><p v-if="description">{{ description }}</p><slot /></div></template>
