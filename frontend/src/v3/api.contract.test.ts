import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSession, getSession, getV3Status, listSessions, mutateSession } from './api'
import type { SessionAction } from './types'

afterEach(() => vi.unstubAllGlobals())
const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
function mockEndpoint(path: string, body: unknown, status = 200) {
  const fetcher = vi.fn(async (url: string, _options?: RequestInit) => {
    if (url !== path) throw new Error(`出现未约定的接口：${url}`)
    return respond(body, status)
  })
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

describe('V3 面试客户端接口归属', () => {
  it.each([
    ['配置状态', getV3Status, '/api/v3/status'],
    ['面试列表', listSessions, '/api/v3/sessions'],
    ['场次详情', () => getSession('同名/场次'), '/api/v3/sessions/%E5%90%8C%E5%90%8D%2F%E5%9C%BA%E6%AC%A1'],
  ] as const)('%s 读取 V3 接口且保持同源', async (_name, read, path) => {
    const fetcher = mockEndpoint(path, [])
    await read()
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(path, expect.objectContaining({ credentials: 'same-origin' }))
    expect(fetcher.mock.calls[0]![1]?.method).toBeUndefined()
  })

  it('创建综合面试只提交到 V3，携带原始规划与幂等标识', async () => {
    const fetcher = mockEndpoint('/api/v3/sessions', { id: 'synthetic-v3' })
    await createSession({ role: '合成岗位', level: 'INTERN', resumeId: '', jdId: '', jd: '', plannedQuestionCount: 12 }, 'synthetic-request')
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/v3/sessions', expect.objectContaining({ method: 'POST' }))
    expect(JSON.parse(String(fetcher.mock.calls[0]![1]?.body))).toMatchObject({
      role: '合成岗位', level: 'INTERN', plannedQuestionCount: 12, requestId: 'synthetic-request',
    })
  })

  const actions = {
    confirm: 'confirm', corrections: 'corrections', answers: 'answers', next: 'next', retry: 'retry',
    cancel: 'cancel', finish: 'finish', refresh: 'refresh', 'skip-stage': 'skip-stage',
    'transcription-wait': 'transcription-wait', 'transcription-resume': 'transcription-resume',
  } satisfies Record<SessionAction, string>
  it.each(Object.entries(actions))('%s 操作绑定 V3 场次，不因 ID 同名进入旧接口', async (action, suffix) => {
    const fetcher = mockEndpoint(`/api/v3/sessions/shared-id/${suffix}`, { id: 'shared-id' })
    const body = { requestId: 'synthetic-operation', expectedRevision: 7 }
    await mutateSession('shared-id', action as SessionAction, body)
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(`/api/v3/sessions/shared-id/${suffix}`, expect.objectContaining({
      method: 'POST', credentials: 'same-origin', body: JSON.stringify(body),
    }))
  })

  it.each([404, 503])('V3 返回 %s 时显式失败，不能改调旧面试接口', async status => {
    const fetcher = mockEndpoint('/api/v3/sessions/shared-id', { code: 'V3_UNAVAILABLE', message: '综合面试暂不可用' }, status)
    await expect(getSession('shared-id')).rejects.toMatchObject({ status, message: '综合面试暂不可用' })
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/v3/sessions/shared-id', expect.any(Object))
  })
})
