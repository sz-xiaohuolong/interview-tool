<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import { training, writeTraining } from '../../api/training'
import { ratingLabels, localDate } from '../../models/training'
import type { Skill, Page } from '../../models/training'
interface SkillEvent { id: string; eventType: string; reviewItemId?: string; createdAt: string; mock?: boolean; details: { value?: string; reason?: string; note?: string } }
const skills = ref<Skill[]>([]), error = ref(''), loading = ref(true), busy = ref(''), events = ref<Record<string, SkillEvent[]>>({})
const cursors = ref<Record<string, string | null>>({})
const actionLabels: Record<string, string> = { CONFIRM: '确认练习目标', CLASSIFY: '调整技能归类', DISPUTE: '对结论提出异议', CONFIRM_EVIDENCE: '确认补充观察', RESOLVE_EVIDENCE: '结束待证事项', START: '开始复习', RETURN: '返回待复习', RESCHEDULE: '调整复习时间', RESOLVE: '用户结束本轮', ARCHIVE: '归档', RESTORE: '恢复' }
async function history(id: string, more = false) { try { const p = await training<Page<SkillEvent>>(`/skills/${id}/events?limit=20&cursor=${encodeURIComponent(more ? cursors.value[id] || '' : '')}`); events.value[id] = more ? [...(events.value[id] || []), ...p.items] : p.items; cursors.value[id] = p.nextCursor } catch (e) { error.value = String(e) } }
async function rate(skill: Skill) {
  busy.value = skill.id
  try { await writeTraining(`/skills/${skill.id}/self-ratings`, { value: skill.selfRating || 'UNRATED' }); await history(skill.id) }
  catch (e) { error.value = e instanceof Error ? e.message : '自评保存失败。' } finally { busy.value = '' }
}
onMounted(async () => { try { skills.value = (await training<{ items: Skill[] }>('/skills')).items } catch (e) { error.value = String(e) } finally { loading.value = false } })
</script>
<template><div class="page training-page"><div class="page-heading"><h1>用证据，看清自己的练习。</h1><p>自评表达你的判断；模型观察仅适用于对应问题、上下文和评估条件。</p></div><Notice :message="error" kind="error" /><LoadingState v-if="loading" label="正在读取能力看板…" /><div v-else class="panel training-list"><section v-for="skill in skills" :key="skill.id" class="training-skill-row"><div><h2>{{ skill.name }}</h2><p class="tiny muted">{{ skill.scope }}</p><details @toggle="($event.target as HTMLDetailsElement).open && history(skill.id)"><summary>查看来源与时间线（{{ skill.eventCount || 0 }}）</summary><p class="tiny muted">不包含：{{ skill.excludes }}。例：{{ skill.example }}</p><p v-if="events[skill.id]?.length === 0" class="muted">目前没有已保存观察。</p><article v-for="event in events[skill.id]" :key="event.id" class="training-quote"><p>{{ event.eventType === 'SELF_RATING' ? `用户自评：${ratingLabels[event.details.value || 'UNRATED']}` : `用户动作：${actionLabels[event.eventType] || '已保存观察'}` }} · {{ localDate(event.createdAt) }}{{ event.mock ? ' · Mock' : '' }}</p><RouterLink v-if="event.reviewItemId" :to="`/review/${event.reviewItemId}`" class="text-link">查看原题证据与当前争议状态</RouterLink></article><button v-if="cursors[skill.id]" class="text-link" @click="history(skill.id, true)">读取更多历史</button></details></div><div><label :for="`rating-${skill.id}`">我的自评</label><select :id="`rating-${skill.id}`" v-model="skill.selfRating"><option v-for="(label, value) in ratingLabels" :key="value" :value="value">{{ label }}</option></select><button class="btn btn-secondary" :disabled="!!busy" @click="rate(skill)">保存自评</button></div></section></div></div></template>
