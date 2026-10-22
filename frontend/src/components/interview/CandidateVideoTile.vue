<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { Camera, CameraOff, Mic } from 'lucide-vue-next'
import CandidateAvatar from './CandidateAvatar.vue'

const cameraActive = ref(false)
const videoRef = ref<HTMLVideoElement | null>(null)
let mediaStream: MediaStream | null = null

async function toggleCamera() {
  if (cameraActive.value) {
    if (mediaStream) {
      mediaStream.getTracks().forEach(track => track.stop())
      mediaStream = null
    }
    cameraActive.value = false
    if (videoRef.value) videoRef.value.srcObject = null
  } else {
    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
      cameraActive.value = true
      await nextTick()
      if (videoRef.value) {
        videoRef.value.srcObject = mediaStream
      }
    } catch {
      cameraActive.value = false
    }
  }
}

onBeforeUnmount(() => {
  if (mediaStream) {
    mediaStream.getTracks().forEach(track => track.stop())
    mediaStream = null
  }
})
</script>

<template>
  <div class="video-tile candidate-tile">
    <!-- Camera stream if active -->
    <video
      v-if="cameraActive"
      ref="videoRef"
      autoplay
      playsinline
      muted
      class="webcam-feed"
    />
    <!-- Default Cute Mascot Avatar when camera is off -->
    <div v-else class="avatar-container">
      <div class="avatar-wrapper">
        <CandidateAvatar />
      </div>
    </div>

    <!-- Camera Toggle Button -->
    <button
      type="button"
      class="camera-toggle-btn"
      :title="cameraActive ? '关闭摄像头' : '开启摄像头'"
      :aria-label="cameraActive ? '关闭摄像头' : '开启摄像头'"
      @click="toggleCamera"
    >
      <CameraOff v-if="cameraActive" :size="13" />
      <Camera v-else :size="13" />
    </button>

    <!-- Overlay Audio Pill Badge -->
    <div class="video-badge candidate-badge">
      <span class="mic-dot">
        <Mic :size="12" />
      </span>
      <span>候选人 (我)</span>
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
  background: #1e293b;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
}

.webcam-feed {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scaleX(-1); /* Mirror view for natural camera reflection */
}

.avatar-container {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: radial-gradient(circle at center, #334155 0%, #1e293b 80%);
}

.avatar-wrapper {
  width: 90px;
  height: 90px;
  filter: drop-shadow(0 4px 10px rgba(0, 0, 0, 0.25));
}

.camera-toggle-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.65);
  backdrop-filter: blur(6px);
  color: #e2e8f0;
  border: 1px solid rgba(255, 255, 255, 0.1);
  cursor: pointer;
  transition: all 0.2s ease;
  z-index: 2;
}

.camera-toggle-btn:hover {
  background: rgba(15, 23, 42, 0.9);
  color: #ffffff;
  transform: scale(1.05);
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
</style>
