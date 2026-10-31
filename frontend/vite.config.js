import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 前端独立开发、独立部署（对接 Spring Boot 8080）：开发期走 Vite 代理绕开跨域，
 * 生产期由 Nginx 反代同源 /api，前端代码里不出现任何后端地址。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_PROXY || 'http://127.0.0.1:18080', changeOrigin: true },
      '/actuator': { target: process.env.VITE_PROXY || 'http://127.0.0.1:18080', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', sourcemap: false },
})
