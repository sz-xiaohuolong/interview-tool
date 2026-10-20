import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Session from './InterviewSession.vue'
import type { InterviewSession, KnowledgeSource } from '../../models/interview'

const source: KnowledgeSource = {
  id: 'official-1', title: 'Java 21 并发工具包官方 API', kind: 'OFFICIAL',
  url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html',
  version: '21', fetchedAt: '2026-10-18T02:00:00Z', topics: ['JAVA'],
  excerpt: '此处为合成来源摘录。\n保留完整段落与换行，供复盘回查。',
  sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
}
const hosts: HTMLElement[] = [], wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0
  hosts.forEach(host => host.remove()); hosts.length = 0
  history.replaceState(null, '', '/')
  vi.unstubAllGlobals(); sessionStorage.clear()
})

async function show(extra: Partial<InterviewSession> = {}) {
  const value: InterviewSession = {
    id: 'sources-test', status: 'IN_PROGRESS', phase: 'IN_PROGRESS', revision: 1,
    role: 'Java 后端工程师', level: 'JUNIOR', durationMinutes: 10, resumeId: 'resume',
    mock: true, createdAt: '2026-10-18T02:00:00Z', updatedAt: '2026-10-18T02:00:00Z',
    messages: [{ id: 'q1', questionId: 'q1', role: 'ASSISTANT', content: '如何处理并发任务的异常？', createdAt: '2026-10-18T02:00:00Z' }],
    sources: [source], warnings: [], report: null, ...extra,
  }
  const requests: { url: string; method: string }[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    const method = options?.method || 'GET'; requests.push({ url, method })
    if (method !== 'GET') throw new Error(`测试禁止写入：${method} ${url}`)
    if (url === '/api/materials') return new Response('[]')
    if (url === '/api/interviews/sources-test') return new Response(JSON.stringify(value))
    if (url === '/api/review-gaps/V3/sources-test') return new Response('{"items":[]}')
    throw new Error(`未约定的测试请求：${method} ${url}`)
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/interviews/:id', component: Session }, { path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/interviews/sources-test'); await router.isReady()
  const host = document.createElement('div'); document.body.append(host); hosts.push(host)
  const wrapper = mount(Session, { attachTo: host, global: { plugins: [router] } }); wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, requests }
}

it('知识依据聚合默认收起，位于结束操作之后；展开不提交回答或修改草稿', async () => {
  const { wrapper, requests } = await show()
  await wrapper.get('#interview-answer').setValue('待校对的并发处理方案')
  const disclosure = wrapper.get<HTMLDetailsElement>('.interview-sources-disclosure')
  expect(disclosure.element.open).toBe(false)
  expect(disclosure.get('summary').text()).toContain('1 份资料')
  expect(wrapper.get('[data-action="finish"]').element.compareDocumentPosition(disclosure.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  disclosure.get<HTMLElement>('summary').element.click(); await flushPromises()
  expect(disclosure.element.open).toBe(true)
  wrapper.get<HTMLElement>('[data-source="official-1"] summary').element.click(); await flushPromises()
  expect(wrapper.get('[data-source="official-1"]').text()).toContain(source.excerpt)
  expect(wrapper.get('[data-source="official-1"]').text()).toContain(source.sha256)
  expect(wrapper.get('[data-source="official-1"]').text()).toContain('版本：21')
  expect(wrapper.get('[data-source="official-1"] a').attributes()).toMatchObject({ href: source.url, target: '_blank', rel: 'noopener noreferrer' })
  expect((wrapper.get('#interview-answer').element as HTMLTextAreaElement).value).toBe('待校对的并发处理方案')
  expect(requests.every(request => request.method === 'GET')).toBe(true)
})

it.each([1, 2])('第%s版复盘的来源锚点展开总入口及对应来源，并将焦点移至来源标题', async version => {
  const { wrapper } = await show({ status: 'REPORT_READY', report: {
    version, summary: '合成复盘', findings: [{ questionId: 'q1', assessment: '需要说明异常边界', status: 'SUPPORTED', sourceIds: ['official-1'], suggestion: '补充处理步骤', answerStatus: 'PARTIAL', answerQuote: '捕获异常' }],
  } })
  expect(wrapper.get('a[href="#interview-source-official-1"]').attributes('href')).toBe('#interview-source-official-1')
  history.replaceState(null, '', '/#interview-source-official-1')
  window.dispatchEvent(new HashChangeEvent('hashchange')); await flushPromises()
  expect(wrapper.get<HTMLDetailsElement>('.interview-sources-disclosure').element.open).toBe(true)
  expect(wrapper.get<HTMLDetailsElement>('#interview-source-official-1').element.open).toBe(true)
  expect(document.activeElement).toBe(wrapper.get('#interview-source-official-1 summary').element)
})

it('初始来源深链在异步场次载入后可见，未知锚点不展开知识依据', async () => {
  history.replaceState(null, '', '/#interview-source-official-1')
  const first = await show()
  expect(first.wrapper.get<HTMLDetailsElement>('.interview-sources-disclosure').element.open).toBe(true)
  expect(first.wrapper.get<HTMLDetailsElement>('#interview-source-official-1').element.open).toBe(true)
  first.wrapper.unmount(); wrappers.splice(wrappers.indexOf(first.wrapper), 1)
  history.replaceState(null, '', '/#unrelated')
  const second = await show()
  expect(second.wrapper.get<HTMLDetailsElement>('.interview-sources-disclosure').element.open).toBe(false)
})

it('来源收起后重复点击相同锚点，仍展开摘录并聚焦标题', async () => {
  history.replaceState(null, '', '/#interview-source-official-1')
  const { wrapper } = await show({ status: 'REPORT_READY', report: {
    summary: '合成复盘', findings: [{ questionId: 'q1', assessment: '核对异常边界', status: 'SUPPORTED', sourceIds: ['official-1'], suggestion: '补充步骤' }],
  } })
  wrapper.get<HTMLElement>('#interview-source-official-1 > summary').element.click()
  wrapper.get<HTMLElement>('.interview-sources-summary').element.click()
  await flushPromises()
  expect(wrapper.get<HTMLDetailsElement>('#interview-source-official-1').element.open).toBe(false)
  wrapper.get<HTMLAnchorElement>('a[href="#interview-source-official-1"]').element.click()
  await flushPromises()
  expect(wrapper.get<HTMLDetailsElement>('.interview-sources-disclosure').element.open).toBe(true)
  expect(wrapper.get<HTMLDetailsElement>('#interview-source-official-1').element.open).toBe(true)
  expect(document.activeElement).toBe(wrapper.get('#interview-source-official-1 > summary').element)
})

it('无来源不显示空折叠入口，无公开链接或指纹时不伪造补足', async () => {
  const empty = await show({ sources: [] })
  expect(empty.wrapper.find('.interview-sources').exists()).toBe(false)
  empty.wrapper.unmount(); wrappers.splice(wrappers.indexOf(empty.wrapper), 1)
  const missing = await show({ sources: [{ ...source, url: 'javascript:alert(1)', sha256: '', version: '', fetchedAt: '' }] })
  expect(missing.wrapper.get('[data-source]').text()).toContain('没有可打开的公开链接')
  expect(missing.wrapper.get('[data-source]').text()).toContain('版本：未标明')
  expect(missing.wrapper.get('[data-source]').text()).toContain('时间未提供')
  expect(missing.wrapper.find('[data-source] a').exists()).toBe(false)
  expect(missing.wrapper.find('.interview-hash').exists()).toBe(false)
})
