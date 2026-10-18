import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import History from './History.vue'
import { registerV3 } from '../v3/routes'

const summary = {
  id: 'same-id', role: '合成综合面试', level: 'INTERN', status: 'IN_PROGRESS', phase: 'INTERVIEW', mock: true,
  createdAt: '2026-10-18T06:00:00Z', updatedAt: '2026-10-18T06:00:00Z',
}
const legacy = { ...summary, role: '合成旧面试', mode: 'PROJECT', messages: [] }
let wrapper: VueWrapper | undefined
let dispose: (() => void) | undefined
const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/history', component: History },
    { path: '/sessions/:id', component: { template: '<div />' } },
  ] })
  // 使用真实注册函数，防止测试自行拼出“已启用 V3”路由而漏测运行入口的变化。
  dispose = registerV3(router)
  await router.push('/history')
  await router.isReady()
  wrapper = mount(History, { global: { plugins: [router] } })
  await flushPromises()
  await wrapper.findAll('.tab').find(tab => tab.text() === '待继续')!.trigger('click')
}
afterEach(() => { wrapper?.unmount(); dispose?.(); vi.unstubAllGlobals() })

describe('真实 V3 路由下的共用历史入口', () => {
  it('只有 V3 记录时也能继续，不能依赖旧列表非空', async () => {
    const fetcher = vi.fn(async (url: string) => {
      if (url === '/api/sessions') return respond([])
      if (url === '/api/v3/sessions') return respond([summary])
      throw new Error(`出现未约定的接口：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    await render()
    expect(wrapper!.get('a[href="/interview-v3/same-id"]').text()).toContain('合成综合面试')
    expect(fetcher.mock.calls.map(([url]) => url).sort()).toEqual(['/api/sessions', '/api/v3/sessions'])
  })

  it('旧接口成功不能掩盖 V3 加载失败，也不能把旧记录路由到 V3', async () => {
    const fetcher = vi.fn(async (url: string) => {
      if (url === '/api/sessions') return respond([legacy])
      if (url === '/api/v3/sessions') return respond({ message: '综合面试接口暂不可用' }, 503)
      throw new Error(`出现未约定的接口：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    await render()
    expect(wrapper!.get('[role="alert"]').text()).toContain('综合面试接口暂不可用')
    expect(wrapper!.get('a[href="/sessions/same-id"]').text()).toContain('合成旧面试')
    expect(wrapper!.find('a[href="/interview-v3/same-id"]').exists()).toBe(false)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
})
