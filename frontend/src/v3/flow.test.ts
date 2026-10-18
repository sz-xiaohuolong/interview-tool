import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { registerV3 } from './routes'
import { extendV3Entry } from './entry-plugin'
import Configure from './Configure.vue'
import Session from './Session.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.useRealTimers(); sessionStorage.clear() })

const session = (extra: Record<string, unknown> = {}) => ({
  id: 'v3-test', status: 'READY', phase: 'READY', revision: 1,
  role: 'Java 后端工程师', level: 'JUNIOR', resumeId: 'resume', jdId: '', durationMinutes: 10,
  mock: true, createdAt: '2026-10-17T10:00:00', updatedAt: '2026-10-17T10:00:00',
  summary: { technicalStack: ['Redis'], responsibilities: ['参与缓存模块'], clarifications: ['负责范围待确认'], focus: ['异常恢复'] },
  messages: [], sources: [], warnings: [], error: '', report: null, usage: {}, metrics: {}, ...extra,
})

async function show(component: any, path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/interview-v3', component: Configure }, { path: '/interview-v3/:id', component: Session }, { path: '/materials', component: { template: '<div />' } }, { path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(component, { global: { plugins: [router] } }); wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

it('追加入口先保留 V2 转换，再只替换一个 V3 入口', () => {
  expect(extendV3Entry('<script type="module" src="/src/v2/main.ts"></script>')).toContain('/src/v3/main.ts')
  expect(() => extendV3Entry('<script src="/src/main.ts"></script>')).toThrow('入口')
})

it('V3 接管普通综合面试入口，但保留 V2 专项训练的原路由和查询', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/configure/:mode', component: { template: '<div />' } }] })
  const dispose = registerV3(router)
  await router.push('/configure/quick?role=Java&level=SENIOR')
  expect(router.currentRoute.value.path).toBe('/interview-v3')
  expect(router.currentRoute.value.query).toEqual({ role: 'Java', level: 'SENIOR' })
  await router.push('/configure/quick?trainingLinkId=linked&role=Java')
  expect(router.currentRoute.value.path).toBe('/configure/quick')
  await router.push('/configure/resume')
  expect(router.currentRoute.value.path).toBe('/configure/resume')
  dispose()
})

it('配置岗位和职级必填，简历与 JD 真实传入，空资料明确提示基准摸底', async () => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response(JSON.stringify([{ id: 'r1', name: '我的简历', kind: 'RESUME', documents: [] }, { id: 'j1', name: '后端 JD', kind: 'JD', documents: [] }]))
    if (url === '/api/v3/status') return new Response(JSON.stringify({ enabled: true, configured: true, provider: 'mock' }))
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify(session({ status: 'QUEUED' }))) }
    return new Response('[]')
  })
  const { wrapper, router } = await show(Configure, '/interview-v3')
  expect(wrapper.get('[data-action="prepare"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('通用基准摸底')
  await wrapper.get('#v3-role').setValue('Java 后端工程师')
  await wrapper.get('#v3-level').setValue('SENIOR')
  await wrapper.get('#v3-resume').setValue('r1')
  await wrapper.get('#v3-jd-id').setValue('j1')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ role: 'Java 后端工程师', level: 'SENIOR', resumeId: 'r1', jdId: 'j1', plannedQuestionCount: 8 })
  expect(writes[0].requestId).toBeTruthy()
  expect(router.currentRoute.value.path).toBe('/interview-v3/v3-test')
})

it.each(['TRAINING', 'TIMED'])('两种模式共用计划题量，切换模式后保留所选十二题：%s', async (mode) => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (url === '/api/v3/status') return new Response(JSON.stringify({ enabled: true, configured: true, provider: 'mock' }))
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify(session({ status: 'QUEUED' }))) }
    return new Response('[]')
  })
  const { wrapper } = await show(Configure, '/interview-v3')
  await wrapper.get('#v3-role').setValue('Java 后端工程师')
  await wrapper.get('#v3-level').setValue('JUNIOR')
  await wrapper.get('#v3-question-count').setValue('12')
  await wrapper.get('#v3-mode').setValue('TIMED')
  expect((wrapper.get('#v3-question-count').element as HTMLSelectElement).value).toBe('12')
  await wrapper.get('#v3-mode').setValue(mode)
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(writes[0]).toMatchObject({ plannedQuestionCount: 12, interviewMode: mode })
  expect(wrapper.text()).toContain('实际题数')
})

it('准备摘要允许纠偏，本场更正携带版本且不会直接开考', async () => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') { writes.push({ url, body: JSON.parse(String(options.body)) }); return new Response(JSON.stringify(session({ status: 'QUEUED', revision: 2 }))) }
    return new Response(JSON.stringify(session()))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  expect(wrapper.text()).toContain('参与缓存模块')
  expect(wrapper.text()).toContain('确认无误，直接开始')
  await wrapper.get('#v3-correction').setValue('缓存架构由同事设计，我负责接入和异常处理')
  await wrapper.get('[data-action="correct"]').trigger('click'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ url: '/api/v3/sessions/v3-test/corrections', body: { expectedRevision: 1, correction: '缓存架构由同事设计，我负责接入和异常处理' } })
  expect(wrapper.text()).toContain('排队')
})

it('首次准备失败且摘要尚未生成时不把初始假设项目当成分析结论', async () => {
  const writes: string[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (options?.method === 'POST') writes.push(url)
    if (url === '/api/materials') return new Response('[]')
    return new Response(JSON.stringify(session({
      experienceVersion: 1, status: 'FAILED', phase: '处理失败，可显式重试', summary: {},
      mainProject: { id: 'brief:v3-test', name: '假设场景，尚未提供真实项目', brief: '', hypothetical: true },
      error: '第2道主问题的资料引用未逐字匹配当前资料原文', allowedActions: ['retry', 'corrections', 'cancel'],
    })))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  expect(wrapper.text()).toContain('资料引用未逐字匹配')
  expect(wrapper.find('.v3-summary').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('本场暂用假设场景')
  expect(wrapper.find('[data-action="retry"]').exists()).toBe(true)
  expect(wrapper.find('[data-action="correct"]').exists()).toBe(true)
  expect(writes).toEqual([])
})

it('答案提交同步显示思考状态，网络失败保留草稿并使用同一请求重试', async () => {
  const writes: any[] = []
  let answerResolve: ((response: Response) => void) | undefined
  const active = session({ status: 'IN_PROGRESS', phase: 'INTERVIEW', revision: 2, messages: [{ id: 'q1', role: 'ASSISTANT', content: '你负责的缓存更新失败时如何恢复？', questionId: 'q-1', createdAt: '2026-10-17T10:00:00' }] })
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') {
      writes.push(JSON.parse(String(options.body)))
      return new Promise<Response>(resolve => { answerResolve = resolve })
    }
    return new Response(JSON.stringify(active))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.get('#v3-answer').setValue('把失败记录到重试队列，提供补偿与告警。')
  await wrapper.get('[data-action="answer"]').trigger('click')
  expect(wrapper.get('[data-thinking]').text()).toContain('面试官思考中')
  expect(writes[0]).toMatchObject({ expectedRevision: 2, expectedQuestionId: 'q-1', content: '把失败记录到重试队列，提供补偿与告警。' })
  answerResolve!(new Response(JSON.stringify({ code: 'NETWORK', message: '连接中断，请确认保存结果' }), { status: 503 }))
  await flushPromises()
  expect((wrapper.get('#v3-answer').element as HTMLTextAreaElement).value).toContain('失败记录')
  expect(wrapper.get('[data-action="reload"]').text()).toContain('刷新已保存状态')
  await wrapper.get('[data-action="answer"]').trigger('click')
  expect(writes[1].requestId).toBe(writes[0].requestId)
  answerResolve!(new Response(JSON.stringify({ ...active, status: 'ANSWERING', revision: 3 })))
  await flushPromises()
})

it('复盘中没有来源的技术判错显示待核实，来源可以回查具体片段', async () => {
  vi.stubGlobal('fetch', async (url: string) => new Response(JSON.stringify(url === '/api/materials' ? [] : session({
    status: 'REPORT_READY', phase: 'REPORT_READY',
    report: { summary: '围绕你的真实职责进行复盘', findings: [{ questionId: 'q1', assessment: '一致性结论需要进一步核实', status: 'FACT_ERROR', sourceIds: [], suggestion: '补充故障边界' }, { questionId: 'q2', assessment: '不同约束下存在两种方案', status: 'CONFLICTING', sourceIds: ['s1'], suggestion: '明确业务要求', sourceQuote: '结合业务一致性约束' }] },
    sources: [{ id: 's1', title: 'Redis 官方文档', url: 'https://redis.io/docs/latest/', version: '最新文档', fetchedAt: '2026-10-17T10:00:00', excerpt: '缓存失效应结合业务一致性约束。', sha256: 'abc', kind: 'OFFICIAL', topics: ['redis'] }],
  }))))
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  expect(wrapper.get('[data-finding-status]').text()).toContain('待核实')
  expect(wrapper.get('[data-source="s1"] a').attributes('href')).toBe('https://redis.io/docs/latest/')
  expect(wrapper.text()).toContain('缓存失效应结合业务一致性约束。')
  expect(wrapper.text()).toContain('来源存在冲突')
  expect(wrapper.get('[data-source-quote]').text()).toContain('结合业务一致性约束')
})

it('题目完成后仍可发起复盘，复盘后台执行时显示真实阶段', async () => {
  let current = session({ status: 'COMPLETED', phase: 'COMPLETED', messages: [{ id: 'q1', role: 'ASSISTANT', content: '怎样实现异常补偿？', questionId: 'q1' }, { id: 'a1', role: 'USER', content: '持久化失败记录并按幂等键补偿。', questionId: 'q1' }] })
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') current = { ...current, status: 'REPORTING', phase: 'REVIEW_VERIFICATION', revision: 2 }
    return new Response(JSON.stringify(current))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.get('[data-action="finish"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('正在查证复盘')
  expect(wrapper.text()).toContain('核对问答与来源')
})

it('单独更换配置也会明确重新准备，防止空更正被后端拒绝', async () => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify(session({ status: 'QUEUED', revision: 2 }))) }
    return new Response(JSON.stringify(session()))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.get('#v3-role').setValue('大模型应用 / Agent 开发工程师')
  await wrapper.get('[data-action="correct"]').trigger('click'); await flushPromises()
  expect(writes[0].role).toBe('大模型应用 / Agent 开发工程师')
  expect(writes[0].correction).toBe('已调整本场岗位或资料，请按新配置重新准备。')
})

it('刷新后继续轮询已保存任务，备用追问清楚标识，离开后停止轮询', async () => {
  vi.useFakeTimers()
  const calls: string[] = []
  let fetched = 0
  vi.stubGlobal('fetch', async (url: string) => {
    calls.push(url)
    if (url === '/api/materials') return new Response('[]')
    fetched++
    return new Response(JSON.stringify(session({
      status: fetched < 3 ? 'ANSWERING' : 'IN_PROGRESS', phase: '面试官思考中', revision: fetched,
      messages: [{ id: 'q1', role: 'ASSISTANT', content: '缓存失败后怎样补偿？', questionId: 'q1' }, { id: 'a1', role: 'USER', content: '使用补偿队列。', questionId: 'q1' }, ...(fetched < 3 ? [] : [{ id: 'q2', role: 'ASSISTANT', content: '补偿过程中如何防止重复执行？', questionId: 'q2', fallback: true }])],
    })))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  expect(wrapper.get('[data-thinking]').text()).toContain('回答已保存')
  await vi.advanceTimersByTimeAsync(750); await flushPromises()
  expect(wrapper.text()).toContain('补偿过程中如何防止重复执行？')
  expect(wrapper.text()).toContain('切换为已准备的相关备用问题')
  expect(wrapper.find('[data-thinking]').exists()).toBe(false)
  const before = calls.length
  wrapper.unmount(); wrappers.splice(wrappers.indexOf(wrapper), 1)
  await vi.advanceTimersByTimeAsync(2000)
  expect(calls.length).toBe(before)
})

it('重启中断时仅展示服务端允许的恢复动作，不把复盘中断误当追问跳题', async () => {
  vi.stubGlobal('fetch', async (url: string) => new Response(JSON.stringify(url === '/api/materials' ? [] : session({
    status: 'INTERRUPTED', phase: '服务重启，任务已中断', allowedActions: ['retry'],
    messages: [{ id: 'q1', role: 'ASSISTANT', content: '怎样恢复缓存异常？', questionId: 'q1' }, { id: 'a1', role: 'USER', content: '执行幂等补偿', questionId: 'q1' }],
  }))))
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  expect(wrapper.find('[data-action="retry"]').exists()).toBe(true)
  expect(wrapper.find('[data-action="next"]').exists()).toBe(false)
  expect(wrapper.find('[data-action="finish"]').exists()).toBe(false)
})

it('明确409后读取新版本，用户显式重试取消建立新请求，不永久复用旧版本', async () => {
  const writes: any[] = []
  let revision = 4
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') {
      writes.push(JSON.parse(String(options.body)))
      if (writes.length === 1) { revision = 5; return new Response(JSON.stringify({ code: 'CONFLICT', message: '本场状态已变化，请刷新后继续' }), { status: 409 }) }
      return new Response(JSON.stringify(session({ status: 'CANCELLED', phase: '已取消', revision: 6, allowedActions: [] })))
    }
    return new Response(JSON.stringify(session({ status: 'PREPARING', phase: '正在获取本场公开技术依据', revision, allowedActions: ['cancel'] })))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.findAll('button').find(button => button.text() === '取消本场任务')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('本场状态已变化')
  await wrapper.findAll('button').find(button => button.text() === '取消本场任务')!.trigger('click'); await flushPromises()
  expect(writes).toHaveLength(2)
  expect(writes[0].expectedRevision).toBe(4)
  expect(writes[1].expectedRevision).toBe(5)
  expect(writes[1].requestId).not.toBe(writes[0].requestId)
  expect(wrapper.text()).toContain('本场已取消')
})

it('开考前移除已保存 JD 时明确发送空 JD，不保留旧岗位内容', async () => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response(JSON.stringify([{ id: 'jd-old', kind: 'JD', name: '旧岗位要求', documents: [] }]))
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify(session({ status: 'QUEUED', revision: 2, jdId: '' }))) }
    return new Response(JSON.stringify(session({ jdId: 'jd-old' })))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.get('input[type="checkbox"]').setValue(true)
  expect((wrapper.get('#v3-jd-id').element as HTMLSelectElement).value).toBe('jd-old')
  await wrapper.get('#v3-jd-id').setValue('')
  await wrapper.get('[data-action="correct"]').trigger('click'); await flushPromises()
  expect(writes[0].jdId).toBe('')
  expect(writes[0].jd).toBe('')
})

it('新场次支持不限时和实战，实战默认30分钟并快照主项目', async () => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response(JSON.stringify([{ id: 'p1', name: '订单项目', kind: 'PROJECT', documents: [] }]))
    if (url === '/api/v3/status') return new Response(JSON.stringify({ enabled: true, configured: true, provider: 'mock' }))
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify(session({ status: 'QUEUED' }))) }
    return new Response('[]')
  })
  const { wrapper } = await show(Configure, '/interview-v3')
  await wrapper.get('#v3-role').setValue('Java 后端工程师')
  await wrapper.get('#v3-level').setValue('JUNIOR')
  expect((wrapper.get('#v3-mode').element as HTMLSelectElement).value).toBe('TRAINING')
  await wrapper.get('#v3-mode').setValue('TIMED')
  expect((wrapper.get('#v3-time-limit').element as HTMLSelectElement).value).toBe('30')
  await wrapper.get('#v3-time-limit').setValue('45')
  await wrapper.get('#v3-project').setValue('p1')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(writes[0]).toMatchObject({ interviewMode: 'TIMED', timeLimitMinutes: 45, projectId: 'p1' })
})

it('服务端时钟刷新可恢复，到点保留草稿和最后提交机会且不自动提交', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-18T02:00:00Z'))
  const writes: any[] = []
  const active = session({ experienceVersion: 1, interviewMode: 'TIMED', timeLimitMinutes: 30, status: 'IN_PROGRESS', stage: 'PROJECT',
    stages: [{ id: 'INTRO', label: '开场介绍', status: 'COMPLETED', answeredCount: 1 }, { id: 'PROJECT', label: '项目深挖', status: 'IN_PROGRESS', answeredCount: 0 }, { id: 'TECHNICAL', label: '岗位技术与场景', status: 'NOT_STARTED', answeredCount: 0 }, { id: 'QUESTIONS', label: '候选人反问', status: 'NOT_STARTED', answeredCount: 0 }],
    timing: { enabled: true, limitMs: 1800000, elapsedMs: 1798000, remainingMs: 2000, running: true, expired: false, serverNow: Date.now() },
    messages: [{ id: 'q1', role: 'ASSISTANT', content: '订单重试如何去重？', questionId: 'q1' }] })
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify({ ...active, status: 'COMPLETED', revision: 2 })) }
    return new Response(JSON.stringify(active))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  expect(wrapper.get('[data-interview-clock]').text()).toContain('00:02')
  expect(wrapper.get('[aria-label="面试四阶段"]').text()).toContain('候选人反问')
  await wrapper.get('#v3-answer').setValue('使用订单号和业务状态幂等校验。')
  await vi.advanceTimersByTimeAsync(2500)
  expect(wrapper.text()).toContain('时间已到')
  expect((wrapper.get('#v3-answer').element as HTMLTextAreaElement).value).toContain('订单号')
  expect(wrapper.get('#v3-answer').attributes('disabled')).toBeUndefined()
  expect(writes).toHaveLength(0)
  expect(sessionStorage.getItem('interview-v3:draft:v3-test')).toContain('订单号')
  await wrapper.get('[data-action="answer"]').trigger('click'); await flushPromises()
  expect(writes[0].content).toContain('订单号')
  expect(wrapper.text()).toContain('生成查证复盘')
})

it('模型生成等待不扣实战时长，旧历史不追加四阶段或时钟', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-18T02:00:00Z'))
  let value = session({ experienceVersion: 1, interviewMode: 'TIMED', status: 'ANSWERING', timing: { enabled: true, limitMs: 1800000, elapsedMs: 60000, remainingMs: 1740000, running: false, expired: false, serverNow: Date.now() },
    messages: [{ id: 'q1', role: 'ASSISTANT', content: '异常怎么办？', questionId: 'q1' }, { id: 'a1', role: 'USER', content: '保存重试记录', questionId: 'q1' }] })
  vi.stubGlobal('fetch', async (url: string) => new Response(JSON.stringify(url === '/api/materials' ? [] : value)))
  const first = await show(Session, '/interview-v3/v3-test')
  await vi.advanceTimersByTimeAsync(3000)
  expect(first.wrapper.get('[data-interview-clock]').text()).toContain('29:00')
  first.wrapper.unmount(); wrappers.splice(wrappers.indexOf(first.wrapper), 1)
  value = session({ status: 'REPORT_READY', report: { summary: '历史报告', findings: [] } })
  const old = await show(Session, '/interview-v3/v3-test')
  expect(old.wrapper.find('[data-interview-clock]').exists()).toBe(false)
  expect(old.wrapper.find('[aria-label="面试四阶段"]').exists()).toBe(false)
  expect(old.wrapper.text()).toContain('历史报告')
})

it('新场次可在摘要纠偏时更换主项目简述，历史报告不会被修改', async () => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify(session({ status: 'QUEUED', revision: 2 }))) }
    return new Response(JSON.stringify(session({ experienceVersion: 1, mainProject: { id: 'brief', name: '本场项目', brief: '订单系统', hypothetical: false } })))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  expect(wrapper.text()).toContain('订单系统')
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.get('#v3-project-brief').setValue('本场聚焦库存扣减，我负责幂等和补偿。')
  await wrapper.get('[data-action="correct"]').trigger('click'); await flushPromises()
  expect(writes[0].projectBrief).toContain('库存扣减')
})

it('V3语音最终文字可校对，到点停止识别并拒绝迟到结果', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-18T03:00:00Z'))
  let recognition: any
  class SpeechRecognition {
    onstart: (() => void) | null = null; onresult: ((event: any) => void) | null = null
    start() { recognition = this; this.onstart?.() }
    stop() {} abort() {}
  }
  vi.stubGlobal('SpeechRecognition', SpeechRecognition)
  const writes: any[] = []
  const active = session({ experienceVersion: 1, interviewMode: 'TIMED', status: 'IN_PROGRESS', timing: { enabled: true, limitMs: 1200000, elapsedMs: 1198000, remainingMs: 2000, running: true, expired: false, serverNow: Date.now() }, messages: [{ id: 'q1', role: 'ASSISTANT', content: '谈谈项目故障处理', questionId: 'q1' }] })
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (options?.method === 'POST') writes.push(url)
    return new Response(JSON.stringify(url === '/api/materials' ? [] : active))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.get('#v3-answer').setValue('我的职责：')
  await wrapper.findAll('button').find(button => button.text() === '语音输入')!.trigger('click')
  const lateResult = recognition.onresult
  recognition.onresult({ resultIndex: 0, results: [{ 0: { transcript: '接口幂等', confidence: 1 }, length: 1, isFinal: true }] })
  await flushPromises()
  expect((wrapper.get('#v3-answer').element as HTMLTextAreaElement).value).toContain('接口幂等')
  await wrapper.get('#v3-answer').setValue('校对后的接口幂等方案')
  await vi.advanceTimersByTimeAsync(2500)
  lateResult({ resultIndex: 1, results: [{ 0: { transcript: '不应写入的迟到文字', confidence: 1 }, length: 1, isFinal: true }] })
  await flushPromises()
  expect((wrapper.get('#v3-answer').element as HTMLTextAreaElement).value).toBe('校对后的接口幂等方案')
  expect(writes).toHaveLength(0)
})

it('停止听写的等待通知先完成，提交使用新版本且不被迟到的计时写入覆盖', async () => {
  let recognition: any
  class SpeechRecognition { onstart: (() => void) | null = null; onresult: ((event: any) => void) | null = null; start() { recognition = this; this.onstart?.() } stop() {} abort() {} }
  vi.stubGlobal('SpeechRecognition', SpeechRecognition)
  const writes: any[] = []
  let value = session({ experienceVersion: 1, interviewMode: 'TIMED', status: 'IN_PROGRESS', timing: { enabled: true, limitMs: 1800000, elapsedMs: 0, remainingMs: 1800000, running: true, expired: false, serverNow: Date.now() }, messages: [{ id: 'q1', role: 'ASSISTANT', content: '故障如何恢复？', questionId: 'q1' }] })
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') {
      const body = JSON.parse(String(options.body)); writes.push({ url, body })
      value = { ...value, revision: value.revision + 1, ...(url.endsWith('/answers') ? { status: 'ANSWERING' } : {}) }
    }
    return new Response(JSON.stringify(value))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.findAll('button').find(button => button.text() === '语音输入')!.trigger('click')
  await wrapper.findAll('button').find(button => button.text() === '停止听写')!.trigger('click'); await flushPromises()
  expect(writes[0].url).toContain('/transcription-wait')
  recognition.onresult({ resultIndex: 0, results: [{ 0: { transcript: '保留失败记录并补偿', confidence: 1 }, length: 1, isFinal: true }] })
  await flushPromises()
  await wrapper.get('[data-action="answer"]').trigger('click'); await flushPromises()
  expect(writes.map(item => item.url.split('/').at(-1))).toEqual(['transcription-wait', 'transcription-resume', 'answers'])
  expect(writes[2].body).toMatchObject({ expectedRevision: 3, content: '保留失败记录并补偿' })
})

it('没有项目资料时可在开考前简述真实项目并随确认提交', async () => {
  const writes: any[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/materials') return new Response('[]')
    if (options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return new Response(JSON.stringify(session({ status: 'IN_PROGRESS', revision: 2 }))) }
    return new Response(JSON.stringify(session({ experienceVersion: 1, mainProject: { id: 'hypothetical', name: '假设场景', brief: '', hypothetical: true } })))
  })
  const { wrapper } = await show(Session, '/interview-v3/v3-test')
  await wrapper.get('#v3-opening-project').setValue('我参与订单系统，负责重复支付回调的幂等处理。')
  await wrapper.get('[data-action="confirm"]').trigger('click'); await flushPromises()
  expect(writes[0]).toMatchObject({ expectedRevision: 1, projectBrief: '我参与订单系统，负责重复支付回调的幂等处理。' })
})

it('V3复盘接入本场矩阵并加载补练关联，旧报告不触发该接口', async () => {
  const reads: string[] = []
  let value = session({ experienceVersion: 1, status: 'REPORT_READY', report: { summary: '本场复盘', findings: [], matrix: { version: 1, project: { id: 'p1', name: '订单项目' }, items: [{ checkpointId: 'FAILURE', title: '异常与故障处理', status: 'PARTIAL', reason: '尚未说明失败恢复边界', evidence: [{ questionMessageId: 'q1', answerMessageId: 'a1', questionQuote: '故障怎么恢复？', answerQuote: '我用队列重试。' }] }] } } })
  vi.stubGlobal('fetch', async (url: string) => {
    reads.push(url)
    return new Response(JSON.stringify(url === '/api/materials' ? [] : url.includes('/project-evidence/') ? { items: [] } : value))
  })
  const first = await show(Session, '/interview-v3/v3-test')
  expect(first.wrapper.get('[data-checkpoint="FAILURE"]').text()).toContain('我用队列重试。')
  expect(reads.filter(url => url.includes('/project-evidence/'))).toEqual(['/api/project-evidence/V3/v3-test/gaps'])
  first.wrapper.unmount(); wrappers.splice(wrappers.indexOf(first.wrapper), 1); reads.length = 0
  value = session({ status: 'REPORT_READY', report: { summary: '原报告', findings: [] } })
  await show(Session, '/interview-v3/v3-test')
  expect(reads.some(url => url.includes('/project-evidence/'))).toBe(false)
})
