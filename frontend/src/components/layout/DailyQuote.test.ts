import { afterEach, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppLayout from './AppLayout.vue'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function openAt(date: Date) {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(date)
  const router = createRouter({ history: createMemoryHistory(), routes: ['/', '/configure/:mode', '/materials', '/history'].map(path => ({ path, component: { template: '<div />' } })) })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(AppLayout, { props: { providerLabel: '测试模型', mock: true, configured: true }, global: { plugins: [router] } })
  wrappers.push(wrapper)
  return wrapper
}

function quote(wrapper: VueWrapper) { return wrapper.get('[aria-live="polite"]').text() }

it('当天首次打开与晚间刷新保持同一句，按本机日期而非 UTC 切换', async () => {
  const morning = quote(await openAt(new Date(2026, 9, 18, 0, 15)))
  const evening = quote(await openAt(new Date(2026, 9, 18, 23, 45)))
  expect(morning).not.toBe('')
  expect(evening).toBe(morning)
})

it('本机午夜后重新打开使用新一天的句子', async () => {
  const before = quote(await openAt(new Date(2026, 9, 18, 23, 59)))
  const after = quote(await openAt(new Date(2026, 9, 19, 0, 1)))
  expect(after).not.toBe(before)
})

it('手动换句立即更新内容，刷新后恢复当天初始句子', async () => {
  const date = new Date(2026, 9, 18, 12)
  const wrapper = await openAt(date)
  const initial = quote(wrapper)
  await wrapper.get('button[aria-label="换一句面试提醒"]').trigger('click')
  expect(quote(wrapper)).not.toBe(initial)
  expect(quote(await openAt(date))).toBe(initial)
})

it('换句不请求网络、不持久化，并保留本机存储和模型服务说明', async () => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  const localWrite = vi.spyOn(Storage.prototype, 'setItem')
  const wrapper = await openAt(new Date(2026, 9, 18, 12))
  await wrapper.get('button[aria-label="换一句面试提醒"]').trigger('click')
  expect(fetcher).not.toHaveBeenCalled()
  expect(localWrite).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('资料与记录保存在本机')
  expect(wrapper.text()).toContain('真实练习使用你配置的模型服务')
})
