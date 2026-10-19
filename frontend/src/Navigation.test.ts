import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  request: vi.fn(async () => ({ status: 'ok', provider: 'mock', configured: true, model: '导航测试' })),
}))

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

async function open(path: string, trainingEnabled = true) {
  const placeholder = defineComponent({ setup: () => () => h('div', '合成页面') })
  const paths = ['/', '/configure/:mode', '/sessions/:id', '/interviews', '/interviews/:id', '/interview-v3', '/interview-v3/:id', '/materials', '/history', '/reports/:id', '/history/:id/replay', '/history/interviews/:id/replay',
    ...(trainingEnabled ? ['/review', '/review/:id', '/skills', '/practice', '/practice/:id', '/jd-diagnostics',
      '/jd-diagnostics/:id', '/sprints/:id', '/stories', '/stories/:id', '/design-practice', '/design-practice/:id'] : [])]
  const router = createRouter({ history: createMemoryHistory(), routes: paths.map(path => ({ path, component: placeholder })) })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, { props: { trainingEnabled }, global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

it.each([
  ['/', '练习概览'], ['/configure/quick', '开始面试'], ['/configure/project', '开始面试'],
  ['/sessions/session-1', '开始面试'], ['/materials', '个人资料库'], ['/history', '面试历史'],
  ['/reports/session-1', '面试历史'],
  ['/history/session-1/replay', '面试历史'], ['/history/interviews/session-1/replay', '面试历史'],
  ['/interviews', '开始面试'], ['/interviews/session-1', '开始面试'],
  ['/interview-v3', '开始面试'], ['/interview-v3/session-1', '开始面试'],
])('普通页面 %s 保持一级归属且不显示训练二级导航', async (path, primary) => {
  const { wrapper } = await open(path)
  expect(wrapper.find('nav[aria-label="持续训练"]').exists()).toBe(false)
  expect(wrapper.findAll('nav[aria-label="主导航"] a[aria-current="page"]').map(link => link.text())).toEqual([primary])
})

it.each([
  ['/review', '错题复习'], ['/review/item-1', '错题复习'], ['/skills', '能力看板'],
  ['/practice', '原题重练'], ['/practice/case-1', '原题重练'],
  ['/jd-diagnostics', '岗位诊断'], ['/jd-diagnostics/diagnosis-1', '岗位诊断'], ['/sprints/sprint-1', '岗位诊断'],
  ['/stories', '项目经历'], ['/stories/story-1', '项目经历'],
  ['/design-practice', '系统设计'], ['/design-practice/design-1', '系统设计'],
])('直接打开 %s 同时标明专项模块和当前工具', async (path, tool) => {
  const { wrapper } = await open(path)
  expect(wrapper.findAll('nav[aria-label="主导航"] a[aria-current="page"]').map(link => link.text())).toEqual(['专项训练'])
  expect(wrapper.findAll('nav[aria-label="持续训练"] a[aria-current="page"]').map(link => link.text())).toEqual([tool])
  expect(wrapper.find('nav[aria-label="当前位置"]').text()).toContain(tool)
})

it('切换到历史再返回时，训练导航随路由恢复，不依赖点击记忆', async () => {
  const { wrapper, router } = await open('/review/item-1?from=report')
  await wrapper.get('nav[aria-label="主导航"] a[href="/history"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/history')
  expect(wrapper.find('nav[aria-label="持续训练"]').exists()).toBe(false)
  await router.replace('/review/item-1?from=report')
  await flushPromises()
  expect(wrapper.get('nav[aria-label="主导航"] a[aria-current="page"]').text()).toBe('专项训练')
  expect(wrapper.get('nav[aria-label="持续训练"] a[aria-current="page"]').text()).toBe('错题复习')
})

it('复习详情的面包屑可以返回已有列表路径', async () => {
  const { wrapper, router } = await open('/review/item-1')
  const parents = wrapper.findAll('nav[aria-label="当前位置"] a')
  expect(parents.map(link => link.attributes('href'))).toContain('/review')
  await parents.at(-1)!.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/review')
})

it.each(['/REVIEW', '/review/'])('路由兼容路径 %s 保持列表归属与原 URL', async path => {
  const { wrapper, router } = await open(path)
  expect(router.currentRoute.value.path).toBe(path)
  expect(wrapper.get('nav[aria-label="主导航"] a[aria-current="page"]').text()).toBe('专项训练')
  expect(wrapper.get('nav[aria-label="持续训练"] a[aria-current="page"]').text()).toBe('错题复习')
  expect(wrapper.get('nav[aria-label="当前位置"]').text()).not.toContain('复习详情')
})

it('专项训练 关闭时保留四项主导航，不暴露专项入口', async () => {
  const { wrapper } = await open('/history', false)
  expect(wrapper.findAll('nav[aria-label="主导航"] a').map(link => link.text())).toEqual(['练习概览', '开始面试', '个人资料库', '面试历史'])
  expect(wrapper.find('nav[aria-label="持续训练"]').exists()).toBe(false)
})
