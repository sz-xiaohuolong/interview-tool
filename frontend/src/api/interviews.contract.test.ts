import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSession, deleteSession, getAgentPracticeOptions, getSession, getInterviewStatus, listSessions, mutateSession } from './interviews'
import type { SessionAction } from '../models/interview'

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

describe('综合面试 面试客户端接口归属', () => {
  it.each([
    ['配置状态', getInterviewStatus, '/api/interviews/status'],
    ['面试列表', listSessions, '/api/interviews'],
    ['场次详情', () => getSession('同名/场次'), '/api/interviews/%E5%90%8C%E5%90%8D%2F%E5%9C%BA%E6%AC%A1'],
  ] as const)('%s 读取 综合面试 接口且保持同源', async (_name, read, path) => {
    const fetcher = mockEndpoint(path, [])
    await read()
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(path, expect.objectContaining({ credentials: 'same-origin' }))
    expect(fetcher.mock.calls[0]![1]?.method).toBeUndefined()
  })

  it('创建综合面试只提交到 综合面试，携带原始规划与幂等标识', async () => {
    const fetcher = mockEndpoint('/api/interviews', { id: 'synthetic-v3' })
    await createSession({ role: '合成岗位', level: 'INTERN', resumeId: '', jdId: '', jd: '', plannedQuestionCount: 12 }, 'synthetic-request')
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/interviews', expect.objectContaining({ method: 'POST' }))
    expect(JSON.parse(String(fetcher.mock.calls[0]![1]?.body))).toMatchObject({
      role: '合成岗位', level: 'INTERN', plannedQuestionCount: 12, requestId: 'synthetic-request',
    })
  })

  it('删除只访问对应 综合面试 场次，按路径编码 ID 并接受 204', async () => {
    const path = '/api/interviews/shared%2Fid'
    const fetcher = vi.fn(async (url: string) => {
      if (url !== path) throw new Error(`出现未约定的接口：${url}`)
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetcher)
    await expect(deleteSession('shared/id')).resolves.toBeUndefined()
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(path, expect.objectContaining({ method: 'DELETE', credentials: 'same-origin' }))
  })

  it.each([404, 503])('删除返回 %s 时不改调旧接口', async status => {
    const fetcher = mockEndpoint('/api/interviews/shared-id', { message: '新版删除暂不可用' }, status)
    await expect(deleteSession('shared-id')).rejects.toMatchObject({ status, message: '新版删除暂不可用' })
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/interviews/shared-id', expect.objectContaining({ method: 'DELETE' }))
  })

  const actions = {
    confirm: 'confirm', corrections: 'corrections', answers: 'answers', next: 'next', retry: 'retry',
    cancel: 'cancel', finish: 'finish', refresh: 'refresh', 'skip-stage': 'skip-stage',
    'transcription-wait': 'transcription-wait', 'transcription-resume': 'transcription-resume', 'continue-without-source': 'continue-without-source',
  } satisfies Record<SessionAction, string>
  it.each(Object.entries(actions))('%s 操作绑定 综合面试 场次，不因 ID 同名进入旧接口', async (action, suffix) => {
    const fetcher = mockEndpoint(`/api/interviews/shared-id/${suffix}`, { id: 'shared-id' })
    const body = { requestId: 'synthetic-operation', expectedRevision: 7 }
    await mutateSession('shared-id', action as SessionAction, body)
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(`/api/interviews/shared-id/${suffix}`, expect.objectContaining({
      method: 'POST', credentials: 'same-origin', body: JSON.stringify(body),
    }))
  })

  it.each([404, 503])('综合面试 返回 %s 时显式失败，不能改调旧面试接口', async status => {
    const fetcher = mockEndpoint('/api/interviews/shared-id', { code: 'V3_UNAVAILABLE', message: '综合面试暂不可用' }, status)
    await expect(getSession('shared-id')).rejects.toMatchObject({ status, message: '综合面试暂不可用' })
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/interviews/shared-id', expect.any(Object))
  })
})

 it('快速练习保持业务类型与空资料，只创建现代会话', async () => {
  const fetcher = mockEndpoint('/api/interviews', { id: 'quick-created' })
  await createSession({ sessionType: 'QUICK', role: 'Java 后端工程师', level: 'JUNIOR', plannedQuestionCount: 5, resumeId: '', jdId: '', jd: '' }, 'quick-request')
  expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/interviews', expect.objectContaining({ method: 'POST' }))
  expect(JSON.parse(String(fetcher.mock.calls[0]![1]?.body))).toEqual({ sessionType: 'QUICK', role: 'Java 后端工程师', level: 'JUNIOR', plannedQuestionCount: 5, resumeId: '', jdId: '', jd: '', requestId: 'quick-request' })
})

it('Agent 复习预检使用专用同源 POST，输入只携带选题配置', async () => {
  const options = { role: 'Agent 应用工程师', level: 'JUNIOR', plannedQuestionCount: 3, agentTopics: ['AGENT_CONTEXT_MEMORY'] }
  const result = { eligible: true, requestedCount: 3, reviewAvailable: 1, newSupplementCount: 2, canStart: true, requiresAcknowledgement: true, message: '需要补新。' }
  const fetcher = mockEndpoint('/api/interviews/agent-practice-options', result)
  await expect(getAgentPracticeOptions(options)).resolves.toEqual(result)
  expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/interviews/agent-practice-options', expect.objectContaining({ method: 'POST', credentials: 'same-origin', body: JSON.stringify(options) }))
})
