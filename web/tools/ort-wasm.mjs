import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'

/**
 * Serves onnxruntime-web's single-threaded SIMD wasm at `/ort/` for the
 * photo-analysis worker: emitted into the build, served from node_modules in
 * dev. The package does not export the file, so it cannot be imported, and
 * the binary stays out of the repository.
 */
const FILE = 'ort-wasm-simd-threaded.wasm'

function wasmPath() {
  const require = createRequire(import.meta.url)
  // The package exports neither package.json nor the wasm; its CommonJS entry sits beside the wasm in dist/.
  return join(dirname(require.resolve('onnxruntime-web')), FILE)
}

export function ortWasm() {
  return {
    name: 'remanence-ort-wasm',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        if (!request.url?.split('?')[0]?.endsWith(`/ort/${FILE}`)) return next()
        response.setHeader('Content-Type', 'application/wasm')
        response.end(readFileSync(wasmPath()))
      })
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: `ort/${FILE}`, source: readFileSync(wasmPath()) })
    },
  }
}
