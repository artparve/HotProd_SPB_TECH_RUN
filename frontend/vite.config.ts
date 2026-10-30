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
      host: env.DEV_HOST || '127.0.0.1',
      port: Number(env.DEV_PORT || 5173),
      strictPort: true,
      proxy: {
        '/api': { target: env.API_UPSTREAM || 'http://127.0.0.1:8000', changeOrigin: true,
          timeout: Number(env.DEV_PROXY_TIMEOUT_MS || 1265000), proxyTimeout: Number(env.DEV_PROXY_TIMEOUT_MS || 1265000) },
      },
    },
    build: { sourcemap: false },
  }
})
