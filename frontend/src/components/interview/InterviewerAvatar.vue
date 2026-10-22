<template>
  <svg viewBox="0 0 100 100" class="avatar-svg" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <circle cx="50" cy="50" r="48" fill="#e2e8f0" />
    <!-- Clothes / Collar -->
    <path d="M22 88 C25 66 38 60 50 60 C62 60 75 66 78 88 Z" fill="#3b82f6" />
    <path d="M42 60 L50 72 L58 60 Z" fill="#ffffff" />
    <!-- Neck -->
    <rect x="44" y="52" width="12" height="12" fill="#fcd34d" rx="2" />
    <!-- Face -->
    <ellipse cx="50" cy="42" rx="22" ry="24" fill="#fde68a" />
    <!-- Hair -->
    <path d="M26 38 C26 20 36 12 50 12 C64 12 74 20 74 38 C74 40 70 33 66 31 C60 29 54 33 50 33 C44 33 38 29 34 31 C30 33 26 40 26 38 Z" fill="#1e293b" />
    <!-- Ears -->
    <circle cx="28" cy="44" r="5" fill="#fcd34d" />
    <circle cx="72" cy="44" r="5" fill="#fcd34d" />
    <!-- Glasses -->
    <rect x="34" y="38" width="12" height="9" rx="3" fill="none" stroke="#334155" stroke-width="2.5" />
    <rect x="54" y="38" width="12" height="9" rx="3" fill="none" stroke="#334155" stroke-width="2.5" />
    <line x1="46" y1="42" x2="54" y2="42" stroke="#334155" stroke-width="2.5" />
    <!-- Eyes -->
    <circle cx="40" cy="42" r="2.5" fill="#0f172a" />
    <circle cx="60" cy="42" r="2.5" fill="#0f172a" />
    <!-- Smile -->
    <path d="M44 54 Q50 59 56 54" fill="none" stroke="#ea580c" stroke-width="2" stroke-linecap="round" />
  </svg>
</template>

<style scoped>
.avatar-svg {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  display: block;
}
</style>
