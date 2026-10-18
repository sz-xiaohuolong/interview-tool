import { defineConfig } from '@playwright/test'
import original from './playwright.config'

// 使用独立合成库和 V3 后端运行原有页面，验证旧行为与新增模块兼容。
const servers = original.webServer as Array<Record<string, any>>
export default defineConfig({
  ...original,
  webServer: [
    {
      ...servers[0],
      command: 'java -jar v3/target/interview-coach-v3-3.0.0.jar --spring.profiles.active=v3 --app.v3.knowledge.network-enabled=false',
      env: { ...servers[0].env, V2_ENABLED: 'true' },
    },
    servers[1],
  ],
})
