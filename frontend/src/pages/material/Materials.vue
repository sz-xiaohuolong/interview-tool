<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { BriefcaseBusiness, ChevronDown, FileText, FolderOpen, LockKeyhole, Paperclip, Pencil, Plus, Search, Trash2, Upload } from 'lucide-vue-next'
import { DropdownMenuRoot, DropdownMenuTrigger, DropdownMenuPortal, DropdownMenuContent, DropdownMenuItem } from 'reka-ui'
import { errorMessage, post, put, request } from '../../api'
import { dateTime } from '../../format'
import type { Document, Material, MaterialKind } from '../../types'
import Modal from '../../components/common/Modal.vue'
import Notice from '../../components/common/Notice.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import SourceCodeEvidence from '../../components/interview/SourceCodeEvidence.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import FileDropzone from '../../components/material/FileDropzone.vue'
import { sourceLabel } from '../../api/job-search'

const kinds: { value: MaterialKind; label: string; description: string }[] = [
  { value: 'RESUME', label: '简历', description: '保存真实经历，让每一道押题都有依据。' },
  { value: 'PROJECT', label: '项目', description: '补充项目背景、技术选择和个人职责，准备更深入的追问。' },
  { value: 'JD', label: '目标 JD', description: '保存目标岗位要求，配置面试时可直接选用。' },
]
const materials = ref<Material[]>([])
const route = useRoute()
const requestedKind = String(route.query.kind || '')
type FilterKind = MaterialKind | 'ALL'
const kind = ref<FilterKind>(['RESUME', 'PROJECT', 'JD'].includes(requestedKind) ? requestedKind as MaterialKind : 'ALL')
const search = ref('')
const filters = [{ value: 'ALL' as const, label: '全部' }, ...kinds]
const currentKind = computed(() => kinds.find(item => item.value === kind.value))
const visibleMaterials = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return materials.value.filter(item => (kind.value === 'ALL' || item.kind === kind.value) && (!query || `${item.name}\n${item.content}`.toLocaleLowerCase().includes(query)))
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt) || a.id.localeCompare(b.id))
})
const menuOpen = ref(false)
let overviewFocus: HTMLElement | null = null
let returnToDetail = false
function rememberOverview() {
  overviewFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
}
async function restoreOverview(event: Event) {
  event.preventDefault()
  await nextTick()
  if (detailOpen.value || editorOpen.value) return
  const target = overviewFocus?.isConnected ? overviewFocus : document.querySelector<HTMLElement>('.material-add')
  target?.focus({ preventScroll: true })
  // Reka 的滚动锁负责恢复窗口位置；焦点恢复不触发额外滚动。
}
function excerpt(material: Material) {
  const text = material.content.trim() || material.documents.find(item => item.text.trim())?.text || ''
  return { text: text.replace(/\s+/g, ' ').trim().slice(0, 240), source: material.content.trim() ? '正文摘录' : '附件摘录' }
}
const repositoryLabels: Record<string, string> = { PENDING: '等待读取', READING: '正在读取', READY: '读取成功', PARTIAL: '部分可用', FAILED: '读取失败', SKIPPED: '仅使用项目资料' }
function fileSize(size: number) { return size < 1024 ? `${size} B` : size < 1024 * 1024 ? `${(size / 1024).toFixed(1)} KiB` : `${(size / 1024 / 1024).toFixed(1)} MiB` }
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
const githubUrl = ref('')
const resumeMode = ref<'upload' | 'text'>('upload')
const resumeFile = ref<File | null>(null)
const resumeSelectionError = ref('')
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
  try { materials.value = await request<Material[]>('/materials'); openRequestedMaterial() }
  catch (error) { loadError.value = errorMessage(error) }
  finally { loading.value = false }
}

function replaceMaterial(material: Material) {
  const index = materials.value.findIndex(item => item.id === material.id)
  if (index === -1) materials.value.unshift(material)
  else materials.value[index] = material
}

function showDetails(material: Material, remember = true) {
  if (remember) rememberOverview()
  selectedId.value = material.id
  pendingDocuments.value = []
  detailError.value = ''
  detailMessage.value = ''
  detailOpen.value = true
}

function openRequestedMaterial() {
  const materialId = typeof route.query.materialId === 'string' ? route.query.materialId : ''
  if (!materialId) return
  const requested = materials.value.find(item => item.id === materialId && (!route.query.kind || item.kind === route.query.kind))
  if (!requested) { detailOpen.value = false; pageError.value = '目标资料不存在或已删除，可从当前列表选择其他资料。'; return }
  pageError.value = ''; search.value = ''; kind.value = requested.kind
  showDetails(requested)
}
watch(() => [route.query.materialId, route.query.kind], () => { if (!loading.value && !loadError.value) openRequestedMaterial() })

async function openEditor(material?: Material, newKind?: MaterialKind) {
  if (busy.value || loading.value) return
  returnToDetail = detailOpen.value
  if (!returnToDetail) rememberOverview()
  menuOpen.value = false
  editingId.value = material?.id || null
  editorKind.value = material?.kind || newKind || (kind.value === 'ALL' ? 'RESUME' : kind.value)
  name.value = material?.name || ''
  content.value = material?.content || ''
  githubUrl.value = material?.repository?.url || ''
  resumeFile.value = null
  resumeSelectionError.value = ''
  resumeMode.value = 'upload'
  formError.value = ''
  detailOpen.value = false
  await nextTick()
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

function chooseResume(files: File[]) {
  if (busy.value || !files.length) return
  resumeSelectionError.value = files.length !== 1 ? '每次请选择一份简历文件。' : fileError(files, 'RESUME')
  formError.value = resumeSelectionError.value
  resumeFile.value = formError.value ? null : files[0]!
}

async function closeEditor(value: boolean) {
  if (busy.value) return
  editorOpen.value = value
  if (!value && returnToDetail && selected.value) {
    await nextTick()
    detailOpen.value = true
  }
}

async function saveMaterial() {
  if (busy.value) return
  formError.value = ''
  if (uploadForm.value) {
    if (resumeSelectionError.value) { formError.value = resumeSelectionError.value; return }
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
      const previousUrl = materials.value.find(item => item.id === editingId.value)?.repository?.url || ''
      const body = { name: name.value.trim(), content: content.value, ...(editorKind.value === 'PROJECT' && (githubUrl.value.trim() || previousUrl) ? { githubUrl: githubUrl.value.trim() } : {}) }
      material = editingId.value
        ? await put<Material>(`/materials/${editingId.value}`, body)
        : await post<Material>('/materials', { kind: editorKind.value, ...body })
    }
    replaceMaterial(material)
    if (!visibleMaterials.value.some(item => item.id === material.id)) { kind.value = material.kind; search.value = '' }
    editorOpen.value = false
    await nextTick()
    showDetails(material, false)
    detailMessage.value = '资料已保存到本机。'
  } catch (error) { formError.value = errorMessage(error) }
  finally { busy.value = false }
}

function chooseDocuments(files: File[]) {
  if (busy.value || !selected.value || !files.length) return
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
  detailError.value = ''
  try {
    await request(`/materials/${material.id}`, { method: 'DELETE' })
    materials.value = materials.value.filter(item => item.id !== material.id)
    if (selectedId.value === material.id) detailOpen.value = false
  } catch (error) { if (detailOpen.value) detailError.value = errorMessage(error); else pageError.value = errorMessage(error) }
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
      <div><h1>我的资料库</h1><p>整理你的经历、项目与目标岗位，为每次练习提供真实背景。</p></div>
      <DropdownMenuRoot v-model:open="menuOpen">
        <DropdownMenuTrigger class="btn btn-dark material-add" :disabled="busy || loading || !!loadError"><Plus :size="17" />新增资料<ChevronDown :size="15" /></DropdownMenuTrigger>
        <DropdownMenuPortal><DropdownMenuContent class="material-create-menu" align="end" :side-offset="8" @close-auto-focus="event => { if (editorOpen) event.preventDefault() }">
          <DropdownMenuItem v-for="item in kinds" :key="item.value" as-child @select="openEditor(undefined, item.value)"><button type="button">新增{{ item.label }}</button></DropdownMenuItem>
        </DropdownMenuContent></DropdownMenuPortal>
      </DropdownMenuRoot>
    </div>

    <div class="materials-toolbar">
      <div class="material-filters" role="group" aria-label="资料分类">
        <button v-for="item in filters" :key="item.value" class="material-filter" :class="{ active: kind === item.value }" :data-kind="item.value" :aria-pressed="kind === item.value" @click="kind = item.value">
          {{ item.label }}<span v-if="!loading && !loadError" class="material-count">{{ item.value === 'ALL' ? materials.length : materials.filter(material => material.kind === item.value).length }}</span>
        </button>
      </div>
      <div class="material-search"><Search :size="16" aria-hidden="true" /><label for="materials-search" class="sr-only">搜索资料名称或手工正文</label><input id="materials-search" v-model="search" type="search" placeholder="搜索名称或手工正文" :disabled="loading || !!loadError" /></div>
    </div>

    <p v-if="search.trim() && !loading && !loadError" class="material-results" role="status">匹配 {{ visibleMaterials.length }} 份资料</p>
    <Notice v-if="pageError" :message="pageError" kind="error" />
    <LoadingState v-if="loading" label="正在读取本机资料…" />
    <div v-else-if="loadError" class="panel"><Notice :message="loadError" kind="error" /><button class="btn btn-secondary" @click="loadMaterials">重新加载</button></div>
    <template v-else>
      <div v-if="!visibleMaterials.length" class="material-empty">
        <EmptyState :title="search.trim() ? '没有匹配的资料' : currentKind ? `还没有${currentKind.label}` : '还没有资料'" :description="search.trim() ? '试试其他名称或手工正文关键词，附件内容不参与搜索。' : kind === 'ALL' || kind === 'RESUME' ? '上传简历或填写正文，开始整理你的面试资料。' : kind === 'PROJECT' ? '保存项目背景和个人职责后，可继续添加附件。' : '保存目标岗位的完整要求，配置面试时可直接选用。'" />
        <button v-if="search.trim()" class="btn btn-secondary" @click="search = ''">清空搜索</button>
        <button v-else class="btn btn-secondary" @click="openEditor()">{{ kind === 'ALL' || kind === 'RESUME' ? '新增简历' : `新增${currentKind?.label}` }}</button>
      </div>
      <div v-else class="material-grid" aria-label="资料列表">
        <article v-for="material in visibleMaterials" :key="material.id" class="material-card">
          <div class="material-meta"><span class="material-type"><FileText v-if="material.kind === 'RESUME'" :size="16" /><FolderOpen v-else-if="material.kind === 'PROJECT'" :size="16" /><BriefcaseBusiness v-else :size="16" />{{ kinds.find(item => item.value === material.kind)?.label }}</span><time :datetime="material.updatedAt">{{ dateTime(material.updatedAt) }}</time></div>
          <h2><button class="material-select" :data-material-preview="material.id" :title="material.name" @click="showDetails(material)">{{ material.name }}</button></h2>
          <div class="material-excerpt"><span v-if="excerpt(material).text" class="material-excerpt-label">{{ excerpt(material).source }}</span><p>{{ excerpt(material).text || '暂无正文，可打开资料补充。' }}</p></div>
          <div class="material-assets"><span><Paperclip :size="14" />{{ material.documents.length }} 个附件</span><p v-if="material.kind === 'PROJECT'"><span v-if="material.repository">{{ material.repository.repository }} · {{ repositoryLabels[material.repository.status] || '状态待确认' }}</span><span v-else>未关联仓库</span></p></div>
          <div class="card-actions"><button class="btn btn-ghost btn-sm" :disabled="busy" @click="showDetails(material)">查看</button><button class="btn btn-ghost btn-sm" :disabled="busy" @click="openEditor(material)"><Pencil :size="14" />编辑</button><button class="btn btn-ghost btn-sm" :disabled="busy" @click="deleteMaterial(material)"><Trash2 :size="14" />删除</button></div>
        </article>
      </div>
    </template>
    <p class="footer-note materials-privacy"><LockKeyhole :size="14" />资料与附件保存在本机。开始练习时，相关资料会发送给已配置的模型服务。</p>

    <Modal :open="editorOpen" :title="`${editingId ? '编辑' : '新增'}${editorLabel}`" :description="editingId ? '仅修改名称与手工填写的正文；附件请在资料详情中单独管理。' : '保存你的真实资料，为面试和复盘提供依据。'" :busy="busy" @update:open="closeEditor" @close-auto-focus="restoreOverview">
      <form class="material-editor" @submit.prevent="saveMaterial">
        <Notice :message="formError" kind="error" />
        <div v-if="editorKind === 'RESUME' && !editingId" class="segmented form-field" role="group" aria-label="简历录入方式">
          <button type="button" class="segment" :class="{ active: resumeMode === 'upload' }" :disabled="busy" @click="resumeMode = 'upload'; formError = ''">上传简历</button>
          <button type="button" class="segment" :class="{ active: resumeMode === 'text' }" :disabled="busy" @click="resumeMode = 'text'; formError = ''">手动填写</button>
        </div>
        <div class="form-field"><label for="material-name">{{ editorKind === 'PROJECT' ? '项目名称' : '资料名称' }}{{ uploadForm ? '（选填）' : '' }}</label><input id="material-name" v-model="name" :disabled="busy" :required="!uploadForm" maxlength="120" :placeholder="uploadForm ? '留空时使用文件名' : editorKind === 'PROJECT' ? '例如：企业知识库问答系统' : editorKind === 'JD' ? '例如：Java 后端开发岗位' : '例如：后端开发简历'" /></div>
        <div v-if="uploadForm" class="form-field"><FileDropzone id="resume-file" label="选择简历文件" accept=".pdf,.docx,.md,.markdown" :disabled="busy" @files="chooseResume">支持 PDF、DOCX、Markdown；单文件不超过 5 MiB。</FileDropzone><p class="form-help">扫描 PDF 请先转换为可选择文字的文档。</p><div v-if="resumeFile" class="pending-files"><p>待上传：{{ resumeFile.name }} <span class="muted">{{ resumeFile.name.split('.').pop()?.toUpperCase() }} · {{ fileSize(resumeFile.size) }}</span></p><button type="button" class="btn btn-ghost btn-sm" :disabled="busy" @click="resumeFile = null">取消选择</button></div></div>
        <div v-else class="form-field"><label for="material-content">{{ editorKind === 'PROJECT' ? '项目简介、技术栈与个人职责' : editorKind === 'JD' ? '岗位要求' : '手工正文' }}</label><textarea id="material-content" v-model="content" :disabled="busy" maxlength="24000" rows="8" :placeholder="editorKind === 'PROJECT' ? '项目简介：解决什么问题？\n技术栈：使用了哪些技术？\n个人职责：你具体负责哪些部分？\n实现细节：遇到的难点与取舍。' : '在此粘贴完整资料，可使用多行文本。'" /><p class="form-help">{{ content.length.toLocaleString() }} / 24,000 字符{{ editorKind === 'PROJECT' && !editingId ? '。保存后可继续添加多个项目文档。' : '。附件内容会独立保留。' }}</p></div>
        <div v-if="editorKind === 'PROJECT'" class="form-field"><label for="material-github">GitHub 公开仓库（可选）</label><input id="material-github" v-model="githubUrl" inputmode="url" placeholder="https://github.com/所有者/仓库" maxlength="300" :disabled="busy" /><p class="form-help">源码补充项目资料，仍需填写真实项目说明。每场准备时锁定代码版本，源码仅在复盘展示。</p><p v-if="busy && githubUrl.trim()" role="status" class="form-help">正在核实公开仓库与代码版本…</p></div>
        <div class="dialog-actions"><button type="button" class="btn btn-secondary" :disabled="busy" @click="closeEditor(false)">取消</button><button type="submit" class="btn btn-dark" :disabled="busy"><span v-if="busy" class="spinner" aria-hidden="true" />{{ busy ? '正在保存…' : uploadForm ? '上传并保存' : '保存资料' }}</button></div>
      </form>
    </Modal>

    <Modal variant="drawer" :busy="busy" :open="detailOpen && !!selected" :title="selected?.name || '资料详情'" description="查看已保存的完整正文与附件解析文本，继续补充或整理资料。" @update:open="value => { if (!busy) detailOpen = value }" @close-auto-focus="restoreOverview">
      <section v-if="selected" class="material-detail" aria-label="资料内容预览">
        <p class="material-detail-meta">{{ kinds.find(item => item.value === selected?.kind)?.label }} · 更新于 {{ dateTime(selected.updatedAt) }}</p>
        <Notice :message="detailError" kind="error" /><Notice :message="detailMessage" />
        <section v-if="selected.jobSource" class="material-job-source" aria-label="岗位来源信息"><p>原站来源：{{ sourceLabel(selected.jobSource.source) }} · 原获取时间 {{ dateTime(selected.jobSource.fetchedAt) }}</p><p>{{ selected.jobSource.contentEdited ? '正文已由用户修改，保留原来源信息。' : '正文与获取时的原始内容一致。' }}</p><a v-if="selected.jobSource.url" data-job-source-link class="text-link" :href="selected.jobSource.url" target="_blank" rel="noopener noreferrer">查看原岗位（新标签打开）</a></section>
        <SourceCodeEvidence v-if="selected.kind === 'PROJECT'" :source="selected.repository" /><p v-if="selected.kind === 'PROJECT' && !selected.repository" class="form-help">未关联 GitHub 仓库，可编辑资料补充。</p>
        <section class="material-reading"><div class="heading-row"><h3>手工正文</h3><button class="text-link reading-edit" :disabled="busy" @click="openEditor(selected)"><Pencil :size="15" />编辑正文</button></div><pre v-if="selected.content">{{ selected.content }}</pre><p v-else class="muted tiny">尚未填写手工正文{{ selected.documents.length ? '，已上传的内容见下方附件。' : '，可通过编辑补充。' }}</p></section>
        <section class="form-section"><h3>附件 <span class="muted tiny">{{ selected.documents.length }} / 20</span></h3><p v-if="!selected.documents.length" class="muted tiny material-intro">暂无附件。</p>
          <div v-for="document in selected.documents" :key="document.id" class="document-item">
            <Notice v-if="document.truncated" message="此附件文本过长，服务端已截断至前 24,000 字符。下方展示全部已保存文本；如缺少关键内容，请拆分或精简后重新上传。" kind="warning" />
            <details :open="selected.documents.length === 1"><summary><FileText :size="17" /><span>{{ document.name }}</span><small>查看全文</small></summary><pre>{{ document.text }}</pre></details>
            <button class="btn btn-ghost btn-sm document-delete" :disabled="busy" @click="deleteDocument(document)"><Trash2 :size="14" />删除附件</button>
          </div>
          <div v-if="selected.kind !== 'JD'" class="document-uploader"><FileDropzone id="document-files" label="添加附件（可多选）" :accept="selected.kind === 'RESUME' ? '.pdf,.docx,.md,.markdown' : '.pdf,.docx,.md,.markdown,.txt'" multiple :disabled="busy || selected.documents.length >= 20" @files="chooseDocuments">{{ selected.kind === 'RESUME' ? '支持 PDF、DOCX、Markdown' : '支持 PDF、DOCX、Markdown、TXT' }}；单文件不超过 5 MiB。</FileDropzone><p class="form-help">超长解析文本会截断并提示。</p><div v-if="pendingDocuments.length" class="pending-files"><p class="tiny muted">待上传 {{ pendingDocuments.length }} 个文件：</p><ul><li v-for="(file, index) in pendingDocuments" :key="`${file.name}-${index}`">{{ file.name }} <span class="muted">{{ file.name.split('.').pop()?.toUpperCase() }} · {{ fileSize(file.size) }}</span></li></ul><div class="pending-actions"><button class="btn btn-secondary btn-sm" :disabled="busy" @click="uploadDocuments"><Upload v-if="!busy" :size="16" /><span v-else class="spinner" aria-hidden="true" />{{ busy ? '正在上传…' : '上传附件' }}</button><button class="btn btn-ghost btn-sm" :disabled="busy" @click="pendingDocuments = []">取消选择</button></div></div></div>
        </section>
      </section>
      <template v-if="selected" #footer><div class="material-detail-actions"><button class="btn btn-secondary" :disabled="busy" @click="detailOpen = false">关闭</button><RouterLink v-if="selected.kind !== 'JD' && !busy" class="btn btn-secondary" :to="interviewRoute(selected)">{{ selected.kind === 'RESUME' ? '开始简历押题' : '开始项目面试' }}</RouterLink></div></template>
    </Modal>
  </main>
</template>

<style>
.material-create-menu { z-index:var(--layer-dialog); min-width:180px; padding:var(--space-1); border:1px solid var(--border-subtle); border-radius:var(--radius-panel); background:var(--surface); box-shadow:0 8px 24px #0f172a14; }
.material-create-menu button { display:block; width:100%; min-height:36px; border:0; border-radius:var(--radius-control); background:transparent; padding:var(--space-2) var(--space-3); text-align:left; color:var(--text-secondary); }
.material-create-menu [data-highlighted] { outline:none; background:var(--accent-soft); color:var(--accent-ink); }
@media(max-width:768px) { .material-create-menu button { min-height:44px; } }
@media(max-width:768px) { .dialog-content:has(.material-editor) .icon-button { min-width:44px; min-height:44px; } }
</style>
<style scoped>
.materials-page { --material-reading-size:14px; --material-meta-size:12px; --material-card-min:280px; }
.materials-page > .page-heading { align-items:flex-start; margin-bottom:var(--space-6); }
.material-add { min-height:36px; flex-shrink:0; }
.materials-toolbar { display:flex; align-items:center; flex-wrap:wrap; gap:var(--space-4); justify-content:space-between; margin-bottom:var(--space-6); }
.material-filters { display:flex; flex-wrap:wrap; gap:var(--space-1); }
.material-filter { display:flex; align-items:center; gap:var(--space-2); min-height:36px; padding:var(--space-2) var(--space-3); border:1px solid transparent; border-radius:var(--radius-control); background:transparent; color:var(--text-secondary); }
.material-filter:hover { background:var(--surface-soft); }.material-filter.active { color:var(--accent-ink); background:var(--accent-soft); }
.material-count { font-size:var(--material-meta-size); font-variant-numeric:tabular-nums; }
.material-results { margin-bottom:var(--space-4); color:var(--text-muted); font-size:var(--material-meta-size); }
.material-search { flex:0 1 280px; min-width:220px; position:relative; }.material-search svg { position:absolute; left:12px; top:50%; transform:translateY(-50%); color:var(--text-muted); pointer-events:none; }.material-search input { padding-left:36px; min-height:36px; }
.material-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(max(min(100%,var(--material-card-min)),calc((100% - 2 * var(--space-4)) / 3)),1fr)); gap:var(--space-4); }
.material-card { display:flex; flex-direction:column; min-width:0; min-height:280px; padding:var(--space-5); border:1px solid var(--border-subtle); border-radius:var(--radius-panel); background:var(--surface); }
.material-card:hover { border-color:var(--border-control); }
.material-meta { display:flex; align-items:center; justify-content:space-between; gap:var(--space-2); color:var(--text-muted); font-size:var(--material-meta-size); margin-bottom:var(--space-3); }
.material-type { display:flex; align-items:center; gap:var(--space-2); }.material-meta time { white-space:nowrap; }
.material-card h2 { margin-bottom:var(--space-3); }.material-select { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden; width:100%; border:0; padding:0; text-align:left; background:transparent; color:var(--text-primary); font-size:16px; line-height:1.5; font-weight:600; overflow-wrap:anywhere; }.material-select:hover { color:var(--accent-ink); }
.material-excerpt { margin-bottom:var(--space-4); }.material-excerpt-label { display:block; margin-bottom:var(--space-1); font-size:var(--material-meta-size); color:var(--text-muted); }.material-excerpt p { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:3; overflow:hidden; font-size:var(--material-reading-size); line-height:1.75; color:var(--text-secondary); }
.material-assets { margin-top:auto; padding-bottom:var(--space-3); font-size:var(--material-meta-size); color:var(--text-muted); }.material-assets > span { display:flex; align-items:center; gap:var(--space-1); }.material-assets p { margin-top:var(--space-1); overflow-wrap:anywhere; }
.card-actions { display:flex; flex-wrap:wrap; gap:var(--space-1); padding-top:var(--space-2); border-top:1px solid var(--border-subtle); }.card-actions .btn { min-height:36px; padding-inline:var(--space-2); font-size:var(--material-meta-size); }.card-actions .btn:first-child { color:var(--accent-ink); }.card-actions .btn:last-child { margin-left:auto; }
.material-empty { padding:var(--space-8) var(--space-4); border-block:1px solid var(--border-subtle); text-align:center; }.material-empty :deep(.empty-state) { border:0; padding:var(--space-6); }.materials-privacy { margin-top:var(--space-6); font-size:12px; }
.material-detail { min-width:0; }.material-detail-meta { color:var(--text-muted); font-size:12px; margin-bottom:var(--space-5); }.material-detail-actions { display:flex; justify-content:space-between; flex-wrap:wrap; gap:var(--space-2); }.material-detail-actions .btn { min-height:36px; }
.material-job-source { margin-bottom:var(--space-5); padding-bottom:var(--space-4); border-bottom:1px solid var(--border-subtle); font-size:14px; line-height:1.75; overflow-wrap:anywhere; }.material-job-source p { margin-bottom:var(--space-2); color:var(--text-secondary); }
.material-reading .heading-row { margin-bottom:var(--space-3); }.reading-edit { border:0; background:transparent; padding:var(--space-2); min-height:36px; flex-shrink:0; }
.material-reading pre,.document-item pre { font-size:14px; line-height:1.75; max-width:72ch; color:var(--text-secondary); overflow-wrap:anywhere; }.material-reading > p { font-size:14px; }
.document-item { padding:var(--space-3) 0; border-bottom:1px solid var(--border-subtle); }.document-item summary { display:flex; align-items:center; gap:var(--space-2); min-height:36px; font-size:14px; color:var(--accent-ink); }.document-item summary span { overflow-wrap:anywhere; min-width:0; }.document-item summary small { margin-left:auto; color:var(--text-muted); white-space:nowrap; }.document-item details[open] summary { margin-bottom:var(--space-3); }.document-delete { margin-top:var(--space-2); }.document-uploader { margin-top:var(--space-4); }.pending-files { margin-top:var(--space-3); font-size:14px; overflow-wrap:anywhere; }.pending-files ul { padding-left:var(--space-5); font-size:12px; color:var(--text-secondary); }.pending-actions { display:flex; flex-wrap:wrap; gap:var(--space-2); margin-top:var(--space-3); }
@media(max-width:768px) { .materials-page .btn,.material-editor .btn,.material-editor .segment,.material-detail .btn,.material-detail-actions .btn,.reading-edit,.document-item summary,.material-filter { min-height:44px; }.materials-toolbar { gap:var(--space-3); }.material-search { flex-basis:100%; }.material-search input { min-height:44px; }.material-card { padding:var(--space-4); }.material-editor input:not([type=file]),.material-editor textarea { font-size:16px; } }
@media(max-width:560px) { .materials-page > .page-heading { flex-direction:column; gap:var(--space-3); }.material-filter { padding-inline:var(--space-2); } }
</style>
