import { defineConfig } from '@playwright/test'
import original from './playwright.config'

// 复用隔离合成库，使用统一应用入口。
const servers = original.webServer as Array<Record<string, any>>
export default defineConfig({
  ...original,
  projects: [{ name: 'model-settings' }],
  testMatch: '**/model-settings.spec.ts',
  webServer: [servers[0], { ...servers[1], command: 'npm run dev -- --port 4173' }],
})
