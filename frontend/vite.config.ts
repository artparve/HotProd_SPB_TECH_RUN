import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

const envDir = fileURLToPath(new URL('../env', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, envDir, '')
  return {
    envDir,
    plugins: [vue()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: env.API_UPSTREAM || 'http://127.0.0.1:8000', changeOrigin: true },
      },
    },
    build: { sourcemap: false },
  }
})
