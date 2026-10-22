<script setup lang="ts">
import { Mic } from 'lucide-vue-next'

defineProps<{
  thinking?: boolean
}>()
</script>

<template>
  <div class="video-tile interviewer-tile" :class="{ 'is-thinking': thinking }">
    <!-- Simulated Video Stream Background & Illustration -->
    <svg viewBox="0 0 320 200" class="video-canvas" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <defs>
        <linearGradient id="bg-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#cbd5e1" />
          <stop offset="50%" stop-color="#e2e8f0" />
          <stop offset="100%" stop-color="#f8fafc" />
        </linearGradient>
        <linearGradient id="shirt-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#f8fafc" />
          <stop offset="100%" stop-color="#e2e8f0" />
        </linearGradient>
      </defs>
      <!-- Room Background -->
      <rect width="320" height="200" fill="url(#bg-grad)" />
      <!-- Minimalist shelf on back wall -->
      <rect x="230" y="30" width="70" height="4" rx="2" fill="#94a3b8" opacity="0.6" />
      <rect x="240" y="16" width="12" height="14" rx="1" fill="#64748b" opacity="0.7" />
      <rect x="256" y="12" width="10" height="18" rx="1" fill="#3b82f6" opacity="0.7" />
      <rect x="270" y="20" width="16" height="10" rx="1" fill="#059669" opacity="0.7" />
      
      <!-- Desk/laptop outline at bottom -->
      <path d="M0 185 L320 185 L320 200 L0 200 Z" fill="#64748b" opacity="0.4" />

      <!-- Interviewer Body -->
      <!-- Shoulders & Torso -->
      <path d="M90 200 C95 145 125 130 160 130 C195 130 225 145 230 200 Z" fill="url(#shirt-grad)" />
      <!-- Graphic on shirt -->
      <rect x="145" y="155" width="30" height="22" rx="4" fill="#3b82f6" opacity="0.7" />
      <text x="160" y="170" font-size="10" fill="#ffffff" font-family="sans-serif" text-anchor="middle" font-weight="bold">GEN</text>

      <!-- Neck -->
      <rect x="150" y="112" width="20" height="24" fill="#fcd34d" rx="4" />

      <!-- Head / Face -->
      <ellipse cx="160" cy="90" rx="34" ry="38" fill="#fed7aa" />

      <!-- Hair -->
      <path d="M122 85 C120 54 138 42 160 42 C182 42 200 54 198 85 C198 88 190 78 184 76 C176 72 166 78 160 78 C152 78 144 72 136 76 C130 78 122 88 122 85 Z" fill="#1e293b" />
      
      <!-- Ears -->
      <circle cx="126" cy="94" r="7" fill="#fcd34d" />
      <circle cx="194" cy="94" r="7" fill="#fcd34d" />

      <!-- Glasses -->
      <rect x="135" y="85" width="20" height="15" rx="4" fill="none" stroke="#334155" stroke-width="3" />
      <rect x="165" y="85" width="20" height="15" rx="4" fill="none" stroke="#334155" stroke-width="3" />
      <line x1="155" y1="92" x2="165" y2="92" stroke="#334155" stroke-width="3" />

      <!-- Eyes -->
      <circle cx="145" cy="92" r="3.5" fill="#0f172a" />
      <circle cx="175" cy="92" r="3.5" fill="#0f172a" />
      <!-- Eye catchlights -->
      <circle cx="146.5" cy="90.5" r="1.2" fill="#ffffff" />
      <circle cx="176.5" cy="90.5" r="1.2" fill="#ffffff" />

      <!-- Eyebrows -->
      <path d="M136 79 Q145 77 153 80" stroke="#1e293b" stroke-width="2.5" fill="none" stroke-linecap="round" />
      <path d="M167 80 Q175 77 184 79" stroke="#1e293b" stroke-width="2.5" fill="none" stroke-linecap="round" />

      <!-- Nose -->
      <path d="M160 94 L158 103 L164 103" stroke="#f59e0b" stroke-width="1.8" fill="none" stroke-linecap="round" />

      <!-- Friendly Smile -->
      <path d="M150 110 Q160 118 170 110" fill="none" stroke="#ea580c" stroke-width="2.5" stroke-linecap="round" />
    </svg>

    <!-- Overlay Audio Pill Badge -->
    <div class="video-badge interviewer-badge">
      <span class="mic-dot" :class="{ 'is-active': !thinking, 'is-thinking': thinking }">
        <Mic :size="12" />
      </span>
      <span>{{ thinking ? 'AI 面试官 · 思考中' : 'AI 面试官' }}</span>
    </div>

    <!-- Thinking Wave Animation -->
    <div v-if="thinking" class="thinking-waves">
      <span class="wave wave-1" />
      <span class="wave wave-2" />
      <span class="wave wave-3" />
    </div>
  </div>
</template>

<style scoped>
.video-tile {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 10;
  border-radius: 12px;
  overflow: hidden;
  background: #0f172a;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
}

.video-canvas {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.video-badge {
  position: absolute;
  bottom: 10px;
  left: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.75);
  backdrop-filter: blur(8px);
  color: #f8fafc;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.01em;
  z-index: 2;
}

.mic-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #22c55e;
}

.mic-dot.is-thinking {
  color: #eab308;
  animation: pulse 1s infinite;
}

.thinking-waves {
  position: absolute;
  top: 10px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 4px 8px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.65);
  backdrop-filter: blur(4px);
  z-index: 2;
}

.wave {
  width: 3px;
  height: 14px;
  background: #38bdf8;
  border-radius: 2px;
  animation: wave-anim 1s ease-in-out infinite alternate;
}

.wave-2 {
  animation-delay: 0.2s;
  height: 20px;
}

.wave-3 {
  animation-delay: 0.4s;
}

@keyframes wave-anim {
  0% { transform: scaleY(0.4); opacity: 0.5; }
  100% { transform: scaleY(1.1); opacity: 1; }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
