<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { writeV2, V2Error } from './api'
import { RouterLink } from 'vue-router'
import Notice from '../components/Notice.vue'
import type { Session } from '../types'
const route = useRoute(), router = useRouter()
const role = ref(String(route.query.role || '大模型应用 / Agent 开发工程师')), level = ref(String(route.query.level || 'JUNIOR')), duration = ref(10), style = ref('NORMAL'), focus = ref(String(route.query.focus || '')), jd = ref(''), error = ref(''), busy = ref(false)
const savedSessionId = ref('')
async function start() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await writeV2<Session>('/api/sessions', { mode: 'QUICK', role: role.value, level: level.value, durationMinutes: duration.value, style: style.value, focus: focus.value, jd: jd.value, trainingLinkId: route.query.trainingLinkId })
    await router.push(`/sessions/${result.id}`)
  } catch (e) { error.value = e instanceof Error ? e.message : '开始失败，请保留配置后重试。'; if (e instanceof V2Error && e.resourceId) savedSessionId.value = e.resourceId } finally { busy.value = false }
}
</script>
<template><div class="page v2-page"><div class="page-heading"><h1>围绕这个目标，再练一次。</h1><p>沿用原岗位面试与动态追问；完成后回到来源确认相应证据。</p></div><Notice :message="error" kind="error" /><RouterLink v-if="savedSessionId" :to="`/sessions/${savedSessionId}`" class="text-link">打开已保存场次并恢复</RouterLink><form class="panel v2-form" @submit.prevent="start"><div><label for="linked-role">目标岗位</label><input id="linked-role" v-model="role" maxlength="120" required /></div><div><label for="linked-level">岗位级别</label><select id="linked-level" v-model="level"><option value="INTERN">实习生</option><option value="JUNIOR">初级</option><option value="SENIOR">中高级</option></select></div><div><label for="linked-duration">参考时长</label><select id="linked-duration" v-model="duration"><option :value="5">5 分钟</option><option :value="10">10 分钟</option><option :value="20">20 分钟</option></select></div><div><label for="linked-style">面试风格</label><select id="linked-style" v-model="style"><option value="NORMAL">常规面试</option><option value="PRESSURE">压力面试</option></select></div><div><label for="linked-focus">本次训练目标</label><textarea id="linked-focus" v-model="focus" maxlength="2000" /></div><div><label for="linked-jd">补充 JD（可选）</label><textarea id="linked-jd" v-model="jd" maxlength="10000" /></div><button class="btn btn-primary" :disabled="busy">{{ busy ? '正在规划面试…' : '开始专项练习' }}</button></form></div></template>
