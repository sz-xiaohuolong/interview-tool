<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { Plus } from 'lucide-vue-next'
import Modal from '../../components/common/Modal.vue'
import Notice from '../../components/common/Notice.vue'
import LoadingState from '../../components/common/LoadingState.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import JobApplicationForm from '../../components/jobs/JobApplicationForm.vue'
import { errorMessage } from '../../api'
import { downloadJobCsv, importJobCsv, previewJobCsv, type JobCsvPreview, displayJobTime, jobApplications, jobStatuses, jobTypes, type JobApplication, type JobStatus } from '../../api/job-applications'
const rows = ref<JobApplication[]>([]), loading = ref(true), loadError = ref(''), actionError = ref(''), message = ref('')
const company = ref(''), status = ref(''), archived = ref('false'), sorting = ref('updatedAt:desc')
const formOpen = ref(false), editing = ref<JobApplication | null>(null), detail = ref<JobApplication | null>(null), removing = ref<JobApplication | null>(null)
const deleting = ref(false), deleteError = ref(''), busyIds = ref(new Set<string>())
const fileInput = ref<HTMLInputElement | null>(null), csvFile = ref<File | null>(null), csvPreview = ref<JobCsvPreview | null>(null)
const csvBusy = ref(false), csvError = ref(''), csvVisible = ref(false), downloading = ref(false)
async function selectCsv(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || csvBusy.value) return
  if (fileInput.value) fileInput.value.value = ''
  csvVisible.value = true; csvError.value = ''; csvPreview.value = null; csvFile.value = file
  if (!file.size || file.size > 5 * 1024 * 1024) { csvError.value = file.size ? 'CSV 不能超过 5 MiB' : 'CSV 文件为空，请填写模板后重新选择'; return }
  csvBusy.value = true
  try { csvPreview.value = await previewJobCsv(file) }
  catch (e) { csvError.value = errorMessage(e) }
  finally { csvBusy.value = false; if (fileInput.value) fileInput.value.value = '' }
}
async function confirmImport() {
  if (!csvFile.value || !csvPreview.value || csvPreview.value.errors.length || csvBusy.value) return
  csvBusy.value = true; csvError.value = ''
  try {
    const result = await importJobCsv(csvFile.value, csvPreview.value.previewToken)
    csvVisible.value = false; csvFile.value = null; csvPreview.value = null
    message.value = `已导入 ${result.created} 条岗位，跳过 ${result.duplicates} 条重复记录`; await load()
  } catch (e) { csvError.value = errorMessage(e) }
  finally { csvBusy.value = false }
}
async function download(kind: 'template' | 'export') {
  if (downloading.value) return
  downloading.value = true; actionError.value = ''
  try { await downloadJobCsv(kind) }
  catch (e) { actionError.value = errorMessage(e) }
  finally { downloading.value = false }
}
const hasFilters = computed(() => !!company.value || !!status.value)
let requestVersion = 0
async function load() {
  const version = ++requestVersion
  loading.value = true; loadError.value = ''
  const [sort, direction] = sorting.value.split(':')
  try {
    const result = await jobApplications.list({ company: company.value, status: status.value, archived: archived.value, sort: sort!, direction: direction! })
    if (version === requestVersion) rows.value = result
  } catch (e) { if (version === requestVersion) loadError.value = errorMessage(e) }
  finally { if (version === requestVersion) loading.value = false }
}
watch([company, status, archived, sorting], load)
onMounted(load)
onBeforeUnmount(() => { requestVersion++ })
function edit(row: JobApplication | null) { editing.value = row; detail.value = null; formOpen.value = true }
async function saved() { formOpen.value = false; message.value = '岗位已保存'; await load() }
async function changeStatus(row: JobApplication, event: Event) {
  const select = event.target as HTMLSelectElement
  const value = select.value as JobStatus
  select.value = row.status
  if (busyIds.value.has(row.id)) return
  busyIds.value.add(row.id); actionError.value = ''; message.value = ''
  try { await jobApplications.status(row.id, value); message.value = '投递状态已更新'; await load() }
  catch (e) { actionError.value = errorMessage(e) }
  finally { busyIds.value.delete(row.id) }
}
async function toggleArchive(row: JobApplication) {
  if (busyIds.value.has(row.id)) return
  busyIds.value.add(row.id); actionError.value = ''; message.value = ''
  try { await jobApplications.archive(row.id, !row.archived); message.value = row.archived ? '岗位已恢复' : '岗位已归档'; await load() }
  catch (e) { actionError.value = errorMessage(e) }
  finally { busyIds.value.delete(row.id) }
}
function confirmDelete(row: JobApplication) { removing.value = row; deleteError.value = '' }
async function remove() {
  if (!removing.value || deleting.value) return
  deleting.value = true; deleteError.value = ''
  try { await jobApplications.remove(removing.value.id); removing.value = null; message.value = '岗位已删除'; await load() }
  catch (e) { deleteError.value = errorMessage(e) }
  finally { deleting.value = false }
}
function clearFilters() { company.value = ''; status.value = '' }
function safeUrl(url: string) { try { const parsed = new URL(url); return ['https:', 'http:'].includes(parsed.protocol) && !parsed.username && !parsed.password ? url : undefined } catch { return undefined } }
</script>
<template>
  <section class="page jobs-page">
    <header class="page-heading heading-row"><div><h1>求职管理</h1><p>记录每一次投递，安排下一步行动。</p></div><button type="button" class="btn btn-primary" @click="edit(null)"><Plus :size="16" aria-hidden="true" />新增岗位</button></header>
    <Notice :message="actionError" kind="error" /><Notice :message="message" />
    <div class="jobs-toolbar"><div class="segmented" aria-label="归档筛选"><button v-for="option in [{ value: 'false', label: '未归档' }, { value: 'true', label: '已归档' }, { value: 'all', label: '全部' }]" :key="option.value" class="segment" :class="{ active: archived === option.value }" :aria-pressed="archived === option.value" @click="archived = option.value">{{ option.label }}</button></div><div class="jobs-csv-actions"><input ref="fileInput" data-job-file type="file" accept=".csv,text/csv" hidden @change="selectCsv" /><button class="btn btn-ghost" data-job-template :disabled="downloading" @click="download('template')">CSV 模板</button><button class="btn btn-secondary" data-job-import :disabled="csvBusy" @click="fileInput?.click()">导入 CSV</button><button class="btn btn-secondary" :disabled="downloading" @click="download('export')">导出全部</button></div></div>
    <details class="jobs-csv-help"><summary>CSV 填写与恢复说明</summary>
      <p>保留模板全部列与顺序，每行「格式」填写「投递记录-1」。公司名称、岗位名称必填，其他业务字段可留空；状态默认待投递，岗位类型默认其他。</p>
      <p>日期填写 YYYY-MM-DD；面试时间填写带时区的 ISO 时间，例如 2026-10-24T10:30:00+08:00。已归档填 true 或 false，留空为 false。</p>
      <p>状态：{{ jobStatuses.map(s => `${s.value}（${s.label}）`).join('、') }}。岗位类型：{{ jobTypes.map(t => `${t.value}（${t.label}）`).join('、') }}。</p>
      <p>新记录的 ID、创建时间、修改时间可留空。恢复备份时保留它们；相同记录跳过，同 ID 不同内容会提示冲突，不覆盖已有记录。</p>
      <p>导出中的「文本:」是公式保护前缀，导入时去掉一层。不要删除备份中的前缀；手工内容若本来以「文本:」开头，请再加一层。中文、逗号、引号和换行按 CSV 标准保留。</p>
      <p>使用 UTF-8 编码，每个文件最多 5 MiB、5000 条。导出包含所有岗位及已归档记录，不受当前筛选影响；超限备份可保留表头分成多个文件导入。</p>
    </details>
    <section v-if="csvVisible" class="jobs-csv-preview" aria-label="CSV 导入预览">
      <div class="heading-row"><h2>导入预览</h2><button class="btn btn-ghost" :disabled="csvBusy" @click="csvVisible = false">关闭预览</button></div>
      <p class="form-help">{{ csvFile?.name }}。最多 5 MiB、5000 条；预览不会写入，确认后新增，重复记录跳过。</p>
      <Notice :message="csvError" kind="error" />
      <LoadingState v-if="csvBusy" :label="csvPreview ? '正在导入…' : '正在检查 CSV…'" />
      <template v-else-if="csvPreview"><p class="jobs-csv-summary">共 {{ csvPreview.total }} 条 · 新增 {{ csvPreview.created }} 条 · 重复 {{ csvPreview.duplicates }} 条 · 冲突 {{ csvPreview.conflicts }} 条</p>
        <Notice v-if="csvPreview.errors.length" :message="`存在 ${csvPreview.errors.length} 行错误，本次不会写入。请修正文件后重新选择。`" kind="error" />
        <ul v-if="csvPreview.errors.length" class="jobs-csv-errors"><li v-for="error in csvPreview.errors" :key="error.line">第 {{ error.line }} 条：{{ error.message }}</li></ul>
        <details v-if="csvPreview.rows?.length" class="jobs-csv-rows"><summary>查看逐条结果（{{ csvPreview.rows.length }} 条）</summary><ol><li v-for="row in csvPreview.rows" :key="row.line">第 {{ row.line }} 条 · {{ row.company }} / {{ row.position }}：{{ row.message }}</li></ol></details>
        <button v-if="!csvPreview.errors.length" data-job-import-confirm class="btn btn-primary" :disabled="csvBusy || !csvPreview.created" @click="confirmImport">确认导入 {{ csvPreview.created }} 条</button><p v-if="!csvPreview.errors.length && !csvPreview.created" class="form-help">全部记录已存在，无需重复导入。</p>
      </template>
      <button v-if="csvError || csvPreview?.errors.length" class="btn btn-secondary" :disabled="csvBusy" @click="fileInput?.click()">重新选择文件</button>
    </section>
    <div class="jobs-filters">
      <div><label for="job-search">搜索公司</label><input id="job-search" v-model="company" type="search" maxlength="200" placeholder="输入公司名称" /></div>
      <div><label for="job-filter-status">投递状态</label><select id="job-filter-status" v-model="status"><option value="">全部状态</option><option v-for="item in jobStatuses" :key="item.value" :value="item.value">{{ item.label }}</option></select></div>
      <div><label for="job-sort">日期排序</label><select id="job-sort" v-model="sorting"><option value="updatedAt:desc">最近更新</option><option value="createdAt:desc">最近创建</option><option value="appliedDate:desc">投递日期 · 从晚到早</option><option value="appliedDate:asc">投递日期 · 从早到晚</option><option value="nextActionDate:asc">下次行动 · 从早到晚</option><option value="nextActionDate:desc">下次行动 · 从晚到早</option><option value="interviewAt:asc">面试时间 · 从早到晚</option><option value="interviewAt:desc">面试时间 · 从晚到早</option></select></div>
    </div>
    <LoadingState v-if="loading" label="正在读取岗位记录…" />
    <div v-else-if="loadError"><Notice :message="loadError" kind="error" /><button class="btn btn-secondary" @click="load">重新加载</button></div>
    <EmptyState v-else-if="!rows.length" :title="hasFilters ? '没有匹配的岗位' : archived === 'true' ? '还没有归档岗位' : '还没有岗位记录'" :description="hasFilters ? '试试其他公司名称或投递状态。' : archived === 'true' ? '归档只收起记录，不改变投递状态。' : '点击右上角「新增岗位」，从公司和岗位开始。'"><button v-if="hasFilters" class="btn btn-secondary" @click="clearFilters">清除筛选</button></EmptyState>
    <template v-else>
      <p class="jobs-count" aria-live="polite">{{ rows.length }} 条记录<span v-if="archived === 'false'"> · 不含已归档</span></p>
      <div class="jobs-table-wrap"><table class="jobs-table"><caption class="sr-only">求职岗位列表</caption><thead><tr><th scope="col">公司 / 岗位</th><th scope="col">工作地点</th><th scope="col">招聘平台</th><th scope="col">投递状态</th><th scope="col">投递日期</th><th scope="col">下次行动</th><th scope="col">操作</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id" :data-job-id="row.id">
          <td class="job-identity"><button data-job-detail class="job-name" @click="detail = row"><strong>{{ row.company }}</strong><span>{{ row.position }}</span></button><span v-if="row.archived" class="badge badge-gray">已归档</span></td>
          <td data-label="工作地点">{{ row.location || '—' }}</td><td data-label="招聘平台">{{ row.platform || '—' }}</td>
          <td data-label="投递状态"><select data-job-status :value="row.status" :aria-label="`${row.company} ${row.position}的投递状态`" :disabled="busyIds.has(row.id)" :class="['job-status', `status-${row.status}`]" @change="changeStatus(row, $event)"><option v-for="item in jobStatuses" :key="item.value" :value="item.value">{{ item.label }}</option></select></td>
          <td data-label="投递日期" class="job-date">{{ row.appliedDate || '—' }}</td><td data-label="下次行动" class="job-date">{{ row.nextActionDate || '—' }}</td>
          <td class="job-actions"><button class="job-action" :disabled="busyIds.has(row.id)" @click="edit(row)">编辑</button><button class="job-action" :disabled="busyIds.has(row.id)" @click="toggleArchive(row)">{{ row.archived ? '恢复' : '归档' }}</button><button class="job-action job-delete" :disabled="busyIds.has(row.id)" @click="confirmDelete(row)">删除</button></td>
        </tr>
      </tbody></table></div>
    </template>
    <JobApplicationForm v-if="formOpen" :record="editing" @close="formOpen = false" @saved="saved" />
    <Modal :open="!!detail" :title="detail?.company || '岗位详情'" :description="detail?.position" variant="drawer" @update:open="detail = null">
      <template v-if="detail"><dl class="job-facts"><div><dt>投递状态</dt><dd>{{ jobStatuses.find(s => s.value === detail?.status)?.label }}<span v-if="detail.archived">（已归档）</span></dd></div><div><dt>岗位类型</dt><dd>{{ jobTypes.find(t => t.value === detail?.jobType)?.label }}</dd></div><div><dt>工作地点</dt><dd>{{ detail.location || '未填写' }}</dd></div><div><dt>招聘平台</dt><dd>{{ detail.platform || '未填写' }}</dd></div><div><dt>投递日期</dt><dd>{{ detail.appliedDate || '未填写' }}</dd></div><div><dt>下次行动日期</dt><dd>{{ detail.nextActionDate || '未填写' }}</dd></div></dl>
        <a v-if="safeUrl(detail.url)" :href="safeUrl(detail.url)" target="_blank" rel="noopener noreferrer" class="text-link">打开原始岗位链接</a>
        <section class="job-reading"><h2>面试安排</h2><p>{{ displayJobTime(detail.interviewAt) }}<span v-if="detail.interviewAt" class="muted">（本地时间）</span></p><p v-if="detail.interviewNote">{{ detail.interviewNote }}</p></section>
        <section class="job-reading"><h2>JD 正文</h2><p>{{ detail.jd || '尚未填写岗位描述' }}</p></section><section class="job-reading"><h2>备注</h2><p>{{ detail.notes || '尚未填写备注' }}</p></section>
        <p class="form-help">创建于 {{ displayJobTime(detail.createdAt) }}<br />最近更新 {{ displayJobTime(detail.updatedAt) }}</p>
      </template>
      <template #footer><button v-if="detail" class="btn btn-primary" @click="edit(detail)">编辑岗位</button></template>
    </Modal>
    <Modal :open="!!removing" title="删除岗位记录" description="删除后无法在页面恢复，请确认是否已备份需要保留的内容。" :busy="deleting" @update:open="value => { if (!value && !deleting) removing = null }">
      <p class="job-delete-target">{{ removing?.company }} · {{ removing?.position }}</p><Notice :message="deleteError" kind="error" />
      <div class="dialog-actions"><button class="btn btn-secondary" :disabled="deleting" @click="removing = null">取消</button><button class="btn btn-danger" :disabled="deleting" @click="remove">{{ deleting ? '正在删除…' : '确认删除' }}</button></div>
    </Modal>
  </section>
</template>
<style scoped>
.jobs-csv-help { margin-bottom:var(--space-4); color:var(--text-muted); font-size:12px; }.jobs-csv-help summary { cursor:pointer; min-height:32px; display:list-item; padding-block:6px; }.jobs-csv-help p { font-size:14px; line-height:1.8; max-width:90ch; margin:var(--space-2) 0; overflow-wrap:anywhere; }
.jobs-csv-actions { display:flex; gap:var(--space-2); flex-wrap:wrap; }.jobs-csv-preview { background:var(--surface-soft); border-block:1px solid var(--border-subtle); padding:var(--space-4); margin-bottom:var(--space-5); }.jobs-csv-preview h2 { font-size:16px; }.jobs-csv-summary { font-size:14px; margin:var(--space-3) 0; }.jobs-csv-errors,.jobs-csv-rows ol { padding-left:20px; margin:var(--space-3) 0; font-size:14px; max-height:240px; overflow-y:auto; overflow-wrap:anywhere; }.jobs-csv-rows { margin-bottom:var(--space-4); font-size:14px; }.jobs-csv-rows summary { cursor:pointer; }.jobs-csv-preview .form-help { overflow-wrap:anywhere; }
.jobs-page { max-width:1520px; }.jobs-page .page-heading { align-items:flex-start; }.jobs-page .page-heading .btn { flex-shrink:0; }.jobs-toolbar { display:flex; align-items:center; justify-content:space-between; gap:var(--space-4); flex-wrap:wrap; margin-bottom:var(--space-4); }
.jobs-filters { display:grid; grid-template-columns:minmax(180px,1fr) minmax(140px,190px) minmax(180px,230px); gap:var(--space-3); margin-bottom:var(--space-4); }.jobs-filters > div { min-width:0; }.jobs-filters input,.jobs-filters select { font-size:14px; }.jobs-count { color:var(--text-muted); font-size:12px; padding-bottom:var(--space-3); }
.jobs-table-wrap { max-width:100%; overflow-x:auto; }.jobs-table { border-collapse:collapse; width:100%; font-size:14px; table-layout:fixed; }.jobs-table th { text-align:left; font-weight:500; font-size:12px; color:var(--text-muted); background:var(--surface-soft); padding:var(--space-3) 10px; border-block:1px solid var(--border-subtle); }.jobs-table td { border-bottom:1px solid var(--border-subtle); padding:var(--space-4) 10px; vertical-align:middle; overflow-wrap:anywhere; }.jobs-table th:first-child { width:24%; }.jobs-table th:nth-child(2),.jobs-table th:nth-child(3) { width:10%; }.jobs-table th:nth-child(4) { width:150px; }.jobs-table th:last-child { width:140px; }.jobs-table tbody tr:hover { background:var(--surface-soft); }
.job-name { display:block; text-align:left; background:none; border:0; padding:0; width:100%; color:var(--text-primary); cursor:pointer; line-height:1.65; overflow-wrap:anywhere; }.job-name strong { display:block; font-weight:600; }.job-name span { display:block; color:var(--text-secondary); margin-top:2px; }.job-name:hover strong { color:var(--accent-ink); text-decoration:underline; }.job-identity .badge { margin-top:6px; }.job-date { font-variant-numeric:tabular-nums; font-size:13px; }.job-status { min-width:0; width:100%; font-size:13px; padding:6px; background:var(--surface); }.status-OFFER { color:var(--success); }.status-INTERVIEW { color:var(--accent-ink); }.status-REJECTED { color:var(--danger); }.status-WITHDRAWN { color:var(--text-muted); }
.job-action { padding:7px 4px; border:0; background:none; color:var(--accent-ink); font-size:13px; cursor:pointer; }.job-action:hover { text-decoration:underline; }.job-delete { color:var(--text-muted); }.job-delete:hover { color:var(--danger); }.job-action:disabled { cursor:wait; opacity:.5; }
.job-facts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--space-4); margin:0 0 var(--space-5); font-size:14px; }.job-facts dt { color:var(--text-muted); font-size:12px; }.job-facts dd { margin:4px 0 0; overflow-wrap:anywhere; }.job-reading { border-top:1px solid var(--border-subtle); margin-top:var(--space-5); padding-top:var(--space-4); }.job-reading h2 { font-size:14px; margin-bottom:var(--space-3); }.job-reading p { font-size:14px; line-height:1.8; white-space:pre-wrap; overflow-wrap:anywhere; margin-bottom:var(--space-3); }.job-delete-target { font-size:14px; overflow-wrap:anywhere; }
@media(min-width:701px) and (max-width:1150px) { .jobs-table { min-width:850px; } }
@media(max-width:700px) {
  .jobs-filters { grid-template-columns:1fr 1fr; }.jobs-filters > div:first-child { grid-column:1 / -1; }.jobs-filters input,.jobs-filters select { font-size:16px; }.jobs-table,.jobs-table tbody { display:block; }.jobs-table thead { position:absolute; width:1px; height:1px; overflow:hidden; clip-path:inset(50%); }.jobs-table tr { display:grid; grid-template-columns:1fr 1fr; gap:var(--space-3); border-top:1px solid var(--border-subtle); padding:var(--space-4) 0; }.jobs-table td { display:block; border:0; padding:0; }.jobs-table td[data-label]::before { content:attr(data-label); display:block; color:var(--text-muted); font-size:12px; margin-bottom:4px; }.jobs-table .job-identity { grid-column:1 / -1; }.jobs-table .job-actions { grid-column:1 / -1; display:flex; gap:var(--space-4); border-top:1px solid var(--border-subtle); padding-top:4px; }.job-action { min-height:44px; padding-inline:8px; }.job-status { min-height:44px; font-size:16px; }.job-facts { gap:var(--space-4) var(--space-3); }
}
</style>
