<template>
  <svg viewBox="0 0 100 100" class="avatar-svg" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <circle cx="50" cy="50" r="48" fill="#fef08a" />
    <!-- Hair / Cap -->
    <path d="M20 45 C18 24 34 13 50 13 C66 13 82 24 80 45 C78 36 70 32 64 33 C58 34 54 38 50 38 C46 38 42 34 36 33 C30 32 22 36 20 45 Z" fill="#475569" />
    <path d="M18 45 C22 43 28 44 32 46 C28 50 24 55 20 54 Z" fill="#475569" />
    <path d="M82 45 C78 43 72 44 68 46 C72 50 76 55 80 54 Z" fill="#475569" />
    <!-- Yellow Face -->
    <circle cx="50" cy="54" r="31" fill="#facc15" />
    <!-- Eyes -->
    <ellipse cx="38" cy="49" rx="3.5" ry="5" fill="#0f172a" />
    <ellipse cx="62" cy="49" rx="3.5" ry="5" fill="#0f172a" />
    <!-- Cheeks -->
    <circle cx="28" cy="56" r="4" fill="#f87171" opacity="0.35" />
    <circle cx="72" cy="56" r="4" fill="#f87171" opacity="0.35" />
    <!-- Duck Bill -->
    <ellipse cx="50" cy="58" rx="12" ry="7.5" fill="#fb923c" stroke="#ea580c" stroke-width="1.5" />
    <line x1="41" y1="58" x2="59" y2="58" stroke="#c2410c" stroke-width="1.2" stroke-linecap="round" />
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
