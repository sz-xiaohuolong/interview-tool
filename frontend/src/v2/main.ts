import { createApp } from 'vue'
import router from '../router'
import '../style.css'
import './v2.css'

async function boot() {
  let enabled = false
  try {
    const response = await fetch('/api/v2/status', { credentials: 'same-origin', signal: AbortSignal.timeout(5000) })
    if (response.ok) enabled = (await response.json()).enabled === true
  } catch { /* 旧后端或离线时保持原有 V1 应用和错误展示。 */ }
  if (!enabled) { await import('../main'); return }
  const [{ registerV2 }, { default: App }] = await Promise.all([import('./routes'), import('./V2App.vue')])
  registerV2(router)
  createApp(App).use(router).mount('#app')
}
void boot()
