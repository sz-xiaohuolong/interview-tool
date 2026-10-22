<script setup lang="ts">
import { reactive, ref } from 'vue'
import Modal from '../common/Modal.vue'
import Notice from '../common/Notice.vue'
import { errorMessage } from '../../api'
import { jobApplications, jobStatuses, jobTypes, localDateTime, type JobApplication, type JobInput } from '../../api/job-applications'
const props = defineProps<{ record: JobApplication | null }>()
const emit = defineEmits<{ close: []; saved: [record: JobApplication] }>()
const form = reactive<JobInput>({ company: '', position: '', location: '', platform: '', url: '', jd: '', jobType: 'OTHER', status: 'TO_APPLY', appliedDate: null, nextActionDate: null, interviewAt: null, interviewNote: '', notes: '' })
if (props.record) for (const key of Object.keys(form) as (keyof JobInput)[]) Object.assign(form, { [key]: props.record[key] })
const interviewLocal = ref(localDateTime(form.interviewAt))
const originalLocal = interviewLocal.value
const busy = ref(false), error = ref('')
const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone
function close() { if (!busy.value) emit('close') }
async function save() {
  if (busy.value) return
  error.value = ''
  if (!form.company.trim() || !form.position.trim()) { error.value = '请填写公司名称和岗位名称'; return }
  let interviewAt = form.interviewAt
  if (interviewLocal.value !== originalLocal) {
    const parsed = new Date(interviewLocal.value)
    if (interviewLocal.value && (Number.isNaN(parsed.getTime()) || localDateTime(parsed.toISOString()).slice(0, interviewLocal.value.length) !== interviewLocal.value)) {
      error.value = '面试时间无效，请核对本地日期和时间'; return
    }
    interviewAt = interviewLocal.value ? parsed.toISOString() : null
  }
  busy.value = true
  try {
    const input = { ...form, appliedDate: form.appliedDate || null, nextActionDate: form.nextActionDate || null, interviewAt }
    const saved = props.record ? await jobApplications.update(props.record.id, input) : await jobApplications.create(input)
    emit('saved', saved)
  } catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
</script>
<template>
  <Modal :open="true" :title="record ? '编辑岗位' : '新增岗位'" description="先记下公司和岗位，其他信息可以稍后补充。" variant="drawer" :busy="busy" @update:open="close">
    <form id="job-form" class="job-form" @submit.prevent="save">
      <Notice :message="error" kind="error" />
      <fieldset :disabled="busy">
        <div class="form-grid">
          <div class="form-field"><label for="job-company">公司名称 <span class="muted">必填</span></label><input id="job-company" v-model="form.company" required maxlength="200" autocomplete="organization" placeholder="例如：星河科技" /></div>
          <div class="form-field"><label for="job-position">岗位名称 <span class="muted">必填</span></label><input id="job-position" v-model="form.position" required maxlength="200" placeholder="例如：Java 后端工程师" /></div>
          <div class="form-field"><label for="job-status">投递状态</label><select id="job-status" v-model="form.status"><option v-for="status in jobStatuses" :key="status.value" :value="status.value">{{ status.label }}</option></select></div>
        </div>
        <details class="job-extra" :open="!!record">
          <summary>补充岗位与安排 <span class="muted">选填</span></summary>
          <div class="form-grid">
            <div class="form-field"><label for="job-location">工作地点</label><input id="job-location" v-model="form.location" maxlength="200" placeholder="城市或办公地点" /></div>
            <div class="form-field"><label for="job-platform">招聘平台</label><input id="job-platform" v-model="form.platform" list="job-platforms" maxlength="100" placeholder="选择或填写平台" /><datalist id="job-platforms"><option v-for="platform in ['BOSS','猎聘','智联','前程无忧','公司官网','其他']" :key="platform" :value="platform" /></datalist></div>
            <div class="form-field"><label for="job-type">岗位类型</label><select id="job-type" v-model="form.jobType"><option v-for="type in jobTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select></div>
            <div class="form-field"><label for="job-url">原始岗位链接</label><input id="job-url" v-model="form.url" type="url" pattern="https?://.*" maxlength="2048" placeholder="https://" /><p class="form-help">仅保存链接，打开链接不会改变投递状态。</p></div>
            <div class="form-field"><label for="job-applied">投递日期</label><input id="job-applied" v-model="form.appliedDate" type="date" min="0001-01-01" max="9999-12-31" /></div>
            <div class="form-field"><label for="job-next">下次行动日期</label><input id="job-next" v-model="form.nextActionDate" type="date" min="0001-01-01" max="9999-12-31" /></div>
            <div class="form-field"><label for="job-interview">面试日期时间</label><input id="job-interview" v-model="interviewLocal" type="datetime-local" step="1" min="0001-01-01T00:00" max="9999-12-31T23:59:59" /><p class="form-help">本地时区：{{ timeZone }}。仅记录，不发送提醒。</p></div>
            <div class="form-field"><label for="job-interview-note">面试说明</label><textarea id="job-interview-note" v-model="form.interviewNote" rows="3" maxlength="1000" placeholder="例如：技术面，提前准备项目介绍" /></div>
            <div class="form-field job-wide"><label for="job-jd">JD 正文</label><textarea id="job-jd" v-model="form.jd" rows="7" maxlength="24000" placeholder="粘贴或填写岗位描述" /></div>
            <div class="form-field job-wide"><label for="job-notes">备注</label><textarea id="job-notes" v-model="form.notes" rows="5" maxlength="12000" placeholder="记录沟通内容、准备事项或个人想法" /></div>
          </div>
        </details>
      </fieldset>
    </form>
    <template #footer><div class="job-form-actions"><button type="button" class="btn btn-secondary" :disabled="busy" @click="close">取消</button><button type="submit" form="job-form" class="btn btn-primary" :disabled="busy">{{ busy ? '正在保存…' : '保存岗位' }}</button></div></template>
  </Modal>
</template>
<style scoped>
.job-form { font-size:14px; }.job-form fieldset { border:0; padding:0; margin:0; min-width:0; }.job-form input,.job-form select,.job-form textarea { font-size:14px; }
.job-extra { margin-top:var(--space-6); border-top:1px solid var(--border-subtle); }.job-extra summary { padding:var(--space-4) 0; font-weight:600; cursor:pointer; }.job-extra summary span { font-weight:400; margin-left:8px; }.job-wide { grid-column:1 / -1; }.job-form-actions { display:flex; justify-content:flex-end; gap:var(--space-2); }
@media(max-width:768px) { .job-form input,.job-form select,.job-form textarea { font-size:16px; }.job-form-actions .btn { min-height:44px; } }
</style>
