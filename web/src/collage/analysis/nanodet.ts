import type { AnalysisObject } from './cropSolver'

/**
 * Port of Android `NanoDetDecoder`: the frozen `runtime/models/nanodet.py`
 * post-process plus the `import-field-3.3` inverse letterbox mapping. Pure:
 * it takes the named output heads the OpenCV forward pass produced.
 */

export const NANODET_INPUT = 416
export const CLASS_COUNT = 80
export const REG_CHANNELS = 32
const REG_MAX = 7
const PROB_THRESHOLD = 0.35
const IOU_THRESHOLD = 0.55
const NMS_PRE = 1000
const MIN_BOX_FRACTION = 0.01
const SIDE_TO_STRIDE = new Map([[52, 8], [26, 16], [13, 32], [6, 64]])

export interface NanoDetHead { name: string; shape: number[]; values: Float32Array }

export interface Letterbox { sourceWidth: number; sourceHeight: number; rw: number; rh: number; padX: number; padY: number; scale: number }

interface Candidate { x1: number; y1: number; x2: number; y2: number; score: number; classId: number }

/** The frozen letterbox: `k = 416/max(w,h)`, Python half-even rounding, centred pad, fill 114. */
export function letterbox(sourceWidth: number, sourceHeight: number): Letterbox {
  const k = NANODET_INPUT / Math.max(sourceWidth, sourceHeight)
  const rw = halfEvenRound(sourceWidth * k)
  const rh = halfEvenRound(sourceHeight * k)
  return { sourceWidth, sourceHeight, rw, rh, padX: Math.trunc((NANODET_INPUT - rw) / 2), padY: Math.trunc((NANODET_INPUT - rh) / 2), scale: k }
}

/** Normalized objects, or null when the heads are malformed. */
export function decodeNanoDet(heads: NanoDetHead[], box: Letterbox): AnalysisObject[] | null {
  const candidates = decodeCandidates(heads)
  if (candidates === null) return null
  const out: AnalysisObject[] = []
  for (const c of nonMaxSuppression(candidates)) {
    const x = clamp01((c.x1 - box.padX) / box.rw)
    const y = clamp01((c.y1 - box.padY) / box.rh)
    const width = clamp01((c.x2 - box.padX) / box.rw) - x
    const height = clamp01((c.y2 - box.padY) / box.rh) - y
    if (width > MIN_BOX_FRACTION && height > MIN_BOX_FRACTION) out.push({ box: { x, y, width, height }, confidence: c.score, classId: c.classId })
  }
  return out
}

function decodeCandidates(heads: NanoDetHead[]): Candidate[] | null {
  if (heads.length === 0) return null
  const cls: Array<{ spatial: number; head: NanoDetHead; side: number }> = []
  const reg = new Map<number, NanoDetHead>()
  for (const head of heads) {
    if (head.shape.length < 2 || head.shape.length > 3) return null
    if (head.shape.length === 3 && head.shape[0] !== 1) return null
    if (head.shape.reduce((a, b) => a * b, 1) !== head.values.length) return null
    const last = head.shape[head.shape.length - 1]!
    const spatial = head.shape[head.shape.length - 2]!
    if (spatial <= 0) return null
    const side = perfectSide(spatial)
    if (side === null || !SIDE_TO_STRIDE.has(side)) return null
    if (last === CLASS_COUNT) cls.push({ spatial, head, side })
    else if (last === REG_CHANNELS) {
      if (reg.has(spatial)) return null
      reg.set(spatial, head)
    } else return null
  }
  if (cls.length === 0 || reg.size !== cls.length) return null
  cls.sort((a, b) => b.spatial - a.spatial)
  const out: Candidate[] = []
  for (const { spatial, head: clsHead, side } of cls) {
    const regHead = reg.get(spatial)
    if (!regHead) return null
    const stride = SIDE_TO_STRIDE.get(side)!
    let anchors = Array.from({ length: spatial }, (_, i) => i)
    if (spatial > NMS_PRE) {
      anchors = anchors
        .map((anchor) => [anchor, clsMax(clsHead.values, anchor)] as const)
        .sort((a, b) => b[1] - a[1] || a[0] - b[0])
        .slice(0, NMS_PRE)
        .map(([anchor]) => anchor)
    }
    for (const anchor of anchors) {
      let classId = 0
      let best = Number.NEGATIVE_INFINITY
      for (let c = 0; c < CLASS_COUNT; c++) {
        const value = clsHead.values[anchor * CLASS_COUNT + c]!
        if (!Number.isFinite(value)) return null
        if (value > best) {
          best = value
          classId = c
        }
      }
      const row = anchor * REG_CHANNELS
      const d = [0, 8, 16, 24].map((offset) => dfl(regHead.values, row + offset))
      if (d.some((value) => value === null)) return null
      const [d0, d1, d2, d3] = d as number[]
      const cx = (anchor % side) * stride + 0.5 * (stride - 1)
      const cy = Math.trunc(anchor / side) * stride + 0.5 * (stride - 1)
      out.push({
        x1: clampInput(cx - d0! * stride),
        y1: clampInput(cy - d1! * stride),
        x2: clampInput(cx + d2! * stride),
        y2: clampInput(cy + d3! * stride),
        score: best,
        classId,
      })
    }
  }
  return out
}

/** Class-agnostic NMS, stable (score desc, index asc); a score equal to the threshold is rejected. */
function nonMaxSuppression(candidates: Candidate[]): Candidate[] {
  const order = candidates.map((_, i) => i).sort((a, b) => candidates[b]!.score - candidates[a]!.score || a - b)
  const removed = new Uint8Array(candidates.length)
  const kept: Candidate[] = []
  for (const i of order) {
    if (removed[i]) continue
    if (candidates[i]!.score <= PROB_THRESHOLD) continue
    kept.push(candidates[i]!)
    for (const j of order) {
      if (j === i || removed[j]) continue
      if (iou(candidates[i]!, candidates[j]!) > IOU_THRESHOLD) removed[j] = 1
    }
  }
  return kept
}

function clsMax(values: Float32Array, anchor: number): number {
  let best = Number.NEGATIVE_INFINITY
  for (let c = 0; c < CLASS_COUNT; c++) best = Math.max(best, values[anchor * CLASS_COUNT + c]!)
  return best
}

/** DFL: softmax over 8 bins, dot with 0..7. */
function dfl(values: Float32Array, offset: number): number | null {
  let maxValue = Number.NEGATIVE_INFINITY
  for (let p = 0; p <= REG_MAX; p++) {
    const raw = values[offset + p]!
    if (!Number.isFinite(raw)) return null
    if (raw > maxValue) maxValue = raw
  }
  let sum = 0
  const exps: number[] = []
  for (let p = 0; p <= REG_MAX; p++) {
    const e = Math.exp(values[offset + p]! - maxValue)
    exps.push(e)
    sum += e
  }
  if (!(sum > 0) || !Number.isFinite(sum)) return null
  let dot = 0
  for (let p = 0; p <= REG_MAX; p++) dot += (exps[p]! / sum) * p
  return dot
}

function iou(a: Candidate, b: Candidate): number {
  const iw = Math.max(0, Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1))
  const ih = Math.max(0, Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1))
  const inter = iw * ih
  const union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
  return union > 0 ? inter / union : 0
}

export function halfEvenRound(value: number): number {
  const lower = Math.floor(value)
  const fraction = value - lower
  if (fraction < 0.5) return lower
  if (fraction > 0.5) return lower + 1
  return lower % 2 === 0 ? lower : lower + 1
}

function perfectSide(spatial: number): number | null {
  const side = Math.round(Math.sqrt(spatial))
  return side * side === spatial ? side : null
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value))
}

function clampInput(value: number): number {
  return Math.min(NANODET_INPUT, Math.max(0, value))
}
