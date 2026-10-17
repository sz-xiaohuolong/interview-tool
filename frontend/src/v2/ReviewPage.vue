<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PhArrowRight, PhArrowClockwise } from '@phosphor-icons/vue'
import Notice from '../components/Notice.vue'
import LoadingState from '../components/LoadingState.vue'
import EvidenceReturn from './EvidenceReturn.vue'
import { v2, writeV2 } from './api'
import { reviewLabels, findingLabels, localDate } from './types'
import type { ReviewItem, Page, Skill } from './types'

const route = useRoute(), router = useRouter()
const id = computed(() => String(route.params.id || route.query.id || ''))
const item = ref<ReviewItem | null>(null), items = ref<ReviewItem[]>([]), skills = ref<Skill[]>([])
const selected = ref<string[]>([]), reason = ref(''), error = ref(''), message = ref(''), busy = ref(false), loading = ref(true), nextCursor = ref<string | null>(null)
const findingIds = ref<string[]>([]), history = ref<any[]>([]), historyCursor = ref<string | null>(null), historical = ref<any>(null)
function skillNames(ids: string[] = []) { return ids.length ? ids.map(id => skills.value.find(s => s.id === id)?.name || id).join('、') : '未归类' }
async function loadHistory(more = false) { try { const page = await v2<Page<any>>(`/review-items/${id.value}/history?limit=20&cursor=${encodeURIComponent(more ? historyCursor.value || '' : '')}`); history.value = more ? [...history.value, ...page.items] : page.items; historyCursor.value = page.nextCursor } catch (e) { error.value = String(e) } }
async function caseFrom(m: { id: string }) { if (!item.value) return; busy.value = true; try { const c = await writeV2<{ id: string }>('/practice-cases', { sourceKind: 'SESSION', sourceId: item.value.sourceSessionId, messageId: m.id }); await router.push(`/practice/${c.id}`) } catch (e) { error.value = String(e) } finally { busy.value = false } }
const status = ref(String(route.query.status || ''))
async function load(cursor = '') {
  loading.value = true; error.value = ''
  try {
    if (id.value) {
      const [detail, profile] = await Promise.all([v2<ReviewItem>(`/review-items/${encodeURIComponent(id.value)}`), v2<{ catalog: { skills: Skill[] } }>('/skills')])
      item.value = detail; findingIds.value = detail.findings.filter(f => !f.disputed).map(f => f.id); selected.value = [...detail.skillIds]; skills.value = profile.catalog.skills
    } else {
      const page = await v2<Page<ReviewItem>>(`/review-items?limit=20&status=${encodeURIComponent(status.value)}&cursor=${encodeURIComponent(cursor)}`)
      items.value = cursor ? [...items.value, ...page.items] : page.items; nextCursor.value = page.nextCursor
    }
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败，请重试。' } finally { loading.value = false }
}
async function action(name: string, extra: Record<string, unknown> = {}) {
  if (!item.value || busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    item.value = await writeV2<ReviewItem>(`/review-items/${item.value.id}/actions`, { expectedVersion: item.value.revision, action: name, ...extra })
    message.value = '已保存至本机服务。结束本轮仅表示队列安排结束。'
  } catch (e) { error.value = e instanceof Error ? e.message : '保存失败，本页选择已保留。' } finally { busy.value = false }
}
async function practice() {
  if (!item.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    const link = await writeV2<{ id: string }>('/training-links', { sourceKind: 'REVIEW', sourceId: item.value.id, findingIds: findingIds.value.filter(id => item.value!.findings.some(f => f.id === id && f.confirmed && !f.disputed)) })
    await router.push({ path: '/configure/quick', query: { trainingLinkId: link.id, focus: item.value.findings.filter(f => f.confirmed && !f.disputed).map(f => f.text).join('；'), role: item.value.role, level: item.value.level } })
  } catch (e) { error.value = e instanceof Error ? e.message : '无法建立训练来源。' } finally { busy.value = false }
}
onMounted(() => load())
</script>

<template>
  <div class="page v2-page">
    <div class="page-heading heading-row"><div><h1>{{ id ? '回到问题，安排下一次练习。' : '把报告，变成下一次练习。' }}</h1><p>原始证据、模型观察与自己的判断，分别保留。</p></div><RouterLink v-if="id" to="/review" class="text-link">返回错题复习</RouterLink></div>
    <Notice :message="error" kind="error" /><Notice :message="message" />
    <button v-if="error" class="btn btn-secondary" data-action="reload" :disabled="loading" @click="load()"><PhArrowClockwise :size="16" />重新读取服务端记录</button>
    <LoadingState v-if="loading" label="正在读取本机复习记录…" />
    <template v-if="!id && !loading">
      <div class="v2-toolbar"><label for="review-status">查看范围</label><select id="review-status" v-model="status" @change="load()"><option value="">全部复习项</option><option v-for="(label, value) in reviewLabels" :key="value" :value="value">{{ label }}</option></select></div>
      <div v-if="!items.length" class="panel v2-empty"><h2>还没有复习项</h2><p>完成一场面试后，在报告页整理错误、遗漏与未知项。</p><RouterLink to="/configure/quick" class="btn btn-primary">开始面试 <PhArrowRight :size="16" /></RouterLink></div>
      <div v-else class="panel v2-list"><article v-for="entry in items" :key="entry.id" class="v2-list-row"><div><span class="badge badge-gray">{{ reviewLabels[entry.status] }}</span><span v-if="entry.mock" class="badge badge-orange">Mock 测试记录</span><h2><RouterLink :to="`/review/${entry.id}`">{{ entry.question }}</RouterLink></h2><p class="muted">{{ entry.status === 'WAITING_EVIDENCE' ? '未知不计为错题，需要补充证据。' : entry.nextReviewAt ? `下次复习：${localDate(entry.nextReviewAt)}` : '请先核对原始问答与报告发现。' }}</p></div><RouterLink :to="`/review/${entry.id}`" class="btn btn-secondary">查看 <PhArrowRight :size="16" /></RouterLink></article></div>
      <button v-if="nextCursor" class="btn btn-secondary" @click="load(nextCursor || '')">加载更多复习项</button>
    </template>
    <div v-if="item" class="v2-reading-layout">
      <article class="panel v2-flow"><div><span class="badge badge-gray">{{ reviewLabels[item.status] }}</span><span v-if="item.mock" class="badge badge-orange">Mock · 仅验证流程</span><h2>{{ item.question }}</h2><p class="tiny muted">主问题 {{ item.questionIndex + 1 }} · 仅有整题依据</p><RouterLink :to="`/reports/${item.sourceSessionId}`" class="text-link">查看来源报告</RouterLink></div>
        <details open><summary>查看原始问答</summary><div v-for="m in item.dialogue" :key="m.id" class="v2-quote"><strong>{{ m.role === 'USER' ? '我的原始回答' : m.followUp ? '追问' : '原问题' }}</strong><pre>{{ m.content }}</pre><button v-if="m.role === 'ASSISTANT' && item.dialogue.some((x, i) => x.id === m.id && item!.dialogue[i + 1]?.role === 'USER')" class="text-link" :disabled="busy" @click="caseFrom(m)">只针对这条问题新建同题案例</button></div></details>
        <section><h3>报告中的发现</h3><p v-if="!item.findings.length" class="notice">这版报告没有待练发现。请核对来源版本；可归档保留历史，这不表示能力已提升。</p><p class="tiny muted">模型结论不是事实证明。异议会保留原文并排除当前自动支持。</p><div v-for="finding in item.findings" :key="finding.id" class="v2-finding"><label v-if="!finding.disputed" class="v2-inline-check"><input v-model="findingIds" type="checkbox" :value="finding.id" :disabled="busy || item.status === 'ARCHIVED'" />选择这条待练发现</label><span class="badge" :class="finding.kind === 'uncertain' ? 'badge-gray' : 'badge-orange'">{{ findingLabels[finding.kind] }}{{ finding.disputed ? ' · 有异议' : finding.confirmed ? ' · 已确认练习' : ' · 待确认' }}</span><p>{{ finding.text }}</p><p v-if="finding.disputed" class="tiny muted">异议：{{ finding.disputeReason }}</p><button v-else class="text-link" :disabled="busy || !reason.trim() || item.status === 'ARCHIVED'" @click="action('DISPUTE', { findingIds: [finding.id], reason })">对这条结论提出异议</button></div><label for="dispute-reason">异议说明</label><textarea id="dispute-reason" v-model="reason" maxlength="1000" placeholder="说明原回答中哪些内容支持你的判断。" /></section>
        <section><h3>稳定技能标签</h3><p class="tiny muted">无法归类时可留空；标签不改变原报告判断。</p><div class="v2-checks"><label v-for="skill in skills" :key="skill.id"><input v-model="selected" type="checkbox" :value="skill.id" :disabled="busy || item.status === 'ARCHIVED'" />{{ skill.name }}</label></div><button v-if="item.status !== 'ARCHIVED'" class="btn btn-secondary" :disabled="busy" @click="action('CLASSIFY', { skillIds: selected })">保存标签</button></section>
        <details v-if="item.sourceVersions?.length"><summary>查看来源报告版本（{{ item.sourceVersions.length }} 个旧版）</summary><section v-for="version in item.sourceVersions" :key="version.reportHash" class="v2-quote"><h3>{{ version.question }}</h3><p class="tiny muted">保存时间：{{ localDate(version.updatedAt) }} · 当时状态：{{ reviewLabels[version.status] }}</p><p>当时标签：{{ skillNames(version.skillIds) }}</p><details><summary>版本标识与整题依据</summary><pre>{{ version.reportHash }}</pre><p>{{ version.sourceLimit }}</p><pre>{{ version.sourceReport?.answerOutline }}</pre><pre>{{ version.sourceReport?.knowledgeExplanation }}</pre></details><div v-for="m in version.dialogue" :key="m.id"><strong>{{ m.role === 'USER' ? '当时回答' : '当时问题' }}</strong><pre>{{ m.content }}</pre></div><p v-for="f in version.findings" :key="f.id">{{ findingLabels[f.kind] }}：{{ f.text }}{{ f.disputed ? '（有异议）' : '' }}</p><p v-if="!version.findings.length" class="tiny muted">这版报告没有待练发现。</p></section></details>
        <EvidenceReturn v-if="item.findings.some(f => f.confirmed && !f.disputed) && item.status !== 'ARCHIVED'" :item="item" @saved="load()" />
        <details @toggle="($event.target as HTMLDetailsElement).open && loadHistory()"><summary>查看确认、补证与安排历史</summary><div v-for="h in history" :key="h.id"><button class="text-link" @click="v2<any>(`/review-items/${id}/history/${h.id}`).then(r => historical = r).catch(e => error = String(e))">查看 {{ localDate(h.createdAt) }} 的原文与判词快照</button></div><button v-if="historyCursor" class="text-link" @click="loadHistory(true)">继续读取历史</button><div v-if="historical"><p>当时状态：{{ reviewLabels[historical.status] }}</p><p>当时标签：{{ skillNames(historical.skillIds) }}</p><p v-for="f in historical.findings" :key="f.id">{{ findingLabels[f.kind] }}：{{ f.text }}{{ f.disputed ? '（有异议）' : '' }}</p><p v-for="b in historical.lastEvidence?.bindings" :key="b.findingId">{{ b.reportText || b.checkId }}；用户覆盖说明：{{ b.reason }}</p></div></details>
      </article>
      <aside class="panel v2-flow v2-actions"><h2>本轮安排</h2><p class="muted">{{ reviewLabels[item.status] }}<br />{{ localDate(item.nextReviewAt) }}</p>
        <button v-if="item.status === 'PENDING_CONFIRMATION' && item.findings.length" class="btn btn-primary" data-action="confirm" :disabled="busy" @click="action('CONFIRM', { skillIds: selected, findingIds })">确认待练内容并入队</button>
        <button v-if="['DUE', 'WAITING_EVIDENCE', 'IN_PROGRESS'].includes(item.status)" class="btn btn-primary" :disabled="busy" @click="practice">专项练习 <PhArrowRight :size="16" /></button>
        <button v-if="item.status === 'DUE'" class="btn btn-secondary" :disabled="busy" @click="action('START')">开始本轮复习</button>
        <button v-if="item.status === 'IN_PROGRESS'" class="btn btn-secondary" :disabled="busy" @click="action('RETURN')">返回待复习，保留进度</button>
        <template v-if="item.status === 'IN_PROGRESS' && item.lastEvidence"><button class="btn btn-secondary" :disabled="busy" @click="action('RESCHEDULE', { ...item.lastEvidence, findingIds: item.lastEvidence.bindings?.map((b: any) => b.findingId), intervalDays: 1 })">明日继续</button><button class="btn btn-secondary" :disabled="busy" @click="action('RESCHEDULE', { ...item.lastEvidence, findingIds: item.lastEvidence.bindings?.map((b: any) => b.findingId), intervalDays: 3 })">3 天后再练</button><button class="btn btn-secondary" :disabled="busy" @click="action('RESOLVE', { ...item.lastEvidence, findingIds: item.lastEvidence.bindings?.map((b: any) => b.findingId) })">结束本轮</button></template>
        <p class="tiny muted">有效练习与反馈保存后，才可结束或安排下次。完成不表示能力提升。</p>
        <RouterLink to="/skills" class="text-link">查看能力看板</RouterLink>
        <button class="btn btn-ghost" :disabled="busy" @click="action(item.status === 'ARCHIVED' ? 'RESTORE' : 'ARCHIVE')">{{ item.status === 'ARCHIVED' ? '恢复归档项' : '归档（保留原文）' }}</button>
      </aside>
    </div>
  </div>
</template>
