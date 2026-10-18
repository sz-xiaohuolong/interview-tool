export type ProjectCheckpointId = 'BUSINESS' | 'RESPONSIBILITY' | 'ARCHITECTURE' | 'TECH_CHOICE' | 'FAILURE' | 'PERFORMANCE' | 'TRADEOFF'
export interface ProjectAnswerEvidence {
  questionMessageId: string
  answerMessageId: string
  questionQuote: string
  answerQuote: string
}
export interface ProjectEvidenceItem {
  checkpointId: ProjectCheckpointId
  title: string
  status: 'CLEAR' | 'PARTIAL' | 'UNCOVERED'
  reason: string
  evidence: ProjectAnswerEvidence[]
}
export interface ProjectEvidenceMatrix {
  version: number
  project: { id: string; name: string }
  notice?: string
  items: ProjectEvidenceItem[]
}
export interface ProjectGapPractice {
  checkpointId: ProjectCheckpointId
  caseId: string | null
  status: 'RUNNING' | 'READY' | 'FAILED' | 'INTERRUPTED'
  operationId?: string
  latestAttempt?: { id: string; status: string }
}
