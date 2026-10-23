import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  // Only frontend/.env* is read; root backend credentials never enter Vite.
  const env = loadEnv(mode, process.cwd(), 'DEV_')
  const target = env.DEV_BACKEND_TARGET || 'http://127.0.0.1:8080'
  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1', port: 5173, strictPort: true,
      proxy: {
        '/api': { target, changeOrigin: false },
        // Only readiness is needed by the workbench; do not proxy all Actuator endpoints.
        '/actuator/health/readiness': { target, changeOrigin: false },
      },
    },
    preview: { host: '127.0.0.1', port: 4173, strictPort: true },
  }
})
