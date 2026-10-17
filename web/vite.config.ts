import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    strictPort: true,
    fs: { allow: ['..'] },
  },
  preview: {
    host: '127.0.0.1',
    strictPort: true,
  },
  build: {
    sourcemap: false,
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
  },
})
