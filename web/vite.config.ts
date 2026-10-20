import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const staticBase =
  (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env?.REMANENCE_STATIC_BASE ?? '/'
let resolvedStaticBase = staticBase

export default defineConfig({
  base: staticBase,
  plugins: [react(), {
    name: 'remanence-static-base',
    configResolved(config) { resolvedStaticBase = config.base },
  }],
  // Public assets (fonts/boot/etc.) are referenced with root-absolute URLs.
  // Prefix them with the selected static base at build time; hashed assets and
  // import.meta.env.BASE_URL keep their normal Vite behavior.
  experimental: {
    renderBuiltUrl(filename: string, { type }: { type: string }) {
      if (type === 'public') return `${resolvedStaticBase}${filename.replace(/^\/+/, '')}`
      return undefined
    },
  },
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
    include: ['test/**/*.test.ts', 'test/**/*.test.mjs'],
  },
})
