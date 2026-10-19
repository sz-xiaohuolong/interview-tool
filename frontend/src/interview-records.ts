import type { Session } from './types'
import type { InterviewSessionSummary } from './models/interview'
import { modeNames, statusNames } from './format'
import { statusLabels } from './api/interviews'

export interface InterviewRecord {
  id: string
  source: 'legacy' | 'interview'
  key: string
  role: string
  status: string
  mock: boolean
  createdAt: string
  done: boolean
  completed: boolean
  modeLabel: string
  statusLabel: string
  answerCount?: number
  href: string
  replayHref: string
  actionLabel: string
}

export function interviewRecords(legacy: Session[], current: InterviewSessionSummary[]): InterviewRecord[] {
  const oldRecords: InterviewRecord[] = legacy.map(session => ({
    id: session.id, source: 'legacy', key: `legacy:${session.id}`, role: session.role, status: session.status,
    mock: session.mock, createdAt: session.createdAt, done: session.status === 'COMPLETED', completed: session.status === 'COMPLETED',
    modeLabel: modeNames[session.mode], statusLabel: statusNames[session.status],
    answerCount: session.messages.filter(message => message.role === 'USER').length,
    href: session.status === 'COMPLETED' ? `/reports/${encodeURIComponent(session.id)}` : `/sessions/${encodeURIComponent(session.id)}`,
    replayHref: `/history/${encodeURIComponent(session.id)}/replay`, actionLabel: session.status === 'COMPLETED' ? '查看复盘' : '继续面试',
  }))
  const newRecords: InterviewRecord[] = current.map(session => ({
    id: session.id, source: 'interview', key: `interview:${session.id}`, role: session.role, status: session.status,
    mock: session.mock, createdAt: session.createdAt,
    done: ['COMPLETED', 'REPORTING', 'REPORT_READY', 'CANCELLED'].includes(session.status),
    completed: ['COMPLETED', 'REPORTING', 'REPORT_READY'].includes(session.status),
    modeLabel: session.sessionType === 'QUICK' ? '快速练习' : '综合面试', statusLabel: statusLabels[session.status] || '任务处理中',
    answerCount: Number.isInteger(session.answerCount) && session.answerCount! >= 0 ? session.answerCount : undefined,
    href: `/interviews/${encodeURIComponent(session.id)}`, replayHref: `/history/interviews/${encodeURIComponent(session.id)}/replay`,
    actionLabel: ({ COMPLETED: '生成复盘', REPORTING: '查看进度', REPORT_READY: '查看复盘', CANCELLED: '查看记录', IN_PROGRESS: '继续面试', ANSWERING: '继续面试' } as Record<string, string>)[session.status] || '继续查看',
  }))
  return [...oldRecords, ...newRecords].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
}
