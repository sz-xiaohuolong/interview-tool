import type { SourceCodeSnapshot, SourceQuestion } from './source-code'
import type { ProjectEvidenceMatrix } from './project-evidence'
export type MaterialKind = 'RESUME' | 'PROJECT' | 'JD'
export interface Document { id: string; name: string; text: string; truncated: boolean; createdAt: string }
export interface Material { id: string; kind: MaterialKind; name: string; content: string; createdAt: string; updatedAt: string; documents: Document[]; repository?: SourceCodeSnapshot | null }
export interface Health { status: string; provider: string; configured: boolean; model: string; demo?: boolean }
export type InterviewMode = 'QUICK' | 'RESUME' | 'PROJECT'
export interface Message { id: string; role: 'ASSISTANT' | 'USER'; content: string; questionIndex: number; hasCodeEvidence?: boolean; followUp: boolean; createdAt: string }
export interface Session { sourceCode?: SourceCodeSnapshot; id: string; status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'INTERRUPTED' | 'FAILED'; mode: InterviewMode; role: string; level: string; durationMinutes: number; style: string; createdAt: string; updatedAt: string; endedAt: string | null; questionIndex: number; followUpCount: number; totalQuestions: number; currentTopic: string; messages: Message[]; mock: boolean; error: string; pendingAction: string; reportStatus: string; contextTruncated: boolean }
export interface PredictionQuestion { id: string; question: string; evidence: string; knowledgePoint: string; priority: string; answerOutline: string; followUps: string[] }
export interface Prediction { id: string; resumeId: string; role: string; createdAt: string; mock: boolean; contextTruncated?: boolean; questions: PredictionQuestion[]; risks: string[] }
export interface Dimension { name: string; score: number; reason: string }
export interface QuestionAnalysis { questionIndex: number; question: string; correct: string[]; incorrect: string[]; missing: string[]; uncertain: string[]; answerOutline: string; knowledgeExplanation: string; evidence: string[] }
export interface Report { sourceCode?: SourceCodeSnapshot; sourceQuestions?: SourceQuestion[]; sessionId: string; summary: string; dimensions: Dimension[]; questions: QuestionAnalysis[]; weaknesses: string[]; suggestions: string[]; mock: boolean; createdAt: string; matrix?: ProjectEvidenceMatrix }
