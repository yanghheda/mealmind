import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 本地开发时，先在 services/biz 执行 `mvn spring-boot:run`（连接共享 MySQL 127.0.0.1:3306），
    // 再由该代理把 /api 转发到 127.0.0.1:8080，前端保持同源访问，无需 CORS。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  define: {
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
})
