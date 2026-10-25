import type { JobRecord, JobSource, JobType } from '../../api/job-search'
export interface ResultFilters { source: JobSource | ''; jobType: JobType | ''; minK: number | null; maxK: number | null; experience: string; education: string; unknownSalary: boolean; unknownJobType: boolean; unknownExperience: boolean; unknownEducation: boolean }
export const defaultFilters = (): ResultFilters => ({ source: '', jobType: '', minK: null, maxK: null, experience: '', education: '', unknownSalary: true, unknownJobType: true, unknownExperience: true, unknownEducation: true })
const normalize = (text?: string | null) => text?.replace(/\s+/g, ' ').trim() || ''
export function deduplicateJobs(jobs: JobRecord[]) {
  const result = new Map<string, JobRecord>()
  for (const job of jobs) {
    const key = `${job.source}:${job.sourceId}`
    if (!result.has(key)) result.set(key, { ...job, key })
  }
  const bySource = new Map<JobSource, JobRecord[]>()
  for (const job of result.values()) { const queue = bySource.get(job.source) || []; queue.push(job); bySource.set(job.source, queue) }
  const alternating: JobRecord[] = []
  const maxLength = Math.max(0, ...[...bySource.values()].map(queue => queue.length))
  for (let index = 0; index < maxLength; index++) for (const queue of bySource.values()) if (queue[index]) alternating.push(queue[index]!)
  return alternating
}
export interface JobGroup { id: string; sources: JobRecord[] }
export function groupJobs(jobs: JobRecord[]): JobGroup[] {
  const groups: JobGroup[] = [], signatures = new Map<string, JobGroup>()
  for (const job of deduplicateJobs(jobs)) {
    const fields = [job.title, job.company, job.location, job.description].map(normalize)
    const signature = fields.every(Boolean) ? JSON.stringify([normalize(job.city), ...fields]) : ''
    const existing = signature ? signatures.get(signature) : undefined
    if (existing && !existing.sources.some(item => item.source === job.source)) existing.sources.push(job)
    else {
      const group = { id: job.key, sources: [job] }; groups.push(group)
      if (signature) signatures.set(signature, group)
    }
  }
  return groups
}
export function matchesJob(job: JobRecord, filters: ResultFilters) {
  if (filters.source && filters.source !== job.source) return false
  const jobType = job.jobType || 'UNKNOWN'
  if (jobType === 'UNKNOWN' ? !filters.unknownJobType : filters.jobType && filters.jobType !== jobType) return false
  const salaryUnknown = job.salaryMinK == null && job.salaryMaxK == null
  if (salaryUnknown ? !filters.unknownSalary : (filters.minK != null && (job.salaryMaxK ?? Infinity) < filters.minK) || (filters.maxK != null && (job.salaryMinK ?? -Infinity) > filters.maxK)) return false
  const experience = job.experienceText?.trim() || '', education = job.educationText?.trim() || ''
  if (!experience ? !filters.unknownExperience : filters.experience && experience !== filters.experience) return false
  if (!education ? !filters.unknownEducation : filters.education && education !== filters.education) return false
  return true
}
export function originalOptions(jobs: JobRecord[], field: 'experienceText' | 'educationText') {
  return [...new Set(jobs.map(job => job[field]?.trim()).filter((value): value is string => !!value))]
}
export function sourceDifference(jobs: JobRecord[]) {
  return ['jobType', 'salaryText', 'experienceText', 'educationText'].some(field => new Set(jobs.map(job => job[field as keyof JobRecord] || '')).size > 1)
}
