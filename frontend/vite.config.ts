import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080' },
  },
  test: { environment: 'jsdom', include: ['src/**/*.test.ts'], restoreMocks: true },
})
