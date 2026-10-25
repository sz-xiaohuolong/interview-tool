import { post, request } from '../api'
export const jobSources = [{ value: 'BOSS', label: 'BOSS 直聘' }, { value: 'LIEPIN', label: '猎聘' }, { value: 'ZHAOPIN', label: '智联招聘' }] as const
export type JobSource = typeof jobSources[number]['value']
export const jobTypes = [
  { value: 'DAILY_INTERNSHIP', label: '日常实习' },
  { value: 'SUMMER_INTERNSHIP', label: '暑期实习' },
  { value: 'FULL_TIME', label: '正式岗位' },
] as const
export type JobType = typeof jobTypes[number]['value'] | 'UNKNOWN'
export interface JobRecord {
  key: string; source: JobSource; sourceId: string; title: string; company: string; city: string; location: string; url: string
  salaryText: string; salaryMinK: number | null; salaryMaxK: number | null; salaryMonths: number | null
  experienceText: string | null; educationText: string | null; description: string | null
  fetchedAt: string; detailFetchedAt?: string | null; publishedAt?: string | null; savedAt?: string; jobType?: JobType | null
}
export interface SourceProgress { source: JobSource; status: 'SEARCHING' | 'READY' | 'EMPTY' | 'FAILED' | 'ENDED' | 'LIMIT' | 'STOPPED'; count: number; hasMore: boolean; errorCode?: string | null; errorMessage?: string | null }
export interface SearchInput { keyword: string; cityId: string; sources: JobSource[] }
export interface TaskSnapshot { id: string; keyword: string; cityId: string; cityName: string; status: 'RUNNING' | 'IDLE' | 'STOPPED'; revision: number; sources: SourceProgress[]; jobs: JobRecord[] }
export interface Favorite { id: string; createdAt: string; sources: JobRecord[] }
export interface JobSourceLink { materialId: string; source: JobSource; sourceId: string; url: string; fetchedAt: string; contentEdited: boolean }
export interface SaveJdInput { taskId?: string; favoriteId?: string; jobKey: string; name: string; content: string }
const base = '/job-search'
const task = (id: string) => `${base}/tasks/${encodeURIComponent(id)}`
export const jobSearch = {
  cities: () => request<{ id: string; name: string }[]>(`${base}/cities`),
  start: (input: SearchInput) => post<TaskSnapshot>(`${base}/tasks`, input),
  task: (id: string) => request<TaskSnapshot>(task(id)),
  more: (id: string) => post<TaskSnapshot>(`${task(id)}/more`),
  retry: (id: string, source: JobSource) => post<TaskSnapshot>(`${task(id)}/retry`, { source }),
  stop: (id: string, keepalive = false) => request<TaskSnapshot>(`${task(id)}/stop`, { method: 'POST', body: '{}', ...(keepalive ? { keepalive: true } : {}) }),
  detail: (id: string, key: string) => post<JobRecord>(`${task(id)}/jobs/${encodeURIComponent(key)}/detail`),
  favorites: () => request<Favorite[]>(`${base}/favorites`),
  favorite: (input: { taskId: string; jobKeys: string[]; favoriteId?: string }) => post<Favorite>(`${base}/favorites`, input),
  removeFavorite: (id: string) => request<void>(`${base}/favorites/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  lookup: (keys: string[]) => post<Record<string, JobSourceLink | null>>(`${base}/jd-links/lookup`, { keys }),
  saveJd: (input: SaveJdInput) => post<{ materialId: string; created: boolean; source: JobSourceLink }>(`${base}/jd`, input),
}
export const sourceLabel = (source: JobSource) => jobSources.find(item => item.value === source)?.label || source
export const jobTypeLabel = (type?: JobType | null) => jobTypes.find(item => item.value === type)?.label || '未知类型'
