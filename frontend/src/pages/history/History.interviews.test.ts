import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import History from './History.vue'
import type { Session } from '../../types'

const legacy: Session = {
  id: 'shared-id', role: '旧版项目面试', status: 'IN_PROGRESS', mode: 'PROJECT', level: 'JUNIOR', durationMinutes: 5,
  style: 'NORMAL', createdAt: '2026-10-17T02:00:00Z', updatedAt: '2026-10-17T02:00:00Z', endedAt: null,
  questionIndex: 0, followUpCount: 0, totalQuestions: 3, currentTopic: '', messages: [], mock: true,
  error: '', pendingAction: '', reportStatus: 'NONE', contextTruncated: false,
}
// 综合面试 列表只返回摘要，不包含 messages、report 等详情。
const comprehensive = {
  id: 'shared-id', role: '综合 Agent 面试', status: 'IN_PROGRESS', phase: 'INTERVIEW', level: 'INTERN', mock: true,
  createdAt: '2026-10-18T02:00:00Z', updatedAt: '2026-10-18T02:00:00Z',
}
const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
let wrapper: VueWrapper | undefined
async function render(interviewEnabled = true) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/history', component: History },
    { path: '/configure/quick', component: { template: '<div />' } },
    { path: '/sessions/:id', component: { template: '<div />' } },
    { path: '/reports/:id', component: { template: '<div />' } },
    { path: '/history/:id/replay', component: { template: '<div />' } },
    { path: '/history/interviews/:id/replay', component: { template: '<div />' } },
    ...(interviewEnabled ? [{ path: '/interviews/:id', component: { template: '<div />' } }] : []),
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
  it('同 ID 的已完成新旧面试使用各自回放入口', async () => {
    const fetcher = vi.fn(async (url: string) => respond(url === '/api/interviews'
      ? [{ ...comprehensive, status: 'REPORT_READY' }] : [{ ...legacy, status: 'COMPLETED' }]))
    vi.stubGlobal('fetch', fetcher)
    const router = await render()
    expect(wrapper!.find('a[href="/history/shared-id/replay"]').exists()).toBe(true)
    const replay = wrapper!.find('a[href="/history/interviews/shared-id/replay"]')
    expect(replay.exists()).toBe(true)
    expect(replay.element.parentElement!.closest('a')).toBeNull()
    await replay.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/history/interviews/shared-id/replay')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('合并两类记录并按创建时间倒序，同 ID 不冲突，待继续使用各自入口', async () => {
    const fetcher = vi.fn(async (url: string) => respond(url === '/api/interviews' ? [comprehensive] : [legacy]))
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
    await wrapper!.get('a[href="/interviews/shared-id"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/interviews/shared-id')
    expect(fetcher.mock.calls.map(([url]) => url).sort()).toEqual(['/api/interviews', '/api/sessions'])
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
    vi.stubGlobal('fetch', async (url: string) => respond(url === '/api/interviews' ? [{ ...comprehensive, status }] : []))
    await render()
    await selectTab(done ? '待继续' : '已完成')
    expect(wrapper!.findAll('.list-row')).toHaveLength(0)
    await selectTab(done ? '已完成' : '待继续')
    const link = wrapper!.get('a[href="/interviews/shared-id"]')
    expect(link.text()).toContain(label)
    expect(link.element.closest('.list-row')!.querySelector('.history-actions .text-link')!.textContent).toContain(action)
    expect(link.element.closest('.list-row')!.querySelector('button')!.getAttribute('aria-label')).toBe('删除综合 Agent 面试的面试记录')
  })

  it.each(['/api/interviews', '/api/sessions'])('一侧加载失败时仍显示另一侧记录：%s', async (failedUrl) => {
    vi.stubGlobal('fetch', async (url: string) => url === failedUrl
      ? respond({ message: '本次加载失败' }, 503)
      : respond(url === '/api/interviews' ? [comprehensive] : [legacy]))
    await render()
    expect(wrapper!.findAll('.list-row')).toHaveLength(1)
    expect(wrapper!.text()).toContain(failedUrl === '/api/sessions' ? '综合 Agent 面试' : '旧版项目面试')
    expect(wrapper!.get('[role="alert"]').text()).toContain('本次加载失败')
  })

  it('未启用 综合面试 的旧入口不请求综合面试接口，保留旧复盘路由', async () => {
    const fetcher = vi.fn(async () => respond([{ ...legacy, status: 'COMPLETED' }]))
    vi.stubGlobal('fetch', fetcher)
    await render(false)
    await selectTab('已完成')
    expect(wrapper!.get('a[href="/reports/shared-id"]').text()).toContain('旧版项目面试')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledWith('/api/sessions', expect.any(Object))
  })
})

describe('综合面试删除', () => {
  function row(name = '综合 Agent 面试') { return wrapper!.findAll('.history-row').find(item => item.text().includes(name))! }
  function remove() { return row().find('button.history-delete') }
  it('只有新版记录时也提供删除，取消确认不发送写请求', async () => {
    const fetcher = vi.fn(async (url: string) => {
      if (url === '/api/sessions') return respond([])
      if (url === '/api/interviews') return respond([{ ...comprehensive, status: 'REPORT_READY' }])
      throw new Error(`出现未约定的接口：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(false))
    const router = await render()
    expect(remove().exists()).toBe(true)
    await remove().trigger('click')
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(row().text()).toContain('查看复盘')
    expect(router.currentRoute.value.path).toBe('/history')
  })

  it('删除新版同名 ID 只调用 综合面试 接口并保留旧版记录', async () => {
    const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
      if (url === '/api/interviews/shared-id' && options?.method === 'DELETE') return new Response(null, { status: 204 })
      if (url === '/api/sessions' && !options?.method) return respond([legacy])
      if (url === '/api/interviews' && !options?.method) return respond([{ ...comprehensive, status: 'REPORT_READY' }])
      throw new Error(`出现未约定的接口：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    const router = await render()
    expect(remove().exists()).toBe(true)
    expect(remove().element.closest('a')).toBeNull()
    await remove().trigger('click')
    await flushPromises()
    expect(wrapper!.text()).not.toContain('综合 Agent 面试')
    expect(wrapper!.get('a[href="/sessions/shared-id"]').text()).toContain('旧版项目面试')
    expect(fetcher).toHaveBeenLastCalledWith('/api/interviews/shared-id', expect.objectContaining({ method: 'DELETE' }))
    expect(router.currentRoute.value.path).toBe('/history')
  })

  it('删除中防重复，失败保留新版与旧版同名 ID，重试不会切换旧接口', async () => {
    let rejectDelete!: (error: Error) => void
    let deletions = 0
    const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
      if (url === '/api/interviews/shared-id' && options?.method === 'DELETE') {
        deletions++
        if (deletions === 1) return new Promise<Response>((_, reject) => { rejectDelete = reject })
        return new Response(null, { status: 204 })
      }
      if (url === '/api/sessions') return respond([legacy])
      if (url === '/api/interviews') return respond([comprehensive])
      throw new Error(`出现未约定的接口：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    await render()
    expect(remove().exists()).toBe(true)
    await remove().trigger('click')
    await remove().trigger('click')
    expect(remove().attributes('disabled')).toBeDefined()
    expect(row('旧版项目面试').get('button').text()).toBe('删除')
    expect(deletions).toBe(1)
    rejectDelete(new Error('合成网络错误'))
    await flushPromises()
    expect(wrapper!.get('[role="alert"]').text()).toContain('无法连接后端')
    expect(wrapper!.findAll('.history-row')).toHaveLength(2)
    await remove().trigger('click')
    await flushPromises()
    expect(wrapper!.findAll('.history-row')).toHaveLength(1)
    expect(wrapper!.text()).toContain('旧版项目面试')
    expect(deletions).toBe(2)
  })
})
