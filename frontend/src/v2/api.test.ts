import { afterEach, describe, expect, it, vi } from 'vitest'
import { v2, writeV2, V2Error } from './api'

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
describe('V2 网络恢复', () => {
  it('保留版本冲突的恢复字段供页面保留两份文本', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'V2_VERSION_CONFLICT', message: '版本已更新', currentRevision: 3, nextAction: 'LOAD_LATEST' }), { status: 409 })))
    const error = await v2('/review-items/example').catch(e => e)
    expect(error).toBeInstanceOf(V2Error)
    if (!(error instanceof V2Error)) throw error
    expect(error.currentRevision).toBe(3)
    expect(error.nextAction).toBe('LOAD_LATEST')
  })
  it('响应丢失后显式重发复用请求ID，改变正文使用新ID', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', async (_: unknown, init: RequestInit) => { calls.push(String(init.body)); throw new Error('响应丢失') })
    await writeV2('/review-items/a/actions', { action: 'START', expectedVersion: 0 }).catch(() => {})
    await writeV2('/review-items/a/actions', { expectedVersion: 0, action: 'START' }).catch(() => {})
    await writeV2('/review-items/a/actions', { action: 'RETURN', expectedVersion: 0 }).catch(() => {})
    expect(JSON.parse(calls[0]!).requestId).toBe(JSON.parse(calls[1]!).requestId)
    expect(JSON.parse(calls[2]!).requestId).not.toBe(JSON.parse(calls[0]!).requestId)
  })
})
