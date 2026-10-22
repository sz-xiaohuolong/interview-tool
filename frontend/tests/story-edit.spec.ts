import { test, expect, type APIRequestContext } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

async function post(api: APIRequestContext, path: string, data: unknown) {
  const r = await api.post(`/api${path}`, { data })
  expect(r.ok(), await r.text()).toBeTruthy()
  return r.json()
}
const templates = { STAR: ['situation', 'task', 'action', 'result', 'reflection'], INTRO: ['targetRole', 'background', 'experience', 'contribution', 'aspiration'] }

// 准备与真实生成结果同形的材料引用，仅允许写隔离测试库，不调用真实模型。
async function quotedStory(api: APIRequestContext, kind: keyof typeof templates) {
  const quote = '我负责幂等校验并说明事务边界。'
  const material = await post(api, '/materials', { kind: 'PROJECT', name: '合成引用资料', content: `合成项目材料：${quote}` })
  const keys = kind === 'STAR' ? ['action', 'task'] : ['contribution', 'background']
  const fields = Object.fromEntries(templates[kind].map(key => [key, { text: keys.includes(key) ? quote : `合成事实：${key}`, originType: 'USER_ASSERTION' }]))
  const story = await post(api, '/training/stories', { requestId: crypto.randomUUID(), templateKind: kind, fields })
  const data = process.env.INTERVIEW_E2E_DATA_DIR
  if (!data?.includes('interview-e2e.')) throw new Error('引用夹具只能写入e2e.sh创建的隔离测试库')
  execFileSync('python3', ['-c', `import json,sqlite3,sys
from pathlib import Path
with sqlite3.connect(Path(sys.argv[1])/'interview.db') as db:
    story=json.loads(db.execute('select payload from v2_stories where id=?',(sys.argv[2],)).fetchone()[0])
    version=json.loads(db.execute('select payload from v2_story_versions where id=?',(story['currentVersionId'],)).fetchone()[0])
    material=json.loads(sys.argv[3]); snapshot='material:'+material['id']
    for key in json.loads(sys.argv[4]):
        field=version['fields'][key]; field['originType']='MATERIAL_QUOTE'
        field['sourceRefs']=[{'kind':'QUOTE','snapshotId':snapshot,'quote':field['text']}]
    version['sourceSnapshots']=[{'id':snapshot,'purpose':'MATERIAL','materialId':material['id'],'text':material['content']}]
    story['fields']=version['fields']
    db.execute('update v2_story_versions set payload=? where id=?',(json.dumps(version,ensure_ascii=False),version['id']))
    db.execute('update v2_stories set payload=? where id=?',(json.dumps(story,ensure_ascii=False),story['id']))
`, data, story.id, JSON.stringify(material), JSON.stringify(keys)])
  const original = await (await api.get(`/api/training/stories/${story.id}/versions/${story.currentVersionId}`)).json()
  return { story, original, quote, key: keys[0]!, unchanged: keys[1]! }
}

test('新建草稿重复点选、切换、刷新均保留；保存后只清理当前模板', async ({ page, request }) => {
  const material = await post(request, '/materials', { kind: 'PROJECT', name: `合成草稿资料-${crypto.randomUUID().slice(0, 6)}`, content: '合成内容：本人实现幂等校验。' })
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  await page.goto('/stories')
  await page.locator('#story-action').fill('STAR未保存行动：实现幂等校验。')
  await page.locator('#story-expression').fill('STAR未保存表达稿')
  const selected = page.locator(`input[value="${material.id}"]`)
  await selected.check()
  await page.getByRole('button', { name: 'STAR 项目故事', exact: true }).click()
  await expect(page.locator('#story-action')).toHaveValue('STAR未保存行动：实现幂等校验。')
  await page.getByRole('button', { name: '自我介绍', exact: true }).click()
  await page.locator('#story-contribution').fill('INTRO个人贡献：设计恢复流程。')
  await page.locator('#story-expression').fill('INTRO表达稿')
  await expect(selected).not.toBeChecked()
  await page.reload()
  await expect(page.locator('#story-contribution')).toHaveValue('INTRO个人贡献：设计恢复流程。')
  await expect(page.locator('#story-expression')).toHaveValue('INTRO表达稿')
  await page.getByRole('button', { name: 'STAR 项目故事', exact: true }).click()
  await expect(page.locator('#story-action')).toHaveValue('STAR未保存行动：实现幂等校验。')
  await expect(page.locator('#story-expression')).toHaveValue('STAR未保存表达稿')
  await expect(selected).toBeChecked()
  const out = resolve('../.runtime/story-edit/screens'); await mkdir(out, { recursive: true })
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: resolve(out, `draft-${width}.png`), fullPage: true })
  }
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page).toHaveURL(/\/stories\/[^/]+$/)
  await page.goto('/stories')
  await expect(page.locator('#story-action')).toHaveValue('')
  await page.getByRole('button', { name: '自我介绍', exact: true }).click()
  await expect(page.locator('#story-contribution')).toHaveValue('INTRO个人贡献：设计恢复流程。')
  expect(errors).toEqual([])
})

for (const kind of ['STAR', 'INTRO'] as const) test(`${kind}引用直接编辑保存，无需选择来源，旧版本及材料引用保留`, async ({ page, request }) => {
  const { story, original, quote, key, unchanged } = await quotedStory(request, kind)
  await page.goto(`/stories/${story.id}`)
  const field = page.locator(`#story-${key}`)
  await expect(field.locator('xpath=ancestor::article').locator('.origin-tag')).toHaveText('材料引用')
  await field.fill('用户补充：我增加超时恢复与补偿边界。')
  await expect(field.locator('xpath=ancestor::article').locator('.origin-tag')).toHaveText('用户填写')
  const [savedResponse] = await Promise.all([
    page.waitForResponse(r => r.url().endsWith(`/stories/${story.id}/versions`) && r.request().method() === 'POST'),
    page.getByRole('button', { name: '保存为新版本', exact: true }).click(),
  ])
  expect(savedResponse.ok(), await savedResponse.text()).toBeTruthy()
  const saved = await savedResponse.json()
  expect(saved.fields[key].originType).toBe('USER_ASSERTION')
  expect(saved.fields[key].sourceRefs).toEqual([])
  expect(saved.fields[unchanged].originType).toBe('MATERIAL_QUOTE')
  await expect(field).toHaveValue('用户补充：我增加超时恢复与补偿边界。')
  await page.reload()
  await expect(field).toHaveValue('用户补充：我增加超时恢复与补偿边界。')
  expect(await (await request.get(`/api/training/stories/${story.id}/versions/${story.currentVersionId}`)).json()).toEqual(original)
  await page.getByText(/查看所有故事版本/).click()
  await page.getByRole('button', { name: /^版本 1 ·/ }).click()
  await page.locator('.historical-detail').getByText('查看当时的原材料引用', { exact: true }).first().click()
  await expect(page.locator('.historical-detail')).toContainText(quote)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('新建保存失败后刷新保留草稿，不自动重发保存', async ({ page }) => {
  let saves = 0
  await page.route('**/api/training/stories', route => {
    if (route.request().method() !== 'POST') return route.continue()
    saves++
    return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '隔离测试：暂时无法保存' }) })
  })
  await page.goto('/stories')
  await page.locator('#story-action').fill('保存失败后必须保留的草稿')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('暂时无法保存')
  await page.reload()
  await expect(page.locator('#story-action')).toHaveValue('保存失败后必须保留的草稿')
  expect(saves).toBe(1)
})
