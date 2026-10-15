<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { PhArrowRight, PhBriefcase, PhFileText, PhFolderOpen, PhLockSimple, PhPencilSimple, PhPlus, PhTrash, PhUploadSimple } from '@phosphor-icons/vue'
import { errorMessage, post, put, request } from '../api'
import { dateTime } from '../format'
import type { Document, Material, MaterialKind } from '../types'
import Modal from '../components/Modal.vue'
import Notice from '../components/Notice.vue'
import EmptyState from '../components/EmptyState.vue'
import LoadingState from '../components/LoadingState.vue'

const kinds: { value: MaterialKind; label: string; description: string }[] = [
  { value: 'RESUME', label: '简历', description: '保存真实经历，让每一道押题都有依据。' },
  { value: 'PROJECT', label: '项目', description: '补充项目背景、技术选择和个人职责，准备更深入的追问。' },
  { value: 'JD', label: '目标 JD', description: '保存目标岗位要求，配置面试时可直接选用。' },
]
const materials = ref<Material[]>([])
const route = useRoute()
const requestedKind = String(route.query.kind || '')
const kind = ref<MaterialKind>(['RESUME', 'PROJECT', 'JD'].includes(requestedKind) ? requestedKind as MaterialKind : 'RESUME')
const currentKind = computed(() => kinds.find(item => item.value === kind.value)!)
const visibleMaterials = computed(() => materials.value.filter(item => item.kind === kind.value))
const loading = ref(true)
const loadError = ref('')
const pageError = ref('')
const busy = ref(false)
const editorOpen = ref(false)
const detailOpen = ref(false)
const editorKind = ref<MaterialKind>('RESUME')
const editingId = ref<string | null>(null)
const name = ref('')
const content = ref('')
const resumeMode = ref<'upload' | 'text'>('upload')
const resumeFile = ref<File | null>(null)
const formError = ref('')
const detailError = ref('')
const detailMessage = ref('')
const selectedId = ref<string | null>(null)
const selected = computed(() => materials.value.find(item => item.id === selectedId.value))
const pendingDocuments = ref<File[]>([])
const uploadForm = computed(() => editorKind.value === 'RESUME' && !editingId.value && resumeMode.value === 'upload')
const editorLabel = computed(() => kinds.find(item => item.value === editorKind.value)!.label)

async function loadMaterials() {
  loading.value = true
  loadError.value = ''
  try { materials.value = await request<Material[]>('/materials') }
  catch (error) { loadError.value = errorMessage(error) }
  finally { loading.value = false }
}

function replaceMaterial(material: Material) {
  const index = materials.value.findIndex(item => item.id === material.id)
  if (index === -1) materials.value.unshift(material)
  else materials.value[index] = material
}

function showDetails(material: Material) {
  selectedId.value = material.id
  pendingDocuments.value = []
  detailError.value = ''
  detailMessage.value = ''
  detailOpen.value = true
}

function openEditor(material?: Material) {
  if (busy.value || loading.value) return
  editingId.value = material?.id || null
  editorKind.value = material?.kind || kind.value
  name.value = material?.name || ''
  content.value = material?.content || ''
  resumeFile.value = null
  resumeMode.value = 'upload'
  formError.value = ''
  detailOpen.value = false
  editorOpen.value = true
}

function fileError(files: File[], materialKind: MaterialKind) {
  const extensions = materialKind === 'RESUME' ? ['pdf', 'docx', 'md', 'markdown'] : ['pdf', 'docx', 'md', 'markdown', 'txt']
  for (const file of files) {
    if (!extensions.includes(file.name.split('.').pop()?.toLowerCase() || '')) return `“${file.name}”格式不支持；${materialKind === 'RESUME' ? '简历支持 PDF、DOCX、Markdown。' : '项目支持 PDF、DOCX、Markdown、TXT。'}`
    if (!file.size) return `“${file.name}”为空文件，请重新选择。`
    if (file.size > 5 * 1024 * 1024) return `“${file.name}”超过单文件 5 MiB 的限制。`
  }
  return ''
}

function chooseResume(event: Event) {
  resumeFile.value = (event.target as HTMLInputElement).files?.[0] || null
  formError.value = resumeFile.value ? fileError([resumeFile.value], 'RESUME') : ''
}

async function saveMaterial() {
  if (busy.value) return
  formError.value = ''
  if (uploadForm.value) {
    if (!resumeFile.value) { formError.value = '请先选择一份简历文件。'; return }
    formError.value = fileError([resumeFile.value], 'RESUME')
  } else if (!name.value.trim()) { formError.value = '请填写资料名称。' }
  else if (editorKind.value !== 'PROJECT' && !editingId.value && !content.value.trim()) { formError.value = '请填写资料正文。' }
  if (formError.value) return
  busy.value = true
  try {
    let material: Material
    if (uploadForm.value) {
      const body = new FormData()
      body.append('kind', 'RESUME')
      if (name.value.trim()) body.append('name', name.value.trim())
      body.append('file', resumeFile.value!)
      material = await request<Material>('/materials/upload', { method: 'POST', body })
    } else {
      const body = { name: name.value.trim(), content: content.value }
      material = editingId.value
        ? await put<Material>(`/materials/${editingId.value}`, body)
        : await post<Material>('/materials', { kind: editorKind.value, ...body })
    }
    replaceMaterial(material)
    kind.value = material.kind
    editorOpen.value = false
    showDetails(material)
    detailMessage.value = '资料已保存到本机。'
  } catch (error) { formError.value = errorMessage(error) }
  finally { busy.value = false }
}

function chooseDocuments(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!selected.value || !files.length) return
  detailError.value = fileError(files, selected.value.kind)
  if (detailError.value) return
  if (selected.value.documents.length + files.length > 20) { detailError.value = '每份资料最多保存 20 个附件，请减少文件数量。'; return }
  pendingDocuments.value = files
  detailMessage.value = ''
}

async function uploadDocuments() {
  if (busy.value || !selected.value || !pendingDocuments.value.length) return
  busy.value = true
  detailError.value = ''
  detailMessage.value = ''
  let saved = 0
  try {
    while (pendingDocuments.value.length) {
      const body = new FormData()
      body.append('file', pendingDocuments.value[0]!)
      const material = await request<Material>(`/materials/${selected.value.id}/documents`, { method: 'POST', body })
      replaceMaterial(material)
      pendingDocuments.value.shift()
      saved++
    }
    detailMessage.value = `已保存 ${saved} 个附件。`
  } catch (error) { detailError.value = `${saved ? `已保存 ${saved} 个附件，` : ''}${errorMessage(error)} 未保存的文件仍在待上传列表中。` }
  finally { busy.value = false }
}

async function deleteMaterial(material: Material) {
  if (busy.value || !window.confirm(`删除“${material.name}”及其所有附件？此操作无法撤销，已有面试记录会保留。`)) return
  busy.value = true
  pageError.value = ''
  try {
    await request(`/materials/${material.id}`, { method: 'DELETE' })
    materials.value = materials.value.filter(item => item.id !== material.id)
    if (selectedId.value === material.id) detailOpen.value = false
  } catch (error) { pageError.value = errorMessage(error) }
  finally { busy.value = false }
}

async function deleteDocument(document: Document) {
  if (busy.value || !selected.value || !window.confirm(`删除附件“${document.name}”？此操作无法撤销。`)) return
  busy.value = true
  detailError.value = ''
  detailMessage.value = ''
  try {
    await request(`/materials/${selected.value.id}/documents/${document.id}`, { method: 'DELETE' })
    selected.value.documents = selected.value.documents.filter(item => item.id !== document.id)
    detailMessage.value = '附件已删除。'
  } catch (error) { detailError.value = errorMessage(error) }
  finally { busy.value = false }
}

function interviewRoute(material: Material) {
  return { path: `/configure/${material.kind === 'RESUME' ? 'resume' : 'project'}`, query: { materialId: material.id } }
}

onMounted(loadMaterials)
</script>

<template>
  <main class="page materials-page">
    <div class="page-heading heading-row">
      <div><div class="eyebrow">让准备有据可循</div><h1>我的资料库</h1><p>整理你的经历、项目与目标岗位，为每次练习提供真实背景。</p></div>
      <button class="btn btn-dark" :disabled="busy || loading" @click="openEditor()"><PhPlus :size="17" />新增{{ currentKind.label }}</button>
    </div>

    <div class="tabs" role="group" aria-label="资料分类">
      <button v-for="item in kinds" :key="item.value" class="tab" :class="{ active: kind === item.value }" :data-kind="item.value" :aria-pressed="kind === item.value" @click="kind = item.value">
        {{ item.label }}<span class="material-count">{{ materials.filter(material => material.kind === item.value).length }}</span>
      </button>
    </div>

    <Notice v-if="pageError" :message="pageError" kind="error" />
    <LoadingState v-if="loading" label="正在读取本机资料…" />
    <div v-else-if="loadError" class="panel"><Notice :message="loadError" kind="error" /><button class="btn btn-secondary" @click="loadMaterials">重新加载</button></div>
    <template v-else>
      <p class="muted tiny material-intro">{{ currentKind.description }}</p>
      <EmptyState v-if="!visibleMaterials.length" class="panel" :title="`还没有${currentKind.label}`" :description="kind === 'RESUME' ? '点击右上方新增简历，可上传 PDF、DOCX 或 Markdown。' : kind === 'PROJECT' ? '点击右上方新增项目，保存文字后可继续添加多个附件。' : '点击右上方新增目标 JD，保存完整的岗位要求。'" />
      <div v-else class="material-grid">
        <article v-for="material in visibleMaterials" :key="material.id" class="material-card">
          <div class="material-card-heading"><span class="list-icon"><PhFileText v-if="kind === 'RESUME'" :size="23" /><PhFolderOpen v-else-if="kind === 'PROJECT'" :size="23" /><PhBriefcase v-else :size="23" /></span><div class="list-body"><h3 :title="material.name">{{ material.name }}</h3><p>{{ dateTime(material.updatedAt) }} 更新</p></div></div>
          <p class="material-preview">{{ material.content || material.documents.map(document => document.text).join('\n') || '尚未补充正文或附件，查看资料后可继续完善。' }}</p>
          <div class="material-meta"><span class="badge badge-gray">{{ material.documents.length }} 个附件</span><span v-if="material.documents.some(document => document.truncated)" class="badge badge-orange">部分附件已截断</span></div>
          <div class="card-actions">
            <button class="btn btn-secondary btn-sm" :disabled="busy" @click="showDetails(material)">查看</button>
            <button class="btn btn-ghost btn-sm" :disabled="busy" @click="openEditor(material)"><PhPencilSimple :size="15" />编辑</button>
            <button class="btn btn-ghost btn-sm" :disabled="busy" @click="deleteMaterial(material)"><PhTrash :size="15" />删除</button>
          </div>
          <RouterLink v-if="material.kind !== 'JD'" :to="interviewRoute(material)" class="text-link material-start">{{ material.kind === 'RESUME' ? '开始简历押题' : '开始项目面试' }}<PhArrowRight :size="15" /></RouterLink>
        </article>
      </div>
    </template>
    <p class="footer-note"><PhLockSimple :size="14" />资料与附件保存在本机。开始练习时，相关资料会发送给已配置的模型服务。</p>

    <Modal :open="editorOpen" :title="`${editingId ? '编辑' : '新增'}${editorLabel}`" :description="editingId ? '仅修改名称与手工填写的正文；附件请在资料详情中单独管理。' : '保存你的真实资料，为面试和复盘提供依据。'" @update:open="value => { if (!busy) editorOpen = value }">
      <form @submit.prevent="saveMaterial">
        <Notice :message="formError" kind="error" />
        <div v-if="editorKind === 'RESUME' && !editingId" class="segmented form-field" role="group" aria-label="简历录入方式">
          <button type="button" class="segment" :class="{ active: resumeMode === 'upload' }" :disabled="busy" @click="resumeMode = 'upload'; formError = ''">上传简历</button>
          <button type="button" class="segment" :class="{ active: resumeMode === 'text' }" :disabled="busy" @click="resumeMode = 'text'; formError = ''">手动填写</button>
        </div>
        <div class="form-field"><label for="material-name">{{ editorKind === 'PROJECT' ? '项目名称' : '资料名称' }}{{ uploadForm ? '（选填）' : '' }}</label><input id="material-name" v-model="name" :disabled="busy" :required="!uploadForm" maxlength="120" :placeholder="uploadForm ? '留空时使用文件名' : editorKind === 'PROJECT' ? '例如：企业知识库问答系统' : editorKind === 'JD' ? '例如：Java 后端开发岗位' : '例如：后端开发简历'" /></div>
        <div v-if="uploadForm" class="form-field"><label for="resume-file">选择简历文件</label><input id="resume-file" type="file" accept=".pdf,.docx,.md,.markdown" :disabled="busy" @change="chooseResume" /><p class="form-help">支持 PDF、DOCX、Markdown；单文件不超过 5 MiB。扫描 PDF 请先转换为可选择文字的文档。</p></div>
        <div v-else class="form-field"><label for="material-content">{{ editorKind === 'PROJECT' ? '项目简介、技术栈与个人职责' : editorKind === 'JD' ? '岗位要求' : '手工正文' }}</label><textarea id="material-content" v-model="content" :disabled="busy" maxlength="24000" rows="8" :placeholder="editorKind === 'PROJECT' ? '项目简介：解决什么问题？\n技术栈：使用了哪些技术？\n个人职责：你具体负责哪些部分？\n实现细节：遇到的难点与取舍。' : '在此粘贴完整资料，可使用多行文本。'" /><p class="form-help">{{ content.length.toLocaleString() }} / 24,000 字符{{ editorKind === 'PROJECT' && !editingId ? '。保存后可继续添加多个项目文档。' : '。附件内容会独立保留。' }}</p></div>
        <div class="dialog-actions"><button type="button" class="btn btn-secondary" :disabled="busy" @click="editorOpen = false">取消</button><button type="submit" class="btn btn-dark" :disabled="busy"><span v-if="busy" class="spinner" aria-hidden="true" />{{ busy ? '正在保存…' : uploadForm ? '上传并保存' : '保存资料' }}</button></div>
      </form>
    </Modal>

    <Modal :open="detailOpen && !!selected" :title="selected?.name || '资料详情'" description="查看已保存的完整正文与附件解析文本，继续补充或整理资料。" @update:open="value => { if (!busy) detailOpen = value }">
      <template v-if="selected">
        <Notice :message="detailError" kind="error" /><Notice :message="detailMessage" />
        <section class="material-reading"><div class="heading-row"><h3>手工正文</h3><button class="text-link reading-edit" :disabled="busy" @click="openEditor(selected)"><PhPencilSimple :size="15" />编辑正文</button></div><pre v-if="selected.content">{{ selected.content }}</pre><p v-else class="muted tiny">尚未填写手工正文{{ selected.documents.length ? '，已上传的内容见下方附件。' : '，可通过编辑补充。' }}</p></section>
        <section class="form-section"><h3>附件 <span class="muted tiny">{{ selected.documents.length }} / 20</span></h3><p v-if="!selected.documents.length" class="muted tiny material-intro">暂无附件。</p>
          <div v-for="document in selected.documents" :key="document.id" class="document-item">
            <Notice v-if="document.truncated" message="此附件文本过长，服务端已截断至前 24,000 字符。下方展示全部已保存文本；如缺少关键内容，请拆分或精简后重新上传。" kind="warning" />
            <details :open="selected.documents.length === 1"><summary><PhFileText :size="17" /><span>{{ document.name }}</span><small>查看全文</small></summary><pre>{{ document.text }}</pre></details>
            <button class="btn btn-ghost btn-sm document-delete" :disabled="busy" @click="deleteDocument(document)"><PhTrash :size="14" />删除附件</button>
          </div>
          <div v-if="selected.kind !== 'JD'" class="document-uploader"><label for="document-files">添加附件（可多选）</label><input id="document-files" type="file" :accept="selected.kind === 'RESUME' ? '.pdf,.docx,.md,.markdown' : '.pdf,.docx,.md,.markdown,.txt'" multiple :disabled="busy || selected.documents.length >= 20" @change="chooseDocuments" /><p class="form-help">{{ selected.kind === 'RESUME' ? '支持 PDF、DOCX、Markdown' : '支持 PDF、DOCX、Markdown、TXT' }}；单文件不超过 5 MiB。超长解析文本会截断并提示。</p><div v-if="pendingDocuments.length" class="pending-files"><p class="tiny muted">待上传 {{ pendingDocuments.length }} 个文件：</p><ul><li v-for="(file, index) in pendingDocuments" :key="`${file.name}-${index}`">{{ file.name }}</li></ul><button class="btn btn-secondary btn-sm" :disabled="busy" @click="uploadDocuments"><PhUploadSimple v-if="!busy" :size="16" /><span v-else class="spinner" aria-hidden="true" />{{ busy ? '正在上传…' : '上传附件' }}</button></div></div>
        </section>
        <div class="dialog-actions"><button class="btn btn-secondary" :disabled="busy" @click="detailOpen = false">关闭</button><RouterLink v-if="selected.kind !== 'JD' && !busy" class="btn btn-dark" :to="interviewRoute(selected)">{{ selected.kind === 'RESUME' ? '开始简历押题' : '开始项目面试' }}<PhArrowRight :size="16" /></RouterLink></div>
      </template>
    </Modal>
  </main>
</template>

<style scoped>
.materials-page > .page-heading { align-items: flex-start; }
.materials-page > .page-heading > .btn { margin-top: 18px; }
.material-count { font-size: 11px; margin-left: 8px; opacity: .7; }
.material-intro { margin-bottom: 18px; }
.material-card-heading, .material-meta { display: flex; align-items: center; gap: 12px; }
.material-card-heading { min-width: 0; }
.material-card .list-body h3 { font-size: 16px; }
.material-card .material-preview { height: 94px; }
.material-start { margin-top: 20px; border-top: 1px solid #edf0e8; padding-top: 17px; width: 100%; }
.material-start svg { margin-left: auto; }
.card-actions, .material-meta { flex-wrap: wrap; }
.material-reading .heading-row { margin-bottom: 13px; }
.reading-edit { border: 0; background: transparent; padding: 4px; }
.material-reading pre, .document-item pre { font-size: 13px; color: #52634d; overflow-wrap: anywhere; }
.document-item { padding: 18px 0; border-bottom: 1px solid #e2e8da; }
.document-item summary { display: flex; align-items: center; gap: 8px; color: #48623d; cursor: pointer; font-size: 13px; }
.document-item summary span { overflow-wrap: anywhere; min-width: 0; }
.document-item summary svg { flex-shrink: 0; }
.document-item summary small { margin-left: auto; color: #7e8b74; white-space: nowrap; }
.document-item details[open] summary { margin-bottom: 15px; }
.document-delete { margin-top: 9px; }
.document-uploader { margin-top: 22px; }
.pending-files { margin-top: 14px; }
.pending-files ul { padding-left: 20px; font-size: 12px; color: #63765a; line-height: 1.8; overflow-wrap: anywhere; }
@media (max-width: 560px) { .materials-page > .page-heading { flex-direction: column; gap: 8px; } .materials-page > .page-heading > .btn { margin-top: 5px; } .tabs { gap: 22px; } }
</style>
