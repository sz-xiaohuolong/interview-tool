import { expect, it } from 'vitest'
import ts from 'typescript'

const sources = import.meta.glob<string>(['./api/interviews.ts', './pages/interview/Interview*.vue', './pages/interview/QuickPractice*.vue', './components/interview/Interview*.vue'], {
  eager: true, query: '?raw', import: 'default',
})

it('综合面试 源码不能直接使用旧面试路径，应通过 api/interviews.ts 操作综合面试', () => {
  expect(Object.keys(sources)).toEqual(expect.arrayContaining([
    './api/interviews.ts',
    './pages/interview/InterviewConfigure.vue',
    './pages/interview/InterviewSession.vue',
    './pages/interview/QuickPracticeConfigure.vue',
    './components/interview/InterviewConfigurationFields.vue',
    './components/interview/InterviewReport.vue',
  ]))
  const violations: string[] = []
  for (const [path, source] of Object.entries(sources)) {
    const scripts = path.endsWith('.vue')
      ? [...source.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]!).join('\n')
      : source
    const file = ts.createSourceFile(path, scripts, ts.ScriptTarget.Latest, true)
    function visit(node: ts.Node) {
      const prefix = ts.isStringLiteralLike(node) ? node.text : ts.isTemplateExpression(node) ? node.head.text : ''
      if (/^(?:\/api)?\/sessions(?:[/?#]|$)/.test(prefix)) {
        const { line } = file.getLineAndCharacterOfPosition(node.getStart(file))
        violations.push(`${path}:${line + 1} 使用了旧面试路径 ${prefix}`)
      }
      ts.forEachChild(node, visit)
    }
    visit(file)
  }
  expect(violations, '综合面试 面试请求与跳转应使用 综合面试 客户端和工作台；旧场次兼容应放在共用或旧版模块。').toEqual([])
})
