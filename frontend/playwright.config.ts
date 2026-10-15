import { defineConfig, devices } from '@playwright/test'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = dirname(fileURLToPath(import.meta.url))
const appRoot = resolve(frontendRoot, '..')
const dataDir = process.env.INTERVIEW_E2E_DATA_DIR
if (!dataDir) throw new Error('请从仓库根目录运行 bash scripts/e2e.sh，以使用独立的临时测试数据目录。')

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:4173',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 1000 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: 'java -jar backend/target/interview-coach-1.0.0.jar',
      cwd: appRoot,
      url: 'http://127.0.0.1:18080/api/health',
      env: {
        PORT: '18080',
        DATA_DIR: dataDir,
        LLM_PROVIDER: 'mock',
        LLM_API_KEY: '',
        LLM_BASE_URL: 'http://127.0.0.1:18080/unused-mock-endpoint',
        LLM_MODEL: 'mock-functional-test',
      },
      timeout: 90_000,
      reuseExistingServer: false,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
    },
    {
      command: 'npm run dev -- --port 4173',
      cwd: frontendRoot,
      url: 'http://127.0.0.1:4173',
      env: { API_PROXY_TARGET: 'http://127.0.0.1:18080' },
      timeout: 30_000,
      reuseExistingServer: false,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
    },
  ],
})
