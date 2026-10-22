import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ComparisonResult from './ComparisonResult.vue'

it('原位展示两次原文与已补齐关键点，改进建议合并去重且最多两条', () => {
  const wrapper = mount(ComparisonResult, { props: { before: '原来只说正常流程', after: '本次补充超时降级', result: { comparison: {
    fixed: [{ description: '补齐超时分支' }], remaining: [{ description: '补充观测方案' }], newIssues: [{ description: '本次需要修正回滚时机' }], suggestions: ['补充观测方案', '第三条建议不再堆叠'],
  } } } })
  expect(wrapper.get('.answer-comparison').text()).toContain('原来只说正常流程')
  expect(wrapper.get('.answer-comparison').text()).toContain('本次补充超时降级')
  expect(wrapper.get('.feedback-fixed').text()).toContain('补齐超时分支')
  expect(wrapper.text()).toContain('本次需要修正回滚时机')
  expect(wrapper.text()).not.toContain('第三条建议不再堆叠')
  expect(wrapper.text().match(/补充观测方案/g)).toHaveLength(1)
})
it('没有已补齐项时如实提示，不给出掌握或提升结论', () => {
  const wrapper = mount(ComparisonResult, { props: { result: { fixed: [], remaining: [], newIssues: [], suggestions: [] } } })
  expect(wrapper.text()).toContain('尚未确认新增补齐的关键点')
  expect(wrapper.find('.answer-comparison').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('已掌握')
})
