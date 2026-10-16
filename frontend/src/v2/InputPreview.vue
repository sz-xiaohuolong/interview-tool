<script setup lang="ts">
export interface Snapshot { id: string; text: string; purpose: string; truncated?: boolean; originalStart?: number; originalEnd?: number }
export interface Manifest { manifestId: string; manifestHash: string; snapshots: Snapshot[]; modelInput?: { trainingEvents?: { id: string; findings: { id: string; text: string; status: string }[] }[] } }
defineProps<{ manifest: Manifest }>()
</script>
<template><section class="v2-preview"><h2>本次将发送的文本</h2><p class="tiny muted">只有下列快照和选中的训练观察会进入本次任务。来源改变后需重新预览；点击下一步才会调用模型服务。</p><details v-for="s in manifest.snapshots" :key="s.id"><summary>{{ { JD: '岗位描述', MATERIAL: '资料原文', USER_ASSERTION: '本人补充' }[s.purpose] || '来源快照' }} · {{ s.text.length }} 字符{{ s.truncated ? ' · 不完整片段' : ' · 完整快照' }}</summary><pre>{{ s.text }}</pre><p v-if="s.truncated" class="tiny muted">片段不代表完整材料，不能据此断言资料没有提及某项。</p></details><details v-for="event in manifest.modelInput?.trainingEvents" :key="event.id"><summary>已确认训练观察</summary><p v-for="f in event.findings" :key="f.id">{{ f.text }} · {{ { SUPPORTED: '当前有局部支持', GAP: '练习有缺口', UNVERIFIED: '尚未验证' }[f.status] }}</p></details></section></template>
