export const modeNames: Record<string, string> = { QUICK: '快速练习', RESUME: '简历押题', PROJECT: '项目面试' }
export const levelNames: Record<string, string> = { INTERN: '实习生', JUNIOR: '初级', SENIOR: '中高级' }
export const styleNames: Record<string, string> = { NORMAL: '常规面试', PRESSURE: '压力面试' }
export const statusNames: Record<string, string> = { CREATED: '准备中', IN_PROGRESS: '进行中', COMPLETED: '已完成', INTERRUPTED: '已中断', FAILED: '生成失败' }
export function dateTime(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
export function shortDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(value)) }
