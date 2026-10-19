import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Dashboard from './Dashboard.vue'
let wrapper: VueWrapper | undefined
const respond = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const current = { id: 'same-id', sessionType: 'QUICK', status: 'REPORT_READY', role: '新快速记录', level: 'JUNIOR', phase: '', mock: false, answerCount: 5, createdAt: '2026-10-19T02:00:00Z', updatedAt: '2026-10-19T02:00:00Z' }
const legacy = { id: 'same-id', mode: 'PROJECT', role: '旧项目记录', status: 'COMPLETED', mock: false, createdAt: '2026-10-19T01:00:00Z', messages: [{ role: 'USER', content: '回答一' }, { role: 'USER', content: '回答二' }] }
async function show(old: unknown[] = [], modern: unknown[] = [current], failModern = false) {
  const fetcher = vi.fn(async (url: string) => {
    if (url === '/api/sessions') return respond(old)
    if (url === '/api/interviews') return failModern ? respond({ message: '新面试列表不可用' }, 503) : respond(modern)
    if (url === '/api/materials') return respond([])
    if (url === '/api/health') return respond({ provider: 'openai', configured: true })
    throw new Error(`出现未约定接口：${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Dashboard }, ...['/configure/quick', '/configure/project', '/configure/resume', '/interviews', '/interviews/:id', '/sessions/:id', '/reports/:id', '/history', '/materials'].map(path => ({ path, component: { template: '<div />' } }))] })
  await router.push('/'); await router.isReady(); wrapper = mount(Dashboard, { global: { plugins: [router] } }); await flushPromises()
  return { router, fetcher }
}
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })
it('只有新快速记录也显示最近练习、完成数与真实回答次数', async () => {
  const { router, fetcher } = await show()
  expect(wrapper!.get('a[href="/interviews/same-id"]').text()).toContain('快速练习')
  expect(wrapper!.findAll('.stat-value').map(item => item.text().replace(/\s/g, ''))).toEqual(['1场', '5次', '0份'])
  expect(wrapper!.find('a[href="/interviews"]').exists()).toBe(true)
  await wrapper!.get('a[href="/interviews/same-id"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/interviews/same-id')
  expect(fetcher.mock.calls.map(([url]) => url).sort()).toEqual(['/api/health', '/api/interviews', '/api/materials', '/api/sessions'])
})
it('新旧同 ID 分别继续，统计合并回答且按真实时间排序', async () => {
  await show([legacy])
  const rows = wrapper!.findAll('.list-row')
  expect(rows.slice(0, 2).map(row => row.get('h3').text())).toEqual(['新快速记录', '旧项目记录'])
  expect(wrapper!.get('a[href="/reports/same-id"]').text()).toContain('旧项目记录')
  expect(wrapper!.findAll('.stat-value').map(item => item.text().replace(/\s/g, ''))).toEqual(['2场', '7次', '0份'])
})
it('新列表失败仍保留旧记录且明确展示统计不完整', async () => {
  await show([legacy], [], true)
  expect(wrapper!.get('a[href="/reports/same-id"]').text()).toContain('旧项目记录')
  expect(wrapper!.get('[role=alert]').text()).toContain('新面试列表不可用')
  expect(wrapper!.text()).toContain('统计不完整')
  expect(wrapper!.find('a[href="/interviews/same-id"]').exists()).toBe(false)
})
it('旧摘要没有回答次数时标注缺口，不能当作零次', async () => {
  const { answerCount: _count, ...withoutCount } = current
  await show([], [withoutCount])
  expect(wrapper!.text()).toContain('1 场记录未提供回答次数')
  expect(wrapper!.findAll('.stat-value')[1]!.text()).toContain('未提供')
})
