import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SourceCodeEvidence from './SourceCodeEvidence.vue'
const source = { repository: 'acme/orders', commitSha: 'a'.repeat(40), status: 'READY', evidence: [{ id: 'code-1', path: 'src/Orders.java', startLine: 2, endLine: 4, snippet: 'void save() {}', verified: true, goal: '异常处理', url: 'javascript:alert(1)' }] }
describe('源码依据', () => {
  it('默认摘要不会泄露文件、片段或内部目标', () => {
    const wrapper = mount(SourceCodeEvidence, { props: { source } })
    expect(wrapper.text()).toContain('acme/orders')
    expect(wrapper.text()).not.toContain('save()')
    expect(wrapper.text()).not.toContain('异常处理')
    expect(wrapper.find('a').exists()).toBe(false)
  })
  it('复盘按真实问题显示固定版本引用，链接由已核验身份构建', () => {
    const wrapper = mount(SourceCodeEvidence, { props: { source, review: true, questions: [{ question: '失败后如何回滚？', codeEvidenceIds: ['code-1'] }] } })
    expect(wrapper.text()).toContain('失败后如何回滚？')
    expect(wrapper.text()).toContain('void save() {}')
    expect(wrapper.get('a').attributes('href')).toBe(`https://github.com/acme/orders/blob/${'a'.repeat(40)}/src/Orders.java#L2-L4`)
    expect(wrapper.text()).not.toContain('异常处理')
  })
  it('未知引用不会串用其他源码片段', () => {
    const wrapper = mount(SourceCodeEvidence, { props: { source, review: true, questions: [{ question: '其他问题', codeEvidenceIds: ['foreign'] }] } })
    expect(wrapper.text()).not.toContain('void save() {}')
    expect(wrapper.find('a').exists()).toBe(false)
  })
})
