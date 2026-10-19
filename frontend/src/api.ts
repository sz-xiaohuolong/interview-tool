export class ApiError extends Error {
  currentRevision?: number
  nextAction?: string
  resourceId?: string
  operationId?: string
  retryable?: boolean
  details?: Record<string, unknown>
  constructor(message: string, public status = 0, public code = 'NETWORK') { super(message) }
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path.startsWith('/api/') ? path : `/api${path}`, {
      ...options,
      credentials: 'same-origin',
      headers: {
        ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        ...options.headers,
      },
    })
  } catch {
    throw new ApiError('无法连接后端，请确认服务已启动。已提交的回答可能已保存，请刷新确认后重试。')
  }
  if (response.status === 204) return undefined as T
  let body: unknown
  try { body = await response.json() } catch {
    throw new ApiError('后端未返回有效数据，请检查服务日志后重试。', response.status, 'INVALID_RESPONSE')
  }
  if (!response.ok) {
    const data = body as Partial<ApiError>
    const error = new ApiError(typeof data.message === 'string' ? data.message : '请求失败，请稍后重试。', response.status, data.code || 'REQUEST_FAILED')
    for (const key of ['currentRevision', 'nextAction', 'resourceId', 'operationId', 'retryable', 'details'] as const)
      if (data[key] !== undefined) Object.assign(error, { [key]: data[key] })
    throw error
  }
  return body as T
}

export const post = <T>(path: string, body: unknown = {}) => request<T>(path, { method: 'POST', body: JSON.stringify(body) })
export const put = <T>(path: string, body: unknown) => request<T>(path, { method: 'PUT', body: JSON.stringify(body) })
export function errorMessage(error: unknown) { return error instanceof Error ? error.message : '发生未知错误，请重试。' }
