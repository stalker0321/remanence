/// <reference lib="webworker" />
import * as ort from 'onnxruntime-web/wasm'
import { analyzePhoto, type AnalysisNets, type Cv } from './analyzePhoto'

/**
 * Module worker that analyzes the sender's photos off the main thread. It
 * loads the site's own OpenCV build from `/opencv/` (the scanner's runtime,
 * same-origin, never a CDN) for the pixel maps, and runs the two Android
 * models from `/collage/` in onnxruntime-web, bundled with the site.
 * Pixels arrive as an `ImageBitmap`; nothing leaves the worker but the
 * normalized analysis.
 */

declare const self: DedicatedWorkerGlobalScope & { cv?: unknown; Module?: { locateFile(path: string): string } }

export interface AnalysisRequest {
  id: number
  base: string
  bitmap: ImageBitmap
  sourceWidth: number
  sourceHeight: number
}

let ready: Promise<{ cv: Cv; nets: AnalysisNets }> | null = null

function load(base: string): Promise<{ cv: Cv; nets: AnalysisNets }> {
  ready ??= (async () => {
    // The loader reads a global `Module`; point its wasm at the vendored folder.
    self.Module = { locateFile: (path: string) => `${base}opencv/${path}` }
    // Module workers have no importScripts: run the UMD loader as a classic
    // script against the worker global (indirect eval keeps it global).
    const loader = await fetch(`${base}opencv/opencv.js`)
    if (!loader.ok) throw new Error('opencv loader unavailable')
    ;(0, eval)(await loader.text())
    const cv: Cv = await Promise.resolve(self.cv)
    if (typeof cv.Mat !== 'function') {
      await new Promise<void>((resolve) => {
        cv.onRuntimeInitialized = () => resolve()
      })
    }
    // One thread: the site is not cross-origin isolated, so there is no SharedArrayBuffer.
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'error'
    // The wasm is served at /ort/ (tools/ort-wasm.mjs); the glue is bundled.
    ort.env.wasm.wasmPaths = { wasm: `${base}ort/ort-wasm-simd-threaded.wasm` }
    const session = async (name: string) => {
      const response = await fetch(`${base}collage/${name}.onnx`)
      if (!response.ok) throw new Error(`model ${name} unavailable`)
      return ort.InferenceSession.create(new Uint8Array(await response.arrayBuffer()), { executionProviders: ['wasm'] })
    }
    const nets: AnalysisNets = {
      yunet: await session('yunet'),
      nanodet: await session('nanodet'),
      tensor: (data, dims) => new ort.Tensor('float32', data, dims),
    }
    return { cv, nets }
  })().catch((error: unknown) => {
    ready = null
    throw error
  })
  return ready
}

self.onmessage = async (event: MessageEvent<AnalysisRequest>) => {
  const request = event.data
  try {
    const { cv, nets } = await load(request.base)
    const canvas = new OffscreenCanvas(request.bitmap.width, request.bitmap.height)
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) throw new Error('no 2d context')
    context.drawImage(request.bitmap, 0, 0)
    request.bitmap.close()
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height)
    const analysis = await analyzePhoto(cv, nets, {
      rgba: pixels.data,
      width: pixels.width,
      height: pixels.height,
      sourceWidth: request.sourceWidth,
      sourceHeight: request.sourceHeight,
    })
    pixels.data.fill(0)
    self.postMessage({ id: request.id, analysis })
  } catch (error) {
    request.bitmap.close?.()
    self.postMessage({ id: request.id, error: error instanceof Error ? error.message : String(error) })
  }
}
