import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'
import './styles/training.css'

async function boot() {
  let trainingEnabled = false
  try {
    const response = await fetch('/api/training/status', { credentials: 'same-origin', signal: AbortSignal.timeout(5000) })
    if (response.ok) trainingEnabled = (await response.json()).enabled === true
  } catch { /* 专项训练不可用时仍挂载共用页面，具体请求错误由页面显示。 */ }
  createApp(App, { trainingEnabled }).use(router).mount('#app')
}
void boot()
