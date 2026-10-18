import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 依赖全部本地打包，运行时不依赖任何 CDN。
export default defineConfig({
  base: '/',
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 组件库 + 编辑器体积偏大，放宽告警阈值（不影响构建结果）
    chunkSizeWarningLimit: 2000,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:38080',
        changeOrigin: true,
      },
    },
  },
})
