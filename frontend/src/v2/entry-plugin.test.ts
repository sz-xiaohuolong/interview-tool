import { expect, it } from 'vitest'
import { extendEntry } from './entry-plugin'

it('默认HTML只启动一个入口，并保留其余内容', () => {
  const output = extendEntry('<title>面试练习室</title><script type="module" src="/src/main.ts"></script>')
  expect(output).toContain('<title>面试练习室</title>')
  expect(output).toContain('src="/src/v2/main.ts"')
  expect(output).not.toContain('src="/src/main.ts"')
})
it('入口缺失或重复时构建明确失败，避免挂载两个应用', () => {
  expect(() => extendEntry('<main></main>')).toThrow()
  expect(() => extendEntry('<script src="/src/main.ts"></script><script src="/src/main.ts"></script>')).toThrow()
})
