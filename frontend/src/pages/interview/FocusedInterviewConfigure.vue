<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, RefreshCw, CircleCheck, FileText, Timer, Layers, SlidersHorizontal, Zap, ChevronDown, ChevronUp, ShieldAlert, Lightbulb, MessageCircle, Check, Sparkles, Target, Compass, CircleHelp, Minimize2, Maximize2,  } from 'lucide-vue-next'
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
        <Target :size="16" />
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
            <FileText :size="28" />
          </div>
          <div class="resume-info">
            <div class="resume-headline">
              <h2 class="resume-name">{{ selectedMaterial?.name || '已选简历' }}</h2>
              <span class="role-badge">{{ role }}</span>
              <span class="status-badge" :class="prediction.mock ? 'badge-mock' : 'badge-ready'">
                <CircleCheck :size="14" />
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
            <SlidersHorizontal :size="15" />
            <span>{{ showConfigDrawer ? '收起配置' : '调整配置 / 重新计算' }}</span>
            <ChevronUp v-if="showConfigDrawer" :size="14" />
            <ChevronDown v-else :size="14" />
          </button>
          <button
            type="button"
            class="btn btn-primary btn-sm quick-first-btn"
            :disabled="busy || !prediction.questions.length"
            @click="start(prediction.id, prediction.questions[0]?.id)"
          >
            <Zap :size="15" />
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
                <select id="material-select" :aria-label="isResume ? '选择简历' : '选择项目'" v-model="selected" :disabled="busy">
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
                {{ selectedMaterial.documents.length }} 份附件 · 使用当前资料创建本场快照
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
                <Sparkles v-if="isResume && !prediction" :size="18" />
                <RefreshCw v-else-if="isResume && prediction" :size="18" />
                <ArrowRight v-else :size="18" />
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
                      : '生成 10 道针对性押题'
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
                  :is="isResume ? FileText : mode === 'project' ? Layers : Timer"
                  :size="26"
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
                  <CircleCheck :size="16" class="feature-icon" />
                  <span>{{ isResume ? '10 道高价值大厂风格追问' : '每道核心问题支持 2 轮深度追问' }}</span>
                </div>
                <div class="feature-item">
                  <CircleCheck :size="16" class="feature-icon" />
                  <span>{{ isResume ? '配套简历依据、答题思路与可能追问' : '自动留痕作答记录，中断可随时无缝继续' }}</span>
                </div>
                <div class="feature-item">
                  <CircleCheck :size="16" class="feature-icon" />
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
                <Lightbulb :size="15" />
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
                <span v-if="prediction.mock" class="badge badge-orange">Mock 测试结果</span>
              </div>
              <div class="stream-actions">
                <button type="button" class="btn btn-ghost btn-sm" @click="expandAllQuestions">
                  <Minimize2 v-if="prediction.questions.every(q => expandedQuestions[q.id])" :size="14" />
                  <Maximize2 v-else :size="14" />
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
            <div class="question-cards-list prediction-list">
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
                      <ArrowRight :size="14" class="btn-arrow" />
                    </button>
                  </div>
                </header>

                <!-- 简历依据标签便签 -->
                <div class="qcard-evidence-bar">
                  <div class="evidence-pill">
                    <FileText :size="14" class="evidence-icon" />
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
                    <MessageCircle :size="15" />
                    <span>{{ expandedQuestions[q.id] ? '收起准备思路与追问' : '查看面试官思路与高频追问链条' }}</span>
                    <ChevronUp v-if="expandedQuestions[q.id]" :size="14" />
                    <ChevronDown v-else :size="14" />
                  </button>

                  <transition name="collapse">
                    <div v-show="expandedQuestions[q.id]" class="expandable-drawer">
                      <!-- 答题思路 -->
                      <div class="outline-section">
                        <div class="section-micro-heading">
                          <Lightbulb :size="15" class="micro-icon text-amber" />
                          <span>答题结构与核心思路</span>
                        </div>
                        <p class="outline-content">{{ q.answerOutline }}</p>
                      </div>

                      <!-- 连环追问链条 -->
                      <div v-if="q.followUps && q.followUps.length" class="followups-section">
                        <div class="section-micro-heading">
                          <Compass :size="15" class="micro-icon text-accent" />
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
                  <ShieldAlert :size="22" />
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
                <ChevronUp v-if="showAllRisks" :size="14" />
                <ChevronDown v-else :size="14" />
              </button>
            </div>

            <!-- 考官考核维度卡片 -->
            <div class="panel coaching-tip-card">
              <div class="tip-header">
                <Sparkles :size="18" class="tip-icon" />
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
.configure-page,.resume-workbench { max-width:1200px; }
.config-form-panel { padding:20px; }.config-form-panel .heading-row { padding-bottom:12px; border-bottom:1px solid var(--border-subtle); }
.panel-title-group,.config-summary,.hero-left,.resume-headline,.hero-actions,.stream-title-row,.qcard-tags,.qcard-header,.qcard-main,.qcard-action,.tip-header,.defense-card-header { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.panel-title-group { flex-direction:column; align-items:flex-start; }.page-heading { margin-bottom:20px; }.page-heading h1 { font-size:24px; }
.resume-hero { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:16px 0; border-block:1px solid var(--border-subtle); margin-bottom:20px; }.resume-avatar { color:var(--accent); }.resume-name { font-size:16px; }.resume-subtext,.role-badge,.status-badge { font-size:12px; color:var(--text-muted); }.role-badge,.status-badge { padding:2px 6px; border-radius:6px; background:var(--surface-soft); }.status-badge { display:inline-flex; align-items:center; gap:4px; }
.form-field { margin-bottom:16px; }.form-grid { gap:16px; }.form-section { margin-top:16px; padding-top:16px; }.jd-select-wrapper { margin-bottom:8px; }.submit-btn { width:100%; margin-top:16px; }.material-summary { margin-top:8px; font-size:12px; color:var(--text-muted); }.aside-features-panel { padding:0; border:0; }.aside-icon-bubble { color:var(--accent); margin-bottom:12px; }.aside-desc { margin-top:8px; font-size:13px; color:var(--text-muted); }.feature-checklist,.level-step { margin-top:16px; }.feature-item { display:flex; gap:8px; margin-top:12px; font-size:13px; }.feature-icon { color:var(--accent); }.level-num { font:12px var(--font-mono); }
.prediction-workspace { margin-top:24px; }.workspace-grid { display:grid; grid-template-columns:minmax(0,3fr) minmax(240px,1fr); gap:24px; align-items:start; }.stream-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; }.stream-title-row h2 { font-size:16px; }.question-cards-list { display:grid; gap:0; border-top:1px solid var(--border-subtle); }.question-card { padding:16px 0; border:0; border-bottom:1px solid var(--border-subtle); border-radius:0; }.qcard-header { align-items:flex-start; flex-wrap:nowrap; justify-content:space-between; }.qcard-main { align-items:flex-start; flex-wrap:nowrap; min-width:0; }.qcard-index { font:13px var(--font-mono); color:var(--text-muted); padding-top:4px; }.qcard-question-box { min-width:0; }.qcard-title { font-size:14px; line-height:1.65; }.qcard-tags { margin-top:8px; }.qcard-tags .badge { font-size:11px; }.qcard-action { flex-shrink:0; }.qcard-evidence-bar { margin-top:12px; }.evidence-pill { display:flex; align-items:baseline; gap:6px; font-size:12px; color:var(--text-muted); }.evidence-label { flex-shrink:0; }.evidence-icon { align-self:center; }.qcard-expandable { margin-top:8px; }.expand-toggle-btn { display:flex; align-items:center; gap:6px; min-height:32px; border:0; background:none; color:var(--accent-ink); font-size:12px; }.expandable-drawer { padding:12px 16px; margin-top:8px; background:var(--surface-soft); border-radius:6px; }.section-micro-heading { font-size:12px; margin:8px 0; }.outline-content { font-size:14px; line-height:1.75; white-space:pre-wrap; }.followup-bubbles { display:grid; gap:8px; margin-top:8px; }.followup-item { display:flex; gap:8px; font-size:13px; }.followup-step { font:12px var(--font-mono); }.defense-sidebar { border-left:1px solid var(--border-subtle); padding-left:20px; }.defense-card,.coaching-tip-card { border:0; padding:0; border-radius:0; }.defense-icon-wrap,.tip-icon { color:var(--warning); }.defense-title { font-size:14px; }.defense-list { margin-top:12px; }.risk-item-card { padding:12px 0; border-bottom:1px solid var(--border-subtle); }.risk-title { font-size:13px; }.risk-description { font-size:13px; color:var(--text-muted); margin-top:6px; }.risk-index-tag { font:12px var(--font-mono); }.toggle-risks-btn { margin-top:12px; border:0; background:none; color:var(--accent); }.coaching-tip-card { margin-top:24px; }.coaching-points { padding-left:16px; font-size:13px; line-height:1.75; }.coaching-points li { margin-top:8px; }.text-amber { color:var(--warning); }.text-accent { color:var(--accent); }
@media(max-width:1000px) { .workspace-grid { grid-template-columns:1fr; }.defense-sidebar { border-left:0; border-top:1px solid var(--border-subtle); padding:16px 0; } }
@media(max-width:600px) { .resume-hero,.qcard-header { flex-wrap:wrap; }.page-heading h1 { font-size:22px; }.qcard-action { margin-left:24px; }.config-form-panel { padding:16px; }.expand-toggle-btn { min-height:44px; } }
</style>
