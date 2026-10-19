import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import SpeechInput from './SpeechInput.vue'

type Result = { 0: { transcript: string; confidence: number }; length: number; isFinal: boolean }
type ResultEvent = { resultIndex: number; results: Result[] }
class Recognition {
  static instances: Recognition[] = []
  lang = ''; interimResults = false; continuous = true
  onstart: (() => void) | null = null
  onaudioend: (() => void) | null = null
  onend: (() => void) | null = null
  onerror: ((event: { error: string }) => void) | null = null
  onresult: ((event: ResultEvent) => void) | null = null
  onnomatch: (() => void) | null = null
  constructor() { Recognition.instances.push(this) }
  start() {}
  stop() {}
  abort() {}
}
const result = (...parts: [string, boolean][]): ResultEvent => ({ resultIndex: 0, results: parts.map(([transcript, isFinal]) => ({ 0: { transcript, confidence: 0.9 }, length: 1, isFinal })) })
const wrappers: VueWrapper[] = []
function component(props: { modelValue?: string; disabled?: boolean; contextKey?: string; maxLength?: number } = {}) {
  const wrapper = mount(SpeechInput, { props: { modelValue: '', ...props } })
  wrappers.push(wrapper); return wrapper
}
async function start(wrapper: VueWrapper) { await wrapper.get('button').trigger('click'); expect(Recognition.instances).toHaveLength(1); const api = Recognition.instances[0]!; api.onstart?.(); return api }
function updates(wrapper: VueWrapper) { return (wrapper.emitted('update:modelValue') || []).map(value => value[0]) }
beforeEach(() => { Recognition.instances = []; vi.stubGlobal('SpeechRecognition', Recognition); vi.stubGlobal('webkitSpeechRecognition', undefined) })
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.useRealTimers() })

describe('浏览器语音输入', () => {
  it('仅在明确点击后开始听写，默认中文且不自动提交', async () => {
    const wrapper = component({ modelValue: '原有回答' })
    expect(Recognition.instances).toHaveLength(0)
    expect(wrapper.text()).toContain('浏览器可能在线处理语音')
    const api = await start(wrapper)
    expect(api.lang).toBe('zh-CN'); expect(api.interimResults).toBe(true); expect(api.continuous).toBe(false)
    api.onresult?.(result(['补充回答', true])); await wrapper.vm.$nextTick()
    expect(updates(wrapper)).toEqual(['原有回答\n补充回答'])
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('临时结果仅预览，重复累计结果不会重复追加', async () => {
    const wrapper = component({ modelValue: '已有内容' }), api = await start(wrapper)
    api.onresult?.(result(['Spring', false])); api.onresult?.(result(['Spring Boot', false])); await wrapper.vm.$nextTick()
    expect(updates(wrapper)).toEqual([]); expect(wrapper.text()).toContain('Spring Boot')
    api.onresult?.(result(['Spring Boot', true])); api.onresult?.(result(['Spring Boot', true], ['事务回滚', false]))
    api.onresult?.(result(['Spring Boot', true], ['事务回滚', true])); await wrapper.vm.$nextTick()
    expect(updates(wrapper)).toEqual(['已有内容\nSpring Boot', '已有内容\nSpring Boot\n事务回滚'])
  })

  it('用户同时修改答案时，下一段最终结果附加到最新文字', async () => {
    const wrapper = component({ modelValue: '原稿' }), api = await start(wrapper)
    api.onresult?.(result(['第一段', true])); await wrapper.setProps({ modelValue: '用户修订后的完整回答' })
    api.onresult?.(result(['第一段', true], ['第二段', true])); await wrapper.vm.$nextTick()
    expect(updates(wrapper).at(-1)).toBe('用户修订后的完整回答\n第二段')
  })

  it('停止听写后独立显示等待状态，最终结果可确认修改', async () => {
    const wrapper = component(), api = await start(wrapper)
    await wrapper.get('button').trigger('click')
    expect(wrapper.text()).toContain('正在等待识别结果')
    expect(wrapper.emitted('listening')).toEqual([[true], [false]])
    expect(wrapper.emitted('waiting')).toEqual([[true]])
    api.onresult?.(result(['最后一句', true])); api.onend?.(); await wrapper.vm.$nextTick()
    expect(updates(wrapper)).toEqual(['最后一句']); expect(wrapper.emitted('waiting')).toEqual([[true], [false]])
    expect(wrapper.text()).not.toContain('正在等待识别结果')
  })

  it('浏览器结束采集后才进入结果等待，不能把仍在说话的时间当成等待', async () => {
    const wrapper = component(), api = await start(wrapper)
    expect(wrapper.emitted('waiting')).toBeUndefined()
    api.onaudioend?.(); await wrapper.vm.$nextTick()
    expect(wrapper.emitted('waiting')).toEqual([[true]])
    api.onend?.(); await wrapper.vm.$nextTick()
    expect(wrapper.emitted('waiting')).toEqual([[true], [false]])
  })

  it('等待超时后保留已确认文字并拒绝迟到的识别结果', async () => {
    vi.useFakeTimers()
    const wrapper = component({ modelValue: '已经输入的回答' }), api = await start(wrapper)
    const lateResult = api.onresult!
    await wrapper.get('button').trigger('click'); await vi.advanceTimersByTimeAsync(4001)
    lateResult(result(['迟到结果', true])); await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('等待识别结果超时'); expect(updates(wrapper)).toEqual([])
    expect(wrapper.emitted('waiting')).toEqual([[true], [false]])
  })

  it.each([
    ['not-allowed', '麦克风权限'], ['service-not-allowed', '语音服务'], ['network', '网络'],
    ['audio-capture', '麦克风'], ['no-speech', '没有检测到语音'], ['aborted', '已中止'],
  ])('识别错误 %s 明确反馈且原有答案不变', async (error, message) => {
    const wrapper = component({ modelValue: '现有答案' }), api = await start(wrapper)
    api.onerror?.({ error }); await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain(message); expect(updates(wrapper)).toEqual([])
    expect((wrapper.get('button').element as HTMLButtonElement).disabled).toBe(false)
  })

  it.each(['disabled', 'context', 'cancel', 'unmount'])('%s 会废弃旧识别，迟到事件不得污染当前草稿', async action => {
    const wrapper = component({ modelValue: '保留的草稿', contextKey: 'q1' }), api = await start(wrapper)
    const lateResult = api.onresult!
    await wrapper.get('button').trigger('click')
    if (action === 'disabled') await wrapper.setProps({ disabled: true })
    if (action === 'context') await wrapper.setProps({ contextKey: 'q2', modelValue: '新题草稿' })
    if (action === 'cancel') (wrapper.vm as unknown as { cancel(): void }).cancel()
    if (action === 'unmount') wrapper.unmount()
    lateResult(result(['旧题迟到内容', true])); await Promise.resolve()
    expect(updates(wrapper)).toEqual([])
    expect(wrapper.emitted('waiting')?.at(-1)).toEqual([false])
  })

  it('答案达到长度上限时保留已有内容并明确提示未写入部分', async () => {
    const wrapper = component({ modelValue: '12345', maxLength: 8 }), api = await start(wrapper)
    api.onresult?.(result(['abcdef', true])); await wrapper.vm.$nextTick()
    expect(updates(wrapper)).toEqual(['12345\nab']); expect(wrapper.text()).toContain('字数上限')
  })

  it('一段听写自然结束后明确提示可以再次语音输入', async () => {
    const wrapper = component(), api = await start(wrapper)
    api.onresult?.(result(['完成一段回答', true])); api.onend?.(); await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('本段听写已结束，可再次语音输入')
    expect(updates(wrapper)).toEqual(['完成一段回答'])
  })

  it('到点保留临时识别为待校对草稿，迟到事件仍不得污染答案', async () => {
    const wrapper = component({ modelValue: '已有回答' }), api = await start(wrapper)
    api.onresult?.(result(['需要校对的临时文字', false])); await wrapper.vm.$nextTick()
    const lateResult = api.onresult!
    const exposed = wrapper.vm as unknown as { stopAndKeepDraft?: () => void }
    expect(typeof exposed.stopAndKeepDraft).toBe('function')
    exposed.stopAndKeepDraft!(); await wrapper.setProps({ disabled: true })
    lateResult(result(['迟到的最终文字', true])); await wrapper.vm.$nextTick()
    expect(updates(wrapper)).toEqual(['已有回答\n需要校对的临时文字'])
    expect(wrapper.text()).toContain('未确认的听写已保留到草稿，请校对后提交')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('到点保留临时文字时遵守字数上限并提示截断', async () => {
    const wrapper = component({ modelValue: '12345', maxLength: 8 }), api = await start(wrapper)
    api.onresult?.(result(['abcdef', false])); await wrapper.vm.$nextTick()
    const exposed = wrapper.vm as unknown as { stopAndKeepDraft?: () => void }
    expect(typeof exposed.stopAndKeepDraft).toBe('function')
    exposed.stopAndKeepDraft!(); await wrapper.vm.$nextTick()
    expect(updates(wrapper)).toEqual(['12345\nab'])
    expect(wrapper.text()).toContain('字数上限'); expect(wrapper.text()).toContain('请校对后提交')
  })

  it('原生识别不可用时提示改用文字，不会阻塞答案编辑', () => {
    vi.stubGlobal('SpeechRecognition', undefined)
    const wrapper = component({ modelValue: '仍可编辑的内容' })
    expect((wrapper.get('button').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('当前浏览器不支持语音输入'); expect(updates(wrapper)).toEqual([])
  })
})
