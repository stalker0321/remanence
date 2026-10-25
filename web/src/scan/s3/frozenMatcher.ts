/**
 * Frozen postcard matcher (S3), the web twin of Android's `FrozenMatcherS3`.
 *
 * Sender: decode -> GrabCut auto-crop (or the whole photo) -> 6x6 grid SIFT.
 * Recipient: decode -> centred 2:3 frame -> ONNX visible-card mask ->
 * foreground ROI -> grid SIFT with keypoint centres inside the mask.
 * Matching: soft TF-IDF over the frozen 1024-word vocabulary, top-8 shortlist,
 * ratio-tested unique matches, MAGSAC homography, projected-bbox checks, the
 * frozen floors, strict_combined, the card must reach the frame centre, and
 * the same postcard from several senders becomes a choice.
 *
 * Every OpenCV call goes through the injected `cv` (OpenCV.js), so the same
 * code runs in the browser and in Node parity checks.
 */

// OpenCV.js has no shipped typings for the parts used here.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Cv = any

export const DECODE_MAX_SIDE = 960
export const TOP_K = 8
export const THRESHOLD = 12
export const MARGIN = 1.6
export const MAX_CENTRE_DISTANCE = 0.15
/** Decisive winner: may pass the strict gate with a lower inlier ratio. */
export const DECISIVE_MIN_SCORE = 40
export const DECISIVE_MIN_SEPARATION = 20
export const DECISIVE_MIN_INLIER_RATIO = 0.6
const GRID = 6
const PER_CELL = 45
const RATIO = 0.75
const USAC_MAGSAC = 38
const MIN_SCORE = 20
const MIN_INLIER_RATIO = 0.85
const MIN_COVERAGE = 0.2
const MAX_COMPONENTS = 1
const MULTI_MIN_COVERAGE = 0.5
const MULTI_MIN_INLIER_RATIO = 0.9
const LOCALIZER_SIZE = 320
const MEAN = [0.485, 0.456, 0.406]
const STD = [0.229, 0.224, 0.225]

/** Keypoint positions (x,y pairs) and uint8 SIFT rows, in their crop's pixels. */
export interface Features {
  readonly xy: Float32Array
  readonly descriptors: Uint8Array
  readonly width: number
  readonly height: number
  /** Recipient only: where the ROI sits in the 2:3 frame, and the frame size. */
  readonly originX: number
  readonly originY: number
  readonly frameWidth: number
  readonly frameHeight: number
}

export function featureCount(f: Features): number {
  return f.xy.length / 2
}

export interface Candidate {
  readonly id: string
  readonly finalScore: number
  readonly inliers: number
  readonly matches: number
  readonly inlierRatio: number
  readonly coverage: number
  readonly geometryVerified: boolean
  readonly centreDistance: number
}

export interface Decision {
  readonly baselineAccepted: boolean
  readonly accepted: boolean
  readonly id: string | null
  readonly best: Candidate | null
  /** The same postcard from several senders: the recipient chooses. */
  readonly ambiguousIds: readonly string[]
}

/** Python `round()`: half to even. */
export function pyRound(value: number): number {
  const floor = Math.floor(value)
  const diff = value - floor
  if (diff > 0.5) return floor + 1
  if (diff < 0.5) return floor
  return floor % 2 === 0 ? floor : floor + 1
}

// ---------------------------------------------------------------- decode

/** RGBA pixels -> BGR Mat with the longest side at most 960 (INTER_AREA). */
export function bgr960(cv: Cv, rgba: Uint8ClampedArray | Uint8Array, width: number, height: number): Cv {
  const src = new cv.Mat(height, width, cv.CV_8UC4)
  src.data.set(rgba)
  const bgr = new cv.Mat()
  cv.cvtColor(src, bgr, cv.COLOR_RGBA2BGR)
  src.delete()
  const scale = DECODE_MAX_SIDE / Math.max(width, height)
  if (scale >= 1) return bgr
  const out = new cv.Mat()
  cv.resize(bgr, out, new cv.Size(Math.round(width * scale), Math.round(height * scale)), 0, 0, cv.INTER_AREA)
  bgr.delete()
  return out
}

/** Frozen centred portrait 2:3 frame bounds (x0, y0, x1, y1). */
export function frameBounds(width: number, height: number): [number, number, number, number] {
  const frameWidth = Math.min(width * 0.6934, height * 0.78 * 2 / 3)
  const frameHeight = frameWidth * 3 / 2
  const x0 = pyRound((width - frameWidth) / 2)
  const y0 = pyRound((height - frameHeight) / 2)
  return [x0, y0, x0 + pyRound(frameWidth), y0 + pyRound(frameHeight)]
}

// ---------------------------------------------------------------- features

/** `vision.features` / `adapter.features_masked`: grid-selected SIFT, optionally centre-filtered. */
export function gridFeatures(cv: Cv, bgr: Cv, mask: Uint8Array | null = null, maskWidth = 0): Features {
  const gray = new cv.Mat()
  cv.cvtColor(bgr, gray, cv.COLOR_BGR2GRAY)
  const w = gray.cols
  const h = gray.rows
  const sift = new cv.SIFT(0, 3, 0.018, 12, 1.6)
  const detected = new cv.KeyPointVector()
  const none = new cv.Mat()
  const selectedVector = new cv.KeyPointVector()
  const descriptors = new cv.Mat()
  try {
    sift.detect(gray, detected, none)
    const all: { kp: Cv; index: number }[] = []
    for (let i = 0; i < detected.size(); i++) all.push({ kp: detected.get(i), index: i })
    all.sort((a, b) => b.kp.response - a.kp.response || a.index - b.index)
    const buckets = new Map<number, Cv[]>()
    for (const { kp } of all) {
      const cell = Math.min(GRID - 1, Math.floor(kp.pt.x * GRID / w)) * GRID + Math.min(GRID - 1, Math.floor(kp.pt.y * GRID / h))
      let group = buckets.get(cell)
      if (!group) { group = []; buckets.set(cell, group) }
      if (group.length < PER_CELL) group.push(kp)
    }
    let selected = [...buckets.values()].flat()
    if (mask) {
      const mh = mask.length / maskWidth
      selected = selected.filter((kp) => {
        const x = pyRound(kp.pt.x)
        const y = pyRound(kp.pt.y)
        return x >= 0 && x < maskWidth && y >= 0 && y < mh && mask[y * maskWidth + x] !== 0
      })
    }
    if (selected.length === 0) return emptyFeatures(w, h)
    for (const kp of selected) selectedVector.push_back(kp)
    sift.compute(gray, selectedVector, descriptors)
    const n = descriptors.rows
    if (n === 0) return emptyFeatures(w, h)
    const raw = descriptors.data32F as Float32Array
    const bytes = new Uint8Array(n * 128)
    for (let i = 0; i < n * 128; i++) bytes[i] = Math.min(255, Math.max(0, pyRound(raw[i]!)))
    const xy = new Float32Array(n * 2)
    for (let i = 0; i < n; i++) {
      const kp = selectedVector.get(i)
      xy[2 * i] = kp.pt.x
      xy[2 * i + 1] = kp.pt.y
    }
    return { xy, descriptors: bytes, width: w, height: h, originX: 0, originY: 0, frameWidth: w, frameHeight: h }
  } finally {
    gray.delete(); sift.delete(); detected.delete(); none.delete(); selectedVector.delete(); descriptors.delete()
  }
}

function emptyFeatures(w: number, h: number): Features {
  return { xy: new Float32Array(0), descriptors: new Uint8Array(0), width: w, height: h, originX: 0, originY: 0, frameWidth: w, frameHeight: h }
}

/** RootSIFT rows: L1-normalise then square-root, as a CV_32F Mat. */
function rooted(cv: Cv, descriptors: Uint8Array, count: number): Cv {
  const out = new Float32Array(count * 128)
  for (let i = 0; i < count; i++) {
    let sum = 0
    for (let j = 0; j < 128; j++) sum += descriptors[i * 128 + j]!
    const norm = Math.max(sum, 1e-12)
    for (let j = 0; j < 128; j++) out[i * 128 + j] = Math.sqrt(descriptors[i * 128 + j]! / norm)
  }
  const mat = new cv.Mat(count, 128, cv.CV_32F)
  ;(mat.data32F as Float32Array).set(out)
  return mat
}

// ---------------------------------------------------------------- sender crop

interface Pt { x: number; y: number }
const dist = (a: Pt, b: Pt) => Math.hypot(a.x - b.x, a.y - b.y)
const cross = (o: Pt, a: Pt, b: Pt) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

function ordered(points: Pt[]): Pt[] {
  const cx = points.reduce((s, p) => s + p.x, 0) / points.length
  const cy = points.reduce((s, p) => s + p.y, 0) / points.length
  const byAngle = [...points].sort((a, b) => Math.atan2(a.y - cy, a.x - cx) - Math.atan2(b.y - cy, b.x - cx))
  let start = 0
  for (let i = 1; i < byAngle.length; i++) if (byAngle[i]!.x + byAngle[i]!.y < byAngle[start]!.x + byAngle[start]!.y) start = i
  return byAngle.map((_, i) => byAngle[(start + i) % byAngle.length]!)
}

function rotatedRectPoints(rect: Cv): Pt[] {
  const angle = rect.angle * Math.PI / 180
  const b = Math.cos(angle) * 0.5
  const a = Math.sin(angle) * 0.5
  const { x: cx, y: cy } = rect.center
  const { width, height } = rect.size
  const p0 = { x: cx - a * height - b * width, y: cy + b * height - a * width }
  const p1 = { x: cx + a * height - b * width, y: cy - b * height - a * width }
  const p2 = { x: 2 * cx - p0.x, y: 2 * cy - p0.y }
  const p3 = { x: 2 * cx - p1.x, y: 2 * cy - p1.y }
  return [p0, p1, p2, p3]
}

function pointsMat(cv: Cv, points: Pt[]): Cv {
  return cv.matFromArray(points.length, 1, cv.CV_32FC2, points.flatMap((p) => [p.x, p.y]))
}

function grabCutQuad(cv: Cv, image: Cv): Pt[] | null {
  const width = image.cols
  const height = image.rows
  const smallW = Math.min(240, width)
  const smallH = Math.max(32, pyRound(height * smallW / width))
  const small = new cv.Mat()
  cv.resize(image, small, new cv.Size(smallW, smallH), 0, 0, cv.INTER_AREA)
  const mask = cv.Mat.zeros(smallH, smallW, cv.CV_8UC1)
  const bg = new cv.Mat()
  const fg = new cv.Mat()
  const fgMask = new cv.Mat(smallH, smallW, cv.CV_8UC1)
  const contours = new cv.MatVector()
  const hierarchy = new cv.Mat()
  const open = cv.Mat.ones(5, 5, cv.CV_8U)
  const close = cv.Mat.ones(9, 9, cv.CV_8U)
  try {
    cv.setRNGSeed(42)
    const insetX = Math.max(2, pyRound(smallW * 0.04))
    const insetY = Math.max(2, pyRound(smallH * 0.04))
    cv.grabCut(small, mask, new cv.Rect(insetX, insetY, smallW - 2 * insetX, smallH - 2 * insetY), bg, fg, 1, cv.GC_INIT_WITH_RECT)
    const m = mask.data as Uint8Array
    const f = fgMask.data as Uint8Array
    for (let i = 0; i < m.length; i++) f[i] = m[i] === cv.GC_FGD || m[i] === cv.GC_PR_FGD ? 255 : 0
    cv.morphologyEx(fgMask, fgMask, cv.MORPH_OPEN, open)
    cv.morphologyEx(fgMask, fgMask, cv.MORPH_CLOSE, close)
    cv.findContours(fgMask, contours, hierarchy, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE)
    let contour: Cv = null
    let contourArea = -1
    for (let i = 0; i < contours.size(); i++) {
      const c = contours.get(i)
      const area = cv.contourArea(c)
      if (area > contourArea) {
        contour?.delete()
        contour = c
        contourArea = area
      } else {
        c.delete()
      }
    }
    if (!contour) return null
    const rect = cv.minAreaRect(contour)
    contour.delete()
    const areaFraction = contourArea / Math.max(1, smallW * smallH)
    if (areaFraction < 0.06 || areaFraction > 0.88) return null
    const box = rotatedRectPoints(rect)
    const boxMat = pointsMat(cv, box)
    const boxArea = cv.contourArea(boxMat)
    boxMat.delete()
    if (boxArea <= 0 || contourArea / boxArea < 0.6) return null
    const sides = box.map((p, i) => dist(p, box[(i + 3) % 4]!))
    if (Math.max(...sides) / Math.max(Math.min(...sides), 1) > 5) return null
    const cx = box.reduce((s, p) => s + p.x, 0) / 4 / smallW
    const cy = box.reduce((s, p) => s + p.y, 0) / 4 / smallH
    if (Math.hypot(cx - 0.5, cy - 0.5) > 0.35) return null
    const sx = width / smallW
    const sy = height / smallH
    return ordered(box).map((p) => ({ x: p.x * sx, y: p.y * sy }))
  } finally {
    small.delete(); mask.delete(); bg.delete(); fg.delete(); fgMask.delete(); contours.delete(); hierarchy.delete(); open.delete(); close.delete()
  }
}

/** `vision.auto_crop`: GrabCut silhouette -> rectified crop, else a copy of the image. */
export function autoCrop(cv: Cv, bgr: Cv): Cv {
  const corners = grabCutQuad(cv, bgr)
  if (!corners) return bgr.clone()
  const [tl, tr, br, bl] = corners as [Pt, Pt, Pt, Pt]
  const outW = Math.trunc(Math.max(dist(tr, tl), dist(br, bl)))
  const outH = Math.trunc(Math.max(dist(bl, tl), dist(br, tr)))
  if (Math.min(outW, outH) < 40) return bgr.clone()
  const src = pointsMat(cv, corners)
  const dst = pointsMat(cv, [{ x: 0, y: 0 }, { x: outW - 1, y: 0 }, { x: outW - 1, y: outH - 1 }, { x: 0, y: outH - 1 }])
  const transform = cv.getPerspectiveTransform(src, dst)
  const crop = new cv.Mat()
  cv.warpPerspective(bgr, crop, transform, new cv.Size(outW, outH))
  src.delete(); dst.delete(); transform.delete()
  return crop
}

export function senderFeatures(cv: Cv, bgr: Cv): Features {
  const crop = autoCrop(cv, bgr)
  try {
    return gridFeatures(cv, crop)
  } finally {
    crop.delete()
  }
}

// ---------------------------------------------------------------- recipient

/** OpenCV.js dnn session over the frozen ONNX localizer. */
export interface Localizer {
  /** Visible-card mask (1 = card) for a BGR image, or null when empty. */
  mask(bgr: Cv): Uint8Array | null
}

export function createLocalizer(cv: Cv, modelBytes: Uint8Array): Localizer {
  const path = `frozen-localizer-${Date.now()}.onnx`
  cv.FS_createDataFile('/', path, modelBytes, true, false, false)
  const net = cv.readNetFromONNX(path)
  return {
    mask(bgr: Cv): Uint8Array | null {
      const width = bgr.cols
      const height = bgr.rows
      const scale = Math.min(LOCALIZER_SIZE / width, LOCALIZER_SIZE / height)
      const newW = Math.max(1, pyRound(width * scale))
      const newH = Math.max(1, pyRound(height * scale))
      const padX = Math.floor((LOCALIZER_SIZE - newW) / 2)
      const padY = Math.floor((LOCALIZER_SIZE - newH) / 2)
      const small = new cv.Mat()
      cv.resize(bgr, small, new cv.Size(newW, newH), 0, 0, cv.INTER_AREA)
      const canvas = new cv.Mat(LOCALIZER_SIZE, LOCALIZER_SIZE, cv.CV_32FC3)
      const c = canvas.data32F as Float32Array
      for (let i = 0; i < LOCALIZER_SIZE * LOCALIZER_SIZE; i++) {
        for (let ch = 0; ch < 3; ch++) c[i * 3 + ch] = (0 - MEAN[ch]!) / STD[ch]!
      }
      const s = small.data as Uint8Array
      for (let y = 0; y < newH; y++) {
        for (let x = 0; x < newW; x++) {
          const from = (y * newW + x) * 3
          const to = ((y + padY) * LOCALIZER_SIZE + x + padX) * 3
          for (let ch = 0; ch < 3; ch++) c[to + ch] = (s[from + ch]! / 255 - MEAN[ch]!) / STD[ch]!
        }
      }
      small.delete()
      const blob = cv.blobFromImage(canvas, 1, new cv.Size(LOCALIZER_SIZE, LOCALIZER_SIZE), new cv.Scalar(0, 0, 0, 0), false, false)
      canvas.delete()
      net.setInput(blob)
      const out = net.forward()
      blob.delete()
      const logits = out.data32F as Float32Array
      const crop = new cv.Mat(newH, newW, cv.CV_32F)
      const p = crop.data32F as Float32Array
      for (let y = 0; y < newH; y++) {
        for (let x = 0; x < newW; x++) {
          p[y * newW + x] = 1 / (1 + Math.exp(-logits[(y + padY) * LOCALIZER_SIZE + x + padX]!))
        }
      }
      out.delete()
      const full = new cv.Mat()
      cv.resize(crop, full, new cv.Size(width, height), 0, 0, cv.INTER_LINEAR)
      crop.delete()
      const prob = full.data32F as Float32Array
      const mask = new Uint8Array(width * height)
      let any = false
      for (let i = 0; i < mask.length; i++) if (prob[i]! > 0.5) { mask[i] = 1; any = true }
      full.delete()
      return any ? mask : null
    },
  }
}

/** Mask component count (8-connectivity) for the safety gate. */
function components(cv: Cv, mask: Uint8Array, width: number, height: number): number {
  const m = new cv.Mat(height, width, cv.CV_8UC1)
  ;(m.data as Uint8Array).set(mask)
  const labels = new cv.Mat()
  const stats = new cv.Mat()
  const centroids = new cv.Mat()
  const count = cv.connectedComponentsWithStats(m, labels, stats, centroids, 8)
  m.delete(); labels.delete(); stats.delete(); centroids.delete()
  return Math.max(0, count - 1)
}

/** Foreground ROI of the frame mask, features centre-filtered by it. */
export function recipientFeatures(cv: Cv, frame: Cv, mask: Uint8Array | null): { features: Features; components: number } {
  const w = frame.cols
  const h = frame.rows
  if (!mask) return { features: gridFeatures(cv, frame), components: 0 }
  let x0 = w, y0 = h, x1 = -1, y1 = -1
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (mask[y * w + x] !== 0) {
        if (x < x0) x0 = x
        if (x > x1) x1 = x
        if (y < y0) y0 = y
        if (y > y1) y1 = y
      }
    }
  }
  const comps = components(cv, mask, w, h)
  if (x1 < 0 || x1 + 1 - x0 < 2 || y1 + 1 - y0 < 2) return { features: gridFeatures(cv, frame), components: comps }
  const rw = x1 + 1 - x0
  const rh = y1 + 1 - y0
  const roi = frame.roi(new cv.Rect(x0, y0, rw, rh))
  const local = new Uint8Array(rw * rh)
  for (let y = 0; y < rh; y++) local.set(mask.subarray((y + y0) * w + x0, (y + y0) * w + x0 + rw), y * rw)
  try {
    const f = gridFeatures(cv, roi, local, rw)
    return { features: { ...f, originX: x0, originY: y0, frameWidth: w, frameHeight: h }, components: comps }
  } finally {
    roi.delete()
  }
}

// ---------------------------------------------------------------- retrieval

export interface Vocabulary {
  histogram(features: Features, query: boolean): Float32Array
  delete(): void
}

/** Frozen vocabulary: 1024x128 float32 centres then 1024 float32 IDF weights. */
export function createVocabulary(cv: Cv, blob: Float32Array): Vocabulary {
  const words = 1024
  if (blob.length !== words * 128 + words) throw new Error('vocabulary size')
  const centers = new cv.Mat(words, 128, cv.CV_32F)
  ;(centers.data32F as Float32Array).set(blob.subarray(0, words * 128))
  const idf = blob.slice(words * 128)
  return {
    histogram(features: Features, query: boolean): Float32Array {
      const counts = new Float64Array(words)
      const n = featureCount(features)
      if (n > 0) {
        const rootedMat = rooted(cv, features.descriptors, n)
        const matcher = new cv.BFMatcher(cv.NORM_L2, false)
        const pairs = new cv.DMatchVectorVector()
        matcher.knnMatch(rootedMat, centers, pairs, 2)
        for (let i = 0; i < pairs.size(); i++) {
          const pair = pairs.get(i)
          for (let k = 0; k < pair.size(); k++) {
            const m = pair.get(k)
            const squared = m.distance * m.distance
            counts[m.trainIdx] += query ? Math.exp(-squared / 0.05) : 1
          }
          pair.delete()
        }
        pairs.delete(); matcher.delete(); rootedMat.delete()
      }
      const out = new Float32Array(words)
      let norm = 0
      for (let i = 0; i < words; i++) { out[i] = Math.sqrt(counts[i]!) * idf[i]!; norm += out[i]! * out[i]! }
      norm = Math.max(Math.sqrt(norm), 1e-12)
      for (let i = 0; i < words; i++) out[i] = out[i]! / norm
      return out
    },
    delete() { centers.delete() },
  }
}

export interface Reference {
  readonly id: string
  readonly features: Features
  readonly histogram: Float32Array
}

export function reference(id: string, features: Features, vocabulary: Vocabulary): Reference {
  return { id, features, histogram: vocabulary.histogram(features, false) }
}

// ---------------------------------------------------------------- matching

function hull(points: Pt[]): Pt[] {
  const sorted = [...new Map(points.map((p) => [`${p.x},${p.y}`, p])).values()].sort((a, b) => a.x - b.x || a.y - b.y)
  if (sorted.length < 3) return sorted
  const lower: Pt[] = []
  for (const p of sorted) {
    while (lower.length >= 2 && cross(lower[lower.length - 2]!, lower[lower.length - 1]!, p) <= 0) lower.pop()
    lower.push(p)
  }
  const upper: Pt[] = []
  for (const p of [...sorted].reverse()) {
    while (upper.length >= 2 && cross(upper[upper.length - 2]!, upper[upper.length - 1]!, p) <= 0) upper.pop()
    upper.push(p)
  }
  return [...lower.slice(0, -1), ...upper.slice(0, -1)]
}

function shoelace(points: Pt[]): number {
  let sum = 0
  for (let i = 0; i < points.length; i++) {
    const a = points[i]!, b = points[(i + 1) % points.length]!
    sum += a.x * b.y - b.x * a.y
  }
  return sum / 2
}

function convex(points: Pt[]): boolean {
  let sign = 0
  for (let i = 0; i < points.length; i++) {
    const c = cross(points[i]!, points[(i + 1) % points.length]!, points[(i + 2) % points.length]!)
    const s = c > 0 ? 1 : c < 0 ? -1 : 0
    if (s !== 0) {
      if (sign !== 0 && s !== sign) return false
      sign = s
    }
  }
  return sign !== 0
}

function contains(polygon: Pt[], p: Pt): boolean {
  let sign = 0
  for (let i = 0; i < polygon.length; i++) {
    const c = cross(polygon[i]!, polygon[(i + 1) % polygon.length]!, p)
    const s = c > 0 ? 1 : c < 0 ? -1 : 0
    if (s !== 0) {
      if (sign !== 0 && s !== sign) return false
      sign = s
    }
  }
  return true
}

function segmentDistance(p: Pt, a: Pt, b: Pt): number {
  const dx = b.x - a.x, dy = b.y - a.y
  const len = dx * dx + dy * dy
  const t = len === 0 ? 0 : Math.min(1, Math.max(0, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len))
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

function project(h: Float64Array, p: Pt): Pt {
  const w = h[6]! * p.x + h[7]! * p.y + h[8]!
  return { x: (h[0]! * p.x + h[1]! * p.y + h[2]!) / w, y: (h[3]! * p.x + h[4]! * p.y + h[5]!) / w }
}

const round4 = (v: number) => Math.round(v * 10_000) / 10_000

/** `vision.verify` + `engine.projected_bbox_outside`, plus the frame-centre distance. */
function verify(cv: Cv, query: Features, queryRooted: Cv | null, ref: Reference): Candidate {
  const empty = (): Candidate => ({ id: ref.id, finalScore: 0, inliers: 0, matches: 0, inlierRatio: 0, coverage: 0, geometryVerified: false, centreDistance: Infinity })
  const refCount = featureCount(ref.features)
  if (!queryRooted || Math.min(featureCount(query), refCount) < 4) return empty()
  const refRooted = rooted(cv, ref.features.descriptors, refCount)
  const matcher = new cv.BFMatcher(cv.NORM_L2, false)
  const pairs = new cv.DMatchVectorVector()
  matcher.knnMatch(refRooted, queryRooted, pairs, 2)
  const good: { q: number; t: number; d: number }[] = []
  for (let i = 0; i < pairs.size(); i++) {
    const pair = pairs.get(i)
    if (pair.size() === 2 && pair.get(0).distance < RATIO * pair.get(1).distance) {
      const m = pair.get(0)
      good.push({ q: m.queryIdx, t: m.trainIdx, d: m.distance })
    }
    pair.delete()
  }
  pairs.delete(); matcher.delete(); refRooted.delete()
  good.sort((a, b) => a.d - b.d)
  const used = new Set<string>()
  const unique = good.filter((m) => {
    const key = `${pyRound(query.xy[2 * m.t]!)},${pyRound(query.xy[2 * m.t + 1]!)}`
    if (used.has(key)) return false
    used.add(key)
    return true
  })
  if (unique.length < 6) return { ...empty(), matches: unique.length }
  const a = unique.map((m) => ({ x: ref.features.xy[2 * m.q]!, y: ref.features.xy[2 * m.q + 1]! }))
  const b = unique.map((m) => ({ x: query.xy[2 * m.t]!, y: query.xy[2 * m.t + 1]! }))
  const src = pointsMat(cv, a)
  const dst = pointsMat(cv, b)
  const inlierMask = new cv.Mat()
  cv.setRNGSeed(42)
  const homography = cv.findHomography(src, dst, USAC_MAGSAC, 4, inlierMask, 3000, 0.999)
  src.delete(); dst.delete()
  if (homography.empty() || inlierMask.rows === 0) {
    homography.delete(); inlierMask.delete()
    return { ...empty(), matches: unique.length }
  }
  const h = Float64Array.from(homography.data64F as Float64Array)
  const flags = inlierMask.data as Uint8Array
  const kept = a.filter((_, i) => flags[i] !== 0)
  homography.delete(); inlierMask.delete()
  const n = kept.length
  if (n < 4) return { ...empty(), matches: unique.length }
  const coverage = Math.abs(shoelace(hull(kept))) / (ref.features.width * ref.features.height)
  const left = Math.min(...kept.map((p) => p.x)), top = Math.min(...kept.map((p) => p.y))
  const right = Math.max(...kept.map((p) => p.x)), bottom = Math.max(...kept.map((p) => p.y))
  const poly = [{ x: left, y: top }, { x: right, y: top }, { x: right, y: bottom }, { x: left, y: bottom }].map((p) => project(h, p))
  if (poly.some((p) => !Number.isFinite(p.x) || !Number.isFinite(p.y)) || !convex(poly)) return { ...empty(), matches: unique.length }
  const area = shoelace(poly)
  const lengths = poly.map((p, i) => dist(p, poly[(i + 3) % 4]!))
  if (area < 300 || Math.max(...lengths) / Math.max(Math.min(...lengths), 1) > 8) return { ...empty(), matches: unique.length }
  const ratio = n / unique.length
  const score = n * Math.min(1, coverage / 0.2) * Math.min(1, ratio / 0.5)
  const outside = poly.some((p) => p.x < -0.25 * query.width || p.x > 1.25 * query.width || p.y < -0.25 * query.height || p.y > 1.25 * query.height)
  const card = [{ x: 0, y: 0 }, { x: ref.features.width, y: 0 }, { x: ref.features.width, y: ref.features.height }, { x: 0, y: ref.features.height }]
    .map((p) => project(h, p)).map((p) => ({ x: p.x + query.originX, y: p.y + query.originY }))
  const centre = { x: query.frameWidth / 2, y: query.frameHeight / 2 }
  const cardValid = card.every((p) => Number.isFinite(p.x) && Number.isFinite(p.y)) && convex(card)
  const centreDistance = !cardValid ? Infinity : contains(card, centre) ? 0
    : Math.min(...card.map((p, i) => segmentDistance(centre, p, card[(i + 1) % 4]!))) / Math.hypot(query.frameWidth, query.frameHeight)
  return {
    id: ref.id,
    finalScore: outside ? 0 : score,
    inliers: n,
    matches: unique.length,
    inlierRatio: round4(ratio),
    coverage: round4(coverage),
    geometryVerified: true,
    centreDistance,
  }
}

/** Frozen strict_combined gate on one candidate. */
export function strictCombined(c: Candidate, components: number, minInlierRatio = MIN_INLIER_RATIO): boolean {
  if (!c.geometryVerified || c.finalScore < MIN_SCORE || c.inlierRatio < minInlierRatio || c.coverage < MIN_COVERAGE) return false
  if (components > MAX_COMPONENTS && !(c.coverage >= MULTI_MIN_COVERAGE && c.inlierRatio >= MULTI_MIN_INLIER_RATIO)) return false
  return true
}

export function match(
  cv: Cv,
  query: Features,
  references: readonly Reference[],
  vocabulary: Vocabulary,
  components: number,
  requireCentred = false,
): Decision {
  if (references.length === 0) throw new Error('no references')
  const qh = vocabulary.histogram(query, true)
  const scores = references.map((r) => r.histogram.reduce((s, v, i) => s + v * qh[i]!, 0))
  const shortlist = references.map((_, i) => i).sort((x, y) => scores[y]! - scores[x]! || x - y).slice(0, Math.min(TOP_K, references.length))
  const n = featureCount(query)
  const queryRooted = n >= 4 ? rooted(cv, query.descriptors, n) : null
  const results = shortlist.map((i) => verify(cv, query, queryRooted, references[i]!))
  queryRooted?.delete()
  const ranked = results.map((c, i) => ({ c, i })).sort((x, y) => y.c.finalScore - x.c.finalScore || x.i - y.i).map((x) => x.c)
  const best = ranked[0]!
  const runner = ranked.find((c) => c.id !== best.id)?.finalScore ?? 0
  const separation = best.finalScore / Math.max(runner, 1)
  const baseline = best.finalScore >= THRESHOLD && separation >= MARGIN && best.inliers >= 12 && best.coverage >= 0.04 && best.inlierRatio >= 0.3
  const centred = (c: Candidate) => !requireCentred || c.centreDistance <= MAX_CENTRE_DISTANCE
  // Decisive winner whose only shortfall is the inlier ratio (dark cards with
  // a repeated pattern); every other strict constraint still applies. Same
  // rule as the Android port: +16 correct, 0 wrong, 0 false accepts.
  const decisive = best.finalScore >= DECISIVE_MIN_SCORE && separation >= DECISIVE_MIN_SEPARATION
  const strict = strictCombined(best, components) || (decisive && strictCombined(best, components, DECISIVE_MIN_INLIER_RATIO))
  const accepted = baseline && strict && centred(best)
  const strong = (c: Candidate) => c.finalScore >= THRESHOLD && c.inliers >= 12 && c.coverage >= 0.04 && c.inlierRatio >= 0.3 &&
    strictCombined(c, components) && centred(c)
  const strongIds = [...new Set(ranked.filter(strong).map((c) => c.id))]
  const ambiguousIds = strong(best) && strongIds.length >= 2 ? strongIds : []
  const opens = accepted && ambiguousIds.length === 0
  return { baselineAccepted: baseline, accepted: opens, id: opens ? best.id : null, best, ambiguousIds }
}

/** True when two sender photos show the same postcard (one postcard per recipient). */
export function sameCard(cv: Cv, candidate: Features, earlier: Features, vocabulary: Vocabulary): boolean {
  if (featureCount(candidate) < 4 || featureCount(earlier) < 4) return false
  return match(cv, candidate, [reference('earlier', earlier, vocabulary)], vocabulary, 1).accepted
}
