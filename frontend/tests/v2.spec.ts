import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
const test = base.extend<{ errors: string[] }>({ errors: [async ({ page }, use) => { const errors: string[] = []; page.on('pageerror', e => errors.push(e.message)); await use(errors); expect(errors).toEqual([]) }, { auto: true }] })
async function post(api: APIRequestContext, path: string, data: unknown) { const r = await api.post(`/api${path}`, { data }); expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy(); return r.json() }
async function completed(api: APIRequestContext, role?: string) { const s = await post(api, '/sessions', { durationMinutes: 5, ...(role ? { role } : {}) }); await post(api, `/sessions/${s.id}/answers`, { content: '合成回答：使用事务和唯一约束保证订单幂等，失败后回滚。', requestId: crypto.randomUUID(), expectedMessageId: s.messages[0].id }); await post(api, `/sessions/${s.id}/finish`, {}); return s.id as string }
async function screenshots(page: Page, name: string) { await mkdir(resolve('../.evidence/v2/screens'), { recursive: true }); for (const width of [1440, 390, 320]) { await page.setViewportSize({ width, height: 1000 }); await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBeLessThanOrEqual(width); await page.screenshot({ path: resolve(`../.evidence/v2/screens/${name}-${width}.png`), fullPage: true, animations: 'disabled' }) } await page.setViewportSize({ width: 1440, height: 1000 }) }
test('V2 归档来源更新为空发现后仍待核对，可回看旧来源与历史标签', async ({ page, request }) => {
  const sid = await completed(request)
  const preview = await (await request.get(`/api/v2/review-import-preview?sessionId=${sid}`)).json()
  const initial = (await post(request, '/v2/review-imports', { requestId: crypto.randomUUID(), sessionId: sid, expectedReportHash: preview.reportHash })).items[0]
  const confirmed = await post(request, `/v2/review-items/${initial.id}/actions`, { requestId: crypto.randomUUID(), action: 'CONFIRM', expectedVersion: initial.revision, skillIds: ['JAVA.TRANSACTIONS'] })
  await post(request, `/v2/review-items/${initial.id}/actions`, { requestId: crypto.randomUUID(), action: 'ARCHIVE', expectedVersion: confirmed.revision })
  const dataDir = process.env.INTERVIEW_E2E_DATA_DIR
  if (!dataDir || !dataDir.includes('interview-e2e.')) throw new Error('此合成报告夹具仅允许写入 e2e.sh 创建的隔离临时目录')
  execFileSync('python3', ['-c', `import json, sqlite3, sys
from pathlib import Path
with sqlite3.connect(Path(sys.argv[1]) / 'interview.db') as db:
    report = json.loads(db.execute('SELECT payload FROM reports WHERE session_id=?', (sys.argv[2],)).fetchone()[0])
    for kind in ['incorrect', 'missing', 'uncertain']:
        report['questions'][0][kind] = []
    db.execute('UPDATE reports SET payload=? WHERE session_id=?', (json.dumps(report, ensure_ascii=False), sys.argv[2]))
`, dataDir, sid])
  const sourceReport = await (await request.get(`/api/sessions/${sid}/report`)).json()
  await page.goto(`/reports/${sid}`); await page.getByRole('button', { name: '整理为复习项' }).click(); await page.getByRole('link', { name: '第 1 题：查看待练内容' }).click()
  await page.getByRole('button', { name: '恢复归档项' }).click()
  await expect(page.getByRole('button', { name: '确认待练内容并入队' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '开始本轮复习', exact: true })).toHaveCount(0)
  await expect(page.getByText('这版报告没有待练发现。请核对来源版本；可归档保留历史，这不表示能力已提升。', { exact: true })).toBeVisible()
  const versions = page.getByText('查看来源报告版本（1 个旧版）', { exact: true }).locator('..')
  await versions.locator(':scope > summary').click(); await expect(versions).toContainText(initial.findings[0].text)
  await expect(versions).toContainText('合成回答：使用事务和唯一约束保证订单幂等，失败后回滚。')
  const history = page.getByText('查看确认、补证与安排历史', { exact: true }).locator('..')
  await history.locator(':scope > summary').click(); await history.getByRole('button', { name: /的原文与判词快照/ }).last().click()
  await expect(history.getByText('当时标签：Java 事务', { exact: true })).toBeVisible()
  await screenshots(page, 'review-source-version')
  const current = await (await request.get(`/api/v2/review-items/${initial.id}`)).json()
  expect(current.status).toBe('PENDING_CONFIRMATION'); expect(current.findings).toEqual([]); expect(current.sourceVersions).toHaveLength(1)
  expect(await (await request.get(`/api/sessions/${sid}/report`)).json()).toEqual(sourceReport)
})
test('V2 JD 清单保留优先级分组，同组移动后刷新保留顺序', async ({ page, request }) => {
  const manifest = await post(request, '/v2/jd-diagnostics/input-preparations', { requestId: crypto.randomUUID(), jdText: '必须理解事务\n了解接口幂等\n了解容量评估', materialIds: [], trainingEventIds: [] })
  let d = await post(request, '/v2/jd-diagnostics', { requestId: crypto.randomUUID(), manifestId: manifest.manifestId, manifestHash: manifest.manifestHash })
  const importanceUpdates = Object.fromEntries(d.requirements.map((r: any, i: number) => [r.id, i === 0 ? 'REQUIRED' : 'PREFERRED']))
  d = await post(request, `/v2/jd-diagnostics/${d.id}/confirm`, { requestId: crypto.randomUUID(), expectedVersion: d.revision, requirementIds: d.requirements.map((r: any) => r.id), importanceUpdates })
  const sprint = await post(request, '/v2/sprints', { requestId: crypto.randomUUID(), confirmedDiagnosticId: d.id, actions: d.requirements.map((r: any) => ({ requirementId: r.id, type: 'VERIFY_REQUIREMENT', purpose: r.jdQuote, completionCondition: '完成专项并确认对应证据' })) })
  await page.goto(`/sprints/${sprint.id}`)
  const rows = page.locator('article.v2-finding')
  await expect(rows).toHaveCount(3)
  await expect(rows.nth(0).getByRole('button', { name: '下移', exact: true })).toBeDisabled()
  await expect(rows.nth(1).getByRole('button', { name: '上移', exact: true })).toBeDisabled()
  await rows.nth(1).getByRole('button', { name: '下移', exact: true }).click()
  await expect(rows.locator('h3')).toHaveText(['必须理解事务', '了解容量评估', '了解接口幂等'])
  await page.reload(); await expect(rows.locator('h3')).toHaveText(['必须理解事务', '了解容量评估', '了解接口幂等'])
  const saved = await (await request.get(`/api/v2/sprints/${sprint.id}`)).json()
  expect(saved.actions.map((a: any) => a.id)).toEqual([sprint.actions[0].id, sprint.actions[2].id, sprint.actions[1].id])
})
test('V2 复习、技能、同题与返回补证保留原会话和报告', async ({ page, request }) => {
  const sid = await completed(request), original = await (await request.get(`/api/sessions/${sid}`)).json(), report = await (await request.get(`/api/sessions/${sid}/report`)).json()
  await page.goto(`/reports/${sid}`); await page.getByRole('button', { name: '整理为复习项' }).click(); await page.getByRole('link', { name: '第 1 题：查看待练内容' }).click(); await expect(page.getByText('仅有整题依据', { exact: false }).first()).toBeVisible()
  const reviewPath = new URL(page.url()).pathname
  await page.getByRole('button', { name: '开始重练', exact: true }).click(); await expect(page).toHaveURL(/\/practice\//)
  const practicePath = new URL(page.url()).pathname
  await page.getByLabel('我的新回答', { exact: true }).fill('新回答：先检查幂等键，再在事务中保存订单；异常回滚。'); await page.getByRole('button', { name: '保存草稿到本机服务' }).click(); await expect(page.getByText('草稿已明确保存到本机服务。', { exact: true })).toBeVisible(); await page.reload(); await expect(page.getByLabel('我的新回答', { exact: true })).toHaveValue(/先检查幂等键/)
  await page.getByRole('button', { name: '提交回答并对比' }).click(); await expect(page.getByRole('heading', { name: '这次回答，有哪些变化？' })).toBeVisible(); await screenshots(page, 'practice')
  await page.getByText('历史记录与评估详情', { exact: true }).click(); await page.getByRole('link', { name: '返回复习项，逐条确认本次观察' }).click(); await expect(page).toHaveURL(new RegExp(reviewPath)); await expect(page.getByText('带着结果回来，逐条核对覆盖关系')).toBeVisible()
  await screenshots(page, 'review'); await page.getByText('查看确认、补证与安排历史', { exact: true }).click(); await page.getByRole('button', { name: /的原文与判词快照/ }).first().click(); await expect(page.getByText(/^当时状态：/)).toBeVisible()
  await page.getByText('查看原始问答', { exact: true }).click(); await page.getByRole('button', { name: '只练这条原问题' }).first().click(); await expect(page).toHaveURL(new RegExp(practicePath))
  await expect.poll(async () => (await (await request.get(`/api/sessions/${sid}`)).json()).messages).toEqual(original.messages); expect(await (await request.get(`/api/sessions/${sid}/report`)).json()).toEqual(report)
  await page.goto('/skills'); await screenshots(page, 'skills')
})
test('V2 JD 从范围预览到确认清单，关联训练后逐条确认完成', async ({ page, request }) => {
  await post(request, '/materials', { kind: 'PROJECT', name: '合成事务项目', content: '个人负责事务边界与订单幂等设计。' }); await page.goto('/jd-diagnostics'); await page.getByLabel('岗位描述 JD', { exact: true }).fill('熟悉 Java 事务\n了解接口幂等'); await page.getByRole('button', { name: '预览本次模型输入（不调用模型）' }).click(); await expect(page.getByRole('heading', { name: '本次将发送的文本' })).toBeVisible(); await page.getByRole('button', { name: '确认发送上述快照并调用模型生成诊断' }).click(); await expect(page).toHaveURL(/\/jd-diagnostics\//); const diagnosticPath = new URL(page.url()).pathname
  await expect(page.getByText('未提供材料', { exact: true }).first()).toBeVisible(); await page.getByRole('button', { name: '确认所选要求与重要性' }).click(); await screenshots(page, 'jd'); await page.getByRole('button', { name: '按所选行动创建短清单' }).click(); await expect(page).toHaveURL(/\/sprints\//)
  await page.getByRole('button', { name: '进入关联专项练习' }).first().click(); await page.getByRole('button', { name: '开始专项练习', exact: true }).click(); await expect(page).toHaveURL(/\/sessions\//)
  await page.getByLabel('你的回答', { exact: true }).fill('合成专项回答：事务负责原子提交，幂等使用唯一约束。'); await page.getByRole('button', { name: '发送回答' }).click(); await page.getByRole('button', { name: '结束并复盘', exact: true }).click(); await page.getByRole('dialog').getByRole('button', { name: '确认结束并复盘' }).click(); await expect(page).toHaveURL(/\/reports\//)
  await page.getByRole('link', { name: '返回训练来源，核对本次报告证据' }).click(); await expect(page).toHaveURL(/\/sprints\//); await page.getByLabel('实际覆盖此要求的报告结论').selectOption({ index: 1 }); await page.getByLabel('为何覆盖这个要求').fill('本题明确覆盖所选事务要求；仅确认此次练习证据。'); await page.getByLabel('我已核对，这份资料或训练确实覆盖该要求').check(); await page.getByRole('button', { name: '确认本次行动完成', exact: true }).click(); await expect(page.getByText('用户确认完成', { exact: true })).toBeVisible(); await screenshots(page, 'sprint'); await page.getByText('查看完整操作历史', { exact: true }).click(); await page.getByRole('button', { name: /的清单快照/ }).first().click(); await expect(page.getByRole('heading', { name: '历史行动快照', exact: true })).toBeVisible()
  await page.goto(diagnosticPath); await page.getByRole('button', { name: '按当前资料生成新版诊断' }).click(); await expect(page.getByLabel('岗位描述 JD', { exact: true })).toHaveValue('熟悉 Java 事务\n了解接口幂等')
})
test('V2 STAR 故事保留首版表达稿、确认版本及独立练习', async ({ page }) => {
  await page.goto('/stories'); const texts = ['合成项目：订单故障', '修复重复提交', '我实现唯一约束与事务边界', '合成测试中重复请求只保存一笔', '未知峰值容量，后续压测验证']; for (const [i, key] of ['situation', 'task', 'action', 'result', 'reflection'].entries()) await page.locator(`#story-${key}`).fill(texts[i]!); await page.getByLabel('表达稿（不作为事实证明）').fill('我的个人行动是明确事务与幂等边界。'); await page.getByRole('button', { name: '保存第一版故事' }).click(); await expect(page).toHaveURL(/\/stories\//); await expect(page.getByLabel('表达稿（不作为事实证明）')).toHaveValue('我的个人行动是明确事务与幂等边界。')
  for (const key of ['情境：发生了什么', '任务：需要解决什么', '个人行动：我具体做了什么', '结果：实际发生的变化', '复盘：局限与下次改进']) await page.getByLabel(`${key}：我确认所存事实`, { exact: true }).check(); await page.getByRole('button', { name: '确认这个已保存版本' }).click(); await expect(page.getByRole('button', { name: '用此确认版进入独立作答' })).toBeVisible(); await screenshots(page, 'story'); await page.getByRole('button', { name: '用此确认版进入独立作答' }).click(); await expect(page).toHaveURL(/\/practice\//); await page.getByLabel('我的新回答', { exact: true }).fill('合成练习：我负责订单幂等校验，使用唯一约束与事务保障一致性。'); await page.getByRole('button', { name: '提交回答并对比' }).click(); await expect(page.getByText('评估完成', { exact: true })).toBeVisible(); await expect(page.getByLabel('我的新回答', { exact: true })).toHaveAttribute('readonly', '')
})
test('V2 自我介绍按确认版练习，未保存编辑阻止确认与生成', async ({ page }) => {
  await page.goto('/stories'); await page.getByLabel('整理方式').selectOption('INTRO')
  for (const key of ['targetRole', 'background', 'experience', 'contribution', 'aspiration']) await page.locator(`#story-${key}`).fill(`合成本人声明：${key}，没有未证实的数字。`)
  await page.getByRole('button', { name: '保存第一版故事' }).click(); await expect(page).toHaveURL(/\/stories\//)
  await page.locator('#story-contribution').fill('本页尚未保存的新贡献')
  await expect(page.getByRole('button', { name: '确认这个已保存版本' })).toBeDisabled(); await expect(page.getByRole('button', { name: '预览已保存事实与所选材料' })).toBeDisabled()
  await page.getByRole('button', { name: '保存为新版本', exact: true }).click(); await expect(page.getByText('自我介绍 · 版本 2', { exact: true })).toBeVisible()
  for (const label of ['目标岗位', '个人背景', '相关真实经历', '个人贡献', '下一步诉求']) await page.getByLabel(`${label}：我确认所存事实`, { exact: true }).check()
  await page.getByRole('button', { name: '确认这个已保存版本' }).click(); await page.reload(); await expect(page.locator('#story-contribution')).toHaveValue('本页尚未保存的新贡献')
  await page.getByRole('button', { name: '用此确认版进入独立作答' }).click(); await expect(page).toHaveURL(/\/practice\//); await expect(page.getByRole('heading', { name: '请结合真实经历做一段面向目标岗位的自我介绍。', exact: true })).toBeVisible(); await page.getByLabel('我的新回答', { exact: true }).fill('合成自我介绍：目标是后端岗位，我的个人贡献是实现幂等校验；结果缺少数字，后续补证。'); await page.getByRole('button', { name: '提交回答并对比' }).click(); await expect(page.getByText('评估完成', { exact: true })).toBeVisible(); await expect(page.getByLabel('我的新回答', { exact: true })).toHaveAttribute('readonly', '')
})
for (const scenario of ['ORDER_SERVICE', 'KNOWLEDGE_AGENT']) test(`V2 ${scenario} 五阶段显式推进、刷新与最终汇总`, async ({ page }) => {
  await page.goto('/design-practice'); await page.getByLabel('合成案例').selectOption(scenario); await page.getByRole('button', { name: '开始五阶段训练' }).click(); await expect(page).toHaveURL(/\/design-practice\//)
  const stageTitles = ['需求澄清', '约束与容量', '核心实体与 API', '架构与数据流', '故障、恢复与权衡']
  for (let i = 0; i < stageTitles.length; i++) {
    await expect(page.getByRole('heading', { name: stageTitles[i], exact: true })).toBeVisible()
    const areas = page.locator('textarea[id^="field-"]')
    await expect(areas.first()).toBeEditable()
    const count = await areas.count()
    expect(count).toBeGreaterThan(0)
    for (let j = 0; j < count; j++) await areas.nth(j).fill(`合成设计阶段 ${i + 1}，字段 ${j + 1}：明确职责、约束及未知的验证办法。`)
    await page.getByRole('button', { name: '保存阶段草稿', exact: true }).click()
    await expect(page.getByText('草稿已保存到本机服务。', { exact: true })).toBeVisible()
    await page.reload()
    await expect(areas.first()).toHaveValue(new RegExp(`合成设计阶段 ${i + 1}`))
    await page.getByRole('button', { name: '提交产物并调用模型反馈' }).click()
    await expect(page.getByText('Mock 只验证阶段流程，未评判设计质量').first()).toBeVisible()
    await page.getByLabel('我已核对，接受带未解决项继续').check()
    if (i === 3) await screenshots(page, `design-${scenario}`)
    await page.getByRole('button', { name: i < 4 ? '确认进入下一阶段' : '结束并保存阶段汇总', exact: true }).click()
  }
  await expect(page.getByRole('heading', { name: '阶段汇总', exact: true })).toBeVisible(); await expect(page.getByText('五个阶段的有效产物与反馈均已保存，可逐阶段回看。')).toBeVisible()
})

test('专项训练精简：原题开练、反复继续零创建、自动对比与再练', async ({ page, request }) => {
  const sid = await completed(request, '专项原题回归岗位')
  const original = await (await request.get(`/api/sessions/${sid}`)).json()
  const report = await (await request.get(`/api/sessions/${sid}/report`)).json()
  const before = await (await request.get('/api/sessions')).json()
  const preview = await (await request.get(`/api/v2/review-import-preview?sessionId=${sid}`)).json()
  const review = (await post(request, '/v2/review-imports', { requestId: crypto.randomUUID(), sessionId: sid, expectedReportHash: preview.reportHash })).items[0]
  const writes: string[] = []
  page.on('request', req => { if (req.method() === 'POST') writes.push(new URL(req.url()).pathname) })
  await page.goto('/review')
  const card = page.locator('.review-items article').filter({ has: page.locator(`a[href="/review/${review.id}"]`) })
  await expect(card.getByText('新发现', { exact: true })).toBeVisible()
  await screenshots(page, 'review-practice-list')
  await card.getByRole('button', { name: '开始重练', exact: true }).click()
  await expect(page).toHaveURL(/\/practice\//)
  const practicePath = new URL(page.url()).pathname, caseId = practicePath.split('/').at(-1)
  const savedCase = await (await request.get(`/api/v2/practice-cases/${caseId}`)).json()
  expect(savedCase.sourceId).toBe(sid); expect(savedCase.messageId).toBe(original.messages[0].id)
  await expect(page.getByRole('heading', { name: original.messages[0].content, exact: true })).toBeVisible()
  const answer = page.getByLabel('我的新回答', { exact: true })
  await expect(answer).toBeEditable(); await answer.fill('合成草稿：先明确事务和幂等边界。')
  await page.getByRole('button', { name: '保存草稿到本机服务', exact: true }).click()
  await expect(page.getByText('草稿已明确保存到本机服务。', { exact: true })).toBeVisible()
  for (let i = 0; i < 2; i++) {
    await page.goto('/review'); await page.reload()
    const resume = page.locator('.review-resume article').filter({ hasText: review.question })
    await expect(resume).toHaveCount(1)
    const writeCount = writes.length
    await resume.getByRole('button', { name: '继续作答', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(practicePath))
    await expect(answer).toHaveValue('合成草稿：先明确事务和幂等边界。')
    expect(writes.length).toBe(writeCount)
  }
  await screenshots(page, 'practice-ready')
  await page.getByRole('button', { name: '提交回答并对比', exact: true }).click()
  await expect(page.getByRole('heading', { name: '这次回答，有哪些变化？', exact: true })).toBeVisible()
  const resultUrl = page.url()
  await screenshots(page, 'practice-result')
  const afterSubmitWrites = writes.length
  await page.reload(); await expect(page.getByRole('heading', { name: '这次回答，有哪些变化？', exact: true })).toBeVisible()
  expect(writes.length).toBe(afterSubmitWrites)
  await page.getByRole('link', { name: '返回错题复习', exact: true }).click()
  await page.getByRole('tab', { name: /已完成/ }).click()
  const done = page.locator('.review-items article').filter({ has: page.locator(`a[href="/review/${review.id}"]`) })
  await expect(done.getByRole('link', { name: '查看本次结果', exact: true })).toHaveAttribute('href', new URL(resultUrl).pathname + new URL(resultUrl).search)
  await done.getByRole('link', { name: '查看本次结果', exact: true }).click()
  await page.getByRole('button', { name: '再练一次', exact: true }).click(); await expect(answer).toBeEditable(); await expect(answer).toHaveValue('')
  const savedAttempts = await (await request.get(`/api/v2/practice-cases/${caseId}/attempts?limit=100`)).json()
  expect(savedAttempts.items.filter((a: any) => a.status === 'DRAFT')).toHaveLength(1)
  expect(writes).not.toContain('/api/sessions')
  expect((await (await request.get('/api/sessions')).json()).length).toBe(before.length)
  expect((await (await request.get(`/api/sessions/${sid}`)).json()).messages).toEqual(original.messages)
  expect(await (await request.get(`/api/sessions/${sid}/report`)).json()).toEqual(report)
  const savedReview = await (await request.get(`/api/v2/review-items/${review.id}`)).json()
  expect(savedReview.status).toBe('IN_PROGRESS'); expect(savedReview.lastEvidence).toBeUndefined()
})
