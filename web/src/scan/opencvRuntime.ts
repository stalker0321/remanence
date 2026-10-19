/**
 * T312 — same-origin loader/type bridge for the pinned custom OpenCV.js WASM
 * build (official OpenCV 4.12.0 + features2d SIFT + core dct whitelist).
 *
 * The vendored assets live under the fixed same-origin path `/opencv/`
 * (`opencv.js` + `opencv_js.wasm`). There are no CDN fallbacks and the runtime
 * is never fetched from a third-party origin. Loading is cached; failures are
 * typed and bounded (the extractor maps them to `unavailable`).
 */

import type { CvKeyPoint, CvMat, CvRuntime, CvSift, SiftParameters } from './opencvPort'

/** Fixed same-origin vendor directory. */
export const OPENCV_VENDOR_BASE = '/opencv/'
const OPENCV_SCRIPT_URL = `${OPENCV_VENDOR_BASE}opencv.js`

interface RawSize {
  readonly width: number
  readonly height: number
}
interface RawPoint {
  readonly x: number
  readonly y: number
}
interface RawKeyPoint {
  readonly pt: RawPoint
  readonly size: number
  readonly angle: number
  readonly response: number
  readonly octave: number
  /** Present on some builds; `KeyPointVector.get` may return a plain value object. */
  delete?(): void
}
interface RawKeyPointVector {
  size(): number
  get(index: number): RawKeyPoint
  push_back(keypoint: RawKeyPoint): void
  delete(): void
}
interface RawMat extends CvMat {
  convertTo(dst: CvMat, type: number): void
}
interface RawSift {
  detect(image: CvMat, keypoints: RawKeyPointVector, mask: CvMat): void
  compute(image: CvMat, keypoints: RawKeyPointVector, descriptors: CvMat): void
  delete(): void
}
interface RawCv {
  Mat: new (rows?: number, cols?: number, type?: number) => RawMat
  Size: new (width: number, height: number) => RawSize
  KeyPointVector: new () => RawKeyPointVector
  /**
   * embindgen maps the `SIFT::create` factories to JS constructors, so the
   * detector is built with `new cv.SIFT(...)` (like `new cv.ORB()`), not a
   * static `SIFT.create`.
   */
  SIFT: new (nfeatures?: number, octaveLayers?: number, contrastThreshold?: number, edgeThreshold?: number, sigma?: number, enablePreciseUpscale?: boolean) => RawSift
  CV_8UC1: number
  CV_32F: number
  INTER_AREA: number
  resize: (src: CvMat, dst: CvMat, size: RawSize, fx: number, fy: number, interpolation: number) => void
  dct: (src: CvMat, dst: CvMat, flags?: number) => void
  onRuntimeInitialized?: () => void
  abort?: (reason?: unknown) => void
}

declare global {
  interface Window {
    /** The MODULARIZE wrapper assigns the module factory result (a Promise of the runtime). */
    cv?: RawCv | Promise<RawCv>
  }
}

let runtimePromise: Promise<CvRuntime> | null = null

function unavailable(message: string): Error {
  return new Error(`opencv-unavailable: ${message}`)
}

function loadScript(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined') {
      reject(unavailable('no document'))
      return
    }
    const existing = document.querySelector<HTMLScriptElement>(`script[data-opencv="${url}"]`)
    if (existing) {
      existing.addEventListener('load', () => resolve())
      existing.addEventListener('error', () => reject(unavailable('script failed')))
      return
    }
    const script = document.createElement('script')
    script.src = url
    script.async = true
    script.dataset.opencv = url
    script.addEventListener('load', () => resolve())
    script.addEventListener('error', () => reject(unavailable('script failed')))
    document.head.appendChild(script)
  })
}

function waitForRuntime(cv: RawCv): Promise<void> {
  return new Promise((resolve, reject) => {
    if (typeof cv.Mat === 'function') {
      resolve()
      return
    }
    const prior = cv.onRuntimeInitialized
    cv.onRuntimeInitialized = () => {
      prior?.()
      if (typeof cv.Mat === 'function') resolve()
      else reject(unavailable('runtime missing Mat'))
    }
    const originalAbort = cv.abort
    cv.abort = (reason?: unknown) => {
      originalAbort?.(reason)
      reject(unavailable('runtime aborted'))
    }
  })
}

/** Wipe the exposed view of a Mat by its actual type, then release it. */
function wipeAndDelete(cv: RawCv, mat: RawMat): void {
  try {
    const type = mat.type()
    if (type === cv.CV_8UC1) mat.data.fill(0)
    else if (type === cv.CV_32F) mat.data32F.fill(0)
  } catch {
    /* wiping is best-effort; the release below must still run */
  }
  mat.delete()
}

/**
 * Builds the typed runtime over a raw `cv` module. Exported so the bridge
 * ownership/cleanup contract can be exercised with a bounded fake module; the
 * production path always uses the real loaded module.
 */
export function wrapOpenCvRuntime(cv: RawCv): CvRuntime {
  return {
    CV_8UC1: cv.CV_8UC1,
    CV_32F: cv.CV_32F,
    INTER_AREA: cv.INTER_AREA,
    matFromGray: (gray, width, height) => {
      const mat = new cv.Mat(height, width, cv.CV_8UC1)
      try {
        mat.data.set(gray)
        return mat
      } catch (error) {
        wipeAndDelete(cv, mat)
        throw error
      }
    },
    createEmptyMat: () => new cv.Mat(),
    createSift: (parameters: SiftParameters): CvSift => {
      const inner = new cv.SIFT(
        parameters.nfeatures,
        parameters.octaveLayers,
        parameters.contrastThreshold,
        parameters.edgeThreshold,
        parameters.sigma,
      )
      return {
        detect: (gray) => {
          // KeyPoint is an embind value_object, so `get` yields a plain snapshot
          // that stays valid after the vector itself is released. `vector` and
          // `mask` are owned immediately so a throwing allocation/op cannot leak.
          const vector = new cv.KeyPointVector()
          let mask: RawMat | null = null
          try {
            mask = new cv.Mat()
            inner.detect(gray, vector, mask)
            const points: CvKeyPoint[] = []
            for (let index = 0; index < vector.size(); index++) points.push(vector.get(index) as unknown as CvKeyPoint)
            return points
          } finally {
            if (mask) mask.delete()
            vector.delete()
          }
        },
        compute: (gray, points) => {
          const vector = new cv.KeyPointVector()
          let descriptors: RawMat | null = null
          try {
            for (const point of points) vector.push_back(point as unknown as RawKeyPoint)
            descriptors = new cv.Mat()
            inner.compute(gray, vector, descriptors)
            const keypoints: CvKeyPoint[] = []
            for (let index = 0; index < vector.size(); index++) keypoints.push(vector.get(index) as unknown as CvKeyPoint)
            return { descriptors, keypoints }
          } catch (error) {
            if (descriptors) wipeAndDelete(cv, descriptors)
            throw error
          } finally {
            vector.delete()
          }
        },
        delete: () => inner.delete(),
      }
    },
    resizeArea: (src, size) => {
      const dst = new cv.Mat()
      try {
        cv.resize(src, dst, new cv.Size(size, size), 0, 0, cv.INTER_AREA)
        return dst
      } catch (error) {
        wipeAndDelete(cv, dst)
        throw error
      }
    },
    convertToFloat: (src) => {
      const dst = new cv.Mat()
      try {
        ;(src as RawMat).convertTo(dst, cv.CV_32F)
        return dst
      } catch (error) {
        wipeAndDelete(cv, dst)
        throw error
      }
    },
    dct: (src) => {
      const dst = new cv.Mat()
      try {
        cv.dct(src, dst)
        return dst
      } catch (error) {
        wipeAndDelete(cv, dst)
        throw error
      }
    },
  }
}

/** Loads (once) and returns the pinned OpenCV runtime. */
export async function loadOpenCvRuntime(signal?: AbortSignal): Promise<CvRuntime> {
  if (signal?.aborted) throw unavailable('aborted')
  if (!runtimePromise) {
    runtimePromise = (async () => {
      await loadScript(OPENCV_SCRIPT_URL)
      const candidate = window.cv
      if (!candidate) throw unavailable('no cv global')
      const cv = await Promise.resolve(candidate)
      if (typeof cv.Mat !== 'function') await waitForRuntime(cv)
      if (typeof cv.SIFT !== 'function' || typeof cv.dct !== 'function') throw unavailable('SIFT/dct not exported')
      return wrapOpenCvRuntime(cv)
    })().catch((error) => {
      runtimePromise = null
      throw error instanceof Error ? error : unavailable(String(error))
    })
  }
  const runtime = await runtimePromise
  if (signal?.aborted) throw unavailable('aborted')
  return runtime
}
