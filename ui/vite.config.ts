import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 前端由 Java 服务挂在 /ui/ 下（见 web/UiAssets 与 README），
 * 所以 base 必须与那个前缀一致，否则打包出来的资源路径会 404。
 *
 * 开发时可以 `npm run dev` 单独跑（5173），此时通过代理把 /agui 转给后端，
 * 免去 CORS 折腾。
 */
export default defineConfig({
  plugins: [react()],
  base: '/ui/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    proxy: {
      '/agui': { target: 'http://127.0.0.1:8787', changeOrigin: true },
      '/health': { target: 'http://127.0.0.1:8787', changeOrigin: true },
      '/sessions': { target: 'http://127.0.0.1:8787', changeOrigin: true },
    },
  },
})
