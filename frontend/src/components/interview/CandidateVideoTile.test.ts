import { afterEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import CandidateVideoTile from './CandidateVideoTile.vue'

afterEach(() => vi.unstubAllGlobals())

it('候选人席位始终显示头像，不提供采集设备的操作', async () => {
  const getUserMedia = vi.fn(() => Promise.reject(new Error('不得申请设备权限')))
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } })
  const wrapper = mount(CandidateVideoTile)
  expect(wrapper.find('.avatar-container svg').exists()).toBe(true)
  expect(wrapper.text()).toContain('候选人')
  expect(wrapper.findAll('button, video')).toHaveLength(0)
  await wrapper.trigger('click')
  expect(getUserMedia).not.toHaveBeenCalled()
  wrapper.unmount()
})
