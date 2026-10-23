import { defineConfig } from '@playwright/test'
import original from './playwright.materials.config'

const servers = original.webServer as any[]
const activePortFile = process.env.INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE
if (!activePortFile) throw new Error('请运行 bash scripts/e2e-job-search.sh，使用隔离的合成招聘浏览器。')
export default defineConfig({
  ...original,
  timeout: 60_000,
  outputDir: '../.evidence/job-search/test-results',
  webServer: [
    { ...servers[0], command: 'java -jar .runtime/job-search/build/interview-coach-1.0.0.jar --app.knowledge.network-enabled=false', env: { ...servers[0].env, INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE: activePortFile } },
    servers[1],
  ],
})
