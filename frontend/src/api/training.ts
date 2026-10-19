import { request } from '../api'
export { ApiError } from '../api'

export const training = <T>(path: string, options: RequestInit = {}) => request<T>(path.startsWith('/api/') ? path : `/training${path}`, options)

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, canonical(item)]))
  return value
}
export async function writeTraining<T>(path: string, body: Record<string, unknown>, method = 'POST'): Promise<T> {
  const key = `v2:pending:${method}:${path}`, input = JSON.stringify(canonical(body))
  let requestId = crypto.randomUUID()
  try {
    const pending = JSON.parse(sessionStorage.getItem(key) || 'null')
    if (pending?.input === input && typeof pending.requestId === 'string') requestId = pending.requestId
    sessionStorage.setItem(key, JSON.stringify({ input, requestId }))
  } catch { /* 无法使用本页存储时，服务端历史仍保留已经提交的操作。 */ }
  const result = await training<T>(path, { method, body: JSON.stringify({ ...body, requestId }) })
  sessionStorage.removeItem(key)
  return result
}
