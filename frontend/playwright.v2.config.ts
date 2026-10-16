import { defineConfig } from '@playwright/test'
import base from './playwright.config'
const servers = Array.isArray(base.webServer) ? base.webServer : [base.webServer!]
export default defineConfig({ ...base, timeout: 60000, webServer: servers.map((server, i) => i ? server : { ...server, command: 'python3 scripts/migrate-v2.py --data-dir "$INTERVIEW_E2E_DATA_DIR" --backup "$INTERVIEW_E2E_DATA_DIR-backup" --confirm-maintenance && java -jar backend/target/interview-coach-1.0.0.jar', env: { ...server.env, V2_ENABLED: 'true' } }) })
