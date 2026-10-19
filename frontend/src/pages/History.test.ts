import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import History from './History.vue'
import type { Session } from '../types'

const session: Session = {
  id: 'test-session', role: '测试面试', status: 'IN_PROGRESS', mode: 'QUICK', level: 'JUNIOR', durationMinutes: 5,
  style: 'NORMAL', createdAt: '2026-10-18T02:00:00Z', updatedAt: '2026-10-18T02:00:00Z', endedAt: null,
  questionIndex: 0, followUpCount: 0, totalQuestions: 3, currentTopic: '', messages: [], mock: true,
  error: '', pendingAction: '', reportStatus: 'NONE', contextTruncated: false,
}
const completed: Session = { ...session, id: 'kept-session', role: '保留的面试', status: 'COMPLETED' }
const respond = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status })
let wrapper: VueWrapper | undefined
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/history', component: History },
    { path: '/sessions/:id', component: { template: '<div />' } },
    { path: '/reports/:id', component: { template: '<div />' } },
    { path: '/history/:id/replay', component: { template: '<div />' } },
  ] })
  await router.push('/history')
  await router.isReady()
  wrapper = mount(History, { global: { plugins: [router] } })
  await flushPromises()
  return router
}
function row() { return wrapper!.findAll('.list-row').find(item => item.text().includes('测试面试'))! }
function removeButton() { return row().get('button') }
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })

it('已完成面试的复盘左侧提供独立回放入口，点击只导航', async () => {
  const fetcher = vi.fn().mockResolvedValue(respond([session, completed]))
  vi.stubGlobal('fetch', fetcher)
  const router = await render()
  const completedRow = wrapper!.findAll('.history-row').find(item => item.text().includes('保留的面试'))!
  const replay = completedRow.find('a[href="/history/kept-session/replay"]')
  expect(replay.exists()).toBe(true)
  expect(replay.element.parentElement!.closest('a')).toBeNull()
  const actions = completedRow.findAll('.history-actions a').map(link => link.text())
  expect(actions).toEqual(['回放', '查看复盘'])
  expect(row().text()).not.toContain('回放')
  await replay.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/history/kept-session/replay')
  expect(fetcher).toHaveBeenCalledTimes(1)
})

describe('面试历史删除', () => {
  it('取消确认时保留记录和当前位置', async () => {
    const fetcher = vi.fn().mockResolvedValue(respond([session, completed]))
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(false))
    const router = await render()
    await removeButton().trigger('click')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(wrapper!.text()).toContain('测试面试')
    expect(router.currentRoute.value.path).toBe('/history')
  })

  it('确认后只删除选中记录，删除按钮独立于面试链接', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(respond([session, completed])).mockResolvedValueOnce(respond(null, 204))
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    const router = await render()
    expect(removeButton().element.closest('a')).toBeNull()
    await removeButton().trigger('click')
    await flushPromises()
    expect(fetcher).toHaveBeenLastCalledWith('/api/sessions/test-session', expect.objectContaining({ method: 'DELETE' }))
    expect(wrapper!.text()).not.toContain('测试面试')
    expect(wrapper!.get('a[href="/reports/kept-session"]').text()).toContain('保留的面试')
    expect(router.currentRoute.value.path).toBe('/history')
  })

  it('请求未完成时防止重复删除', async () => {
    let resolveDelete!: (response: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(respond([session, completed]))
      .mockImplementation(() => new Promise(resolve => { resolveDelete = resolve }))
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    await render()
    await removeButton().trigger('click')
    await removeButton().trigger('click')
    expect(removeButton().attributes('disabled')).toBeDefined()
    expect(fetcher).toHaveBeenCalledTimes(2)
    resolveDelete(respond(null, 204))
    await flushPromises()
    expect(wrapper!.text()).not.toContain('测试面试')
  })

  it('失败时保留记录并显示可重试的错误', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(respond([session]))
      .mockResolvedValueOnce(respond({ message: '删除失败，请重试' }, 500)).mockResolvedValueOnce(respond(null, 204))
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    await render()
    await removeButton().trigger('click')
    await flushPromises()
    expect(wrapper!.get('[role="alert"]').text()).toContain('删除失败')
    expect(wrapper!.text()).toContain('测试面试')
    expect(removeButton().attributes('disabled')).toBeUndefined()
    await removeButton().trigger('click')
    await flushPromises()
    expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper!.text()).toContain('这里还没有面试记录')
  })
})
