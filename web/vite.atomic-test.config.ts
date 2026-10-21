import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/** Separate opt-in build; ordinary index.html/main.tsx remain byte-for-byte gated. */
export default defineConfig(({ mode }) => {
  if (mode !== 'atomic-test') throw new Error('Select --mode atomic-test for the TEST-only entry.')
  let base = '/'
  return {
    root: 'atomic-test', publicDir: '../public',
    plugins: [react(), {
      name: 'atomic-test-entry',
      configResolved(config) {
        base = config.base
        if (!base.startsWith('/') || base.startsWith('//') || !base.endsWith('/') || /[?#%\\]/.test(base) || base.split('/').some(part => part === '.' || part === '..')) {
          throw new Error('TEST --base must be an absolute same-origin directory path.')
        }
      },
      generateBundle() {
        this.emitFile({ type: 'asset', fileName: 'atomic-test-config.json', source: '{"environment":"UNCONFIGURED"}\n' })
      },
    }],
    experimental: {
      renderBuiltUrl(filename: string, { type }: { type: string }) {
        if (type === 'public') return `${base}${filename.replace(/^\/+/, '')}`
        return undefined
      },
    },
    server: { host: '127.0.0.1', strictPort: true, fs: { allow: ['../..'] } },
    preview: { host: '127.0.0.1', strictPort: true },
    build: { outDir: '../atomic-test-dist', emptyOutDir: true, sourcemap: false },
  }
})
