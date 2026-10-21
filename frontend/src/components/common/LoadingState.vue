<script setup lang="ts">
defineProps<{ label?: string }>()
</script>
<template><div class="loading-state" aria-busy="true" role="status"><span class="spinner" aria-hidden="true"></span>{{ label || '正在加载…' }}</div></template>
