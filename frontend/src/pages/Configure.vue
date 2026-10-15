<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PhArrowRight, PhArrowClockwise, PhCheckCircle, PhFileText, PhTimer, PhStack } from '@phosphor-icons/vue'
import { request, post, errorMessage } from '../api'
import type { Material, Prediction, Session, InterviewMode } from '../types'
import Notice from '../components/Notice.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'
const route = useRoute(), router = useRouter()
const mode = computed(() => route.params.mode as string)
const isResume = computed(() => mode.value === 'resume')
const materials = ref<Material[]>([]), jds = ref<Material[]>([]), selected = ref(String(route.query.materialId || ''))
const loading = ref(true), busy = ref(false), error = ref(''), prediction = ref<Prediction | null>(null)
const sourceRole = String(route.query.role || '大模型应用 / Agent 开发工程师')
const isPresetRole = ['大模型应用 / Agent 开发工程师', 'Java 后端工程师'].includes(sourceRole)
const selectedRole = ref(isPresetRole ? sourceRole : '自定义岗位'), customRole = ref(isPresetRole ? '' : sourceRole)
const sourceLevel = String(route.query.level || 'JUNIOR')
const level = ref(['INTERN', 'JUNIOR', 'SENIOR'].includes(sourceLevel) ? sourceLevel : 'JUNIOR'), duration = ref(10), style = ref('NORMAL'), jd = ref(''), jdId = ref('')
const role = computed(() => selectedRole.value === '自定义岗位' ? customRole.value.trim() : selectedRole.value)
const titles: Record<string, string> = { quick: '准备好，开始你的下一场面试。', resume: '让简历里的每一行，都经得起追问。', project: '把做过的项目，真正讲透。' }
const notes: Record<string, string> = { quick: '选择岗位和节奏，面试官会根据你的回答继续深入。', resume: '从真实经历中提炼值得准备的问题，逐题练习你的表达。', project: '围绕项目背景、实现细节和方案权衡，进行多轮深度对话。' }
const selectedMaterial = computed(() => materials.value.find(m => m.id === selected.value))
const truncated = computed(() => selectedMaterial.value?.documents.some(d => d.truncated))
const ready = computed(() => !!role.value && (mode.value === 'quick' || !!selected.value))
const focus = String(route.query.focus || '')
async function loadPredictions() {
  prediction.value = null
  if (!isResume.value || !selected.value) return
  const id = selected.value
  try { const list = await request<Prediction[]>(`/predictions?resumeId=${encodeURIComponent(id)}`); if (selected.value === id) prediction.value = list[0] || null } catch (e) { error.value = errorMessage(e) }
}
watch(selected, loadPredictions)
watch(jdId, id => { if (id) jd.value = jds.value.find(m => m.id === id)?.content || '' })
onMounted(async () => {
  try {
    const all = await request<Material[]>('/materials'); jds.value = all.filter(m => m.kind === 'JD')
    materials.value = all.filter(m => m.kind === (isResume.value ? 'RESUME' : 'PROJECT'))
    if (!materials.value.some(m => m.id === selected.value)) selected.value = materials.value[0]?.id || ''
    await loadPredictions()
  } catch (e) { error.value = errorMessage(e) } finally { loading.value = false }
})
async function start(predictionId?: string, questionId?: string) {
  if (busy.value || !ready.value) return
  busy.value = true; error.value = ''
  try {
    const session = await post<Session>('/sessions', { mode: mode.value.toUpperCase() as InterviewMode, role: role.value, level: level.value, durationMinutes: duration.value, style: style.value, jd: jd.value, materialId: selected.value || undefined, predictionId, questionId, focus })
    await router.push(`/sessions/${session.id}`)
  } catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
async function predict() {
  if (busy.value || !ready.value) return
  busy.value = true; error.value = ''
  try { prediction.value = await post<Prediction>('/predictions', { resumeId: selected.value, role: role.value, jd: jd.value }) }
  catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
</script>
<template>
  <div class="page configure-page">
    <div class="page-heading"><h1>{{ titles[mode] }}</h1><p>{{ notes[mode] }}</p></div>
    <Notice :message="error" kind="error" /><Notice v-if="focus" :message="`本次专项练习：${focus}`" />
    <LoadingState v-if="loading" />
    <div v-else class="two-columns configure-layout">
      <form class="panel" @submit.prevent="isResume ? predict() : start()">
        <div class="heading-row"><h2>{{ isResume ? '押题配置' : '面试配置' }}</h2><span class="badge">{{ { quick: '快速面试', resume: '简历押题', project: '项目深挖' }[mode] }}</span></div>
        <div v-if="mode !== 'quick'" class="form-field">
          <label for="material-select">{{ isResume ? '选择简历' : '选择项目' }}</label>
          <select v-if="materials.length" id="material-select" v-model="selected" :disabled="busy"><option v-for="m in materials" :key="m.id" :value="m.id">{{ m.name }}</option></select>
          <EmptyState v-else :title="isResume ? '还没有简历资料' : '还没有项目资料'" description="先添加真实经历，让问题更有针对性。"><RouterLink :to="`/materials?kind=${isResume ? 'RESUME' : 'PROJECT'}`" class="btn btn-secondary">{{ isResume ? '添加简历' : '添加项目' }}</RouterLink></EmptyState>
          <div v-if="selectedMaterial" class="form-help">{{ selectedMaterial.documents.length }} 份附件 · 使用当前资料创建本场快照</div>
          <Notice v-if="truncated" kind="warning" message="所选资料中存在截断文档，模型仅能使用保留的文本。可在资料库查看具体内容。" />
        </div>
        <div class="form-field"><label for="role-select">目标岗位</label><select id="role-select" v-model="selectedRole" :disabled="busy"><option>大模型应用 / Agent 开发工程师</option><option>Java 后端工程师</option><option>自定义岗位</option></select></div>
        <div v-if="selectedRole === '自定义岗位'" class="form-field"><label for="custom-role">岗位名称</label><input id="custom-role" v-model="customRole" placeholder="例如：数据平台开发工程师" maxlength="120" required :disabled="busy" /></div>
        <div v-if="!isResume" class="form-grid">
          <div class="form-field"><label for="level">岗位级别</label><select id="level" v-model="level" :disabled="busy"><option value="INTERN">实习生</option><option value="JUNIOR">初级</option><option value="SENIOR">中高级</option></select></div>
          <div class="form-field"><label for="style">面试官风格</label><select id="style" v-model="style" :disabled="busy"><option value="NORMAL">常规面试</option><option value="PRESSURE">压力面试</option></select></div>
        </div>
        <div v-if="!isResume" class="form-field form-section"><label>目标时长</label><div class="segmented" role="group" aria-label="目标时长"><button v-for="n in [5, 10, 20]" :key="n" type="button" class="segment" :class="{ active: duration === n }" :aria-pressed="duration === n" :disabled="busy" @click="duration = n">{{ n }} 分钟</button></div><p class="form-help">预计 {{ duration === 5 ? 3 : duration === 10 ? 5 : 8 }} 道主问题，不限时，随时可以结束。</p></div>
        <div class="form-field form-section"><label for="jd">补充技术栈或岗位描述 <span class="muted" style="font-weight: 400">（选填）</span></label><select v-if="jds.length" v-model="jdId" aria-label="使用已保存的 JD" style="margin-bottom: 10px" :disabled="busy"><option value="">使用已保存的 JD…</option><option v-for="item in jds" :value="item.id" :key="item.id">{{ item.name }}</option></select><textarea id="jd" v-model="jd" maxlength="10000" placeholder="粘贴目标岗位的要求，或补充你希望重点练习的技术方向。" :disabled="busy"></textarea><p class="form-help">仅用于本次问题规划；真实模式下会发送给你配置的模型服务。</p></div>
        <button class="btn btn-primary" type="submit" :disabled="busy || !ready" style="width: 100%"><span v-if="busy" class="spinner"></span><template v-else><PhArrowClockwise v-if="isResume && prediction" :size="17" /><PhArrowRight v-else :size="17" /></template>{{ busy ? (isResume ? '正在分析简历与生成押题…' : '面试官正在准备问题…') : isResume ? (prediction ? '重新生成 10 道押题' : '生成 10 道针对性押题') : '开始面试' }}</button>
      </form>
      <aside class="configure-aside"><div class="panel"><component :is="isResume ? PhFileText : mode === 'project' ? PhStack : PhTimer" :size="29" weight="light" style="color: var(--accent-ink); margin-bottom: 21px" /><h3>{{ isResume ? '准备有据可依的回答' : '像真实面试一样练习' }}</h3><p class="tiny muted" style="margin-top: 12px">{{ isResume ? '押题优先级是模型评估，不代表企业真实题库或命中概率。对没有充分依据的指标与职责，会提示需要补充。' : '面试官每次提出一个问题，根据你的回答继续追问。正式面试中不会展示标准答案，结束后统一复盘。' }}</p><ul class="reading-list" style="margin-top: 23px"><li>{{ isResume ? '10 道高优先级准备题' : '每道主问题最多追问两次' }}</li><li>{{ isResume ? '附简历依据、回答思路与风险点' : '自动保存对话，支持刷新后继续' }}</li><li>{{ isResume ? '选中一道问题，进入专项面试' : '四维评价与逐题改进建议' }}</li></ul></div><div v-if="mode === 'project'" class="panel" style="margin-top: 18px"><h3>从三个层次理解项目</h3><p class="tiny muted" style="margin-top: 13px">L1 · 背景、架构与个人职责<br />L2 · 实现、数据流与异常处理<br />L3 · 假设场景、挑战与技术权衡</p></div></aside>
    </div>
    <section v-if="isResume && prediction" class="section">
      <div class="heading-row"><div><h2>你的高优先级准备清单</h2><p class="tiny muted" style="margin-top: 8px">基于已保存的简历与生成时的岗位配置 · {{ prediction.role }}</p></div><span class="badge" :class="prediction.mock ? 'badge-orange' : ''">{{ prediction.mock ? 'Mock 测试结果' : '模型评估' }}</span></div>
      <Notice v-if="prediction.contextTruncated" kind="warning" message="本次押题的资料上下文超过上限，模型仅使用保留部分。" />
      <div v-if="prediction.risks.length" class="notice warning"><PhCheckCircle :size="19" /><div><strong>简历中值得提前准备的风险点</strong><ul class="reading-list" style="margin-top: 6px"><li v-for="risk in prediction.risks" :key="risk">{{ risk }}</li></ul></div></div>
      <div class="prediction-list"><article v-for="(q, i) in prediction.questions" :key="q.id" class="panel"><div class="prediction-head"><div class="prediction-question"><span class="question-number">{{ String(i + 1).padStart(2, '0') }}</span><div><h3>{{ q.question }}</h3><div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px"><span class="badge badge-orange">{{ q.priority }}优先级</span><span class="badge badge-gray">{{ q.knowledgePoint }}</span></div></div></div><button class="btn btn-secondary btn-sm" :disabled="busy" @click="start(prediction.id, q.id)">练习这道题 <PhArrowRight :size="14" /></button></div><div class="prediction-details"><p class="evidence">简历依据：{{ q.evidence }}</p><details style="margin-top: 16px"><summary class="text-link" style="cursor: pointer">查看准备思路与可能追问</summary><p class="tiny" style="margin-top: 12px; white-space: pre-wrap">{{ q.answerOutline }}</p><ul class="reading-list" style="margin-top: 10px"><li v-for="follow in q.followUps" :key="follow">{{ follow }}</li></ul></details></div></article></div>
    </section>
  </div>
</template>
