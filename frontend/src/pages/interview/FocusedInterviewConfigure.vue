<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  PhArrowRight,
  PhArrowClockwise,
  PhCheckCircle,
  PhFileText,
  PhFilePdf,
  PhTimer,
  PhStack,
  PhSliders,
  PhLightning,
  PhCaretDown,
  PhCaretUp,
  PhShieldWarning,
  PhLightbulb,
  PhChatCircleDots,
  PhCheck,
  PhSparkle,
  PhTarget,
  PhCompass,
  PhQuestion,
  PhArrowsInSimple,
  PhArrowsOutSimple,
} from '@phosphor-icons/vue'
import { request, post, errorMessage } from '../../api'
import type { Material, Prediction, Session, InterviewMode } from '../../types'
import Notice from '../../components/common/Notice.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import LoadingState from '../../components/common/LoadingState.vue'

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

// 交互增强与折叠状态
const showConfigDrawer = ref(false)
const showAllRisks = ref(false)
const expandedQuestions = ref<Record<string, boolean>>({})

function toggleQuestion(qid: string) {
  expandedQuestions.value[qid] = !expandedQuestions.value[qid]
}

function expandAllQuestions() {
  if (!prediction.value?.questions) return
  const allOpen = prediction.value.questions.every(q => expandedQuestions.value[q.id])
  const target = !allOpen
  prediction.value.questions.forEach(q => {
    expandedQuestions.value[q.id] = target
  })
}

function parseRisk(risk: string) {
  const idx = risk.indexOf('：') !== -1 ? risk.indexOf('：') : risk.indexOf(':')
  if (idx !== -1) {
    return {
      tag: risk.slice(0, idx).trim(),
      desc: risk.slice(idx + 1).trim()
    }
  }
  return {
    tag: '重点备查',
    desc: risk
  }
}

async function loadPredictions() {
  prediction.value = null
  if (!isResume.value || !selected.value) return
  const id = selected.value
  try {
    const list = await request<Prediction[]>(`/predictions?resumeId=${encodeURIComponent(id)}`)
    if (selected.value === id) {
      prediction.value = list[0] || null
      // 默认展开第一道题作为体验引导
      if (prediction.value?.questions?.[0]) {
        expandedQuestions.value[prediction.value.questions[0].id] = true
      }
    }
  } catch (e) {
    error.value = errorMessage(e)
  }
}

watch(selected, loadPredictions)
watch(jdId, id => { if (id) jd.value = jds.value.find(m => m.id === id)?.content || '' })

onMounted(async () => {
  try {
    const all = await request<Material[]>('/materials')
    jds.value = all.filter(m => m.kind === 'JD')
    materials.value = all.filter(m => m.kind === (isResume.value ? 'RESUME' : 'PROJECT'))
    if (!materials.value.some(m => m.id === selected.value)) {
      selected.value = materials.value[0]?.id || ''
    }
    await loadPredictions()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

async function start(predictionId?: string, questionId?: string) {
  if (busy.value || !ready.value) return
  busy.value = true
  error.value = ''
  try {
    const session = await post<Session>('/sessions', {
      mode: mode.value.toUpperCase() as InterviewMode,
      role: role.value,
      level: level.value,
      durationMinutes: duration.value,
      style: style.value,
      jd: jd.value,
      materialId: selected.value || undefined,
      predictionId,
      questionId,
      focus,
    })
    await router.push(`/sessions/${session.id}`)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function predict() {
  if (busy.value || !ready.value) return
  busy.value = true
  error.value = ''
  try {
    prediction.value = await post<Prediction>('/predictions', {
      resumeId: selected.value,
      role: role.value,
      jd: jd.value
    })
    showConfigDrawer.value = false
    // 默认展开第1道题
    if (prediction.value?.questions?.[0]) {
      expandedQuestions.value[prediction.value.questions[0].id] = true
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page configure-page configure-delight">
    <!-- 页面标题与专项目标 -->
    <header class="page-heading">
      <div class="heading-content">
        <h1>{{ titles[mode] }}</h1>
        <p>{{ notes[mode] }}</p>
      </div>
      <div v-if="focus" class="focus-pill">
        <PhTarget :size="16" weight="bold" />
        <span>专项攻坚：{{ focus }}</span>
      </div>
    </header>

    <Notice :message="error" kind="error" />
    <LoadingState v-if="loading" label="正在加载面试配置与分析数据…" />

    <div v-else class="configure-body">
      <!-- 场景 A：已有押题成果态（isResume && prediction）时的顶置画像卡 -->
      <section v-if="isResume && prediction" class="profile-hero-card">
        <div class="hero-left">
          <div class="resume-avatar">
            <PhFilePdf :size="28" weight="duotone" />
          </div>
          <div class="resume-info">
            <div class="resume-headline">
              <h2 class="resume-name">{{ selectedMaterial?.name || '已选简历' }}</h2>
              <span class="role-badge">{{ role }}</span>
              <span class="status-badge" :class="prediction.mock ? 'badge-mock' : 'badge-ready'">
                <PhCheckCircle :size="14" weight="fill" />
                {{ prediction.mock ? 'Mock 模拟评估' : '10 道针对性押题已就绪' }}
              </span>
            </div>
            <p class="resume-subtext">
              已挂载 {{ selectedMaterial?.documents.length || 1 }} 份材料快照 · 深度匹配真实面试官高频追问点与防虚报考查
            </p>
          </div>
        </div>
        <div class="hero-actions">
          <button
            type="button"
            class="btn btn-secondary btn-sm toggle-config-btn"
            :class="{ active: showConfigDrawer }"
            @click="showConfigDrawer = !showConfigDrawer"
          >
            <PhSliders :size="15" />
            <span>{{ showConfigDrawer ? '收起配置' : '调整配置 / 重新计算' }}</span>
            <PhCaretUp v-if="showConfigDrawer" :size="14" />
            <PhCaretDown v-else :size="14" />
          </button>
          <button
            type="button"
            class="btn btn-primary btn-sm quick-first-btn"
            :disabled="busy || !prediction.questions.length"
            @click="start(prediction.id, prediction.questions[0]?.id)"
          >
            <PhLightning :size="15" weight="fill" />
            <span>从第 01 题开始专项练习</span>
          </button>
        </div>
      </section>

      <!-- 配置表单区域（无押题时始终显示；有押题时可折叠展开） -->
      <transition name="drawer">
        <div
          v-show="!prediction || showConfigDrawer || !isResume"
          class="two-columns configure-layout"
          :class="{ 'embedded-drawer': isResume && prediction }"
        >
          <form class="panel config-form-panel" @submit.prevent="isResume ? predict() : start()">
            <div class="heading-row">
              <div class="panel-title-group">
                <h2>{{ isResume ? '押题规划设置' : '面试准备设置' }}</h2>
                <span class="muted tiny">设置对齐的目标岗位与技术标准</span>
              </div>
              <span class="badge badge-accent">{{ { quick: '快速面试', resume: '简历押题', project: '项目深挖' }[mode] }}</span>
            </div>

            <!-- 选择简历或项目 -->
            <div v-if="mode !== 'quick'" class="form-field">
              <label for="material-select">{{ isResume ? '选择匹配的简历' : '选择深挖的项目' }}</label>
              <div v-if="materials.length" class="select-wrapper">
                <select id="material-select" v-model="selected" :disabled="busy">
                  <option v-for="m in materials" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
              </div>
              <EmptyState
                v-else
                :title="isResume ? '还没有简历资料' : '还没有项目资料'"
                description="先添加真实经历，让问题更有针对性。"
              >
                <RouterLink :to="`/materials?kind=${isResume ? 'RESUME' : 'PROJECT'}`" class="btn btn-secondary">
                  {{ isResume ? '前往添加简历' : '前往添加项目' }}
                </RouterLink>
              </EmptyState>
              <div v-if="selectedMaterial" class="form-help">
                已挂载 {{ selectedMaterial.documents.length }} 份附件快照 · 模型将根据真实经历设计问题
              </div>
              <Notice v-if="truncated" kind="warning" message="所选资料中存在截断文档，模型仅能使用保留的文本。" />
            </div>

            <!-- 目标岗位 -->
            <div class="form-field">
              <label for="role-select">目标岗位</label>
              <div class="select-wrapper">
                <select id="role-select" v-model="selectedRole" :disabled="busy">
                  <option>大模型应用 / Agent 开发工程师</option>
                  <option>Java 后端工程师</option>
                  <option>自定义岗位</option>
                </select>
              </div>
            </div>

            <div v-if="selectedRole === '自定义岗位'" class="form-field">
              <label for="custom-role">输入自定义岗位名称</label>
              <input
                id="custom-role"
                v-model="customRole"
                placeholder="例如：搜索算法工程师 / 基础设施研发工程师"
                maxlength="120"
                required
                :disabled="busy"
              />
            </div>

            <!-- 级别与风格（非简历模式） -->
            <div v-if="!isResume" class="form-grid">
              <div class="form-field">
                <label for="level">岗位级别</label>
                <div class="select-wrapper">
                  <select id="level" v-model="level" :disabled="busy">
                    <option value="INTERN">实习生</option>
                    <option value="JUNIOR">初级工程师</option>
                    <option value="SENIOR">中高级工程师</option>
                  </select>
                </div>
              </div>
              <div class="form-field">
                <label for="style">面试官风格</label>
                <div class="select-wrapper">
                  <select id="style" v-model="style" :disabled="busy">
                    <option value="NORMAL">常规循序引导</option>
                    <option value="PRESSURE">压力与极限追问</option>
                  </select>
                </div>
              </div>
            </div>

            <div v-if="!isResume" class="form-field form-section">
              <label>预计练习时长</label>
              <div class="segmented" role="group" aria-label="目标时长">
                <button
                  v-for="n in [5, 10, 20]"
                  :key="n"
                  type="button"
                  class="segment"
                  :class="{ active: duration === n }"
                  :aria-pressed="duration === n"
                  :disabled="busy"
                  @click="duration = n"
                >
                  {{ n }} 分钟
                </button>
              </div>
              <p class="form-help">预计 {{ duration === 5 ? 3 : duration === 10 ? 5 : 8 }} 道主问题，不限时，可随时结束与复盘。</p>
            </div>

            <!-- JD 补充与技术栈 -->
            <div class="form-field form-section">
              <label for="jd">
                补充目标岗位 JD / 技术栈
                <span class="muted font-normal">（选填，帮助模型精准对齐）</span>
              </label>
              <div v-if="jds.length" class="select-wrapper jd-select-wrapper">
                <select v-model="jdId" aria-label="使用已保存的 JD" :disabled="busy">
                  <option value="">快速载入已保存的 JD 描述…</option>
                  <option v-for="item in jds" :key="item.id" :value="item.id">{{ item.name }}</option>
                </select>
              </div>
              <textarea
                id="jd"
                v-model="jd"
                maxlength="10000"
                rows="4"
                placeholder="粘贴目标职位的核心要求、加分项，或补充你希望重点被追问的技术方向（如：Redis 缓存一致性、Agent 多轮编排等）。"
                :disabled="busy"
              ></textarea>
              <p class="form-help">仅用于定制本次问题规划，真实模式下会作为上下文安全传递给大模型。</p>
            </div>

            <!-- 提交按钮 -->
            <button
              class="btn btn-primary submit-btn"
              type="submit"
              :disabled="busy || !ready"
            >
              <span v-if="busy" class="spinner"></span>
              <template v-else>
                <PhSparkle v-if="isResume && !prediction" :size="18" weight="fill" />
                <PhArrowClockwise v-else-if="isResume && prediction" :size="18" />
                <PhArrowRight v-else :size="18" />
              </template>
              <span>
                {{
                  busy
                    ? isResume
                      ? '正在深度分析经历并定制 10 道押题…'
                      : '面试官正在构思本场问题…'
                    : isResume
                    ? prediction
                      ? '重新生成 10 道针对性押题'
                      : '✨ 开始智能分析并提炼 10 道押题'
                    : '开始面试'
                }}
              </span>
            </button>
          </form>

          <!-- 右侧边栏指导卡片 -->
          <aside class="configure-aside">
            <div class="panel aside-card guidance-card">
              <div class="aside-icon-bubble">
                <component
                  :is="isResume ? PhFileText : mode === 'project' ? PhStack : PhTimer"
                  :size="26"
                  weight="duotone"
                />
              </div>
              <h3>{{ isResume ? '有据可依的面试推演' : '实战沉浸式练习' }}</h3>
              <p class="aside-desc">
                {{
                  isResume
                    ? '模型基于真实简历中的职责描述、技术选型与业务结果提炼问题，严格遵循“有依据可追问、无依据提示核验”的评估机制。'
                    : '模拟真实大厂技术面试节奏，面试官将围绕你的第一轮回答逐步深入追问，不给死板参考答案，助你练就临场反应。'
                }}
              </p>

              <div class="feature-checklist">
                <div class="feature-item">
                  <PhCheckCircle :size="16" class="feature-icon" weight="fill" />
                  <span>{{ isResume ? '10 道高价值大厂风格追问' : '每道核心问题支持 2 轮深度追问' }}</span>
                </div>
                <div class="feature-item">
                  <PhCheckCircle :size="16" class="feature-icon" weight="fill" />
                  <span>{{ isResume ? '配套简历依据、答题思路与可能追问' : '自动留痕作答记录，中断可随时无缝继续' }}</span>
                </div>
                <div class="feature-item">
                  <PhCheckCircle :size="16" class="feature-icon" weight="fill" />
                  <span>{{ isResume ? '支持单题独立开启专项模拟实战' : '结束后生成四维维度复盘与逐题建议' }}</span>
                </div>
              </div>
            </div>

            <div v-if="mode === 'project'" class="panel aside-card project-levels-card">
              <h3>三层递进拆解项目</h3>
              <div class="level-step">
                <span class="level-num">L1</span>
                <div>
                  <strong>背景与架构</strong>
                  <p class="tiny muted">业务起源、核心指标与个人实际负责范围</p>
                </div>
              </div>
              <div class="level-step">
                <span class="level-num">L2</span>
                <div>
                  <strong>核心实现与细节</strong>
                  <p class="tiny muted">数据流转、核心算法、状态机与异常容灾</p>
                </div>
              </div>
              <div class="level-step">
                <span class="level-num">L3</span>
                <div>
                  <strong>技术选型与权衡</strong>
                  <p class="tiny muted">极端流量场景、方案局限性与架构演进反思</p>
                </div>
              </div>
            </div>

            <div v-if="isResume && !prediction" class="panel aside-card coaching-mindset-card">
              <div class="card-tag">
                <PhLightbulb :size="15" weight="fill" />
                <span>备战心法</span>
              </div>
              <h4>面试官究竟在问什么？</h4>
              <p class="tiny muted leading-relaxed">
                优秀的技术面试不是背诵八股文，而是通过真实工程决策、排障日志和方案权衡，证明项目由你亲自主导或深度参与。
              </p>
            </div>
          </aside>
        </div>
      </transition>

      <!-- 场景 B：已有押题结果时的工作台主体区域 -->
      <section v-if="isResume && prediction" class="prediction-workspace">
        <div class="workspace-grid">
          <!-- 左侧：10 道针对性押题流 (~67%) -->
          <div class="questions-stream">
            <div class="stream-header">
              <div class="stream-title-row">
                <h2>高优先级押题与追问演练</h2>
                <span class="badge badge-accent">共 10 道定制题目</span>
                <span v-if="prediction.mock" class="badge badge-orange">Mock 仿真结果</span>
              </div>
              <div class="stream-actions">
                <button type="button" class="btn btn-ghost btn-sm" @click="expandAllQuestions">
                  <PhArrowsInSimple v-if="prediction.questions.every(q => expandedQuestions[q.id])" :size="14" />
                  <PhArrowsOutSimple v-else :size="14" />
                  <span>{{ prediction.questions.every(q => expandedQuestions[q.id]) ? '全部收起' : '展开全部思路' }}</span>
                </button>
              </div>
            </div>

            <Notice
              v-if="prediction.contextTruncated"
              kind="warning"
              message="本次押题的简历上下文超过上限，模型已针对保留的核心部分进行提炼。"
            />

            <!-- 题目卡片列表 -->
            <div class="question-cards-list">
              <article
                v-for="(q, i) in prediction.questions"
                :key="q.id"
                class="question-card"
                :class="{ 'is-expanded': expandedQuestions[q.id] }"
              >
                <!-- 卡片顶层：序号 + 题干 + 练习按钮 -->
                <header class="qcard-header">
                  <div class="qcard-main">
                    <span class="qcard-index">{{ String(i + 1).padStart(2, '0') }}</span>
                    <div class="qcard-question-box">
                      <h3 class="qcard-title">{{ q.question }}</h3>
                      <div class="qcard-tags">
                        <span class="badge" :class="q.priority === '高' ? 'badge-priority-high' : 'badge-priority-normal'">
                          {{ q.priority }}优先级
                        </span>
                        <span class="badge badge-knowledge">{{ q.knowledgePoint }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="qcard-action">
                    <button
                      class="btn btn-primary btn-sm practice-btn"
                      :disabled="busy"
                      @click="start(prediction.id, q.id)"
                    >
                      <span>练习这道题</span>
                      <PhArrowRight :size="14" class="btn-arrow" />
                    </button>
                  </div>
                </header>

                <!-- 简历依据标签便签 -->
                <div class="qcard-evidence-bar">
                  <div class="evidence-pill">
                    <PhFileText :size="14" class="evidence-icon" />
                    <span class="evidence-label">简历依据：</span>
                    <span class="evidence-text">{{ q.evidence }}</span>
                  </div>
                </div>

                <!-- 答题思路与可能追问折叠面板 -->
                <div class="qcard-expandable">
                  <button
                    type="button"
                    class="expand-toggle-btn"
                    :aria-expanded="!!expandedQuestions[q.id]"
                    @click="toggleQuestion(q.id)"
                  >
                    <PhChatCircleDots :size="15" />
                    <span>{{ expandedQuestions[q.id] ? '收起准备思路与追问' : '查看面试官思路与高频追问链条' }}</span>
                    <PhCaretUp v-if="expandedQuestions[q.id]" :size="14" />
                    <PhCaretDown v-else :size="14" />
                  </button>

                  <transition name="collapse">
                    <div v-show="expandedQuestions[q.id]" class="expandable-drawer">
                      <!-- 答题思路 -->
                      <div class="outline-section">
                        <div class="section-micro-heading">
                          <PhLightbulb :size="15" weight="fill" class="micro-icon text-amber" />
                          <span>答题结构与核心思路</span>
                        </div>
                        <p class="outline-content">{{ q.answerOutline }}</p>
                      </div>

                      <!-- 连环追问链条 -->
                      <div v-if="q.followUps && q.followUps.length" class="followups-section">
                        <div class="section-micro-heading">
                          <PhCompass :size="15" weight="bold" class="micro-icon text-accent" />
                          <span>面试官可能的连环追问预演（{{ q.followUps.length }} 问）</span>
                        </div>
                        <div class="followup-bubbles">
                          <div
                            v-for="(follow, fi) in q.followUps"
                            :key="fi"
                            class="followup-item"
                          >
                            <span class="followup-step">追问 {{ fi + 1 }}</span>
                            <span class="followup-text">{{ follow }}</span>
                          </div>
                        </div>
                      </div>
                    </div>
                  </transition>
                </div>
              </article>
            </div>
          </div>

          <!-- 右侧：考官防御盾牌与备战建议 (~33%) -->
          <aside class="defense-sidebar">
            <!-- 考官视角的防虚报与薄弱点诊断卡片 -->
            <div v-if="prediction.risks && prediction.risks.length" class="panel defense-card">
              <header class="defense-card-header">
                <div class="defense-icon-wrap">
                  <PhShieldWarning :size="22" weight="fill" />
                </div>
                <div>
                  <h3 class="defense-title">简历真实度防线诊断</h3>
                  <p class="tiny muted">面试官可能重点质疑或要求提供代码证明的薄弱点</p>
                </div>
              </header>

              <div class="defense-list">
                <div
                  v-for="(risk, ri) in (showAllRisks ? prediction.risks : prediction.risks.slice(0, 3))"
                  :key="ri"
                  class="risk-item-card"
                >
                  <div class="risk-badge-row">
                    <span class="risk-index-tag">防线 {{ ri + 1 }}</span>
                    <strong class="risk-title">{{ parseRisk(risk).tag }}</strong>
                  </div>
                  <p class="risk-description">{{ parseRisk(risk).desc }}</p>
                </div>
              </div>

              <button
                v-if="prediction.risks.length > 3"
                type="button"
                class="btn btn-ghost btn-sm toggle-risks-btn"
                @click="showAllRisks = !showAllRisks"
              >
                <span>{{ showAllRisks ? '收起部分防线' : `查看全部 ${prediction.risks.length} 项防线细节` }}</span>
                <PhCaretUp v-if="showAllRisks" :size="14" />
                <PhCaretDown v-else :size="14" />
              </button>
            </div>

            <!-- 考官考核维度卡片 -->
            <div class="panel coaching-tip-card">
              <div class="tip-header">
                <PhSparkle :size="18" weight="fill" class="tip-icon" />
                <h4>如何通过这 10 道题立住人设？</h4>
              </div>
              <ul class="coaching-points">
                <li>
                  <strong>用时间序说清链路：</strong>
                  明确还原当时从“接到需求/发现故障”到“定位根因、编写方案、落地验证”的完整过程。
                </li>
                <li>
                  <strong>坦诚边界不心虚：</strong>
                  对于未亲手独立实现的技术点（如仅接入而非开发），主动界定职责，反显严谨可靠。
                </li>
                <li>
                  <strong>准备好脱敏日志或指标：</strong>
                  数字不是空喊的百分比，而是“日均请求量、降级阈值与缓存命中率”等真实指标。
                </li>
              </ul>
            </div>
          </aside>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.configure-delight {
  max-width: 1260px;
  margin: 0 auto;
  padding-bottom: 64px;
}

/* 顶部标题区 */
.heading-content h1 {
  font-size: 26px;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: -0.02em;
}

.heading-content p {
  margin-top: 6px;
  font-size: 14.5px;
  color: #64748b;
  line-height: 1.6;
}

.focus-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding: 5px 12px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 13px;
  font-weight: 600;
}

/* 顶置英雄画像条 */
.profile-hero-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 24px;
  margin-bottom: 24px;
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(32, 44, 64, 0.03);
}

.hero-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.resume-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 10px;
  background: #eff6ff;
  color: #2563eb;
  flex-shrink: 0;
}

.resume-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.resume-headline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.resume-name {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.role-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #334155;
  font-size: 12.5px;
  font-weight: 600;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 6px;
  font-size: 12.5px;
  font-weight: 600;
}

.status-badge.badge-ready {
  background: #ecfdf5;
  color: #059669;
}

.status-badge.badge-mock {
  background: #fff7ed;
  color: #d97706;
}

.resume-subtext {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.toggle-config-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.toggle-config-btn.active {
  background: #eff6ff;
  border-color: #bfdbfe;
  color: #1d4ed8;
}

.quick-first-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.2);
}

/* 抽屉式配置表单包裹 */
.embedded-drawer {
  margin-bottom: 28px;
  animation: fadeIn 0.25s ease-out;
}

.config-form-panel {
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 10px rgba(32, 44, 64, 0.03);
}

.panel-title-group h2 {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.panel-title-group span {
  display: block;
  margin-top: 2px;
  color: #64748b;
}

/* 下拉菜单和输入框美化 */
.select-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.select-wrapper select {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background-color: #ffffff;
  font-size: 14px;
  color: #1e293b;
  transition: all 0.2s ease;
  appearance: none;
  background-image: url("data:image/svg+xml;charset=UTF-8,%3csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3e%3cpolyline points='6 9 12 15 18 9'%3e%3c/polyline%3e%3c/svg%3e");
  background-repeat: no-repeat;
  background-position: right 12px center;
  background-size: 14px;
}

.select-wrapper select:focus {
  border-color: #3b82f6;
  outline: none;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.jd-select-wrapper {
  margin-bottom: 10px;
}

.form-field textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.6;
  color: #1e293b;
  resize: vertical;
  transition: all 0.2s ease;
}

.form-field textarea:focus {
  border-color: #3b82f6;
  outline: none;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.submit-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  margin-top: 8px;
  padding: 12px 20px;
  font-size: 15px;
  font-weight: 600;
  border-radius: 8px;
  transition: all 0.2s ease;
}

/* 侧边栏引导卡片 */
.aside-card {
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  padding: 22px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(32, 44, 64, 0.02);
}

.aside-icon-bubble {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: #eff6ff;
  color: #2563eb;
  margin-bottom: 14px;
}

.aside-desc {
  font-size: 13px;
  line-height: 1.7;
  color: #64748b;
  margin-top: 8px;
}

.feature-checklist {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 18px;
}

.feature-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  color: #334155;
  line-height: 1.5;
}

.feature-icon {
  color: #10b981;
  flex-shrink: 0;
  margin-top: 2px;
}

.level-step {
  display: flex;
  gap: 12px;
  margin-top: 14px;
}

.level-num {
  font-size: 12px;
  font-weight: 700;
  background: #f1f5f9;
  color: #475569;
  padding: 2px 8px;
  border-radius: 4px;
  height: fit-content;
}

/* ================= 押题工作台主体布局 ================= */
.prediction-workspace {
  margin-top: 16px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.8fr) minmax(300px, 1fr);
  gap: 24px;
  align-items: start;
}

/* 左侧押题流 */
.stream-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.stream-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.stream-title-row h2 {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.question-cards-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 单道题目卡片 */
.question-card {
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  padding: 20px 22px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  box-shadow: 0 1px 3px rgba(32, 44, 64, 0.02);
}

.question-card:hover {
  border-color: #cbd5e1;
  box-shadow: 0 4px 16px rgba(32, 44, 64, 0.05);
}

.question-card.is-expanded {
  border-color: #bfdbfe;
  background: #fcfdfe;
}

.qcard-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.qcard-main {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  flex: 1;
}

.qcard-index {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 13.5px;
  font-weight: 700;
  flex-shrink: 0;
  margin-top: 1px;
}

.qcard-question-box {
  flex: 1;
}

.qcard-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.6;
  margin: 0;
}

.qcard-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.badge-priority-high {
  background: #fff7ed;
  color: #c2410c;
  border: 1px solid #ffedd5;
  font-weight: 600;
}

.badge-priority-normal {
  background: #f8fafc;
  color: #475569;
  border: 1px solid #e2e8f0;
}

.badge-knowledge {
  background: #f1f5f9;
  color: #334155;
  font-size: 12px;
}

.qcard-action {
  flex-shrink: 0;
}

.practice-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  padding: 8px 14px;
  transition: all 0.2s ease;
}

.practice-btn:hover .btn-arrow {
  transform: translateX(3px);
}

.btn-arrow {
  transition: transform 0.2s ease;
}

/* 简历依据便签条 */
.qcard-evidence-bar {
  margin-top: 14px;
  margin-left: 46px;
}

.evidence-pill {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 6px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  font-size: 13px;
  line-height: 1.5;
  color: #475569;
  max-width: 100%;
}

.evidence-icon {
  color: #64748b;
  flex-shrink: 0;
  transform: translateY(2px);
}

.evidence-label {
  font-weight: 600;
  color: #334155;
  flex-shrink: 0;
}

.evidence-text {
  color: #475569;
}

/* 折叠开关与抽屉 */
.qcard-expandable {
  margin-top: 14px;
  margin-left: 46px;
}

.expand-toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  background: transparent;
  border: 0;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #2563eb;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.expand-toggle-btn:hover {
  background: #eff6ff;
}

.expandable-drawer {
  margin-top: 12px;
  padding: 16px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-micro-heading {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 8px;
}

.text-amber {
  color: #d97706;
}

.text-accent {
  color: #2563eb;
}

.outline-content {
  font-size: 13.5px;
  line-height: 1.7;
  color: #334155;
  white-space: pre-wrap;
  margin: 0;
}

.followup-bubbles {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.followup-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #f8fafc;
  border-left: 3px solid #3b82f6;
  font-size: 13px;
  color: #1e293b;
  line-height: 1.5;
}

.followup-step {
  font-size: 11.5px;
  font-weight: 700;
  color: #2563eb;
  background: #eff6ff;
  padding: 1px 6px;
  border-radius: 4px;
  flex-shrink: 0;
}

/* 右侧防御盾牌卡片 */
.defense-sidebar {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.defense-card {
  background: #ffffff;
  border: 1px solid #fcd34d;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 10px rgba(217, 119, 6, 0.04);
}

.defense-card-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}

.defense-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #fef3c7;
  color: #b45309;
  flex-shrink: 0;
}

.defense-title {
  font-size: 15.5px;
  font-weight: 700;
  color: #92400e;
  margin: 0;
}

.defense-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.risk-item-card {
  padding: 12px;
  border-radius: 8px;
  background: #fffbeb;
  border: 1px solid #fef3c7;
}

.risk-badge-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.risk-index-tag {
  font-size: 11px;
  font-weight: 700;
  color: #b45309;
  background: #fde68a;
  padding: 1px 5px;
  border-radius: 3px;
}

.risk-title {
  font-size: 13px;
  font-weight: 700;
  color: #78350f;
}

.risk-description {
  font-size: 12.5px;
  line-height: 1.6;
  color: #92400e;
  margin: 0;
}

.toggle-risks-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  margin-top: 12px;
  color: #b45309;
  font-weight: 600;
  font-size: 12.5px;
}

.toggle-risks-btn:hover {
  background: #fef3c7;
}

/* 备战心法与立人设卡片 */
.coaching-tip-card {
  background: #ffffff;
  border: 1px solid #dce3ee;
  border-radius: 12px;
  padding: 20px;
}

.tip-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.tip-icon {
  color: #2563eb;
}

.coaching-tip-card h4 {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}

.coaching-points {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.coaching-points li {
  font-size: 13px;
  line-height: 1.65;
  color: #475569;
  position: relative;
  padding-left: 14px;
}

.coaching-points li::before {
  content: '•';
  position: absolute;
  left: 0;
  color: #3b82f6;
  font-weight: bold;
}

.coaching-points strong {
  color: #1e293b;
}

/* 动画效果 */
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.drawer-enter-active,
.drawer-leave-active {
  transition: all 0.25s ease-out;
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

.collapse-enter-active,
.collapse-leave-active {
  transition: all 0.2s ease-out;
}

.collapse-enter-from,
.collapse-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* 响应式断点 */
@media (max-width: 1080px) {
  .workspace-grid {
    grid-template-columns: 1fr;
  }
  .profile-hero-card {
    flex-direction: column;
    align-items: flex-start;
  }
  .hero-actions {
    width: 100%;
    justify-content: flex-start;
  }
}

@media (max-width: 768px) {
  .qcard-header {
    flex-direction: column;
  }
  .qcard-action {
    width: 100%;
  }
  .practice-btn {
    width: 100%;
    justify-content: center;
  }
  .qcard-evidence-bar,
  .qcard-expandable {
    margin-left: 0;
  }
}
</style>
