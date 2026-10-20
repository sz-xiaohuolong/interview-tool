export interface CodeEvidence {
  id: string
  path: string
  startLine: number
  endLine: number
  snippet: string
  verified: boolean
}
export interface SourceCodeSnapshot {
  repository: string
  url?: string
  commitSha?: string
  status: string
  error?: string
  warnings?: string[]
  evidenceCount?: number
  evidence?: CodeEvidence[]
}
export interface SourceQuestion { question: string; codeEvidenceIds: string[] }
