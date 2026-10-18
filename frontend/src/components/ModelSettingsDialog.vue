<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import Modal from './Modal.vue'
import Notice from './Notice.vue'
import { errorMessage } from '../api'
import { modelRequest, type ConnectionTest, type ModelConfiguration, type ModelDraft } from '../model-settings'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: [value: ModelConfiguration] }>()
const original = ref<ModelConfiguration | null>(null)
const empty = (): ModelDraft => ({ expectedRevision: 0, preset: 'CUSTOM', baseUrl: '', apiKey: '', model: '', fastModel: '', reasoningEffort: '', fastReasoningEffort: '' })
const draft = reactive<ModelDraft>(empty())
const loading = ref(false), busy = ref(''), error = ref(''), message = ref('')
const models = ref<{ id: string }[]>([]), tested = ref<ConnectionTest | null>(null)
let generation = 0, controller: AbortController | undefined
const normalize = (url: string) => url.trim().replace(/\/+$/, '')
const reuseKey = computed(() => original.value?.apiKeyConfigured && original.value.preset === draft.preset && normalize(original.value.baseUrl) === normalize(draft.baseUrl))
const connectionValid = computed(() => !loading.value && !!draft.baseUrl.trim() && (!!draft.apiKey.trim() || reuseKey.value))
const valid = computed(() => connectionValid.value && !!draft.model.trim())
const disabled = computed(() => loading.value || !!busy.value)
const efforts = ['', 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']
function load(value: ModelConfiguration) {
  original.value = value
  Object.assign(draft, { expectedRevision: value.revision, preset: value.preset, baseUrl: value.baseUrl, apiKey: '', model: value.model,
    fastModel: value.fastModel, reasoningEffort: value.reasoningEffort, fastReasoningEffort: value.fastReasoningEffort })
}
watch(() => props.open, async open => {
  const run = ++generation
  controller?.abort(); controller = undefined
  Object.assign(draft, empty()); original.value = null; models.value = []; tested.value = null
  error.value = ''; message.value = ''; busy.value = ''; loading.value = false
  if (!open) return
  controller = new AbortController(); loading.value = true
  try { const value = await modelRequest<ModelConfiguration>('', undefined, controller.signal); if (run === generation) load(value) }
  catch (e) { if (run === generation) error.value = errorMessage(e) }
  finally { if (run === generation) loading.value = false }
}, { immediate: true })
function presetChanged() {
  draft.apiKey = ''; draft.model = ''; draft.fastModel = ''; draft.reasoningEffort = ''; draft.fastReasoningEffort = ''
  if (draft.preset === 'OPENAI') draft.baseUrl = 'https://api.openai.com/v1'
  else if (draft.preset === 'DEEPSEEK') draft.baseUrl = 'https://api.deepseek.com/v1'
  models.value = []; tested.value = null; message.value = ''; error.value = ''
}
async function act(action: 'save' | 'reset' | 'models' | 'test') {
  if (disabled.value || !original.value) return
  if (action === 'reset' && !window.confirm('恢复后，后续请求将使用启动时的模型配置。确认恢复？')) return
  const run = generation
  busy.value = action; error.value = ''; message.value = ''; tested.value = null
  const body = action === 'reset' ? { expectedRevision: draft.expectedRevision } : { ...draft }
  controller = new AbortController()
  try {
    if (action === 'test') {
      const result = await modelRequest<ConnectionTest>('/test', body, controller.signal)
      if (run === generation) tested.value = result
    } else if (action === 'models') {
      const result = await modelRequest<{ models: { id: string }[] }>('/models', body, controller.signal)
      if (run === generation) { models.value = result.models; message.value = result.models.length ? `已获取 ${result.models.length} 个模型，也可手动填写。` : '服务未返回可用模型，请手动填写模型 ID。' }
    } else {
      const result = await modelRequest<ModelConfiguration>(action === 'save' ? '' : '/reset', body, undefined, action === 'save' ? 'PUT' : 'POST')
      // 关闭期间已完成的保存仍需通知全站，避免显示旧模型状态。
      emit('saved', result)
      if (run === generation) { load(result); message.value = action === 'save' ? '已保存，后续模型请求使用此配置。' : '已恢复启动配置。' }
    }
  } catch (e) { if (run === generation) error.value = errorMessage(e) }
  finally { if (run === generation) busy.value = '' }
}
// 修改连接后，旧列表与检测结果不再代表当前草稿。
watch(() => [draft.baseUrl, draft.model, draft.apiKey, draft.fastModel, draft.reasoningEffort, draft.fastReasoningEffort], () => { tested.value = null; message.value = '' }, { flush: 'sync' })
</script>

<template>
  <Modal :open="open" title="模型配置" description="全站共用一套连接，适用于综合面试、项目深挖与专项练习。" @update:open="emit('update:open', $event)">
    <form class="model-settings" @submit.prevent="act('save')">
      <p v-if="loading" role="status">正在读取本机配置…</p>
      <Notice :message="error" kind="error" />
      <fieldset :disabled="disabled || !original">
        <div class="model-grid">
          <label class="field"><span>服务商</span><select id="model-preset" v-model="draft.preset" @change="presetChanged"><option value="OPENAI">OpenAI</option><option value="DEEPSEEK">DeepSeek</option><option value="CUSTOM">自定义 OpenAI 兼容服务</option></select></label>
          <label class="field"><span>服务地址</span><input id="model-url" v-model="draft.baseUrl" type="url" placeholder="https://api.example.com/v1" autocomplete="off" /><small>填写 API 基础地址，支持本机 HTTP 服务。</small></label>
        </div>
        <label class="field"><span>API Key</span><input id="model-key" v-model="draft.apiKey" type="password" :placeholder="reuseKey ? '已配置，留空沿用当前连接密钥' : '请输入此服务的 API Key'" autocomplete="new-password" spellcheck="false" /><small>密钥仅保存在本机后端；更换地址或服务商后需重新填写。</small></label>
        <label class="field"><span>模型 ID</span><input id="model-id" v-model="draft.model" list="available-models" placeholder="手动填写，或获取后选择" autocomplete="off" /><datalist id="available-models"><option v-for="m in models" :key="m.id" :value="m.id" /></datalist></label>
        <button type="button" class="btn btn-secondary" data-action="model-models" :disabled="!connectionValid" @click="act('models')">{{ busy === 'models' ? '正在获取…' : '获取模型列表' }}</button>
        <details class="model-advanced"><summary>高级配置</summary>
          <label class="field"><span>追问模型 ID（可选）</span><input v-model="draft.fastModel" list="available-models" placeholder="留空使用主模型" autocomplete="off" /></label>
          <div class="model-grid"><label class="field"><span>主模型思考强度</span><select v-model="draft.reasoningEffort"><option v-for="effort in efforts" :key="effort" :value="effort">{{ effort || '服务默认' }}</option></select></label>
            <label v-if="draft.preset !== 'DEEPSEEK'" class="field"><span>追问思考强度</span><select v-model="draft.fastReasoningEffort"><option v-for="effort in efforts" :key="effort" :value="effort">{{ effort || '服务默认' }}</option></select></label></div>
          <p class="form-help">仅设置服务支持的思考强度。DeepSeek 追问关闭思考以减少等待。</p>
        </details>
      </fieldset>
      <p v-if="original?.provider === 'mock'" class="form-help">启动配置为显式 Mock 测试模式。保存真实连接后全站切换；恢复可回到启动配置。</p>
      <p class="form-help">测试连接会发送一条合成 JSON 请求，消耗少量 Token。仅验证连通与格式；保存不会调用模型。</p>
      <p v-if="tested" class="test-result" role="status">JSON 格式检测通过 · {{ tested.elapsedMs }} 毫秒<span v-if="tested.usage.totalTokens !== undefined"> · 实际 {{ tested.usage.totalTokens }} Token</span><span v-else> · 服务未返回完整 Token 用量</span></p>
      <p v-if="message" role="status" class="test-result">{{ message }}</p>
      <div class="model-actions">
        <button type="button" class="btn btn-secondary" data-action="model-reset" :disabled="disabled || !original" @click="act('reset')">恢复启动配置</button>
        <button type="button" class="btn btn-secondary" data-action="model-test" :disabled="disabled || !valid" @click="act('test')">{{ busy === 'test' ? '正在检测…' : '测试连接' }}</button>
        <button type="submit" class="btn btn-primary" data-action="model-save" :disabled="disabled || !valid">{{ busy === 'save' ? '正在保存…' : '保存配置' }}</button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
.model-settings { display: grid; gap: 14px; }
fieldset { border: 0; padding: 0; margin: 0; display: grid; gap: 14px; min-width: 0; }
.model-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.field { margin: 0; display: grid; gap: 6px; min-width: 0; }
.field small { color: var(--text-muted); font-size: 12px; }
.model-advanced { padding-top: 4px; }
.model-advanced summary { cursor: pointer; margin-bottom: 14px; }
.model-advanced .field { margin-bottom: 10px; }
.model-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.test-result { color: var(--text-primary); font-size: 13px; margin: 0; }
@media (max-width: 600px) { .model-grid { grid-template-columns: 1fr; } .model-actions .btn { flex: 1 1 auto; } }
</style>
