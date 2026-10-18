import router from '../router'
import { registerV3 } from './routes'

async function boot() {
  try {
    const response = await fetch('/api/v3/status', { credentials: 'same-origin', signal: AbortSignal.timeout(5000) })
    if (response.ok && (await response.json()).enabled === true) registerV3(router)
  } catch { /* V3 未启动时保留原有应用入口与错误提示。 */ }
  await import('../v2/main')
}
void boot()
