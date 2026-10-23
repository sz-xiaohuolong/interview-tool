import { afterEach, expect, it, vi } from 'vitest'
import { jobSearch } from './job-search'
afterEach(() => vi.unstubAllGlobals())
it('搜索、详情、收藏及 JD 只使用冻结的路径、方法和正文', async () => {
  const expected: [string, string, unknown][] = [
    ['/api/job-search/cities', 'GET', undefined], ['/api/job-search/tasks', 'POST', { keyword: 'Java 后端', cityId: 'sz', sources: ['BOSS'] }],
    ['/api/job-search/tasks/t%2F1', 'GET', undefined], ['/api/job-search/tasks/t%2F1/more', 'POST', {}],
    ['/api/job-search/tasks/t%2F1/retry', 'POST', { source: 'LIEPIN' }], ['/api/job-search/tasks/t%2F1/stop', 'POST', {}],
    ['/api/job-search/tasks/t%2F1/jobs/BOSS%3Asame/detail', 'POST', {}], ['/api/job-search/favorites', 'GET', undefined],
    ['/api/job-search/favorites', 'POST', { taskId: 't/1', jobKeys: ['BOSS:same'], favoriteId: 'f1' }],
    ['/api/job-search/favorites/f%2F1', 'DELETE', undefined], ['/api/job-search/jd-links/lookup', 'POST', { keys: ['BOSS:same'] }],
    ['/api/job-search/jd', 'POST', { taskId: 't/1', jobKey: 'BOSS:same', name: '名称', content: '正文' }],
  ]
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    const [path, method, body] = expected.shift()!
    expect([url, options?.method || 'GET', options?.body ? JSON.parse(String(options.body)) : undefined]).toEqual([path, method, body])
    return new Response(method === 'DELETE' ? null : '{}', { status: method === 'DELETE' ? 204 : 200 })
  }))
  await jobSearch.cities(); await jobSearch.start({ keyword: 'Java 后端', cityId: 'sz', sources: ['BOSS'] }); await jobSearch.task('t/1')
  await jobSearch.more('t/1'); await jobSearch.retry('t/1', 'LIEPIN'); await jobSearch.stop('t/1'); await jobSearch.detail('t/1', 'BOSS:same')
  await jobSearch.favorites(); await jobSearch.favorite({ taskId: 't/1', jobKeys: ['BOSS:same'], favoriteId: 'f1' }); await jobSearch.removeFavorite('f/1')
  await jobSearch.lookup(['BOSS:same']); await jobSearch.saveJd({ taskId: 't/1', jobKey: 'BOSS:same', name: '名称', content: '正文' })
  expect(expected).toHaveLength(0)
})
it('新搜索来源失败时不改调旧投递或面试接口', async () => {
  const fetch = vi.fn(async (url: string) => { expect(url).toBe('/api/job-search/tasks'); return new Response(JSON.stringify({ message: '来源不可用' }), { status: 503 }) })
  vi.stubGlobal('fetch', fetch)
  await expect(jobSearch.start({ keyword: 'Java', cityId: 'sz', sources: ['BOSS'] })).rejects.toThrow('来源不可用')
  expect(fetch).toHaveBeenCalledTimes(1)
})
