import { defineConfig } from '@playwright/test'
import original from './playwright.config'

const servers = original.webServer as any[]
export default defineConfig({
  ...original,
  use: { ...original.use, baseURL: 'http://localhost:4173' },
  webServer: [
    { ...servers[0], url: 'http://127.0.0.1:18180/api/health', env: { ...servers[0].env, PORT: '18180' } },
    { ...servers[1], command: 'npm run dev -- --host ::1 --port 4173', url: 'http://[::1]:4173', env: { API_PROXY_TARGET: 'http://127.0.0.1:18180' } },
  ],
})
