import { post, put, request } from '../api'

export const jobStatuses = [
  { value: 'TO_APPLY', label: '待投递' }, { value: 'APPLIED', label: '已投递' },
  { value: 'ASSESSMENT', label: '笔试 / 测评' }, { value: 'INTERVIEW', label: '面试中' },
  { value: 'OFFER', label: '收到 Offer' }, { value: 'REJECTED', label: '未通过' }, { value: 'WITHDRAWN', label: '已放弃' },
] as const
export const jobTypes = [
  { value: 'CAMPUS', label: '校招' }, { value: 'INTERNSHIP', label: '日常实习' },
  { value: 'SUMMER_INTERNSHIP', label: '暑期实习' }, { value: 'FULL_TIME', label: '正式岗位' }, { value: 'OTHER', label: '其他' },
] as const
export type JobStatus = typeof jobStatuses[number]['value']
export type JobType = typeof jobTypes[number]['value']
export interface JobInput {
  company: string; position: string; location: string; platform: string; url: string; jd: string
  jobType: JobType; status: JobStatus; appliedDate: string | null; nextActionDate: string | null
  interviewAt: string | null; interviewNote: string; notes: string
}
export interface JobApplication extends JobInput { id: string; archived: boolean; createdAt: string; updatedAt: string }
export interface JobFilters { company: string; status: string; archived: string; sort: string; direction: string }
const base = '/job-applications'
export const jobApplications = {
  list: (filters: JobFilters) => request<JobApplication[]>(`${base}?${new URLSearchParams({ ...filters })}`),
  create: (input: JobInput) => post<JobApplication>(base, input),
  update: (id: string, input: JobInput) => put<JobApplication>(`${base}/${encodeURIComponent(id)}`, input),
  status: (id: string, status: JobStatus) => request<JobApplication>(`${base}/${encodeURIComponent(id)}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }),
  archive: (id: string, archived: boolean) => request<JobApplication>(`${base}/${encodeURIComponent(id)}/archive`, { method: 'PATCH', body: JSON.stringify({ archived }) }),
  remove: (id: string) => request<void>(`${base}/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}
export function localDateTime(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${String(date.getFullYear()).padStart(4, '0')}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
export function displayJobTime(value: string | null) { return value ? localDateTime(value).replace('T', ' ').slice(0, 16) : '未填写' }

export interface JobCsvPreview {
  previewToken: string; total: number; valid: number; created: number; duplicates: number; conflicts: number
  errors: { line: number; message: string }[]
  rows?: { line: number; company: string; position: string; disposition: string; message: string }[]
}
export function previewJobCsv(file: File) {
  const data = new FormData(); data.append('file', file)
  return request<JobCsvPreview>(`${base}/csv/preview`, { method: 'POST', body: data })
}
export function importJobCsv(file: File, previewToken: string) {
  const data = new FormData(); data.append('file', file); data.append('previewToken', previewToken)
  return request<{ created: number; duplicates: number }>(`${base}/csv/import`, { method: 'POST', body: data })
}
export async function downloadJobCsv(kind: 'template' | 'export') {
  let response: Response
  try { response = await fetch(`/api${base}/csv/${kind}`, { credentials: 'same-origin' }) }
  catch { throw new Error('无法下载 CSV，请确认本地服务可用后重试') }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.message || 'CSV 下载失败，请重试')
  }
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a'); link.href = url; link.download = kind === 'template' ? '投递记录模板.csv' : '投递记录备份.csv'
  document.body.appendChild(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
