import { onUnmounted } from 'vue'
import { request } from './api'

export interface ModelConfiguration {
  revision: number
  source: 'STARTUP' | 'CUSTOM'
  preset: 'OPENAI' | 'DEEPSEEK' | 'CUSTOM'
  provider: 'openai' | 'mock'
  baseUrl: string
  model: string
  fastModel: string
  reasoningEffort: string
  fastReasoningEffort: string
  apiKeyConfigured: boolean
  configured: boolean
}
export type ModelDraft = Pick<ModelConfiguration, 'preset' | 'baseUrl' | 'model' | 'fastModel' | 'reasoningEffort' | 'fastReasoningEffort'> & { apiKey: string; expectedRevision: number }
export interface ConnectionTest { jsonSupported: boolean; elapsedMs: number; usage: { promptTokens?: number; completionTokens?: number; totalTokens?: number } }
const changed = new EventTarget()
export function notifyModelConfigurationChanged() { changed.dispatchEvent(new Event('change')) }
/** 刷新健康状态时保留当前页面及答题草稿。 */
export function onModelConfigurationChanged(refresh: () => void | Promise<void>) {
  const listener = () => { void refresh() }
  changed.addEventListener('change', listener)
  onUnmounted(() => changed.removeEventListener('change', listener))
}
export function modelRequest<T>(path = '', body?: unknown, signal?: AbortSignal, method = 'POST') {
  return request<T>(`/model-config${path}`, body === undefined ? { signal } : { method, body: JSON.stringify(body), signal })
}
