export class V2Error extends Error {
  status = 0
  code = 'NETWORK'
  currentRevision?: number
  nextAction?: string
  resourceId?: string
  operationId?: string
  retryable?: boolean
  details?: Record<string, unknown>
}
export async function v2<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path.startsWith('/api/') ? path : `/api/v2${path}`, { ...options, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', ...options.headers } })
  } catch { throw new V2Error('连接中断。输入保留在本页；请先查看服务端记录，确认后再显式重试。') }
  let body: unknown
  try { body = await response.json() } catch { throw new V2Error('服务端响应不完整。请保留本页输入并重新读取记录。') }
  if (!response.ok) {
    const data = body as Partial<V2Error>
    const error = new V2Error(typeof data.message === 'string' ? data.message : '操作失败，请保留本页输入。')
    for (const key of ['code', 'currentRevision', 'nextAction', 'resourceId', 'operationId', 'retryable', 'details'] as const)
      if (data[key] !== undefined) Object.assign(error, { [key]: data[key] })
    error.status = response.status
    throw error
  }
  return body as T
}
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, canonical(item)]))
  return value
}
export async function writeV2<T>(path: string, body: Record<string, unknown>, method = 'POST'): Promise<T> {
  const key = `v2:pending:${method}:${path}`, input = JSON.stringify(canonical(body))
  let requestId = crypto.randomUUID()
  try {
    const pending = JSON.parse(sessionStorage.getItem(key) || 'null')
    if (pending?.input === input && typeof pending.requestId === 'string') requestId = pending.requestId
    sessionStorage.setItem(key, JSON.stringify({ input, requestId }))
  } catch { /* 无法使用本页存储时，服务端历史仍保留已经提交的操作。 */ }
  const result = await v2<T>(path, { method, body: JSON.stringify({ ...body, requestId }) })
  sessionStorage.removeItem(key)
  return result
}
