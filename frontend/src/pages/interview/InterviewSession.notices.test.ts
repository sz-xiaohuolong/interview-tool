import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Session from './InterviewSession.vue'
import * as interviews from '../../api/interviews'
import type { InterviewSession } from '../../models/interview'

const fallback = '本轮未及时取得有效即时追问，已切换本场已校验备用问题'
const baseline = '本场为无资料的通用基准摸底，不代表已针对个人经历分析'
const failure = '本轮没有合适备用问题，请重试或进入下一题'
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals(); sessionStorage.clear() })

function fixture(extra: Partial<InterviewSession> = {}): InterviewSession {
  return {
    id: 'notice-test', sessionType: 'QUICK', status: 'INTERRUPTED', phase: '', revision: 7,
    role: 'Java 后端工程师', level: 'JUNIOR', durationMinutes: 10, plannedQuestionCount: 3,
    createdAt: '2026-10-19T08:00:00Z', updatedAt: '2026-10-19T08:00:00Z', mock: false,
    messages: [{ id: 'q1', role: 'ASSISTANT', questionId: 'q1', content: '怎样处理重复请求？', createdAt: '2026-10-19T08:00:00Z', fallback: true }],
    sources: [], warnings: [baseline, fallback, fallback], error: failure, allowedActions: ['retry', 'next', 'finish'],
    ...extra,
  }
}

async function show(value = fixture()) {
  const writes: { url: string; body: unknown }[] = []
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/interviews/notice-test' && !options?.method) return new Response(JSON.stringify(value))
    if (['/api/interviews/notice-test/retry', '/api/interviews/notice-test/next'].includes(url) && options?.method === 'POST') {
      writes.push({ url, body: JSON.parse(String(options.body)) })
      return new Response(JSON.stringify({ ...value, status: 'IN_PROGRESS', revision: 8, error: '', allowedActions: ['answers', 'finish'] }))
    }
    throw new Error(`出现未约定接口：${options?.method || 'GET'} ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/interviews/:id', component: Session }] })
  await router.push('/interviews/notice-test'); await router.isReady()
  const wrapper = mount(Session, { global: { plugins: [router] } }); wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, writes, fetcher }
}

it('重复过程提示合并并保留次数，默认收起，当前错误留在恢复操作旁', async () => {
  const { wrapper } = await show()
  const records = wrapper.get<HTMLDetailsElement>('.interview-session-notices')
  expect(records.element.open).toBe(false)
  expect(records.get('summary').text()).toContain('2 项提示')
  expect(records.get('summary').text()).toContain('3 条记录')
  expect(records.findAll('li')).toHaveLength(2)
  expect(records.findAll('li').filter(item => item.text().includes(fallback))).toHaveLength(1)
  expect(records.findAll('li')[1]!.text()).toContain('记录 2 次')
  expect(records.find('[role=alert]').exists()).toBe(false)
  const recovery = wrapper.get('.interview-recovery')
  expect(recovery.get('[role=alert]').text()).toBe(failure)
  expect(recovery.find('[data-action=retry]').exists()).toBe(true)
  expect(recovery.find('[data-action=next]').exists()).toBe(true)
  expect(recovery.element.compareDocumentPosition(wrapper.get('.interview-dialogue').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(wrapper.findAll('[role=alert]')).toHaveLength(1)
  expect(wrapper.get('.message-meta .badge').text()).toContain('备用问题')
})

it('合并展示不改写服务端警告数组，也不把不同提示误合并', async () => {
  const warnings = Object.freeze([fallback, baseline, fallback, `${fallback}，请检查连接`]) as unknown as string[]
  vi.spyOn(interviews, 'getSession').mockResolvedValue(fixture({ warnings }))
  const { wrapper } = await show()
  const records = wrapper.get('.interview-session-notices')
  expect(records.findAll('li')).toHaveLength(3)
  expect(records.findAll('li').map(item => item.get('.interview-notice-text').text())).toEqual([fallback, baseline, `${fallback}，请检查连接`])
  expect(warnings).toEqual([fallback, baseline, fallback, `${fallback}，请检查连接`])
})

it.each([undefined, []])('没有提示记录时不显示空折叠区：%s', async warnings => {
  const { wrapper } = await show(fixture({ warnings, status: 'IN_PROGRESS', error: '', allowedActions: ['answers', 'finish'] }))
  expect(wrapper.find('.interview-session-notices').exists()).toBe(false)
  expect(wrapper.find('[role=alert]').exists()).toBe(false)
})

it.each([1, 2])('第%s版复盘沿用合并提示，长文本完整保留', async version => {
  const long = '需要核对的技术依据与异常边界。'.repeat(100)
  const { wrapper } = await show(fixture({ status: 'REPORT_READY', warnings: [long, long], error: '', report: { version, summary: '合成复盘', findings: [] }, allowedActions: [] }))
  const records = wrapper.get<HTMLDetailsElement>('.interview-session-notices')
  expect(records.element.open).toBe(false)
  expect(records.findAll('li')).toHaveLength(1)
  expect(records.get('.interview-notice-text').text()).toBe(long)
  expect(wrapper.find('.interview-recovery').exists()).toBe(false)
})

it.each(['retry', 'next'] as const)('恢复动作%s仍提交原接口和版本，收起记录不会触发请求', async action => {
  const { wrapper, writes, fetcher } = await show()
  expect(fetcher.mock.calls).toHaveLength(1)
  expect(writes).toHaveLength(0)
  await wrapper.get(`[data-action=${action}]`).trigger('click'); await flushPromises()
  expect(writes).toEqual([{ url: `/api/interviews/notice-test/${action}`, body: { requestId: expect.any(String), expectedRevision: 7 } }])
  expect(wrapper.find('[role=alert]').exists()).toBe(false)
  expect(wrapper.get<HTMLDetailsElement>('.interview-session-notices').element.open).toBe(false)
})

it('只展示服务端允许的恢复操作，非中断错误也不会被收进历史记录', async () => {
  const first = await show(fixture({ status: 'FAILED', allowedActions: ['retry'] }))
  expect(first.wrapper.find('[data-action=next]').exists()).toBe(false)
  expect(first.wrapper.find('[data-action=retry]').exists()).toBe(true)
  const second = await show(fixture({ status: 'IN_PROGRESS', allowedActions: ['answers', 'finish'] }))
  expect(second.wrapper.get('[role=alert]').text()).toBe(failure)
  expect(second.wrapper.get('[role=alert]').element.closest('details')).toBeNull()
})

it('历史提示使用新文案展示且不改写原始失败记录', async () => {
  const value = fixture({ warnings: ['来源未获取：官方资料；相关技术依据不足', '官方说明：重新获取失败，使用此前有效缓存'] })
  const { wrapper } = await show(value)
  const records = wrapper.get('.interview-session-notices')
  expect(records.text()).toContain('本次未获取到参考文档：官方资料')
  expect(records.text()).toContain('官方说明：已使用本地缓存')
  expect(records.text()).not.toContain('来源未获取')
  expect(value.warnings[1]).toContain('重新获取失败')
})
