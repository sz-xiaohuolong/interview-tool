import type { SourceCodeSnapshot, SourceQuestion } from '../source-code'
import type { ProjectEvidenceMatrix } from '../project-evidence'

export type InterviewSessionType = 'COMPREHENSIVE' | 'QUICK'
export type AgentPracticePreference = 'NEW_FIRST' | 'REVIEW'

export interface AgentPracticeOptionsRequest {
  role: string
  level: string
  plannedQuestionCount: number
  agentTopics: string[]
}

export interface AgentPracticeOptions {
  eligible: boolean
  requestedCount: number
  reviewAvailable: number
  newSupplementCount: number
  canStart: boolean
  requiresAcknowledgement: boolean
  message: string
}

export interface InterviewConfig {
  sessionType?: InterviewSessionType
  role: string
  level: string
  resumeId: string
  jdId: string
  jd: string
  durationMinutes?: number
  plannedQuestionCount?: number
  interviewMode?: 'TRAINING' | 'TIMED'
  timeLimitMinutes?: number
  projectId?: string
  projectBrief?: string
  agentTopics?: string[]
  practicePreference?: AgentPracticePreference
  acceptReviewFill?: boolean
  expectedNewSupplementCount?: number
}

export interface PreparationSummary {
  technicalStack: string[]
  responsibilities: string[]
  clarifications: string[]
  focus: string[]
}

export interface InterviewMessage {
  hasCodeEvidence?: boolean
  id: string
  role: 'ASSISTANT' | 'USER'
  content: string
  questionId?: string
  kind?: 'REPLY'
  stage?: InterviewStage['id']
  rootQuestionId?: string
  questionKind?: 'MAIN' | 'FOLLOW_UP'
  createdAt: string
  fallback?: boolean
}

export interface KnowledgeRetrieval {
  sourceId: string
  title: string
  url: string
  status: string
  message: string
  httpStatus?: number
  cacheUsed: boolean
  stale: boolean
  attemptedAt?: string
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
  retrieval?: KnowledgeRetrieval
  stale?: boolean
  warning?: string
}

export interface ReviewFinding {
  answerStatus?: 'CLEAR' | 'PARTIAL' | 'UNVERIFIED'
  headline?: string
  observation?: string
  answerQuote?: string
  priority?: number
  terms?: { term: string; explanation: string }[]
  questionId: string
  assessment: string
  status: string
  sourceIds: string[]
  suggestion: string
  sourceQuote?: string
}

export interface InterviewDiagnosis {
  questionId: string
  answerMessageId: string
  action: string
  effectiveAction?: string
  diagnosis: {
    category: 'SUFFICIENT' | 'INCOMPLETE' | 'INCORRECT' | 'UNCERTAIN' | 'CONTRADICTORY' | 'NO_EVIDENCE'
    coveredPoints: string[]
    answerQuote: string
    gap: string
    nextGoal: string
    reason: string
  }
}

export interface InterviewReport {
  sourceCode?: SourceCodeSnapshot
  sourceQuestions?: SourceQuestion[]
  topicCoverage?: {
    askedCount: number
    answeredCount: number
    newCount: number
    reviewCount: number
    items: {
      pointId: string
      theme: string
      title: string
      questionId: string
      rootQuestionId: string
      seedQuestionId?: string
      seedQuestionTitle?: string
      seedQuestionUrl?: string
      seedQuestionDifficulty?: string
      review: boolean
      answered: boolean
    }[]
  }
  diagnostics?: InterviewDiagnosis[]
  version?: number
  detailedAssessment?: {
    version: 1
    overall: AssessmentParagraph
    strengths: AssessmentParagraph[]
    improvements: AssessmentParagraph[]
    nextSteps: AssessmentParagraph[]
  }
  overview?: { answeredCount: number; mainQuestionCount: number; followUpCount: number; endReason: string }
  summary: string
  findings: ReviewFinding[]
  stages?: InterviewStage[]
  matrix?: ProjectEvidenceMatrix
}

export interface AssessmentParagraph {
  text: string
  questionIds: string[]
}

export interface InterviewStage {
  id: 'INTRO' | 'PROJECT' | 'TECHNICAL' | 'QUESTIONS'
  label: string
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED'
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
  sourceCode?: SourceCodeSnapshot
  sessionType?: InterviewSessionType
  answerCount?: number
  id: string
  status: string
  phase: string
  revision: number
  role: string
  level: string
  resumeId?: string
  jdId?: string
  durationMinutes: number
  plannedQuestionCount?: number
  agentTopics?: string[]
  experienceVersion?: number
  reviewVersion?: number
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
  knowledgeRetrieval?: KnowledgeRetrieval[]
  sources: KnowledgeSource[]
  warnings: string[]
  error?: string | null
  report?: InterviewReport | null
  usage?: { calls?: number; attempts?: number; maxCalls?: number; promptTokens?: number; completionTokens?: number; totalTokens?: number; usageIncomplete?: boolean; [key: string]: unknown }
  metrics?: { [key: string]: unknown }
  allowedActions?: SessionAction[]
}

export interface InterviewStatus { enabled: boolean; provider: string; configured: boolean; model?: string }
export type InterviewSessionSummary = Pick<InterviewSession, 'id' | 'status' | 'phase' | 'role' | 'level' | 'createdAt' | 'updatedAt' | 'mock' | 'resumeId' | 'jdId' | 'sessionType' | 'answerCount'>
export interface MutationBody { requestId: string; expectedRevision: number; [key: string]: unknown }
export type SessionAction = 'confirm' | 'corrections' | 'answers' | 'next' | 'skip-stage' | 'retry' | 'cancel' | 'finish' | 'refresh' | 'transcription-wait' | 'transcription-resume' | 'continue-without-source'
