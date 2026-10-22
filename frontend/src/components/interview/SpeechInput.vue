<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

interface RecognitionResult { readonly isFinal: boolean; readonly length: number; readonly [index: number]: { transcript: string; confidence: number } }
interface RecognitionEvent { resultIndex: number; results: ArrayLike<RecognitionResult> }
interface RecognitionInstance {
  lang: string; interimResults: boolean; continuous: boolean
  onstart: (() => void) | null; onaudioend: (() => void) | null; onend: (() => void) | null
  onresult: ((event: RecognitionEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null; onnomatch: (() => void) | null
  start(): void; stop(): void; abort(): void
}
type RecognitionConstructor = new () => RecognitionInstance
type Phase = 'idle' | 'starting' | 'listening' | 'waiting'
interface ActiveRecognition { api: RecognitionInstance; committed: Set<number>; receivedFinal: boolean }

const props = withDefaults(defineProps<{
  modelValue: string
  disabled?: boolean
  contextKey?: string
  maxLength?: number
  variant?: 'default' | 'apple'
}>(), {
  disabled: false,
  contextKey: '',
  maxLength: 6000,
  variant: 'default',
})
const emit = defineEmits<{ 'update:modelValue': [value: string]; waiting: [value: boolean]; listening: [value: boolean] }>()
const speechWindow = window as Window & { SpeechRecognition?: RecognitionConstructor; webkitSpeechRecognition?: RecognitionConstructor }
const Recognition = speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition
const phase = ref<Phase>('idle'), notice = ref(''), preview = ref('')
const label = computed(() => ({ idle: '语音输入', starting: '取消听写', listening: '停止听写', waiting: '取消等待' })[phase.value])
const status = computed(() => ({ idle: '', starting: '正在请求麦克风，请在浏览器中确认…', listening: '正在听写，结束后请核对文字再提交。', waiting: '正在等待识别结果…' })[phase.value])
let active: ActiveRecognition | null = null, waitTimer: ReturnType<typeof setTimeout> | undefined
let currentValue = props.modelValue, disposed = false

function changePhase(next: Phase) {
  const previous = phase.value
  if (previous === next) return
  phase.value = next
  if ((previous === 'listening') !== (next === 'listening')) emit('listening', next === 'listening')
  if ((previous === 'waiting') !== (next === 'waiting')) emit('waiting', next === 'waiting')
}
function detach(abort: boolean) {
  const previous = active
  active = null
  clearTimeout(waitTimer); waitTimer = undefined
  preview.value = ''
  if (previous) {
    previous.api.onstart = null; previous.api.onaudioend = null; previous.api.onend = null
    previous.api.onresult = null; previous.api.onerror = null; previous.api.onnomatch = null
  }
  changePhase('idle')
  if (abort && previous) { try { previous.api.abort() } catch { /* 浏览器已经关闭识别时无需再次处理。 */ } }
}
function cancel() { detach(true) }
function waitForResult(run: ActiveRecognition) {
  if (active !== run || phase.value === 'waiting') return
  changePhase('waiting')
  waitTimer = setTimeout(() => {
    if (active !== run) return
    notice.value = '等待识别结果超时，已有文字已保留。请重试听写或继续打字。'
    cancel()
  }, 4000)
}
function errorNotice(code: string) {
  const messages: Record<string, string> = {
    'not-allowed': '麦克风权限未获允许，请检查浏览器权限或继续打字。',
    'service-not-allowed': '浏览器语音服务不可用，请继续打字或检查浏览器设置。',
    'audio-capture': '无法使用麦克风，请检查设备连接后重试或继续打字。',
    network: '语音识别网络连接失败，已有文字已保留，请重试或继续打字。',
    'no-speech': '没有检测到语音，请重新听写或继续打字。',
    aborted: '语音输入已中止，已有文字已保留。',
    'language-not-supported': '浏览器语音服务暂不支持中文，请继续打字。',
  }
  return messages[code] || '语音识别失败，已有文字已保留，请重试或继续打字。'
}
function appendFinal(text: string) {
  const addition = text.trim()
  if (!addition) return false
  const combined = currentValue + (currentValue && !/\s$/.test(currentValue) ? '\n' : '') + addition
  const next = combined.slice(0, Math.max(currentValue.length, props.maxLength))
  if (next !== currentValue) { currentValue = next; emit('update:modelValue', next) }
  const truncated = combined.length > props.maxLength
  if (truncated) {
    notice.value = '回答已达到字数上限，部分识别内容未写入，请检查文字后再提交。'
    cancel()
  }
  return truncated
}
function stopAndKeepDraft() {
  const unconfirmed = preview.value.trim()
  cancel()
  if (!unconfirmed) return
  const truncated = appendFinal(unconfirmed)
  notice.value = '未确认的听写已保留到草稿，请校对后提交。' + (truncated ? '回答已达到字数上限，部分识别内容未写入。' : '')
}
function start() {
  if (!Recognition || props.disabled || active || disposed) return
  notice.value = ''; preview.value = ''
  let run: ActiveRecognition
  try {
    run = { api: new Recognition(), committed: new Set(), receivedFinal: false }
    active = run
    run.api.lang = 'zh-CN'; run.api.interimResults = true; run.api.continuous = false
    run.api.onstart = () => { if (active === run && phase.value === 'starting') changePhase('listening') }
    run.api.onaudioend = () => waitForResult(run)
    run.api.onresult = event => {
      if (active !== run || props.disabled) return
      const interim: string[] = []
      for (let index = 0; index < event.results.length; index++) {
        const item = event.results[index]
        if (!item || !item[0]) continue
        if (item.isFinal) {
          if (run.committed.has(index)) continue
          run.committed.add(index); run.receivedFinal = true; appendFinal(item[0].transcript)
          if (active !== run) return
        } else interim.push(item[0].transcript)
      }
      preview.value = interim.join('')
      if (phase.value === 'waiting' && run.receivedFinal && !preview.value) detach(true)
    }
    run.api.onerror = event => {
      if (active !== run) return
      notice.value = errorNotice(event.error); cancel()
    }
    run.api.onnomatch = () => {
      if (active !== run) return
      notice.value = '没有识别出可用文字，请重新听写或继续打字。'; cancel()
    }
    run.api.onend = () => {
      if (active !== run) return
      notice.value = run.receivedFinal ? '本段听写已结束，可再次语音输入。' : '本次没有得到最终识别文字，请重新听写或继续打字。'
      detach(false)
    }
    changePhase('starting'); run.api.start()
  } catch {
    notice.value = '无法启动浏览器语音输入，已有文字已保留，请重试或继续打字。'; cancel()
  }
}
function control() {
  if (phase.value === 'idle') { start(); return }
  if (phase.value !== 'listening') { cancel(); return }
  const run = active
  if (!run) return
  waitForResult(run)
  try { run.api.stop() } catch { notice.value = '停止语音输入失败，已有文字已保留。'; cancel() }
}
watch(() => props.modelValue, value => { currentValue = value }, { flush: 'sync' })
watch(() => props.disabled, value => { if (value) cancel() }, { flush: 'sync' })
watch(() => props.contextKey, () => cancel(), { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; cancel() })
defineExpose({ cancel, stopAndKeepDraft })
</script>
<template>
  <div class="speech-input" :class="[variant === 'apple' ? 'variant-apple' : 'variant-default']">
    <template v-if="variant === 'apple'">
      <button
        class="apple-mic-btn"
        type="button"
        :class="{
          'is-listening': phase === 'listening',
          'is-waiting': phase === 'waiting' || phase === 'starting',
          'is-unsupported': !Recognition
        }"
        :disabled="disabled || !Recognition"
        :aria-pressed="phase === 'listening'"
        :title="!Recognition ? '当前浏览器不支持语音输入' : phase === 'listening' ? '停止听写' : '语音输入 (Apple 风格)'"
        @click="control"
      >
        <span class="sr-only">{{ label }}</span>
        <!-- Apple Siri-style Voice Waves when Listening -->
        <span v-if="phase === 'listening'" class="apple-mic-waves" aria-hidden="true">
          <span class="apple-wave wave-1" />
          <span class="apple-wave wave-2" />
          <span class="apple-wave wave-3" />
        </span>
        <!-- Spinner when starting / waiting -->
        <span v-else-if="phase === 'waiting' || phase === 'starting'" class="apple-mic-spinner" aria-hidden="true" />
        <!-- Apple Dictation Microphone Icon -->
        <svg v-else class="apple-mic-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" />
          <path d="M19 10v2a7 7 0 0 1-14 0v-2" />
          <line x1="12" y1="19" x2="12" y2="22" />
          <line x1="8" y1="22" x2="16" y2="22" />
        </svg>
      </button>

      <!-- Accessibility text & test compatibility -->
      <span v-if="!Recognition" class="sr-only" role="status">当前浏览器不支持语音输入，请继续打字或使用 Chrome。</span>
      <span v-else class="sr-only">浏览器可能在线处理语音；本工具不保存音频。文字可修改，请确认后手动提交。</span>
      <span v-if="status" class="sr-only" role="status" aria-live="polite">{{ status }}</span>

      <!-- Floating notification pill for active transcription preview or errors -->
      <div v-if="preview || notice" class="apple-status-toast" :class="{ 'is-notice': !!notice }" role="status">
        <span v-if="preview" class="apple-toast-text">听写中：{{ preview }}</span>
        <span v-if="notice" class="apple-toast-alert" role="alert">{{ notice }}</span>
      </div>
    </template>

    <template v-else>
      <button class="btn btn-secondary btn-sm" type="button" :disabled="disabled || !Recognition" :aria-pressed="phase === 'listening'" @click="control">{{ label }}</button>
      <p v-if="!Recognition" class="form-help" role="status">当前浏览器不支持语音输入，请继续打字或使用 Chrome。</p>
      <p v-else class="form-help">浏览器可能在线处理语音；本工具不保存音频。文字可修改，请确认后手动提交。</p>
      <p v-if="status" class="form-help" role="status" aria-live="polite">{{ status }}</p>
      <p v-if="preview" class="form-help speech-preview" role="status">听写预览：{{ preview }}</p>
      <p v-if="notice" class="form-help" role="alert">{{ notice }}</p>
    </template>
  </div>
</template>
<style scoped>
.variant-default { display: grid; justify-items: start; gap: 6px; margin-top: 12px; }
.variant-default p { margin: 0; }
.speech-preview { white-space: pre-wrap; overflow-wrap: anywhere; }

/* Apple 极简语音按钮风格 */
.variant-apple {
  display: inline-flex;
  align-items: center;
  position: relative;
  margin: 0;
  gap: 0;
}

.apple-mic-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid transparent;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  padding: 0;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  position: relative;
}

.apple-mic-btn:hover:not(:disabled) {
  background: #f1f5f9;
  color: #0f172a;
  transform: scale(1.05);
}

.apple-mic-btn:focus-visible {
  outline: 2px solid #f59e0b;
  outline-offset: 2px;
}

.apple-mic-btn:disabled,
.apple-mic-btn.is-disabled {
  opacity: 0.35;
  cursor: not-allowed;
  background: transparent;
}

.apple-mic-svg {
  width: 19px;
  height: 19px;
}

/* 听写中动态呼吸光晕 (Apple Siri 风格) */
.apple-mic-btn.is-listening {
  background: #fef2f2;
  color: #ef4444;
  border-color: #fecaca;
  animation: apple-mic-pulse 1.6s infinite ease-in-out;
}

@keyframes apple-mic-pulse {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.4);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(239, 68, 68, 0);
  }
}

.apple-mic-waves {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2.5px;
  height: 16px;
}

.apple-wave {
  width: 2.5px;
  border-radius: 9999px;
  background: #ef4444;
  animation: apple-wave-bounce 0.9s infinite ease-in-out;
}

.wave-1 { animation-delay: 0s; height: 8px; }
.wave-2 { animation-delay: 0.18s; height: 16px; }
.wave-3 { animation-delay: 0.36s; height: 10px; }

@keyframes apple-wave-bounce {
  0%, 100% { transform: scaleY(0.4); }
  50% { transform: scaleY(1); }
}

.apple-mic-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid #e2e8f0;
  border-top-color: #f59e0b;
  border-radius: 50%;
  animation: apple-spin 0.8s linear infinite;
}

@keyframes apple-spin {
  to { transform: rotate(360deg); }
}

.apple-status-toast {
  position: absolute;
  bottom: calc(100% + 10px);
  right: 0;
  max-width: 360px;
  background: #0f172a;
  color: #ffffff;
  font-size: 12px;
  line-height: 1.5;
  padding: 6px 14px;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
  pointer-events: none;
  z-index: 50;
  white-space: nowrap;
  animation: toast-fade 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.apple-status-toast.is-notice {
  background: #dc2626;
}

@keyframes toast-fade {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
