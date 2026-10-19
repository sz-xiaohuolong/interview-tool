import { defineConfig } from '@playwright/test'

// 本用例仅使用显式合成接口响应，验证报告展示与原答定位。
export default defineConfig({
  testDir: './tests', testMatch: '**/detailed-assessment.spec.ts', workers: 1, retries: 0,
  reporter: 'list', timeout: 30000,
  use: { baseURL: 'http://127.0.0.1:4176', locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, screenshot: 'only-on-failure' },
  webServer: { command: 'npm run dev -- --port 4176', url: 'http://127.0.0.1:4176', reuseExistingServer: false },
})
