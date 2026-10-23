<script setup lang="ts">
defineProps<{ label?: string; message?: string }>()
</script>
<template><div class="loading-state" aria-busy="true" role="status"><span class="spinner" aria-hidden="true"></span>{{ message || label || '正在加载…' }}</div></template>
