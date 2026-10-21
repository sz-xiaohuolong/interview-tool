<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Sparkles, Check, Copy, ArrowRight, Play, Trash2, Save, History, FileText, Zap,  } from 'lucide-vue-next'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import MaterialPicker from '../../components/material/MaterialPicker.vue'
import InputPreview from '../../components/material/InputPreview.vue'
import type { Manifest } from '../../components/material/InputPreview.vue'
import ModelRecovery from '../../components/training/ModelRecovery.vue'
import { training, writeTraining, ApiError } from '../../api/training'
import { localDate } from '../../models/training'
import type { Page } from '../../models/training'

type Row = Record<string, any>
const route = useRoute(), router = useRouter(), id = computed(() => String(route.params.id || ''))

const stories = ref<Row[]>([])
const cursor = ref<string | null>(null)
const story = ref<Row | null>(null)
const kind = ref('STAR')
const materialIds = ref<string[]>([])
const fields = ref<Row>({})
const serverFields = ref<Row>({})
const expression = ref('')
const manifest = ref<Manifest | null>(null)
const history = ref<Row[]>([])
const historyCursor = ref<string | null>(null)
const historical = ref<Row | null>(null)
const questionTemplate = ref('PROJECT_CHALLENGE')
const ackSource = ref(false)
const confirmations = ref<Row>({})
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const message = ref('')
const recovery = ref<ApiError | null>(null)
const deletingId = ref('')

// AI 一键提炼 / 润色分步状态
const aiStep = ref<0 | 1 | 2 | 3>(0)
const copied = ref(false)
const showAdvancedConfirm = ref(false)
const showManifestPreview = ref(false)

const templates: Record<string, string[]> = {
  STAR: ['situation', 'task', 'action', 'result', 'reflection'],
  INTRO: ['targetRole', 'background', 'experience', 'contribution', 'aspiration'],
}

const labels: Record<string, string> = {
  situation: '情境（Situation）',
  task: '任务（Task）',
  action: '个人行动（Action）',
  result: '结果（Result）',
  reflection: '复盘（Reflection）',
  targetRole: '目标岗位',
  background: '个人背景',
  experience: '相关真实经历',
  contribution: '个人贡献',
  aspiration: '下一步诉求',
}

const fieldBadges: Record<string, string> = {
  situation: 'S',
  task: 'T',
  action: 'A',
  result: 'R',
  reflection: '反思',
  targetRole: '岗位',
  background: '背景',
  experience: '经历',
  contribution: '贡献',
  aspiration: '诉求',
}

const fieldPlaceholders: Record<string, string> = {
  situation: '业务背景与前置条件。例如：线上支付高峰期偶发回调超时，日均影响数百笔核心订单…',
  task: '你负责的核心挑战或技术指标。例如：需在两周内排查分布式锁与数据库事务冲突，彻底消除掉单…',
  action: '你的关键方案与具体行动。例如：设计基于 Redis 锁 + 状态机幂等表的双层校验，重构异步重试机制…',
  result: '量化产出与业务收益。例如：上线后核心交易成功率提升至 99.99%，掉单归零，并在全组落地规范…',
  reflection: '方案局限、技术权衡与复盘思考。例如：当时为赶进度采用同步等待，后续可进一步引入消息队列削峰解耦…',
  targetRole: '例如：Java 资深研发工程师 / 大模型应用开发工程师',
  background: '个人技术背景与擅长领域概括…',
  experience: '与该岗位最匹配的核心项目经历…',
  contribution: '你个人带来的最显著业务/架构贡献…',
  aspiration: '期望发展的技术方向与职业诉求…',
}

const storyStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  ARCHIVED: '已归档',
}

const originLabels: Record<string, string> = {
  MATERIAL_QUOTE: '材料引用',
  USER_ASSERTION: '本人声明',
  MODEL_DRAFT: 'AI草稿·待核对',
  MISSING: '待补充',
}

const questions: Record<string, string> = {
  PROJECT_CHALLENGE: '项目困难与个人行动',
  TEAM_CONFLICT: '处理项目分歧',
  SELF_INTRO: '面向岗位的自我介绍',
  PROJECT_VALUE: '经历与岗位的关联',
}

const fieldIds = computed(() => templates[story.value?.templateKind || kind.value] || [])

function fail(e: unknown) {
  error.value = e instanceof Error ? e.message : '操作失败，本页编辑保留。'
  recovery.value = e instanceof ApiError ? e : null
}

function resetFields() {
  fields.value = Object.fromEntries((templates[kind.value] || []).map(k => [k, { text: '', originType: 'MISSING' }]))
  confirmations.value = {}
  expression.value = ''
}
resetFields()

function setCurrent(s: Row, keepLocal = false) {
  story.value = s
  serverFields.value = structuredClone(s.fields)
  const current = { fields: s.fields, expression: s.currentVersionDetail?.expressionDraft || '' }
  let local: Row | null = null
  if (keepLocal) {
    try {
      local = JSON.parse(sessionStorage.getItem(`v2:story:${id.value}`) || 'null')
    } catch {
      /* 从服务端恢复 */
    }
  }
  fields.value = structuredClone(local?.fields || current.fields)
  expression.value = local?.expression ?? current.expression
  materialIds.value = s.materialIds || []
  confirmations.value = {}
  for (const k of fieldIds.value) {
    confirmations.value[k] = { confirmed: false, notApplicable: false, reason: '' }
  }
  questionTemplate.value = s.templateKind === 'STAR' ? 'PROJECT_CHALLENGE' : 'SELF_INTRO'
  if (['FAILED', 'INTERRUPTED', 'RUNNING'].includes(s.generationState)) {
    recovery.value = Object.assign(new ApiError('故事生成尚未完成'), { operationId: s.operationId })
  }
}

watch(
  [fields, expression],
  () => {
    if (id.value && story.value?.status !== 'ARCHIVED') {
      try {
        sessionStorage.setItem(`v2:story:${id.value}`, JSON.stringify({ fields: fields.value, expression: expression.value }))
      } catch {
        /* 本页编辑保留 */
      }
    }
  },
  { deep: true },
)

async function load(more = false) {
  loading.value = true
  try {
    if (id.value) {
      setCurrent(await training<Row>(`/stories/${id.value}`), true)
    } else {
      const p = await training<Page<Row>>(`/stories?limit=20&cursor=${encodeURIComponent(more ? cursor.value || '' : '')}`)
      stories.value = more ? [...stories.value, ...p.items] : p.items
      cursor.value = p.nextCursor
    }
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}

function payloadFields() {
  return Object.fromEntries(
    fieldIds.value.map(k => [
      k,
      {
        text: fields.value[k]?.text || '',
        originType: fields.value[k]?.text?.trim()
          ? fields.value[k]?.originType === 'MISSING'
            ? 'USER_ASSERTION'
            : fields.value[k]?.originType || 'USER_ASSERTION'
          : 'MISSING',
      },
    ]),
  )
}

const dirty = computed(() => {
  if (!story.value) return false
  const currentSaved = Object.fromEntries(
    Object.entries(story.value.fields).map(([k, f]) => [k, { text: (f as any).text, originType: (f as any).originType }]),
  )
  return (
    JSON.stringify(payloadFields()) !== JSON.stringify(currentSaved) ||
    expression.value !== (story.value.currentVersionDetail?.expressionDraft || '')
  )
})

async function save() {
  busy.value = true
  error.value = ''
  try {
    const s = await writeTraining<Row>(
      id.value ? `/stories/${id.value}/versions` : '/stories',
      {
        ...(id.value ? { expectedVersion: story.value!.revision } : { templateKind: kind.value, materialIds: materialIds.value }),
        fields: payloadFields(),
        expressionDraft: expression.value,
      },
    )
    message.value = '已保存故事版本，旧版及确认保留。'
    if (!id.value) {
      await router.push(`/stories/${s.id}`)
    } else {
      sessionStorage.removeItem(`v2:story:${id.value}`)
      setCurrent(await training(`/stories/${id.value}`))
    }
  } catch (e) {
    fail(e)
    if (id.value) {
      try {
        serverFields.value = (await training<Row>(`/stories/${id.value}`)).fields
      } catch {
        /* 不覆盖本页编辑 */
      }
    }
  } finally {
    busy.value = false
  }
}

/**
 * 核心优化：智能全自动链式提炼（同时支持 !id 新建与已有 id 润色）
 * 1. 若无故事 id，自动先创建故事草稿；若有未保存修改，先保存新版本
 * 2. 自动调用 input-preparations 提取材料 manifest
 * 3. 自动调用 draft-generation 触发大模型提炼
 * 4. 路由与数据无缝更新
 */
async function aiDraftFromMaterials() {
  if (busy.value || aiStep.value > 0) return
  error.value = ''
  message.value = ''
  recovery.value = null
  aiStep.value = 1
  busy.value = true

  try {
    let targetId = id.value
    let currentRevision = story.value?.revision ?? 0

    // 步骤 1：若无草稿先建草稿；若有未保存修改先存版本
    if (!targetId) {
      const created = await writeTraining<Row>('/stories', {
        templateKind: kind.value,
        materialIds: materialIds.value,
        fields: payloadFields(),
        expressionDraft: expression.value,
      })
      targetId = created.id
      currentRevision = created.revision
      sessionStorage.removeItem(`v2:story:${targetId}`)
    } else if (dirty.value) {
      const saved = await writeTraining<Row>(`/stories/${targetId}/versions`, {
        expectedVersion: currentRevision,
        fields: payloadFields(),
        expressionDraft: expression.value,
      })
      currentRevision = saved.revision
    }

    // 步骤 2：生成输入快照 manifest
    aiStep.value = 2
    const prepManifest = await writeTraining<Manifest>(`/stories/${targetId}/input-preparations`, {
      expectedVersion: currentRevision,
      materialIds: materialIds.value,
    })
    manifest.value = prepManifest

    // 步骤 3：触发模型生成
    aiStep.value = 3
    const generated = await writeTraining<Row>(`/stories/${targetId}/draft-generation`, {
      expectedVersion: currentRevision,
      manifestId: prepManifest.manifestId,
      manifestHash: prepManifest.manifestHash,
    })

    sessionStorage.removeItem(`v2:story:${targetId}`)
    manifest.value = null

    if (!id.value) {
      await router.push(`/stories/${targetId}`)
    } else {
      setCurrent(generated)
    }
    message.value = 'AI 已为你生成 STAR 结构化事实与口语表达稿，请核对细节！'
  } catch (e) {
    fail(e)
  } finally {
    aiStep.value = 0
    busy.value = false
  }
}

/**
 * 核心优化：一键全选确认全部事实
 * 自动处理 MODEL_DRAFT 转换为本人声明（USER_ASSERTION）并一次性调用 /confirm
 */
async function quickConfirmAll() {
  if (busy.value || !id.value || !story.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    let currentRevision = story.value.revision
    let currentVersion = story.value.currentVersion

    const currentFields = story.value.fields || {}
    const hasModelDraft = fieldIds.value.some(k => currentFields[k]?.originType === 'MODEL_DRAFT')

    // 后端契约：确认前字段必须是本人声明或材料引用，不能带未确认的 MODEL_DRAFT
    if (dirty.value || hasModelDraft) {
      const saved = await writeTraining<Row>(`/stories/${id.value}/versions`, {
        expectedVersion: currentRevision,
        fields: payloadFields(),
        expressionDraft: expression.value,
      })
      currentRevision = saved.revision
      currentVersion = saved.currentVersion
    }

    // 组装所有字段确认
    const confs: Record<string, any> = {}
    for (const k of fieldIds.value) {
      const hasText = !!fields.value[k]?.text?.trim()
      if (hasText) {
        confs[k] = { confirmed: true, notApplicable: false, reason: '' }
      } else {
        if (['action', 'contribution'].includes(k)) {
          confs[k] = { confirmed: true, notApplicable: false, reason: '' }
        } else {
          confs[k] = { confirmed: false, notApplicable: true, reason: '当前阶段不适用' }
        }
      }
    }

    await writeTraining(`/stories/${id.value}/confirm`, {
      expectedVersion: currentRevision,
      storyVersion: currentVersion,
      confirmations: confs,
      acknowledgeSourcesChanged: ackSource.value || !!story.value.sourcesChanged,
    })

    sessionStorage.removeItem(`v2:story:${id.value}`)
    setCurrent(await training(`/stories/${id.value}`))
    message.value = '🎉 故事已全量确认！你可以直接用此故事发起实战模拟练习。'
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

// 兼容逐字段确认
async function confirm() {
  busy.value = true
  try {
    await writeTraining(`/stories/${id.value}/confirm`, {
      expectedVersion: story.value!.revision,
      storyVersion: story.value!.currentVersion,
      confirmations: confirmations.value,
      acknowledgeSourcesChanged: ackSource.value,
    })
    sessionStorage.removeItem(`v2:story:${id.value}`)
    setCurrent(await training(`/stories/${id.value}`))
    message.value = '已保存逐字段确认；这是本人声明，不代表系统外部核验。'
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function prepare() {
  busy.value = true
  try {
    manifest.value = await writeTraining<Manifest>(`/stories/${id.value}/input-preparations`, {
      expectedVersion: story.value!.revision,
      materialIds: materialIds.value,
    })
    showManifestPreview.value = true
    message.value = '预览使用已保存的本人声明与材料；本页未保存修改不会发送。'
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function generate() {
  if (!manifest.value) return
  busy.value = true
  try {
    const s = await writeTraining<Row>(`/stories/${id.value}/draft-generation`, {
      expectedVersion: story.value!.revision,
      manifestId: manifest.value.manifestId,
      manifestHash: manifest.value.manifestHash,
    })
    sessionStorage.removeItem(`v2:story:${id.value}`)
    setCurrent(s)
    manifest.value = null
    message.value = '生成已另存新草稿，事实仍需核对。'
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function recoverGenerated() {
  const keepLocal = dirty.value
  if (!keepLocal) sessionStorage.removeItem(`v2:story:${id.value}`)
  recovery.value = null
  error.value = ''
  await load()
  message.value = keepLocal ? '生成结果已保存，本页编辑仍保留；可展开服务端事实版本核对。' : '已载入保存的生成结果，请核对。'
}

async function archive() {
  busy.value = true
  try {
    await writeTraining(`/stories/${id.value}/archive`, {
      expectedVersion: story.value!.revision,
      restore: story.value!.status === 'ARCHIVED',
    })
    await load()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function deleteStory(item: Row) {
  const title = `${item.templateKind === 'STAR' ? 'STAR 项目故事' : '自我介绍'} · 版本 ${item.currentVersion}`
  if (busy.value || !window.confirm(`删除“${title}”？删除后将从故事库移除，已有独立作答与练习记录会保留。`)) return
  busy.value = true
  deletingId.value = item.id
  error.value = ''
  recovery.value = null
  message.value = ''
  try {
    await writeTraining(`/stories/${item.id}`, { expectedVersion: item.revision }, 'DELETE')
    stories.value = stories.value.filter(s => s.id !== item.id)
    sessionStorage.removeItem(`v2:story:${item.id}`)
    message.value = '故事已删除，已有独立作答与练习记录保留。'
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
    deletingId.value = ''
  }
}

async function versions(more = false) {
  try {
    const p = await training<Page<Row>>(
      `/stories/${id.value}/versions?limit=20&cursor=${encodeURIComponent(more ? historyCursor.value || '' : '')}`,
    )
    history.value = more ? [...history.value, ...p.items] : p.items
    historyCursor.value = p.nextCursor
  } catch (e) {
    fail(e)
  }
}

async function practice(versionId: string) {
  busy.value = true
  try {
    const c = await writeTraining<Row>('/practice-cases', {
      sourceKind: 'STORY',
      sourceId: id.value,
      storyVersionId: versionId,
      questionTemplateId: questionTemplate.value,
    })
    await router.push(`/practice/${c.id}`)
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

function copyExpression() {
  if (!expression.value) return
  navigator.clipboard.writeText(expression.value)
  copied.value = true
  setTimeout(() => {
    copied.value = false
  }, 2000)
}

onMounted(() => load())
</script>

<template>
  <div class="page training-page story-studio">
    <!-- 页面顶栏 -->
    <header class="studio-header">
      <div class="header-left">
        <h1 class="studio-title">
          {{ !id ? '把做过的项目，提炼成听得懂的故事。' : story?.templateKind === 'INTRO' ? '结构化自我介绍' : 'STAR 项目故事' }}
        </h1>
        <p class="studio-subtext">
          用情境（S）、任务（T）、行动（A）、结果（R）结构化梳理真实经历。可直接通过材料一键起草，或手动速记后润色。
        </p>
      </div>

      <div class="header-actions">
        <RouterLink v-if="id" to="/stories" class="btn btn-secondary btn-sm">
          返回故事库
        </RouterLink>
        <div v-if="!id" class="kind-segmented">
          <button
            type="button"
            class="kind-btn"
            :class="{ active: kind === 'STAR' }"
            :disabled="busy"
            @click="kind = 'STAR'; resetFields()"
          >
            STAR 项目故事
          </button>
          <button
            type="button"
            class="kind-btn"
            :class="{ active: kind === 'INTRO' }"
            :disabled="busy"
            @click="kind = 'INTRO'; resetFields()"
          >
            自我介绍
          </button>
        </div>
      </div>
    </header>

    <Notice :message="error" kind="error" />
    <Notice :message="message" kind="info" />
    <ModelRecovery :error="recovery" @recovered="recoverGenerated" />
    <LoadingState v-if="loading" label="正在读取故事版本…" />

    <!-- AI 链式生成状态提示条 -->
    <div v-if="aiStep > 0" class="ai-progress-banner">
      <div class="spinner"></div>
      <div class="ai-progress-content">
        <strong>正在通过 AI 提炼故事内容…</strong>
        <p class="tiny muted">
          {{
            aiStep === 1
              ? '第 1/3 步：初始化故事结构与草稿…'
              : aiStep === 2
                ? '第 2/3 步：提取所选材料上下文与事实清单…'
                : '第 3/3 步：大模型正在提炼 STAR 细节与口语化表达稿…'
          }}
        </p>
      </div>
    </div>

    <div v-if="!loading" class="studio-layout">
      <!-- ================= 左侧：结构化事实卡片编辑区 ================= -->
      <section class="studio-main-panel panel">
        <div class="main-panel-toolbar">
          <div class="toolbar-title-group">
            <h2>{{ story?.templateKind === 'INTRO' ? '自我介绍事实结构' : 'STAR 结构化事实' }}</h2>
            <div class="toolbar-badges">
              <span v-if="id" class="badge" :class="story?.status === 'CONFIRMED' ? 'badge-green' : 'badge-gray'">
                版本 {{ story?.currentVersion }} · {{ storyStatusLabels[story?.status || 'DRAFT'] }}
              </span>
              <span v-if="story?.mock" class="badge badge-orange">Mock 流程</span>
              <span v-if="dirty" class="badge badge-orange">有未保存修改</span>
            </div>
          </div>

          <div class="toolbar-actions">
            <button
              v-if="story?.status !== 'ARCHIVED'"
              type="button"
              class="btn btn-secondary btn-sm"
              :disabled="busy || aiStep > 0"
              @click="save"
            >
              <Save :size="16" />
              {{ id ? '保存为新版本' : '保存草稿' }}
            </button>
          </div>
        </div>

        <p v-if="story?.sourcesChanged" class="notice warning">
          参考来源版本已变化，历史快照仍保留。确认前需核对当时事实。
        </p>

        <!-- STAR / INTRO 5 个字段卡片 -->
        <div class="star-cards-container">
          <article v-for="k in fieldIds" :key="k" class="star-card">
            <header class="star-card-header">
              <div class="star-card-meta">
                <span class="star-badge">{{ fieldBadges[k] }}</span>
                <label :for="`story-${k}`" class="star-label">{{ labels[k] }}</label>
              </div>
              <div class="star-card-status">
                <span
                  class="origin-tag"
                  :class="`origin-${fields[k].originType?.toLowerCase() || 'missing'}`"
                  :title="`当前来源：${originLabels[fields[k].originType] || '本人声明'}`"
                >
                  {{ originLabels[fields[k].originType] || '本人声明' }}
                </span>
                <span class="char-count">{{ fields[k].text?.length || 0 }}/6000</span>
              </div>
            </header>

            <div class="star-card-body">
              <textarea
                :id="`story-${k}`"
                v-model="fields[k].text"
                :readonly="busy || story?.status === 'ARCHIVED'"
                maxlength="6000"
                rows="3"
                class="star-textarea"
                :placeholder="fieldPlaceholders[k] || '请填写真实事实…'"
              />
            </div>

            <!-- 轻量来源细节展示 -->
            <footer v-if="story?.fields[k]?.sourceRefs?.length" class="star-card-footer">
              <details class="source-refs-details">
                <summary class="tiny muted">查看 {{ story.fields[k].sourceRefs.length }} 条材料引用来源</summary>
                <div class="source-refs-list">
                  <p v-for="(r, i) in story.fields[k].sourceRefs" :key="i" class="tiny">
                    “{{ r.quote }}” · 来源快照 {{ r.snapshotId }}
                  </p>
                </div>
              </details>
            </footer>
          </article>
        </div>

        <!-- 服务端事实版本对比（当有本地未保存时展开核对） -->
        <details
          v-if="id && dirty"
          class="server-diff-details"
        >
          <summary class="text-link">查看服务端已保存版本对比</summary>
          <div class="server-diff-content">
            <div v-for="k in fieldIds" :key="k" class="diff-field">
              <strong>{{ labels[k] }}：</strong>
              <pre class="tiny">{{ serverFields[k]?.text || '待补充' }}</pre>
            </div>
          </div>
        </details>
      </section>

      <!-- ================= 右侧：AI 提炼、表达稿与练习辅助栏 ================= -->
      <aside class="studio-sidebar">
        <!-- 卡片 1：AI 智能起草/提炼专区 -->
        <div class="panel sidebar-block ai-generator-card">
          <div class="block-header">
            <div class="sparkle-title">
              <Sparkles :size="20" class="sparkle-icon" />
              <h3>从材料一键提炼</h3>
            </div>
            <span class="tiny muted">智能提取 STAR 事实</span>
          </div>

          <p class="tiny muted block-desc">
            勾选你的简历或项目资料，AI 将自动分析业务背景、提炼核心行动与量化结果。
          </p>

          <MaterialPicker v-model="materialIds" />

          <button
            type="button"
            class="btn btn-magic"
            :disabled="busy || aiStep > 0 || materialIds.length === 0"
            @click="aiDraftFromMaterials"
          >
            <Sparkles :size="18" />
            <span>{{ !id ? '从所选材料一键提炼故事' : '结合材料重新提炼' }}</span>
          </button>
          <p v-if="materialIds.length === 0" class="tiny muted text-center mt-1">
            请先在上方勾选至少 1 份资料
          </p>
        </div>

        <!-- 卡片 2：口语化面试陈述稿 -->
        <div class="panel sidebar-block expression-card">
          <div class="block-header">
            <div>
              <h3>面试口述表达稿</h3>
              <p class="tiny muted">面试可以直接口头陈述的版本（不作为书面凭证）</p>
            </div>
            <button
              type="button"
              class="btn btn-ghost btn-sm copy-btn"
              :disabled="!expression"
              @click="copyExpression"
            >
              <Copy v-if="!copied" :size="15" />
              <Check v-else :size="15" class="text-green" />
              <span>{{ copied ? '已复制' : '复制全文' }}</span>
            </button>
          </div>

          <textarea
            id="story-expression"
            v-model="expression"
            :readonly="busy || story?.status === 'ARCHIVED'"
            maxlength="6000"
            rows="6"
            class="expression-textarea"
            placeholder="AI 会在此生成一段连贯流畅的面试口述稿；你也可以根据自己的表达习惯随时调整…"
          />
          <div class="expression-footer">
            <span class="tiny muted">{{ expression.length }}/6000 字符</span>
          </div>
        </div>

        <!-- 卡片 3：确认与独立实战练习 -->
        <div v-if="id && story?.status !== 'ARCHIVED'" class="panel sidebar-block action-card">
          <!-- 状态 A：草稿态，支持一键确认全部 -->
          <div v-if="story?.status === 'DRAFT'" class="confirm-section">
            <div class="block-header">
              <h3>核对与确认</h3>
              <span class="badge badge-orange">草稿态</span>
            </div>
            <p class="tiny muted">
              核对无误后确认此版本，确认为本人声明。确认后即可开展独立作答练习。
            </p>

            <button
              type="button"
              class="btn btn-primary w-full"
              :disabled="busy || aiStep > 0"
              @click="quickConfirmAll"
            >
              <Check :size="18" />
              <span>确认全部内容（本人声明）</span>
            </button>

            <!-- 高级手动确认入口折叠 -->
            <details class="advanced-confirm-details" :open="showAdvancedConfirm">
              <summary class="tiny text-link" @click.prevent="showAdvancedConfirm = !showAdvancedConfirm">
                {{ showAdvancedConfirm ? '收起逐字段确认' : '高级：展开逐字段手动确认' }}
              </summary>
              <div class="advanced-confirm-body">
                <div v-for="k in fieldIds" :key="k" class="confirm-field-row">
                  <label class="training-inline-check">
                    <input v-model="confirmations[k].confirmed" type="checkbox" />
                    <span>{{ labels[k] }}：我确认事实</span>
                  </label>
                  <template v-if="!['action', 'contribution'].includes(k) && !story.fields[k]?.text">
                    <label class="training-inline-check">
                      <input v-model="confirmations[k].notApplicable" type="checkbox" />
                      <span>标记为不适用</span>
                    </label>
                    <input
                      v-if="confirmations[k].notApplicable"
                      v-model="confirmations[k].reason"
                      placeholder="不适用原因…"
                      class="na-input"
                    />
                  </template>
                </div>
                <label v-if="story?.sourcesChanged" class="training-inline-check">
                  <input v-model="ackSource" type="checkbox" />
                  <span>我已核对并接受该历史来源版本边界</span>
                </label>
                <button
                  type="button"
                  class="btn btn-secondary btn-sm mt-2 w-full"
                  :disabled="busy || dirty"
                  @click="confirm"
                >
                  保存逐字段确认
                </button>
              </div>
            </details>
          </div>

          <!-- 状态 B：已确认，直接进入实战练习 -->
          <div v-if="story?.status === 'CONFIRMED'" class="practice-section">
            <div class="block-header">
              <div class="confirmed-badge-group">
                <Check :size="20" class="text-green" />
                <h3>本版事实已确认</h3>
              </div>
              <span class="badge badge-green">已就绪</span>
            </div>
            <p class="tiny muted">
              根据你准备的经历，围绕面试官最常问的场景进行针对性模拟答辩。AI 将以此经历为底牌进行现场提问与逻辑核验。
            </p>

            <div class="practice-form">
              <label for="story-question" class="field-label">模拟练习场景</label>
              <select id="story-question" v-model="questionTemplate" class="practice-select">
                <option
                  v-for="key in story.templateKind === 'STAR'
                    ? ['PROJECT_CHALLENGE', 'TEAM_CONFLICT']
                    : ['SELF_INTRO', 'PROJECT_VALUE']"
                  :key="key"
                  :value="key"
                >
                  {{ questions[key] }}
                </option>
              </select>
            </div>

            <button
              type="button"
              class="btn btn-primary w-full practice-cta"
              :disabled="busy"
              @click="practice(story!.currentVersionId)"
            >
              <Play :size="18" />
              <span>🎙️ 开始模拟答辩演练</span>
            </button>
            <p class="practice-tip tiny muted text-center mt-1">
              进入单题考场，AI 面试官将向你发问，检验你对该经历的现场表达与前后一致性
            </p>
          </div>

          <!-- 归档操作 -->
          <div class="sidebar-secondary-actions">
            <button
              type="button"
              class="btn btn-ghost btn-sm"
              :disabled="busy"
              @click="archive"
            >
              {{ story?.status === 'ARCHIVED' ? '恢复故事' : '归档故事' }}
            </button>
          </div>
        </div>

        <!-- 卡片 4：历史版本追溯 -->
        <details
          v-if="id"
          class="panel sidebar-block history-block"
          @toggle="($event.target as HTMLDetailsElement).open && versions()"
        >
          <summary class="history-summary">
            <div class="history-summary-title">
              <History :size="18" />
              <span>查看所有故事版本（{{ history.length || 1 }}）</span>
            </div>
          </summary>

          <div class="history-body">
            <div v-for="h in history" :key="h.id" class="history-item">
              <button
                type="button"
                class="text-link"
                @click="training<Row>(`/stories/${id}/versions/${h.id}`).then(r => historical = r).catch(fail)"
              >
                版本 {{ h.version }} · {{ h.status === 'CONFIRMED' ? '已确认' : '草稿' }} · {{ localDate(h.createdAt) }}
              </button>
            </div>
            <button v-if="historyCursor" type="button" class="text-link" @click="versions(true)">
              加载更多版本
            </button>

            <section v-if="historical" class="historical-detail">
              <h4>历史版本 {{ historical.version }} 详情</h4>
              <div v-for="k in fieldIds" :key="k" class="historical-field">
                <strong>{{ labels[k] }}</strong>
                <pre class="tiny">{{ historical.fields[k]?.text || '待补充' }}</pre>
              </div>
              <pre v-if="historical.expressionDraft" class="tiny expression-preview">{{ historical.expressionDraft }}</pre>
              <button
                v-if="historical.status === 'CONFIRMED' && story?.status !== 'ARCHIVED'"
                type="button"
                class="btn btn-secondary btn-sm w-full mt-2"
                :disabled="busy"
                @click="practice(historical.id)"
              >
                🎙️ 按此版本开始模拟演练
              </button>
            </section>
          </div>
        </details>
      </aside>
    </div>

    <!-- ================= 底部：已保存的故事列表（新建态或全部列表） ================= -->
    <section v-if="!id && !loading" class="panel story-archive-section">
      <div class="section-header">
        <div>
          <h2>已保存故事</h2>
          <p class="tiny muted">你可以随时重新打开打磨、修改或发起模拟面试</p>
        </div>
      </div>

      <div v-if="!stories.length" class="empty-story-placeholder">
        <FileText :size="40" class="text-muted" />
        <p class="muted">还没有保存的故事。在上方输入内容或勾选简历，开启你的第一个 STAR 项目故事吧！</p>
      </div>

      <div v-else class="story-list-grid">
        <article v-for="s in stories" :key="s.id" class="training-list-row story-card-item">
          <div class="story-card-info">
            <div class="story-card-title-row">
              <h3>{{ s.templateKind === 'STAR' ? 'STAR 项目故事' : '自我介绍' }}</h3>
              <span class="badge" :class="s.status === 'CONFIRMED' ? 'badge-green' : 'badge-gray'">
                版本 {{ s.currentVersion }} · {{ storyStatusLabels[s.status] }}
              </span>
            </div>
            <p class="tiny muted">最后更新：{{ localDate(s.updatedAt) }}</p>
          </div>

          <div class="story-list-actions">
            <RouterLink :to="`/stories/${s.id}`" class="btn btn-secondary btn-sm">
              打开故事与版本
            </RouterLink>
            <button
              type="button"
              class="btn btn-ghost btn-sm story-delete"
              :disabled="busy"
              @click="deleteStory(s)"
            >
              <Trash2 :size="16" />
              <span>{{ deletingId === s.id ? '删除中…' : '删除' }}</span>
            </button>
          </div>
        </article>
      </div>

      <button v-if="cursor" type="button" class="text-link mt-4" @click="load(true)">
        加载更多故事
      </button>
    </section>
  </div>
</template>

<style scoped>
.story-studio { max-width:1320px; }.studio-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:20px; }.studio-title { font-size:24px; }.studio-subtext { color:var(--text-muted); font-size:13px; max-width:70ch; margin-top:8px; }.header-actions { flex-shrink:0; }.kind-segmented { display:flex; padding:3px; border:1px solid var(--border-subtle); border-radius:6px; background:var(--surface-soft); }.kind-btn { display:flex; align-items:center; gap:6px; border:0; padding:6px 10px; color:var(--text-muted); background:transparent; border-radius:4px; font-size:12px; }.kind-btn.active { background:var(--surface); color:var(--accent-ink); }
.studio-layout { display:grid; grid-template-columns:minmax(0,1.6fr) minmax(280px,1fr); border-top:1px solid var(--border-subtle); gap:0; }.studio-main-panel { border:0; border-radius:0; padding:0 24px 0 0; }.main-panel-toolbar, .toolbar-title-group, .toolbar-badges, .toolbar-actions, .star-card-header, .star-card-meta, .star-card-status { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }.main-panel-toolbar { justify-content:space-between; padding:16px 0; border-bottom:1px solid var(--border-subtle); }.toolbar-title-group h2 { font-size:14px; }
.star-card { padding:16px 0; border-bottom:1px solid var(--border-subtle); }.star-card-header { justify-content:space-between; margin-bottom:10px; }.star-label { margin:0; font-size:13px; }.star-badge { color:var(--accent-ink); font:12px var(--font-mono); }.origin-tag, .char-count { font-size:11px; color:var(--text-muted); }.char-count { font-family:var(--font-mono); }.star-textarea { min-height:110px; font-size:14px; line-height:1.8; background:var(--surface); }.star-card-footer { margin-top:8px; }.source-refs-list { padding:10px; margin-top:8px; background:var(--surface-soft); }.source-refs-list p + p { margin-top:8px; }
.studio-sidebar { border-left:1px solid var(--border-subtle); padding-left:24px; min-width:0; }.sidebar-block { padding:16px 0; border:0; border-bottom:1px solid var(--border-subtle); border-radius:0; }.block-header { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; margin-bottom:12px; flex-wrap:wrap; }.block-header h3 { font-size:13px; }.sparkle-title, .confirmed-badge-group { display:flex; align-items:center; gap:8px; }.sparkle-icon { color:var(--accent); }.block-desc { margin-bottom:12px; }.btn-magic { background:var(--accent); color:white; width:100%; margin-top:12px; }.btn-magic:hover:not(:disabled) { background:var(--accent-hover); }.btn-magic:disabled { color:var(--text-muted); background:var(--surface-soft); border-color:var(--border-control); }.expression-textarea { min-height:180px; font-size:14px; }.expression-footer { margin-top:8px; }.action-card p { margin-bottom:12px; }.advanced-confirm-details { margin-top:12px; }.advanced-confirm-body { padding-top:12px; }.confirm-field-row { padding:10px 0; border-bottom:1px solid var(--border-subtle); }.confirm-field-row .training-inline-check { font-size:12px; }.practice-form { margin:12px 0; }.practice-cta { margin-top:4px; }.sidebar-secondary-actions { margin-top:12px; }.history-summary, .history-summary-title { display:flex; align-items:center; gap:8px; }.history-body { padding-top:12px; }.history-item { padding:8px 0; border-bottom:1px solid var(--border-subtle); }.historical-detail, .server-diff-details { margin-top:16px; padding-top:12px; border-top:1px solid var(--border-subtle); }.historical-field, .diff-field { margin:12px 0; }.historical-field strong, .diff-field strong { font-size:12px; }.historical-field pre, .diff-field pre { margin-top:6px; }
.ai-progress-banner { display:flex; align-items:center; gap:12px; padding:12px; background:var(--accent-soft); border-radius:6px; margin-bottom:16px; }.story-archive-section { border:0; border-top:1px solid var(--border-subtle); border-radius:0; padding:20px 0 0; margin-top:24px; }.section-header { margin-bottom:12px; }.section-header h2 { font-size:16px; }.empty-story-placeholder { display:flex; gap:12px; align-items:center; padding:20px 0; }.story-list-grid { display:block; }.story-card-item { min-height:52px; padding:10px 0; align-items:center; }.story-card-title-row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }.story-card-title-row h3 { font-size:13px; }.story-list-actions { display:flex; align-items:center; gap:8px; }.story-delete, .text-green { color:var(--success); }.story-delete { color:var(--danger); }
@media(max-width:1000px) { .studio-header { flex-direction:column; gap:12px; }.studio-layout { grid-template-columns:minmax(0,1fr); }.studio-main-panel { padding-right:0; }.studio-sidebar { border:0; padding:0; }.star-card-status { margin-left:auto; } }
@media(max-width:600px) { .studio-title { font-size:22px; }.star-textarea, .expression-textarea { font-size:16px; }.star-card-header { gap:8px; }.story-card-item { gap:8px; }.story-list-actions { justify-content:flex-end; }.kind-btn { min-height:44px; } }
</style>
