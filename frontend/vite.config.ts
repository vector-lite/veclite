import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  build: {
    outDir: path.resolve(__dirname, '../src/main/resources/static'),
    emptyOutDir: true
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      '/veclite/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
