<script setup lang="ts">
import { Mic } from 'lucide-vue-next'
import CandidateAvatar from './CandidateAvatar.vue'
</script>

<template>
  <div class="video-tile candidate-tile">
    <div class="avatar-container">
      <div class="avatar-wrapper">
        <CandidateAvatar />
      </div>
    </div>
    <div class="video-badge candidate-badge">
      <span class="mic-dot"><Mic :size="12" /></span>
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
