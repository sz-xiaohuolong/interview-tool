import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import History from './History.vue'
import type { Session } from '../types'

const legacy: Session = {
  id: 'shared-id', role: '旧版项目面试', status: 'IN_PROGRESS', mode: 'PROJECT', level: 'JUNIOR', durationMinutes: 5,
  style: 'NORMAL', createdAt: '2026-10-17T02:00:00Z', updatedAt: '2026-10-17T02:00:00Z', endedAt: null,
  questionIndex: 0, followUpCount: 0, totalQuestions: 3, currentTopic: '', messages: [], mock: true,
  error: '', pendingAction: '', reportStatus: 'NONE', contextTruncated: false,
}
// V3 列表只返回摘要，不包含 messages、report 等详情。
const comprehensive = {
  id: 'shared-id', role: '综合 Agent 面试', status: 'IN_PROGRESS', phase: 'INTERVIEW', level: 'INTERN', mock: true,
  createdAt: '2026-10-18T02:00:00Z', updatedAt: '2026-10-18T02:00:00Z',
}
const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
let wrapper: VueWrapper | undefined
async function render(v3Enabled = true) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/history', component: History },
    { path: '/configure/quick', component: { template: '<div />' } },
    { path: '/sessions/:id', component: { template: '<div />' } },
    { path: '/reports/:id', component: { template: '<div />' } },
    ...(v3Enabled ? [{ path: '/interview-v3/:id', component: { template: '<div />' } }] : []),
  ] })
  await router.push('/history')
  await router.isReady()
  wrapper = mount(History, { global: { plugins: [router] } })
  await flushPromises()
  return router
}
async function selectTab(label: string) {
  await wrapper!.findAll('.tab').find(tab => tab.text() === label)!.trigger('click')
}
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })

describe('综合面试进入统一练习记录', () => {
  it('合并两类记录并按创建时间倒序，同 ID 不冲突，待继续使用各自入口', async () => {
    const fetcher = vi.fn(async (url: string) => respond(url === '/api/v3/sessions' ? [comprehensive] : [legacy]))
    vi.stubGlobal('fetch', fetcher)
    const router = await render()
    await selectTab('待继续')
    const rows = wrapper!.findAll('.list-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('综合 Agent 面试')
    expect(rows[0].text()).toContain('综合面试')
    expect(rows[0].text()).toContain('面试进行中')
    expect(rows[0].text()).not.toContain('已回答')
    expect(rows[1].text()).toContain('旧版项目面试')
    expect(wrapper!.get('a[href="/sessions/shared-id"]').text()).toContain('旧版项目面试')
    await wrapper!.get('a[href="/interview-v3/shared-id"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/interview-v3/shared-id')
    expect(fetcher.mock.calls.map(([url]) => url).sort()).toEqual(['/api/sessions', '/api/v3/sessions'])
  })

  it.each([
    ['QUEUED', false, '排队中', '继续查看'],
    ['PREPARING', false, '准备中', '继续查看'],
    ['READY', false, '待确认开考', '继续查看'],
    ['IN_PROGRESS', false, '面试进行中', '继续面试'],
    ['ANSWERING', false, '面试官思考中', '继续面试'],
    ['FAILED', false, '任务失败', '继续查看'],
    ['INTERRUPTED', false, '已中断，等待恢复', '继续查看'],
    ['COMPLETED', true, '面试已完成，待复盘', '生成复盘'],
    ['REPORTING', true, '查证复盘中', '查看进度'],
    ['REPORT_READY', true, '复盘已完成', '查看复盘'],
    ['CANCELLED', true, '已取消', '查看记录'],
  ])('综合面试状态 %s 显示正确筛选、状态和操作', async (status, done, label, action) => {
    vi.stubGlobal('fetch', async (url: string) => respond(url === '/api/v3/sessions' ? [{ ...comprehensive, status }] : []))
    await render()
    await selectTab(done ? '待继续' : '已完成')
    expect(wrapper!.findAll('.list-row')).toHaveLength(0)
    await selectTab(done ? '已完成' : '待继续')
    const link = wrapper!.get('a[href="/interview-v3/shared-id"]')
    expect(link.text()).toContain(label)
    expect(link.get('.text-link').text()).toContain(action)
    expect(link.element.closest('.list-row')!.querySelector('button')).toBeNull()
  })

  it.each(['/api/sessions', '/api/v3/sessions'])('一侧加载失败时仍显示另一侧记录：%s', async (failedUrl) => {
    vi.stubGlobal('fetch', async (url: string) => url === failedUrl
      ? respond({ message: '本次加载失败' }, 503)
      : respond(url === '/api/v3/sessions' ? [comprehensive] : [legacy]))
    await render()
    expect(wrapper!.findAll('.list-row')).toHaveLength(1)
    expect(wrapper!.text()).toContain(failedUrl === '/api/sessions' ? '综合 Agent 面试' : '旧版项目面试')
    expect(wrapper!.get('[role="alert"]').text()).toContain('本次加载失败')
  })

  it('未启用 V3 的旧入口不请求综合面试接口，保留旧复盘路由', async () => {
    const fetcher = vi.fn(async () => respond([{ ...legacy, status: 'COMPLETED' }]))
    vi.stubGlobal('fetch', fetcher)
    await render(false)
    await selectTab('已完成')
    expect(wrapper!.get('a[href="/reports/shared-id"]').text()).toContain('旧版项目面试')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledWith('/api/sessions', expect.any(Object))
  })
})
