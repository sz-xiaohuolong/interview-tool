import { request } from '../api'
import type { AgentPracticeOptions, AgentPracticeOptionsRequest, InterviewConfig, InterviewSession, InterviewSessionSummary, MutationBody, SessionAction, InterviewStatus } from '../models/interview'

export function requestId() { return crypto.randomUUID() }
export const getInterviewStatus = () => request<InterviewStatus>('/interviews/status')
export const listSessions = () => request<InterviewSessionSummary[]>('/interviews')
export const getSession = (id: string) => request<InterviewSession>(`/interviews/${encodeURIComponent(id)}`, { signal: AbortSignal.timeout(2000) })
export const deleteSession = (id: string) => request<void>(`/interviews/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const getAgentPracticeOptions = (config: AgentPracticeOptionsRequest) => request<AgentPracticeOptions>('/interviews/agent-practice-options', {
  method: 'POST', body: JSON.stringify(config), signal: AbortSignal.timeout(12000),
})

export function createSession(config: InterviewConfig, id: string) {
  return request<InterviewSession>('/interviews', {
    method: 'POST', signal: AbortSignal.timeout(12000),
    body: JSON.stringify({ ...config, resumeId: config.sessionType === 'QUICK' ? '' : config.resumeId || undefined, jdId: config.sessionType === 'QUICK' ? '' : config.jdId || undefined, requestId: id }),
  })
}

export function mutateSession(id: string, action: SessionAction, body: MutationBody) {
  return request<InterviewSession>(`/interviews/${encodeURIComponent(id)}/${action}`, {
    method: 'POST', body: JSON.stringify(body), signal: AbortSignal.timeout(action.startsWith('transcription-') ? 1500 : action === 'answers' ? 7000 : 12000),
  })
}

export const levelLabels: Record<string, string> = { INTERN: '实习生', JUNIOR: '初级', SENIOR: '中高级' }
export const agentTopicLabels: Record<string, string> = {
  AGENT_ARCHITECTURE: '架构与规划',
  AGENT_CONTEXT_MEMORY: '上下文与记忆',
  AGENT_HARNESS_TOOL: '工具与执行控制',
  AGENT_RAG: 'RAG',
  AGENT_EVALUATION: '评测与质量',
  AGENT_FRAMEWORK: '框架实践 / 模型接入',
}
export function isAgentRole(role: string) { return /agent|大模型|人工智能|智能体|\bllm\b|\brag\b|ai\s*应用/i.test(role) }
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
