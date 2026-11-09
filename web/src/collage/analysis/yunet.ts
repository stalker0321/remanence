import type { AnalysisFace } from './cropSolver'
import { halfEvenRound } from './nanodet'

/**
 * YuNet face decoding. Android runs OpenCV's `FaceDetectorYN`; OpenCV.js
 * does not bind it, so this is its OpenCV 4.12 `postProcess` (score
 * `sqrt(cls*obj)`, box and landmark decode per stride, `NMSBoxes` on integer
 * rects) followed by Android's `YuNetFacePostProcessor` (normalize, drop
 * degenerate boxes, merge the two passes).
 */

export const YUNET_CONFIG = { longEdges: [640, 960], scoreThreshold: 0.65, nmsThreshold: 0.3, topK: 5000, dedupIou: 0.25 } as const
export const YUNET_STRIDES = [8, 16, 32] as const
const PAD_DIVISOR = 32

/** Python half-even resize: `round(dim * longEdge / max(w, h))`. */
export function yunetSize(sourceWidth: number, sourceHeight: number, longEdge: number): [number, number] {
  const k = longEdge / Math.max(sourceWidth, sourceHeight)
  return [halfEvenRound(sourceWidth * k), halfEvenRound(sourceHeight * k)]
}

/** `FaceDetectorYN` pads the input to a multiple of 32. */
export function paddedSize(width: number, height: number): [number, number] {
  return [Math.trunc((width - 1) / PAD_DIVISOR) * PAD_DIVISOR + PAD_DIVISOR, Math.trunc((height - 1) / PAD_DIVISOR) * PAD_DIVISOR + PAD_DIVISOR]
}

export interface YuNetOutputs {
  cls: Float32Array[]
  obj: Float32Array[]
  bbox: Float32Array[]
  kps: Float32Array[]
}

/** One pass's rows in the resized frame: `x, y, w, h`, ten landmark values, score. */
export function decodeYuNet(outputs: YuNetOutputs, padW: number, padH: number): Float32Array[] {
  const faces: Float32Array[] = []
  YUNET_STRIDES.forEach((stride, i) => {
    const cols = Math.trunc(padW / stride)
    const rows = Math.trunc(padH / stride)
    const cls = outputs.cls[i]!
    const obj = outputs.obj[i]!
    const bbox = outputs.bbox[i]!
    const kps = outputs.kps[i]!
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const idx = r * cols + c
        const clsScore = Math.min(1, Math.max(0, cls[idx]!))
        const objScore = Math.min(1, Math.max(0, obj[idx]!))
        const score = Math.fround(Math.sqrt(clsScore * objScore))
        if (score < YUNET_CONFIG.scoreThreshold) continue
        const face = new Float32Array(15)
        const cx = (c + bbox[idx * 4]!) * stride
        const cy = (r + bbox[idx * 4 + 1]!) * stride
        const w = Math.exp(bbox[idx * 4 + 2]!) * stride
        const h = Math.exp(bbox[idx * 4 + 3]!) * stride
        face[0] = cx - w / 2
        face[1] = cy - h / 2
        face[2] = w
        face[3] = h
        for (let n = 0; n < 5; n++) {
          face[4 + 2 * n] = (kps[idx * 10 + 2 * n]! + c) * stride
          face[5 + 2 * n] = (kps[idx * 10 + 2 * n + 1]! + r) * stride
        }
        face[14] = score
        faces.push(face)
      }
    }
  })
  if (faces.length <= 1) return faces
  return nmsBoxes(faces).map((index) => faces[index]!)
}

/** `cv::dnn::NMSBoxes` on `Rect2i` boxes: stable score order, topK, keep while IoU <= threshold. */
function nmsBoxes(faces: Float32Array[]): number[] {
  const rects = faces.map((face) => [Math.trunc(face[0]!), Math.trunc(face[1]!), Math.trunc(face[2]!), Math.trunc(face[3]!)] as const)
  const order = faces
    .map((face, index) => ({ index, score: face[14]! }))
    .filter((entry) => entry.score > YUNET_CONFIG.scoreThreshold)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .slice(0, YUNET_CONFIG.topK)
  const kept: number[] = []
  for (const { index } of order) {
    const keep = kept.every((other) => rectIou(rects[index]!, rects[other]!) <= YUNET_CONFIG.nmsThreshold)
    if (keep) kept.push(index)
  }
  return kept
}

function rectIou(a: readonly number[], b: readonly number[]): number {
  const x1 = Math.max(a[0]!, b[0]!)
  const y1 = Math.max(a[1]!, b[1]!)
  const x2 = Math.min(a[0]! + a[2]!, b[0]! + b[2]!)
  const y2 = Math.min(a[1]! + a[3]!, b[1]! + b[3]!)
  const inter = x2 > x1 && y2 > y1 ? (x2 - x1) * (y2 - y1) : 0
  const union = a[2]! * a[3]! + b[2]! * b[3]! - inter
  return union > 0 ? inter / union : 0
}

/** Android `YuNetFacePostProcessor.normalize`: one pass to normalized faces. */
export function normalizeYuNetPass(rows: Float32Array[], passWidth: number, passHeight: number): AnalysisFace[] {
  const out: AnalysisFace[] = []
  for (const row of rows) {
    if (!row.every(Number.isFinite)) throw new Error('non-finite YuNet row')
    const x0 = clamp01(row[0]! / passWidth)
    const y0 = clamp01(row[1]! / passHeight)
    const x1 = clamp01((row[0]! + row[2]!) / passWidth)
    const y1 = clamp01((row[1]! + row[3]!) / passHeight)
    const width = x1 - x0
    const height = y1 - y0
    if (width <= 0 || height <= 0) continue
    out.push({ box: { x: x0, y: y0, width, height }, confidence: row[14]! })
  }
  return out
}

/** Android `YuNetFacePostProcessor.merge`: stable confidence order, drop overlaps above IoU 0.25. */
export function mergeYuNetPasses(passes: AnalysisFace[][]): AnalysisFace[] {
  const sorted = passes.flat()
    .map((face, index) => ({ face, index }))
    .sort((a, b) => b.face.confidence - a.face.confidence || a.index - b.index)
    .map(({ face }) => face)
  const kept: AnalysisFace[] = []
  const suppressed = new Uint8Array(sorted.length)
  for (let i = 0; i < sorted.length; i++) {
    if (suppressed[i]) continue
    kept.push(sorted[i]!)
    for (let j = i + 1; j < sorted.length; j++) {
      if (!suppressed[j] && faceIou(sorted[i]!, sorted[j]!) > YUNET_CONFIG.dedupIou) suppressed[j] = 1
    }
  }
  return kept
}

function faceIou(a: AnalysisFace, b: AnalysisFace): number {
  const iw = Math.min(a.box.x + a.box.width, b.box.x + b.box.width) - Math.max(a.box.x, b.box.x)
  const ih = Math.min(a.box.y + a.box.height, b.box.y + b.box.height) - Math.max(a.box.y, b.box.y)
  const inter = iw > 0 && ih > 0 ? iw * ih : 0
  return inter / Math.max(1e-9, a.box.width * a.box.height + b.box.width * b.box.height - inter)
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value))
}
