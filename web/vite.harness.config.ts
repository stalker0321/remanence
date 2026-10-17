import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/** Isolated fixture page. vite build of index.html does not include this root. */
export default defineConfig({
  root: 'harness',
  publicDir: '../public',
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    fs: { allow: ['..'] },
  },
  build: {
    outDir: '../harness-dist',
    emptyOutDir: true,
    sourcemap: false,
  },
})
