import { defineConfig } from '@playwright/test'
import original from './playwright.materials.config'

const servers = original.webServer as any[]
export default defineConfig({
  ...original,
  outputDir: '../.evidence/job-applications/test-results',
  webServer: [
    { ...servers[0], command: 'java -jar .runtime/job-applications/build/interview-coach-1.0.0.jar --app.knowledge.network-enabled=false' },
    servers[1],
  ],
})
