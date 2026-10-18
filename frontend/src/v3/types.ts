import type { ProjectEvidenceMatrix } from '../project-evidence'

export interface InterviewConfig {
  role: string
  level: string
  resumeId: string
  jdId: string
  jd: string
  durationMinutes: number
  interviewMode?: 'TRAINING' | 'TIMED'
  timeLimitMinutes?: number
  projectId?: string
  projectBrief?: string
}

export interface PreparationSummary {
  technicalStack: string[]
  responsibilities: string[]
  clarifications: string[]
  focus: string[]
}

export interface InterviewMessage {
  id: string
  role: 'ASSISTANT' | 'USER'
  content: string
  questionId?: string
  kind?: 'REPLY'
  createdAt: string
  fallback?: boolean
}

export interface KnowledgeSource {
  id: string
  title: string
  url: string
  version: string
  fetchedAt: string
  sha256: string
  excerpt: string
  kind: string
  topics: string[]
}

export interface ReviewFinding {
  questionId: string
  assessment: string
  status: string
  sourceIds: string[]
  suggestion: string
  sourceQuote?: string
}

export interface InterviewReport {
  summary: string
  findings: ReviewFinding[]
  stages?: InterviewStage[]
  matrix?: ProjectEvidenceMatrix
}

export interface InterviewStage {
  id: 'INTRO' | 'PROJECT' | 'TECHNICAL' | 'QUESTIONS'
  label: string
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'
  answeredCount: number
}

export interface InterviewTiming {
  enabled: boolean
  limitMs: number
  elapsedMs: number
  remainingMs: number
  running: boolean
  expired: boolean
  serverNow: number
  waitingUntil?: number
}

export interface InterviewSession {
  id: string
  status: string
  phase: string
  revision: number
  role: string
  level: string
  resumeId?: string
  jdId?: string
  durationMinutes: number
  experienceVersion?: number
  interviewMode?: 'TRAINING' | 'TIMED'
  timeLimitMinutes?: number
  projectId?: string
  mainProject?: { id: string; name: string; brief: string; hypothetical: boolean }
  stage?: InterviewStage['id']
  stages?: InterviewStage[]
  timing?: InterviewTiming
  mock: boolean
  createdAt: string
  updatedAt: string
  summary?: PreparationSummary | null
  messages: InterviewMessage[]
  sources: KnowledgeSource[]
  warnings: string[]
  error?: string | null
  report?: InterviewReport | null
  usage?: { calls?: number; attempts?: number; maxCalls?: number; declaredOutputTokens?: number; cost?: string; [key: string]: unknown }
  metrics?: { [key: string]: unknown }
  allowedActions?: SessionAction[]
}

export interface V3Status { enabled: boolean; provider: string; configured: boolean; model?: string }
export interface MutationBody { requestId: string; expectedRevision: number; [key: string]: unknown }
export type SessionAction = 'confirm' | 'corrections' | 'answers' | 'next' | 'retry' | 'cancel' | 'finish' | 'refresh' | 'transcription-wait' | 'transcription-resume'
