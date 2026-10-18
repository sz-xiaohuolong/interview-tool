import { request } from '../api'
import type { InterviewConfig, InterviewSession, MutationBody, SessionAction, V3Status } from './types'

export function requestId() { return crypto.randomUUID() }
export const getV3Status = () => request<V3Status>('/v3/status')
export const listSessions = () => request<InterviewSession[]>('/v3/sessions')
export const getSession = (id: string) => request<InterviewSession>(`/v3/sessions/${encodeURIComponent(id)}`, { signal: AbortSignal.timeout(2000) })

export function createSession(config: InterviewConfig, id: string) {
  return request<InterviewSession>('/v3/sessions', {
    method: 'POST', signal: AbortSignal.timeout(12000),
    body: JSON.stringify({ ...config, resumeId: config.resumeId || undefined, jdId: config.jdId || undefined, requestId: id }),
  })
}

export function mutateSession(id: string, action: SessionAction, body: MutationBody) {
  return request<InterviewSession>(`/v3/sessions/${encodeURIComponent(id)}/${action}`, {
    method: 'POST', body: JSON.stringify(body), signal: AbortSignal.timeout(action.startsWith('transcription-') ? 1500 : action === 'answers' ? 7000 : 12000),
  })
}

export const levelLabels: Record<string, string> = { INTERN: '实习生', JUNIOR: '初级', SENIOR: '中高级' }
export const statusLabels: Record<string, string> = {
  QUEUED: '排队中', PREPARING: '准备中', ANALYZING: '分析简历', RETRIEVING: '检索知识', PLANNING: '准备题目',
  READY: '待确认开考', IN_PROGRESS: '面试进行中', ANSWERING: '面试官思考中', REPORTING: '查证复盘中',
  COMPLETED: '面试已完成，待复盘', REPORT_READY: '复盘已完成', FAILED: '任务失败', INTERRUPTED: '已中断，等待恢复', CANCELLED: '已取消',
}

export function displayDate(value?: string) {
  if (!value || !Number.isFinite(new Date(value).getTime())) return '时间未提供'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

export function publicSourceUrl(value: string) {
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) ? url.href : undefined } catch { return undefined }
}
