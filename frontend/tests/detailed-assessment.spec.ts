import { expect, test } from '@playwright/test'
import type { InterviewSession } from '../src/v3/types'

const time='2026-10-18T08:00:00Z'
const session: InterviewSession = {
  id:'detail-report',status:'REPORT_READY',phase:'复盘已完成',revision:10,
  role:'合成 Agent 开发',level:'MIDDLE',durationMinutes:10,mock:true,createdAt:time,updatedAt:time,
  allowedActions:[],warnings:[],sources:[],
  messages:[
    {id:'m1',role:'ASSISTANT',questionId:'q1',rootQuestionId:'q1',content:'请解释工具超时后如何重试。',createdAt:time},
    {id:'m2',role:'USER',questionId:'q1',content:'目前重新执行整个工作流，最多重试两次，尚未按步骤保存状态。',createdAt:time},
    {id:'m3',role:'ASSISTANT',questionId:'q2',rootQuestionId:'q2',content:'你负责哪些具体工作？',createdAt:time},
    {id:'m4',role:'USER',questionId:'q2',content:'我负责Java工具执行器，先校验参数再调用白名单中的只读工具。',createdAt:time},
  ],
  report:{version:2,summary:'合成短摘要',overview:{answeredCount:2,mainQuestionCount:2,followUpCount:0,endReason:'USER_FINISHED'},
    detailedAssessment:{version:1,
      overall:{text:'本场能说明工具执行链路和职责边界；恢复机制仍需要结合步骤状态进一步验证。',questionIds:['q1','q2']},
      strengths:[{text:'你将参数校验与工具执行分开，明确限制只读工具。这让执行边界可以检查，但本场尚未验证复杂调用。',questionIds:['q2']}],
      improvements:[{text:'整段重试会重复已成功的查询。本场尚未说明如何按步骤恢复，需进一步核对重试范围与状态保存。',questionIds:['q1']}],
      nextSteps:[{text:'绘制步骤恢复状态图，记录已完成步骤；注入一次工具超时，检查恢复时是否重复执行。',questionIds:['q1']}],
    },
    findings:[
      {questionId:'q1',answerStatus:'PARTIAL',answerQuote:'重新执行整个工作流',headline:'重试粒度需要补充',observation:'尚未保存步骤状态',assessment:'技术结论待核实',status:'INSUFFICIENT',sourceIds:[],suggestion:'验证步骤恢复',priority:1},
      {questionId:'q2',answerStatus:'CLEAR',answerQuote:'先校验参数再调用白名单中的只读工具',headline:'职责与调用边界具体',observation:'解释了执行边界',assessment:'技术结论待核实',status:'INSUFFICIENT',sourceIds:[],suggestion:'继续验证失败场景',priority:2},
    ],
  },
}
for(const width of [1440,390,320])test(`${width}px 四部分正文、行动与原答定位`,async({page},testInfo)=>{
  await page.setViewportSize({width,height:1000})
  const writes:string[]=[],unknown:string[]=[],errors:string[]=[]
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
    const r=route.request(),path=new URL(r.url()).pathname
    if(r.method()!=='GET')writes.push(path)
    const responses:Record<string,unknown>={
      '/api/health':{status:'ok',provider:'mock',configured:true,model:'mock'},
      '/api/v3/status':{enabled:true,provider:'mock',configured:true},'/api/v2/status':{enabled:true},
      '/api/materials':[], '/api/v3/sessions/detail-report':session,
      '/api/review-gaps/V3/detail-report':{items:[]},
    }
    if(path in responses)await route.fulfill({json:responses[path]})
    else{unknown.push(path);await route.fulfill({status:404,json:{message:'合成用例未定义接口'}})}
  })
  await page.goto('/interview-v3/detail-report')
  const detail=page.locator('[data-detailed-assessment]')
  await expect(detail.locator('h3')).toHaveText(['整体印象','亮点分析','可提升空间','下一步行动'])
  await expect(detail.locator('ol li')).toHaveCount(1)
  await expect(detail).toContainText('注入一次工具超时')
  await expect(page.locator('[data-question-group]').first()).not.toHaveAttribute('open')
  const locator=detail.locator('[data-assessment-locate="q1"]').last()
  await locator.focus();await locator.press('Enter')
  await expect(page.locator('[data-answer="q1"]')).toBeFocused()
  await expect(page.locator('[data-answer="q1"]')).toContainText('尚未按步骤保存状态')
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true)
  expect(writes).toEqual([]);expect(unknown).toEqual([]);expect(errors).toEqual([])
  await page.screenshot({path:testInfo.outputPath(`detail-${width}.png`),fullPage:true})
})
