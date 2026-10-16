import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import ReviewPage from './ReviewPage.vue'

afterEach(() => vi.unstubAllGlobals())
const source = { id: 'r1', revision: 0, question: '如何保证订单一致性？', status: 'PENDING_CONFIRMATION', sourceSessionId: 's1', questionIndex: 0, sourcePrecision: 'QUESTION_ONLY', mock: true, skillIds: [], findings: [{ id: 'f1', kind: 'uncertain', text: '资料不足，无法判断事务行为', confirmed: false, disputed: false }], dialogue: [{ id: 'm1', role: 'USER', content: '这是我的原始回答。' }] }
async function show(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:all(.*)', component: ReviewPage }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(ReviewPage, { global: { plugins: [router] } }); await flushPromises(); return wrapper
}
it('空复习队列给出原有面试入口，不显示虚构项目', async () => {
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ items: [], nextCursor: null })))
  const wrapper = await show('/review')
  expect(wrapper.text()).toContain('还没有复习项')
  expect(wrapper.find('a[href="/configure/quick"]').exists()).toBe(true)
})
it('确认冲突时保留原始证据，并显示重新读取入口', async () => {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') return new Response(JSON.stringify({ code: 'V2_VERSION_CONFLICT', message: '另一页面已修改，请保留本页内容', currentRevision: 1 }), { status: 409 })
    return new Response(JSON.stringify(url.includes('/skills') ? { catalog: { skills: [] } } : source))
  })
  const wrapper = await show('/review?id=r1')
  await wrapper.find('[data-action="confirm"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('另一页面已修改')
  expect(wrapper.text()).toContain('这是我的原始回答。')
  expect(wrapper.find('[data-action="reload"]').exists()).toBe(true)
})
it('新版没有发现时可核对旧版本和历史标签，不提供空确认', async () => {
  const old = { ...source, status: 'DUE', reportHash: 'old-report', skillIds: ['JAVA.TRANSACTIONS'] }
  const updated = { ...source, findings: [], reportHash: 'new-report', sourceVersions: [old] }
  vi.stubGlobal('fetch', async (url: string) => {
    const result = url.includes('/skills') ? { catalog: { skills: [{ id: 'JAVA.TRANSACTIONS', name: 'Java 事务' }] } } : url.includes('/history?') ? { items: [{ id: 'op', createdAt: '2026-10-15T00:00:00Z' }], nextCursor: null } : url.endsWith('/history/op') ? old : updated
    return new Response(JSON.stringify(result))
  })
  const wrapper = await show('/review?id=r1')
  expect(wrapper.find('[data-action="confirm"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('这版报告没有待练发现')
  const versions = wrapper.findAll('details').find(d => d.get('summary').text().startsWith('查看来源报告版本'))!
  expect(versions.text()).toContain('资料不足，无法判断事务行为')
  const history = wrapper.findAll('details').find(d => d.get('summary').text() === '查看确认、补证与安排历史')!
  ;(history.element as HTMLDetailsElement).open = true
  await history.trigger('toggle'); await flushPromises()
  await history.get('button').trigger('click'); await flushPromises()
  expect(history.text()).toContain('当时标签：Java 事务')
  wrapper.unmount()
})
