import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 构建产物由 Gradle 收进 jar 的 static/（AGENTFORGE_WEB_OUT 由 :app-web:npmBuild 给出）；
// 开发时 npm run dev，/api 代理到后端（AGENTFORGE_WEB_URL，缺省本机 8787）。
const backend = process.env.AGENTFORGE_WEB_URL ?? 'http://127.0.0.1:8787'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(import.meta.dirname, './src') } },
  build: {
    outDir: process.env.AGENTFORGE_WEB_OUT ?? 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 700,
    // Markdown + 高亮单独成块（只有聊天页要，长期缓存）
    rolldownOptions: {
      output: {
        advancedChunks: {
          groups: [
            { name: 'markdown', test: /node_modules[\\/](react-markdown|remark-|rehype-|highlight\.js|lowlight|micromark|mdast-|hast-|unified|unist-|vfile)/ },
            { name: 'react', test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/ },
          ],
        },
      },
    },
  },
  server: {
    proxy: {
      // changeOrigin：后端只认 localhost 的 Host 头
      '/api': { target: backend, changeOrigin: true },
    },
  },
})
