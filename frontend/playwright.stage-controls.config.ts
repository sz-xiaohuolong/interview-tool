import { defineConfig } from '@playwright/test'
import original from './playwright.config'

// 显式Mock与独立合成库，验证综合面试完整入口。
const servers = original.webServer as Array<Record<string, any>>
export default defineConfig({
  ...original,
  testMatch: '**/stage-controls.spec.ts',
  use: { ...original.use, baseURL: 'http://127.0.0.1:4173' },
  webServer: [
    { ...servers[0], url: 'http://127.0.0.1:18121/api/health', env: { ...servers[0].env, PORT: '18121', LLM_BASE_URL: 'http://127.0.0.1:18121/unused-mock-endpoint' } },
    { ...servers[1], url: 'http://127.0.0.1:4173', command: 'npm run dev -- --port 4173', env: { ...servers[1].env, API_PROXY_TARGET: 'http://127.0.0.1:18121' } },
  ],
})
