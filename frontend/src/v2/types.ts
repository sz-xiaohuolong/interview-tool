export interface Page<T> { items: T[]; nextCursor: string | null }
export interface Skill { id: string; name: string; scope?: string; excludes?: string; example?: string; selfRating?: string; eventCount?: number }
export interface Finding { id: string; kind: string; text: string; confirmed: boolean; disputed: boolean; disputeReason?: string }
export interface Dialogue { id: string; role: string; content: string; questionIndex?: number; followUp?: boolean }
export interface ReviewItem {
  id: string; revision: number; question: string; status: string; nextReviewAt: string | null
  sourceSessionId: string; questionIndex: number; sourcePrecision: string; sourceLimit: string
  mock: boolean; skillIds: string[]; findings: Finding[]; dialogue: Dialogue[]; role: string; level: string
  lastEvidence?: { attemptId?: string; trainingLinkId?: string; bindings?: { findingId: string }[] }; resolutionReason?: string
  reportHash?: string; sourceVersions?: ReviewItem[]; updatedAt?: string
  sourceReport?: { answerOutline?: string; knowledgeExplanation?: string }
}
export const reviewLabels: Record<string, string> = { PENDING_CONFIRMATION: '待确认', DUE: '待复习', IN_PROGRESS: '练习中', WAITING_EVIDENCE: '待补证据', RESOLVED: '本轮已结束', ARCHIVED: '已归档' }
export const findingLabels: Record<string, string> = { incorrect: '错误', missing: '遗漏', uncertain: '未知' }
export const ratingLabels: Record<string, string> = { UNRATED: '未评', NEEDS_PRACTICE: '需要练习', BASIC_UNDERSTANDING: '基本理解', SELF_MASTERED: '自认为掌握' }
export function localDate(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '尚未安排' }
