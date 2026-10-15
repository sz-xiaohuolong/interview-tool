import { describe, it, expect, vi, afterEach } from 'vitest'
import { request, ApiError } from './api'

afterEach(() => vi.unstubAllGlobals())
describe('API 客户端', () => {
  it('成功响应返回服务器数据，JSON 写入保持同源', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"id":"one"}', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    expect(await request('/materials', { method: 'POST', body: JSON.stringify({ name: '项目' }) })).toEqual({ id: 'one' })
    expect(fetcher).toHaveBeenCalledWith('/api/materials', expect.objectContaining({ credentials: 'same-origin', headers: expect.objectContaining({ 'Content-Type': 'application/json' }) }))
  })
  it('将服务端错误提示传递给页面并保留错误状态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"当前问题已变化，请刷新","code":"CONFLICT"}', { status: 409 })))
    await expect(request('/sessions/one/answers', { method: 'POST' })).rejects.toMatchObject({ message: '当前问题已变化，请刷新', status: 409, code: 'CONFLICT' })
  })
  it('不会把代理返回的 HTML 错误页当作成功', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>upstream diagnostic</html>', { status: 502 })))
    await expect(request('/health')).rejects.toBeInstanceOf(ApiError)
    await expect(request('/health')).rejects.not.toThrow('upstream diagnostic')
  })
  it('删除的空响应可正常结束', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    expect(await request('/materials/one', { method: 'DELETE' })).toBeUndefined()
  })
  it('上传时由浏览器设置 multipart 边界', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"id":"upload"}'))
    vi.stubGlobal('fetch', fetcher)
    const body = new FormData(); body.append('file', new Blob(['资料']), 'project.txt')
    await request('/materials/upload', { method: 'POST', body })
    expect(fetcher.mock.calls[0][1].headers).not.toHaveProperty('Content-Type')
  })
  it('网络断开给出可恢复提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(request('/health')).rejects.toThrow('无法连接后端')
  })
})
