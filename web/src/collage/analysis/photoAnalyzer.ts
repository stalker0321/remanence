import type { CollageAnalysis } from './cropSolver'
import type { AnalysisRequest } from './analysisWorker'

/**
 * Main-thread side of the photo analysis: one shared worker, one analysis
 * per photo (keyed by its content hash and size), and a typed failure the
 * composer turns into the centred-crop fallback.
 */

/** Android decodes for analysis at most 3072 px on the long edge. */
const ANALYSIS_MAX_LONG_EDGE = 3072

export interface AnalysisPhoto {
  contentHash: string
  width: number
  height: number
  image: CanvasImageSource
}

let worker: Worker | null = null
let nextId = 1
const pending = new Map<number, { resolve: (analysis: CollageAnalysis) => void; reject: (error: Error) => void }>()
const cache = new Map<string, Promise<CollageAnalysis>>()

function shared(): Worker {
  if (worker !== null) return worker
  const created = new Worker(new URL('./analysisWorker.ts', import.meta.url), { type: 'module' })
  created.onmessage = (event: MessageEvent<{ id: number; analysis?: CollageAnalysis; error?: string }>) => {
    const entry = pending.get(event.data.id)
    if (!entry) return
    pending.delete(event.data.id)
    if (event.data.analysis) entry.resolve(event.data.analysis)
    else entry.reject(new Error(event.data.error ?? 'analysis failed'))
  }
  created.onerror = (event) => {
    console.warn('photo analysis worker failed:', event.message)
    for (const entry of pending.values()) entry.reject(new Error('analysis worker failed'))
    pending.clear()
    cache.clear()
    created.terminate()
    if (worker === created) worker = null
  }
  worker = created
  return created
}

export function analyzePhotoInWorker(photo: AnalysisPhoto): Promise<CollageAnalysis> {
  const key = `${photo.contentHash}|${photo.width}|${photo.height}`
  const cached = cache.get(key)
  if (cached) return cached
  const job = (async () => {
    const scale = Math.min(1, ANALYSIS_MAX_LONG_EDGE / Math.max(photo.width, photo.height))
    const bitmap = await createImageBitmap(photo.image as ImageBitmapSource, scale < 1
      ? { resizeWidth: Math.round(photo.width * scale), resizeHeight: Math.round(photo.height * scale), resizeQuality: 'high' }
      : {})
    const id = nextId++
    const result = new Promise<CollageAnalysis>((resolve, reject) => pending.set(id, { resolve, reject }))
    const request: AnalysisRequest = { id, base: import.meta.env.BASE_URL, bitmap, sourceWidth: photo.width, sourceHeight: photo.height }
    shared().postMessage(request, [bitmap])
    return result
  })()
  job.catch(() => cache.delete(key))
  cache.set(key, job)
  return job
}

/** Every photo's analysis by content id, or null when any one fails. */
export async function analyzePhotos(photos: Array<AnalysisPhoto & { contentId: string }>): Promise<Map<string, CollageAnalysis> | null> {
  try {
    const results = await Promise.all(photos.map((photo) => analyzePhotoInWorker(photo)))
    return new Map(photos.map((photo, index) => [photo.contentId, results[index]!]))
  } catch (error) {
    // The layouts fall back to the centred crop; say why without any photo data.
    console.warn('photo analysis unavailable:', error instanceof Error ? error.message : String(error))
    return null
  }
}
