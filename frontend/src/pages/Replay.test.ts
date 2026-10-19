import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import appRouter from '../router'
import { registerInterviewRoutes } from '../routing/interviews'
import type { Session } from '../types'
import type { InterviewSession } from '../models/interview'

const originalAnswer = '第一段：我的方案。\n\n第二段：保留原始换行。\n<script>不要执行</script>'
const session: Session = {
  id: 'same-id', role: 'Java 后端', status: 'COMPLETED', mode: 'PROJECT', level: 'JUNIOR', durationMinutes: 5,
  style: 'NORMAL', createdAt: '2026-10-18T02:00:00Z', updatedAt: '2026-10-18T02:05:00Z', endedAt: '2026-10-18T02:05:00Z',
  questionIndex: 0, followUpCount: 1, totalQuestions: 3, currentTopic: '', mock: true,
  error: '', pendingAction: '', reportStatus: 'READY', contextTruncated: false,
  messages: [
    { id: 'q1', role: 'ASSISTANT', content: '请介绍你的项目。', questionIndex: 0, followUp: false, createdAt: '2026-10-18T02:00:00Z' },
    { id: 'a1', role: 'USER', content: originalAnswer, questionIndex: 0, followUp: false, createdAt: '2026-10-18T02:01:00Z' },
    { id: 'q2', role: 'ASSISTANT', content: '如何验证异常边界？', questionIndex: 0, followUp: true, createdAt: '2026-10-18T02:01:00Z' },
  ],
}
const comprehensive: InterviewSession = {
  id: 'same-id', status: 'REPORT_READY', phase: 'REPORT', revision: 3, role: '综合项目面试', level: 'INTERN',
  durationMinutes: 20, createdAt: session.createdAt, updatedAt: session.updatedAt, mock: true,
  sources: [], warnings: [],
  messages: [
    { id: 'interview-q1', role: 'ASSISTANT', content: '你有什么问题想了解？', createdAt: session.createdAt },
    { id: 'interview-a1', role: 'USER', content: '团队的技术栈是什么？', createdAt: session.createdAt },
    { id: 'interview-reply', role: 'ASSISTANT', kind: 'REPLY', content: '团队主要使用 Java。', createdAt: session.updatedAt },
  ],
}
const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
let wrapper: VueWrapper | undefined
async function open(path = '/history/same-id/replay') {
  const router = createRouter({ history: createMemoryHistory(), routes: appRouter.options.routes })
  registerInterviewRoutes(router)
  await router.push(path)
  await router.isReady()
  expect(router.currentRoute.value.meta.title).toBe('对话回放')
  wrapper = mount(RouterView, { global: { plugins: [router] } })
  await flushPromises()
  return router
}
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals() })

it('直接打开旧版回放按保存顺序展示原文与双方身份，只有读取请求', async () => {
  const fetcher = vi.fn().mockResolvedValue(respond(session))
  vi.stubGlobal('fetch', fetcher)
  await open()
  const messages = wrapper!.findAll('.message')
  expect(messages.map(message => message.get('.message-content').element.textContent)).toEqual([
    '请介绍你的项目。', originalAnswer, '如何验证异常边界？',
  ])
  expect(messages[0].classes()).not.toContain('user')
  expect(messages[0].text()).toContain('面试官')
  expect(messages[1].classes()).toContain('user')
  expect(messages[1].text()).toContain('我的回答')
  expect(wrapper!.find('script, textarea, input, form').exists()).toBe(false)
  expect(wrapper!.get('a[href="/history"]').text()).toContain('返回面试历史')
  expect(wrapper!.get('a[href="/reports/same-id"]').text()).toContain('查看复盘')
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(fetcher).toHaveBeenCalledWith('/api/sessions/same-id', expect.not.objectContaining({ method: 'POST' }))
})

it('综合面试使用对应详情接口，完整保留反问阶段的面试官回复', async () => {
  const fetcher = vi.fn().mockResolvedValue(respond(comprehensive))
  vi.stubGlobal('fetch', fetcher)
  await open('/history/interviews/same-id/replay')
  expect(wrapper!.text()).toContain('综合项目面试')
  expect(wrapper!.findAll('.message-content').map(message => message.text())).toEqual([
    '你有什么问题想了解？', '团队的技术栈是什么？', '团队主要使用 Java。',
  ])
  expect(wrapper!.get('a[href="/interviews/same-id"]').text()).toContain('查看复盘')
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(fetcher).toHaveBeenCalledWith('/api/interviews/same-id', expect.not.objectContaining({ method: 'POST' }))
})

it('会话不存在或已删除时显示错误，允许重新读取并返回历史', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(respond({ message: '面试不存在或已删除' }, 404))
    .mockResolvedValueOnce(respond(session))
  vi.stubGlobal('fetch', fetcher)
  await open()
  expect(wrapper!.get('[role="alert"]').text()).toContain('面试不存在或已删除')
  expect(wrapper!.find('a[href="/history"]').exists()).toBe(true)
  await wrapper!.get('button').trigger('click')
  await flushPromises()
  expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
  expect(wrapper!.findAll('.message')).toHaveLength(3)
  expect(fetcher).toHaveBeenCalledTimes(2)
})

it('空对话显示空态，没有作答或生成操作', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond({ ...session, messages: [] })))
  await open()
  expect(wrapper!.text()).toContain('暂无对话记录')
  expect(wrapper!.find('textarea, form').exists()).toBe(false)
})

it('切换回放记录时清空旧内容，较晚返回的旧请求不能覆盖新场次', async () => {
  let resolveOld!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    .mockResolvedValueOnce(respond({ ...session, id: 'new-id', role: '新的面试', messages: [] })))
  const router = await open()
  await router.push('/history/new-id/replay')
  await flushPromises()
  expect(wrapper!.text()).toContain('新的面试')
  resolveOld(respond(session))
  await flushPromises()
  expect(wrapper!.text()).toContain('新的面试')
  expect(wrapper!.text()).not.toContain('请介绍你的项目。')
})

 it('快速练习回放保留业务标签并从现代接口读取', async () => {
  const fetcher = vi.fn(async (url: string) => {
    if (url === '/api/interviews/same-id') return respond({ ...comprehensive, sessionType: 'QUICK' })
    throw new Error(`出现未约定的接口：${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  await open('/history/interviews/same-id/replay')
  expect(wrapper!.get('.replay-summary').text()).toContain('快速练习')
  expect(wrapper!.get('a[href="/interviews/same-id"]').text()).toContain('查看复盘')
  expect(fetcher).toHaveBeenCalledTimes(1)
})
