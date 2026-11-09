import type { InferenceSession, Tensor } from 'onnxruntime-web'
import type { CollageAnalysis, NormBox } from './cropSolver'
import { aggregateFields, gaussianField } from './fieldAggregator'
import { decodeNanoDet, letterbox, NANODET_INPUT, type NanoDetHead } from './nanodet'
import { decodeYuNet, mergeYuNetPasses, normalizeYuNetPass, paddedSize, YUNET_CONFIG, yunetSize } from './yunet'

/**
 * The site's photo analysis, the same stages Android runs in
 * `AndroidCollageCropSource`: the `import-field-3.3` pixel maps
 * (`OpenCvFieldStage`), NanoDet objects, YuNet faces (two passes), folded by
 * the field aggregator. Pixel work runs on the site's own OpenCV 4.12 build,
 * the version Android uses; the two models run in onnxruntime-web, which is
 * far faster in the browser than OpenCV.js DNN, on the same ONNX files.
 *
 * `cv` is the raw OpenCV.js module; its embind surface is untyped here.
 */

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Cv = any

export interface AnalysisNets {
  yunet: InferenceSession
  nanodet: InferenceSession
  /** Builds an input tensor; injected so this module stays free of the runtime import. */
  tensor: (data: Float32Array, dims: number[]) => Tensor
}

export interface AnalysisPixels {
  /** Upright RGBA, row-major. */
  rgba: Uint8ClampedArray
  width: number
  height: number
  /** The photo's verified dimensions; the analysis is normalized, so only these need the original. */
  sourceWidth: number
  sourceHeight: number
}

export async function analyzePhoto(cv: Cv, nets: AnalysisNets, pixels: AnalysisPixels): Promise<CollageAnalysis> {
  const owned: Array<{ delete(): void }> = []
  const own = <T extends { delete(): void }>(mat: T): T => {
    owned.push(mat)
    return mat
  }
  try {
    const rgba = own(new cv.Mat(pixels.height, pixels.width, cv.CV_8UC4))
    rgba.data.set(pixels.rgba)
    const fields = fieldStage(cv, own, rgba)
    const objects = await detectObjects(cv, own, nets, rgba, pixels.width, pixels.height)
    const faces = await detectFaces(cv, own, nets, rgba, pixels.width, pixels.height)
    return aggregateFields({
      sourceWidth: pixels.sourceWidth,
      sourceHeight: pixels.sourceHeight,
      faces,
      objects,
      saliency: fields.saliency,
      edgeEnergy: fields.edgeEnergy,
      contrast: fields.contrast,
      structureField: fields.tips,
      tipBoxes: fields.tipBoxes,
    })
  } finally {
    for (const mat of owned.reverse()) {
      try {
        mat.delete()
      } catch {
        /* every handle is released once; a failed release must not hide the result */
      }
    }
  }
}

type Own = <T extends { delete(): void }>(mat: T) => T

/** Android `OpenCvFieldStage.analyze` (`legacy_features.py:50-73`). */
function fieldStage(cv: Cv, own: Own, rgba: Cv) {
  const rgb = own(new cv.Mat())
  cv.cvtColor(rgba, rgb, cv.COLOR_RGBA2RGB)
  const small96 = own(new cv.Mat())
  cv.resize(rgb, small96, new cv.Size(96, 96), 0, 0, cv.INTER_LINEAR)
  const small96f = own(new cv.Mat())
  small96.convertTo(small96f, cv.CV_32FC3, 1 / 255)
  const grey = own(new cv.Mat())
  cv.cvtColor(small96f, grey, cv.COLOR_RGB2GRAY)
  const saliency = spectralSaliency48(cv, own, grey)
  const contrast = localContrast48(cv, own, small96f)
  const edgeEnergy = edgeEnergy48(cv, own, grey)
  const { tips, tipBoxes } = contourTips(cv, own, rgb)
  return { saliency, contrast, edgeEnergy, tips, tipBoxes }
}

function filled(cv: Cv, own: Own, like: Cv, value: number): Cv {
  return own(new cv.Mat(like.rows, like.cols, like.type(), new cv.Scalar(value, value, value, value)))
}

function split2(cv: Cv, own: Own, mat: Cv): [Cv, Cv] {
  const parts = own(new cv.MatVector())
  cv.split(mat, parts)
  return [own(parts.get(0)), own(parts.get(1))]
}

function spectralSaliency48(cv: Cv, own: Own, grey: Cv): Float64Array {
  const freq = own(new cv.Mat())
  cv.dft(grey, freq, cv.DFT_COMPLEX_OUTPUT, 0)
  const [re, im] = split2(cv, own, freq)
  const mag = own(new cv.Mat())
  cv.magnitude(re, im, mag)
  cv.add(mag, filled(cv, own, mag, 1e-8), mag)
  const logMag = own(new cv.Mat())
  cv.log(mag, logMag)
  const logSmooth = own(new cv.Mat())
  cv.blur(logMag, logSmooth, new cv.Size(3, 3))
  const residual = own(new cv.Mat())
  cv.subtract(logMag, logSmooth, residual)
  const expResidual = own(new cv.Mat())
  cv.exp(residual, expResidual)
  // `Core.phase` is not bound; `cartToPolar` reports the same [0, 2π) angle.
  const unusedMag = own(new cv.Mat())
  const angle = own(new cv.Mat())
  cv.cartToPolar(re, im, unusedMag, angle, false)
  const recX = own(new cv.Mat())
  const recY = own(new cv.Mat())
  cv.polarToCart(expResidual, angle, recX, recY, false)
  const parts = own(new cv.MatVector())
  parts.push_back(recX)
  parts.push_back(recY)
  const complex = own(new cv.Mat())
  cv.merge(parts, complex)
  // `Core.idft(src, dst, DFT_SCALE)` is `dft` with DFT_INVERSE added.
  const spatial = own(new cv.Mat())
  cv.dft(complex, spatial, cv.DFT_INVERSE | cv.DFT_SCALE, 0)
  const [sre, sim] = split2(cv, own, spatial)
  const salMag = own(new cv.Mat())
  cv.magnitude(sre, sim, salMag)
  const salPow = own(new cv.Mat())
  cv.multiply(salMag, salMag, salPow)
  const salBlur = own(new cv.Mat())
  cv.GaussianBlur(salPow, salBlur, new cv.Size(7, 7), 0)
  return area48(cv, own, normalize96(cv, own, salBlur))
}

function localContrast48(cv: Cv, own: Own, small96f: Cv): Float64Array {
  const blur5 = own(new cv.Mat())
  cv.GaussianBlur(small96f, blur5, new cv.Size(0, 0), 5)
  const diff = own(new cv.Mat())
  cv.subtract(small96f, blur5, diff)
  const channels = own(new cv.MatVector())
  cv.split(diff, channels)
  const c0 = own(channels.get(0))
  const c1 = own(channels.get(1))
  const c2 = own(channels.get(2))
  const acc = own(new cv.Mat())
  cv.multiply(c0, c0, acc)
  const tmp = own(new cv.Mat())
  cv.multiply(c1, c1, tmp)
  cv.add(acc, tmp, acc)
  cv.multiply(c2, c2, tmp)
  cv.add(acc, tmp, acc)
  const norm = own(new cv.Mat())
  cv.sqrt(acc, norm)
  return area48(cv, own, normalize96(cv, own, norm))
}

function edgeEnergy48(cv: Cv, own: Own, grey: Cv): Float64Array {
  const gx = own(new cv.Mat())
  cv.Sobel(grey, gx, cv.CV_32F, 1, 0, 3, 1, 0)
  const gy = own(new cv.Mat())
  cv.Sobel(grey, gy, cv.CV_32F, 0, 1, 3, 1, 0)
  const mag = own(new cv.Mat())
  cv.magnitude(gx, gy, mag)
  return area48(cv, own, normalize96(cv, own, mag))
}

/** Lines 60-70: Canny contour endpoints, the merged 48×48 tips map and the contour-order tip boxes. */
function contourTips(cv: Cv, own: Own, rgb: Cv): { tips: Float64Array; tipBoxes: NormBox[] } {
  const structural = own(new cv.Mat())
  cv.resize(rgb, structural, new cv.Size(192, 192), 0, 0, cv.INTER_LINEAR)
  const structGrey = own(new cv.Mat())
  cv.cvtColor(structural, structGrey, cv.COLOR_RGB2GRAY)
  const canny = own(new cv.Mat())
  cv.Canny(structGrey, canny, 20, 60)
  const contours = own(new cv.MatVector())
  const hierarchy = own(new cv.Mat())
  cv.findContours(canny, contours, hierarchy, cv.RETR_LIST, cv.CHAIN_APPROX_SIMPLE)
  const tips = new Float64Array(48 * 48)
  const tipBoxes: NormBox[] = []
  for (let i = 0; i < contours.size(); i++) {
    const contour = own(contours.get(i))
    const rect = cv.boundingRect(contour)
    if (!(rect.height > 12 && rect.width > 2 && rect.height > rect.width * 1.2 && rect.y > 4 && rect.y < 140)) continue
    const points: Int32Array = contour.data32S
    let topX = 0
    let topY = Number.MAX_SAFE_INTEGER
    for (let p = 0; p + 1 < points.length; p += 2) {
      if (points[p + 1]! < topY) {
        topY = points[p + 1]!
        topX = points[p]!
      }
    }
    const bx = Math.max(0, topX / 192 - 0.015)
    const by = Math.max(0, topY / 192 - 0.015)
    const box = { x: bx, y: by, width: Math.min(0.03, 1 - bx), height: Math.min(0.04, 1 - by) }
    const g = gaussianField(box, 0.4, 48)
    for (let c = 0; c < g.length; c++) if (g[c]! > tips[c]!) tips[c] = g[c]!
    if (!tipBoxes.some((kept) => Math.abs(kept.x - box.x) < 0.025 && Math.abs(kept.y - box.y) < 0.025)) tipBoxes.push(box)
  }
  return { tips, tipBoxes }
}

/** The frozen `normal()` on the 96×96 float map: divide by the 0.98 linear quantile (floor 1e-6), clip to [0, 1]. */
function normalize96(cv: Cv, own: Own, map96: Cv): Cv {
  const values = Float64Array.from(map96.data32F as Float32Array)
  const sorted = values.slice().sort()
  const divisor = Math.max(quantileLinear(sorted, 0.98), 1e-6)
  const out = own(new cv.Mat(96, 96, cv.CV_32FC1))
  const target: Float32Array = out.data32F
  for (let i = 0; i < values.length; i++) target[i] = Math.min(1, Math.max(0, values[i]! / divisor))
  return out
}

function quantileLinear(sorted: Float64Array, q: number): number {
  if (sorted.length === 1) return sorted[0]!
  const pos = q * (sorted.length - 1)
  const lower = Math.trunc(pos)
  const fraction = pos - lower
  if (fraction === 0) return sorted[lower]!
  return sorted[lower]! * (1 - fraction) + sorted[lower + 1]! * fraction
}

function area48(cv: Cv, own: Own, map96: Cv): Float64Array {
  const out = own(new cv.Mat())
  cv.resize(map96, out, new cv.Size(48, 48), 0, 0, cv.INTER_AREA)
  return Float64Array.from(out.data32F as Float32Array)
}

/** Android `OpenCvNanoDetRuntime.forward` + `NanoDetDecoder.decode`. */
async function detectObjects(cv: Cv, own: Own, nets: AnalysisNets, rgba: Cv, width: number, height: number) {
  const bgr = own(new cv.Mat())
  cv.cvtColor(rgba, bgr, cv.COLOR_RGBA2BGR)
  const box = letterbox(width, height)
  const canvas = own(new cv.Mat(NANODET_INPUT, NANODET_INPUT, cv.CV_8UC3, new cv.Scalar(114, 114, 114, 0)))
  const resized = own(new cv.Mat())
  cv.resize(bgr, resized, new cv.Size(box.rw, box.rh), 0, 0, cv.INTER_LINEAR)
  const roi = own(canvas.roi(new cv.Rect(box.padX, box.padY, box.rw, box.rh)))
  resized.copyTo(roi)
  const normalized = own(new cv.Mat())
  canvas.convertTo(normalized, cv.CV_32FC3)
  const mean = own(new cv.Mat(NANODET_INPUT, NANODET_INPUT, cv.CV_32FC3, new cv.Scalar(103.53, 116.28, 123.675, 0)))
  const std = own(new cv.Mat(NANODET_INPUT, NANODET_INPUT, cv.CV_32FC3, new cv.Scalar(57.375, 57.12, 58.395, 1)))
  cv.subtract(normalized, mean, normalized)
  cv.divide(normalized, std, normalized)
  const blob = own(cv.blobFromImage(normalized, 1, new cv.Size(NANODET_INPUT, NANODET_INPUT), new cv.Scalar(0, 0, 0, 0), false, false))
  const outputs = await run(nets, nets.nanodet, blob, [1, 3, NANODET_INPUT, NANODET_INPUT])
  const heads: NanoDetHead[] = Object.entries(outputs).map(([name, tensor]) => ({ name, shape: tensor.dims.slice(), values: tensor.data as Float32Array }))
  const objects = decodeNanoDet(heads, box)
  if (objects === null) throw new Error('malformed NanoDet heads')
  return objects
}

/** One model run on an OpenCV NCHW blob. */
async function run(nets: AnalysisNets, session: InferenceSession, blob: Cv, dims: number[]): Promise<Record<string, Tensor>> {
  const input = nets.tensor(Float32Array.from(blob.data32F as Float32Array), dims)
  return session.run({ [session.inputNames[0]!]: input })
}

/** Android `OpenCvYuNetRuntime` at both long edges, decoded like `FaceDetectorYN`, then merged. */
async function detectFaces(cv: Cv, own: Own, nets: AnalysisNets, rgba: Cv, width: number, height: number) {
  const bgr = own(new cv.Mat())
  cv.cvtColor(rgba, bgr, cv.COLOR_RGBA2BGR)
  const passes = []
  for (const longEdge of YUNET_CONFIG.longEdges) {
    const [rw, rh] = yunetSize(width, height, longEdge)
    const resized = own(new cv.Mat())
    cv.resize(bgr, resized, new cv.Size(rw, rh), 0, 0, cv.INTER_LINEAR)
    const [padW, padH] = paddedSize(rw, rh)
    const padded = own(new cv.Mat())
    cv.copyMakeBorder(resized, padded, 0, padH - rh, 0, padW - rw, cv.BORDER_CONSTANT, new cv.Scalar(0, 0, 0, 0))
    const blob = own(cv.blobFromImage(padded, 1, new cv.Size(padW, padH), new cv.Scalar(0, 0, 0, 0), false, false))
    const outputs = await run(nets, nets.yunet, blob, [1, 3, padH, padW])
    const pick = (prefix: string) => [8, 16, 32].map((stride) => {
      const tensor = outputs[`${prefix}_${stride}`]
      if (!tensor) throw new Error(`YuNet output ${prefix}_${stride} missing`)
      return tensor.data as Float32Array
    })
    const rows = decodeYuNet({ cls: pick('cls'), obj: pick('obj'), bbox: pick('bbox'), kps: pick('kps') }, padW, padH)
    passes.push(normalizeYuNetPass(rows, rw, rh))
  }
  return mergeYuNetPasses(passes)
}
