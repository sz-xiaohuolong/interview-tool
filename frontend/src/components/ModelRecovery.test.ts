import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ModelRecovery from './ModelRecovery.vue'
import { ApiError } from '../api/training'

afterEach(() => vi.unstubAllGlobals())
it('查询发现操作已经成功时通知页面载入持久化结果，不再发起模型调用', async () => {
  const fetch = vi.fn(async (_url: string, _options?: RequestInit) => new Response(JSON.stringify({ id: 'op', state: 'SUCCEEDED', generation: 0 })))
  vi.stubGlobal('fetch', fetch)
  const error = Object.assign(new ApiError('提交后连接中断'), { operationId: 'op' })
  const wrapper = mount(ModelRecovery, { props: { error } })
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(wrapper.emitted('recovered')).toHaveLength(1)
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(fetch.mock.calls[0]?.[0]).toBe('/api/training/operations/op')
})
