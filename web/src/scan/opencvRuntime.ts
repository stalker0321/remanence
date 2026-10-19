/**
 * T312 — same-origin loader/type bridge for the pinned custom OpenCV.js WASM
 * build (official OpenCV 4.12.0 + features2d SIFT + core dct whitelist).
 *
 * The vendored assets live under the fixed same-origin path `/opencv/`
 * (`opencv.js` + `opencv_js.wasm`). There are no CDN fallbacks and the runtime
 * is never fetched from a third-party origin. Loading is cached; failures are
 * typed and bounded (the extractor maps them to `unavailable`).
 */

import type { CvKeyPoint, CvMat, CvSift, SiftParameters } from './opencvPort'
import type { CvContourRuntime, CvDetectedQuad, CvWarpedRgba } from './opencvContour'
import type { Point2D } from './cornerGeometry'

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
interface RawMatVector {
  size(): number
  get(index: number): RawMat
  delete(): void
}
/** Approx-poly output rows are 32-bit signed (x, y) pairs. */
interface RawApproxMat extends RawMat {
  data32S: Int32Array
}
interface RawSift {
  detect(image: CvMat, keypoints: RawKeyPointVector, mask: CvMat): void
  compute(image: CvMat, keypoints: RawKeyPointVector, descriptors: CvMat): void
  delete(): void
}
interface RawCv {
  Mat: new (rows?: number, cols?: number, type?: number) => RawMat
  MatVector: new () => RawMatVector
  Size: new (width: number, height: number) => RawSize
  KeyPointVector: new () => RawKeyPointVector
  matFromArray: (rows: number, cols: number, type: number, array: number[]) => RawMat
  /**
   * embindgen maps the `SIFT::create` factories to JS constructors, so the
   * detector is built with `new cv.SIFT(...)` (like `new cv.ORB()`), not a
   * static `SIFT.create`.
   */
  SIFT: new (nfeatures?: number, octaveLayers?: number, contrastThreshold?: number, edgeThreshold?: number, sigma?: number, enablePreciseUpscale?: boolean) => RawSift
  CV_8UC1: number
  CV_8UC4: number
  CV_32F: number
  CV_32SC2: number
  CV_32FC2: number
  COLOR_RGBA2GRAY: number
  RETR_EXTERNAL: number
  CHAIN_APPROX_SIMPLE: number
  MORPH_RECT: number
  MORPH_CLOSE: number
  INTER_AREA: number
  resize: (src: CvMat, dst: CvMat, size: RawSize, fx: number, fy: number, interpolation: number) => void
  dct: (src: CvMat, dst: CvMat, flags?: number) => void
  cvtColor: (src: CvMat, dst: CvMat, code: number) => void
  GaussianBlur: (src: CvMat, dst: CvMat, size: RawSize, sigma: number) => void
  mean: (src: CvMat) => { [index: number]: number }
  Canny: (src: CvMat, dst: CvMat, low: number, high: number) => void
  getStructuringElement: (shape: number, size: RawSize) => RawMat
  morphologyEx: (src: CvMat, dst: CvMat, op: number, kernel: CvMat) => void
  findContours: (src: CvMat, contours: RawMatVector, hierarchy: CvMat, mode: number, method: number) => void
  approxPolyDP: (curve: CvMat, approx: CvMat, epsilon: number, closed: boolean) => void
  isContourConvex: (contour: CvMat) => boolean
  contourArea: (contour: CvMat) => number
  arcLength: (curve: CvMat, closed: boolean) => number
  getPerspectiveTransform: (src: CvMat, dst: CvMat) => RawMat
  warpPerspective: (src: CvMat, dst: CvMat, transform: CvMat, size: RawSize) => void
  onRuntimeInitialized?: () => void
  abort?: (reason?: unknown) => void
}

declare global {
  interface Window {
    /** The MODULARIZE wrapper assigns the module factory result (a Promise of the runtime). */
    cv?: RawCv | Promise<RawCv>
  }
}

let runtimePromise: Promise<CvContourRuntime> | null = null

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
    else mat.data.fill(0)
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
export function wrapOpenCvRuntime(cv: RawCv): CvContourRuntime {
  /**
   * Releases every tracked handle (LIFO). When `strict`, a release failure
   * throws after the remaining handles were still attempted; otherwise release
   * failures are best-effort so the caller's original error still wins.
   */
  const releaseTracked = (tracked: RawMat[], strict: boolean): void => {
    let first: unknown = null
    while (tracked.length > 0) {
      const mat = tracked.pop()!
      try {
        wipeAndDelete(cv, mat)
      } catch (error) {
        if (first === null) first = error
      }
    }
    if (strict && first !== null) throw first
  }
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
    matFromRgba: (bytes, width, height) => {
      if (!(bytes instanceof Uint8Array) || bytes.length !== width * height * 4) {
        throw unavailable('rgba shape mismatch')
      }
      const mat = new cv.Mat(height, width, cv.CV_8UC4)
      try {
        mat.data.set(bytes)
        return mat
      } catch (error) {
        wipeAndDelete(cv, mat)
        throw error
      }
    },
    detectQuadCandidates: (rgba, frameWidth, frameHeight) => {
      if (!(frameWidth > 0) || !(frameHeight > 0)) throw unavailable('frame shape mismatch')
      const owned: RawMat[] = []
      const track = (mat: RawMat): RawMat => {
        owned.push(mat)
        return mat
      }
      // The contour vector is not a Mat; it is allocated only once the
        // pipeline reaches contour enumeration, and released exactly once
        // below after every contour Mat borrowed from it has been released.
        let contours: RawMatVector | null = null
        const out: CvDetectedQuad[] = []
      try {
        const gray = track(new cv.Mat())
        cv.cvtColor(rgba, gray, cv.COLOR_RGBA2GRAY)
        const blur = track(new cv.Mat())
        cv.GaussianBlur(gray, blur, new cv.Size(5, 5), 0)
        const meanValue = Number(cv.mean(blur)[0])
        if (!Number.isFinite(meanValue)) throw unavailable('mean failed')
        const edges = track(new cv.Mat())
        cv.Canny(blur, edges, 0.66 * meanValue, 1.33 * meanValue)
        const kernel = track(cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(7, 7)))
        cv.morphologyEx(edges, edges, cv.MORPH_CLOSE, kernel)
        contours = new cv.MatVector()
        const hierarchy = track(new cv.Mat())
        cv.findContours(edges, contours, hierarchy, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE)
        const frameArea = frameWidth * frameHeight
        for (let i = 0; i < contours.size(); i++) {
          const contour = contours.get(i)
          try {
            const perimeter = cv.arcLength(contour, true)
            if (!Number.isFinite(perimeter) || perimeter <= 0) continue
            const approx = new cv.Mat()
            try {
              cv.approxPolyDP(contour, approx, 0.02 * perimeter, true)
              if (approx.rows !== 4 || approx.cols !== 1) continue
              if (!cv.isContourConvex(approx)) continue
              const quadArea = Math.abs(cv.contourArea(approx))
              if (!(quadArea > 0)) continue
              if (quadArea / frameArea < 0.01) continue
              const contourArea = Math.abs(cv.contourArea(contour))
              if (approx.type() !== cv.CV_32SC2) continue
              const raw = (approx as unknown as RawApproxMat).data32S
              if (!(raw instanceof Int32Array) || raw.length < 8) continue
              const points = [
                { x: raw[0]!, y: raw[1]! },
                { x: raw[2]!, y: raw[3]! },
                { x: raw[4]!, y: raw[5]! },
                { x: raw[6]!, y: raw[7]! },
              ] as [Point2D, Point2D, Point2D, Point2D]
              out.push({ points, quadArea, contourArea })
            } finally {
              wipeAndDelete(cv, approx)
            }
          } finally {
            wipeAndDelete(cv, contour)
          }
        }
        releaseTracked(owned, true)
        return out
      } catch (error) {
        releaseTracked(owned, false)
        throw error
      } finally {
        if (contours !== null) contours.delete()
      }
    },
    warpRgbaQuad: (rgba, srcQuad, dstQuad, dstWidth, dstHeight) => {
      if (srcQuad.length !== 4 || dstQuad.length !== 4) throw unavailable('quad shape mismatch')
      if (!Number.isInteger(dstWidth) || !Number.isInteger(dstHeight) || dstWidth < 1 || dstHeight < 1) {
        throw unavailable('warp shape mismatch')
      }
      const flat = (quad: readonly Point2D[]): number[] => [
        quad[0]!.x, quad[0]!.y, quad[1]!.x, quad[1]!.y, quad[2]!.x, quad[2]!.y, quad[3]!.x, quad[3]!.y,
      ]
      const owned: RawMat[] = []
      try {
        const srcPts = cv.matFromArray(4, 1, cv.CV_32FC2, flat(srcQuad))
        owned.push(srcPts)
        const dstPts = cv.matFromArray(4, 1, cv.CV_32FC2, flat(dstQuad))
        owned.push(dstPts)
        const transform = cv.getPerspectiveTransform(srcPts, dstPts)
        owned.push(transform)
        const dst = new cv.Mat()
        owned.push(dst)
        cv.warpPerspective(rgba, dst, transform, new cv.Size(dstWidth, dstHeight))
        if (dst.rows !== dstHeight || dst.cols !== dstWidth) throw unavailable('warp shape mismatch')
        const bytes = dst.data.slice()
        if (bytes.length !== dstWidth * dstHeight * 4) throw unavailable('warp shape mismatch')
        const result: CvWarpedRgba = { bytes, width: dstWidth, height: dstHeight }
        releaseTracked(owned, true)
        return result
      } catch (error) {
        releaseTracked(owned, false)
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

/** Loads (once) and returns the pinned OpenCV runtime (with contour/warp ops). */
export async function loadOpenCvRuntime(signal?: AbortSignal): Promise<CvContourRuntime> {
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
