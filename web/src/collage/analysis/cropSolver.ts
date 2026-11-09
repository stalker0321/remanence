/**
 * Deterministic, model-independent port of Android `Envelope50CropSolver`
 * (the freeze V5 crop stage: `runtime/crop.js` base window plus the
 * `envelope-50` shift, checked with `runtime/safety.js`). No OpenCV and no
 * inference: it reads a finished `CollageAnalysis`.
 */

/** Normalized source window in [0,1]. */
export interface NormalizedCrop { x: number; y: number; width: number; height: number }

/** Normalized box in [0,1]. */
export interface NormBox { x: number; y: number; width: number; height: number }

export interface AnalysisFace { box: NormBox; confidence: number }
export interface AnalysisObject { box: NormBox; confidence: number; classId: number }
export interface AnalysisProtectedRegion { kind: string; box: NormBox; confidence: number; critical: boolean }

export const GRID_SIZE = 48

/** The normalized `import-field-3.3` analysis of one photo: detections plus six 48×48 fields. */
export interface CollageAnalysis {
  width: number
  height: number
  faces: AnalysisFace[]
  objects: AnalysisObject[]
  protectedRegions: AnalysisProtectedRegion[]
  importance: Float64Array
  baselineField: Float64Array
  objectField: Float64Array
  saliency: Float64Array
  edgeEnergy: Float64Array
  structureField: Float64Array
  gridSize: number
}

export type CropRejectReason =
  | 'LESS_THAN_25_PERCENT_REMAINS'
  | 'FACE_CROSSES_CROP_OR_MASK'
  | 'CLOSE_GROUP_LOSES_CONTEXT'
  | 'OBJECT_LOSES_SUPPORTING_CONTEXT'
  | 'SMALL_ISOLATED_PERSON_CROSSES'
  | 'CONTOUR_ENDPOINT_SEVERED'
  | 'PROTECTION_FIELD_LOW'
  | 'STRUCTURAL_EVIDENCE_LOW'
  | 'SALIENT_DETAIL_LOW'
  | 'IMPORTANT_REGION_DISAPPEARS'
  | 'RESOLUTION_TOO_LOW'
  | 'NO_CROP_CANDIDATE'

export type CropSolveResult = { kind: 'solved'; crop: NormalizedCrop } | { kind: 'rejected'; reason: CropRejectReason }

/** Frozen V4 base crop plus the two fields the assignment cost model reads. */
export interface BaseCropMetrics { rect: NormBox; importance: number; retained: number }

/** Strict preserves the frozen oracle. Balanced is strict first, then an aesthetic fallback. */
export type CropAdmissionPolicy = 'STRICT' | 'BALANCED'

export interface CropSlot { width: number; height: number; shape?: string; zooms?: number[] }

/** Quantize a normalized crop to the sealed 0..10000 crop window. */
export function toCropWindow(crop: NormalizedCrop): { x: number; y: number; width: number; height: number } {
  const quantize = (value: number) => Math.min(10_000, Math.max(0, javaRound(value * 10_000)))
  const left = quantize(crop.x)
  const top = quantize(crop.y)
  let right = quantize(crop.width)
  let bottom = quantize(crop.height)
  if (left + right > 10_000) right = 10_000 - left
  if (top + bottom > 10_000) bottom = 10_000 - top
  return { x: left, y: top, width: right, height: bottom }
}

/** Java `Math.round`: half rounds up toward positive infinity. */
function javaRound(value: number): number {
  return Math.floor(value + 0.5)
}

export function validAnalysis(analysis: CollageAnalysis): boolean {
  const cells = analysis.gridSize * analysis.gridSize
  return analysis.width > 0 && analysis.height > 0 && analysis.gridSize > 0 &&
    fieldsOf(analysis).every(([, values]) => values.length === cells)
}

function fieldsOf(analysis: CollageAnalysis): Array<[MapName, Float64Array]> {
  return [
    ['importance', analysis.importance],
    ['edgeEnergy', analysis.edgeEnergy],
    ['saliency', analysis.saliency],
    ['structureField', analysis.structureField],
    ['baselineField', analysis.baselineField],
    ['objectField', analysis.objectField],
  ]
}

export function solveCrop(analysis: CollageAnalysis, slot: CropSlot, policy: CropAdmissionPolicy = 'STRICT'): NormalizedCrop | null {
  const result = solveCropDetailed(analysis, slot, policy)
  return result.kind === 'solved' ? result.crop : null
}

export function baseMetrics(analysis: CollageAnalysis, slot: CropSlot, policy: CropAdmissionPolicy = 'STRICT'): BaseCropMetrics | null {
  const s = normalSlot(slot)
  if (!validInputs(analysis, s)) return null
  const [base] = selectBase(analysis, mapsFor(analysis), s, policy)
  return base.kind === 'safe' ? { rect: base.box, importance: base.coverage.importance, retained: base.retained } : null
}

/**
 * Detailed solve. On failure the reason is the dominant frozen rejection:
 * every zoom's reject counts summed by reason, the most frequent first,
 * equal counts keeping the first encountered.
 */
export function solveCropDetailed(analysis: CollageAnalysis, slot: CropSlot, policy: CropAdmissionPolicy = 'STRICT'): CropSolveResult {
  const s = normalSlot(slot)
  if (!validInputs(analysis, s)) return { kind: 'rejected', reason: 'NO_CROP_CANDIDATE' }
  const maps = mapsFor(analysis)
  const [base, effective] = selectBase(analysis, maps, s, policy)
  if (base.kind === 'rejected') return { kind: 'rejected', reason: base.reason }
  return { kind: 'solved', crop: envelope50(analysis, maps, s, base.box, effective) }
}

interface Slot { width: number; height: number; shape: string; zooms: number[] }

function normalSlot(slot: CropSlot): Slot {
  return { width: slot.width, height: slot.height, shape: slot.shape ?? 'rect', zooms: slot.zooms ?? [1] }
}

type BaseOutcome = { kind: 'safe'; box: NormBox; coverage: Coverage; retained: number } | { kind: 'rejected'; reason: CropRejectReason }

function selectBase(analysis: CollageAnalysis, maps: AnalysisMaps, slot: Slot, policy: CropAdmissionPolicy): [BaseOutcome, CropAdmissionPolicy] {
  const strict = baseCrop(analysis, maps, slot, 'STRICT')
  if (strict.kind === 'safe' || policy === 'STRICT') return [strict, 'STRICT']
  return [baseCrop(analysis, maps, slot, 'BALANCED'), 'BALANCED']
}

function finiteBox(box: NormBox): boolean {
  return [box.x, box.y, box.width, box.height].every(Number.isFinite) && box.width > 0 && box.height > 0
}

function validBox(box: NormBox): boolean {
  return finiteBox(box) && box.x >= 0 && box.y >= 0 && box.x + box.width <= 1 + 1e-9 && box.y + box.height <= 1 + 1e-9
}

const SHAPES = new Set(['rect', 'ellipse', 'round'])

function validInputs(analysis: CollageAnalysis, slot: Slot): boolean {
  return validAnalysis(analysis) &&
    Number.isFinite(slot.width) && Number.isFinite(slot.height) && slot.width > 0 && slot.height > 0 &&
    SHAPES.has(slot.shape) &&
    slot.zooms.every((zoom) => Number.isFinite(zoom) && zoom >= 1) &&
    analysis.faces.every((face) => validBox(face.box) && Number.isFinite(face.confidence)) &&
    // Frozen context/contour fixtures include finite boxes extending beyond
    // the image. They remain aesthetic regions, not face/crop bounds.
    analysis.objects.every((object) => finiteBox(object.box) && Number.isFinite(object.confidence)) &&
    analysis.protectedRegions.every((region) => finiteBox(region.box) && Number.isFinite(region.confidence)) &&
    fieldsOf(analysis).every(([, field]) => field.every((value) => Number.isFinite(value) && value >= 0))
}

/** One-window diagnostic: the first frozen check `rect` fails, or null when it is safe. */
export function rejectionReason(analysis: CollageAnalysis, slot: CropSlot, rect: NormBox, shape = 'rect', policy: CropAdmissionPolicy = 'STRICT'): CropRejectReason | null {
  const s = normalSlot(slot)
  if (!validInputs(analysis, s) || !validBox(rect) || !SHAPES.has(shape)) return 'NO_CROP_CANDIDATE'
  const verdict = classifyWindow(analysis, mapsFor(analysis), s, rect, shape, policy)
  return verdict.kind === 'safe' ? null : verdict.reason
}

function baseCrop(analysis: CollageAnalysis, maps: AnalysisMaps, slot: Slot, policy: CropAdmissionPolicy): BaseOutcome {
  const shape = slot.shape
  const ratio = slot.width / slot.height
  const ar = analysis.width / analysis.height
  const bw = Math.min(1, ratio / ar)
  const bh = Math.min(1, ar / ratio)
  const reasons = new Map<CropRejectReason, number>()
  for (const zoom of slot.zooms.length === 0 ? [1] : slot.zooms) {
    const cw = bw / zoom
    const ch = bh / zoom
    const centers: Array<[number, number]> = [[0.5, 0.5], [1 / 3, 1 / 3], [2 / 3, 2 / 3], [1 / 3, 2 / 3], [2 / 3, 1 / 3]]
    for (const face of analysis.faces) centers.push([face.box.x + face.box.width / 2, face.box.y + face.box.height / 2])
    for (const object of analysis.objects) centers.push([object.box.x + object.box.width / 2, object.box.y + object.box.height / 2])
    const points: Array<[number, number]> = []
    for (let i = 0; i <= 16; i++) points.push([((1 - cw) * i) / 16, ((1 - ch) * i) / 16])
    for (const [cx, cy] of centers) points.push([clamp(cx - cw / 2, 0, 1 - cw), clamp(cy - ch / 2, 0, 1 - ch)])
    if (cw < 0.99 && ch < 0.99) {
      for (let y = 0; y <= 4; y++) for (let x = 0; x <= 4; x++) points.push([((1 - cw) * x) / 4, ((1 - ch) * y) / 4])
    }
    const seen = new Set<string>()
    let best: { box: NormBox; score: number; coverage: Coverage } | null = null
    for (const [x, y] of points) {
      const key = `${fixed4(x)}:${fixed4(y)}`
      if (seen.has(key)) continue
      seen.add(key)
      const rect = { x, y, width: cw, height: ch }
      const verdict = classifyWindow(analysis, maps, slot, rect, shape, policy)
      if (verdict.kind === 'rejected') {
        reasons.set(verdict.reason, (reasons.get(verdict.reason) ?? 0) + 1)
        continue
      }
      const score = verdict.coverage.importance * 0.52 +
        verdict.coverage.saliency * 0.18 +
        verdict.coverage.structure * 0.12 +
        verdict.objectKeep * 0.18 -
        0.012 * (Math.abs(x + cw / 2 - 0.5) + Math.abs(y + ch / 2 - 0.5)) +
        (policy === 'BALANCED' ? aestheticPreference(analysis, rect, shape, verdict.coverage) : 0)
      if (best === null || score > best.score) best = { box: rect, score, coverage: verdict.coverage }
    }
    if (best !== null) return { kind: 'safe', box: best.box, coverage: best.coverage, retained: best.box.width * best.box.height }
  }
  let dominant: CropRejectReason = 'NO_CROP_CANDIDATE'
  let most = -1
  for (const [reason, count] of reasons) {
    if (count > most) {
      most = count
      dominant = reason
    }
  }
  return { kind: 'rejected', reason: dominant }
}

/** Java `String.format("%.4f")`: half-up on the decimal value. */
function fixed4(value: number): string {
  const scaled = Math.round(Math.abs(value) * 10_000 + 1e-9)
  const sign = value < 0 && scaled !== 0 ? '-' : ''
  return `${sign}${Math.floor(scaled / 10_000)}.${String(scaled % 10_000).padStart(4, '0')}`
}

function envelope50(analysis: CollageAnalysis, maps: AnalysisMaps, slot: Slot, base: NormBox, policy: CropAdmissionPolicy): NormalizedCrop {
  const [anchorX, anchorY] = anchor(analysis)
  const targetX = clamp(anchorX - base.width / 2, 0, 1 - base.width)
  const targetY = clamp(anchorY - base.height / 2, 0, 1 - base.height)
  const x = base.x + (targetX - base.x) * ENVELOPE_STRENGTH
  const y = base.y + (targetY - base.y) * ENVELOPE_STRENGTH
  const shifted = { x, y, width: base.width, height: base.height }
  const safe = classifyWindow(analysis, maps, slot, shifted, slot.shape, policy).kind === 'safe'
  return safe ? shifted : { ...base }
}

function anchor(analysis: CollageAnalysis): [number, number] {
  const faces = analysis.faces.filter((face) => face.box.width * face.box.height > 0.003)
  const objects = analysis.objects.filter((object) =>
    object.confidence >= 0.6 && object.box.width * object.box.height > 0.04 && !CONTEXT_CLASS_IDS.has(object.classId))
  const boxes = faces.length > 0 ? faces.map((face) => face.box) : objects.map((object) => object.box)
  if (boxes.length === 0) return [0.5, 0.5]
  const left = Math.min(...boxes.map((box) => box.x))
  const top = Math.min(...boxes.map((box) => box.y))
  const right = Math.max(...boxes.map((box) => box.x + box.width))
  const bottom = Math.max(...boxes.map((box) => box.y + box.height))
  return [(left + right) / 2, (top + bottom) / 2]
}

type WindowVerdict = { kind: 'safe'; coverage: Coverage; objectKeep: number } | { kind: 'rejected'; reason: CropRejectReason }

/** One window: safe coverage, or the first frozen check it fails, in `runtime/crop.js` order. */
function classifyWindow(analysis: CollageAnalysis, maps: AnalysisMaps, slot: Slot, rect: NormBox, shape: string, policy: CropAdmissionPolicy): WindowVerdict {
  const reject = (reason: CropRejectReason): WindowVerdict => ({ kind: 'rejected', reason })
  if (!validBox(rect)) return reject('NO_CROP_CANDIDATE')
  const faces = analysis.faces
  if (rect.width * rect.height < 0.25) return reject('LESS_THAN_25_PERCENT_REMAINS')
  if (!faces.every((face) => inside(face.box, rect, shape, policy === 'STRICT' ? 0.12 : 0))) return reject('FACE_CROSSES_CROP_OR_MASK')
  const important = analysis.objects.filter((object) =>
    object.confidence >= 0.55 && object.box.width * object.box.height >= 0.015 && !isContextual(object))
  const keepOf = () => (important.length === 0 ? 1 : Math.min(...important.map((object) => regionCoverage(object.box, rect, shape))))
  if (policy === 'BALANCED') {
    const resolution = Math.min((analysis.width * rect.width) / slot.width, (analysis.height * rect.height) / slot.height)
    if (resolution < 1) return reject('RESOLUTION_TOO_LOW')
    return { kind: 'safe', coverage: coverage(analysis, maps, rect, shape), objectKeep: keepOf() }
  }
  const closeFaces = faces.filter((face) => face.box.width * face.box.height > 0.02)
  if (closeFaces.length >= 2) {
    const people = important.filter((object) => object.classId === 0 && object.confidence > 0.65 && object.box.width * object.box.height > 0.18)
    const loses = people.some((o) => {
      const overlap = Math.max(0, Math.min(o.box.x + o.box.width, rect.x + rect.width) - Math.max(o.box.x, rect.x))
      return overlap / o.box.width < 0.96 || rect.y > o.box.y + 0.05
    })
    if (loses) return reject('CLOSE_GROUP_LOSES_CONTEXT')
  }
  const objectKeep = keepOf()
  const destructive = important.some((o) => {
    const kept = regionCoverage(o.box, rect, shape)
    const threshold = o.classId === 0
      ? (o.box.width * o.box.height < 0.2 ? 0.83 : 0.62)
      : isBoundedObject(o) ? 0.82 : 0.67
    return kept < threshold
  })
  if (destructive) return reject('OBJECT_LOSES_SUPPORTING_CONTEXT')
  if (faces.length === 1 && important.some((o) =>
    o.classId === 0 && o.confidence >= 0.6 && o.box.width * o.box.height < 0.2 && !inside(o.box, rect, shape, 0.015))) {
    return reject('SMALL_ISOLATED_PERSON_CROSSES')
  }
  const tips = analysis.protectedRegions.filter((region) => region.kind === 'structure-tip' && region.critical)
  if (tips.some((tip) => regionCoverage(tip.box, rect, shape) < 0.65)) return reject('CONTOUR_ENDPOINT_SEVERED')
  const cover = coverage(analysis, maps, rect, shape)
  if (cover.importance < (shape === 'ellipse' ? 0.57 : 0.6)) return reject('PROTECTION_FIELD_LOW')
  if (cover.edge < 0.48) return reject('STRUCTURAL_EVIDENCE_LOW')
  if (cover.saliency < 0.4) return reject('SALIENT_DETAIL_LOW')
  for (const half of HALVES) {
    const mass = coverage(analysis, maps, half, 'rect').baseline
    if (mass < 0.18) continue
    const ix = Math.max(rect.x, half.x)
    const iy = Math.max(rect.y, half.y)
    const iw = Math.max(0, Math.min(rect.x + rect.width, half.x + half.width) - ix)
    const ih = Math.max(0, Math.min(rect.y + rect.height, half.y + half.height) - iy)
    const retainedMass = iw !== 0 && ih !== 0 ? coverage(analysis, maps, { x: ix, y: iy, width: iw, height: ih }, 'rect').baseline : 0
    if (retainedMass / mass < 0.32) return reject('IMPORTANT_REGION_DISAPPEARS')
  }
  const resolution = Math.min((analysis.width * rect.width) / slot.width, (analysis.height * rect.height) / slot.height)
  if (resolution < 1) return reject('RESOLUTION_TOO_LOW')
  return { kind: 'safe', coverage: cover, objectKeep }
}

/** Relaxed aesthetics still prefer structure and context, but never veto a safe window. */
function aestheticPreference(analysis: CollageAnalysis, rect: NormBox, shape: string, cover: Coverage): number {
  const tips = analysis.protectedRegions.filter((region) => region.kind === 'structure-tip' && region.critical)
  const tipKeep = tips.length === 0 ? 1 : tips.map((tip) => regionCoverage(tip.box, rect, shape)).reduce((a, b) => a + b, 0) / tips.length
  return cover.edge * 0.06 + cover.baseline * 0.04 + tipKeep * 0.06
}

function isContextual(object: AnalysisObject): boolean {
  return CONTEXT_CLASS_IDS.has(object.classId) && object.box.width * object.box.height > 0.45
}

function isBoundedObject(object: AnalysisObject): boolean {
  return object.box.width * object.box.height > 0.25 && BOUNDED_OBJECT_CLASS_IDS.has(object.classId)
}

function inside(box: NormBox, rect: NormBox, shape: string, pad: number): boolean {
  const corners: Array<[number, number]> = [
    [box.x - box.width * pad, box.y - box.height * pad],
    [box.x + box.width * (1 + pad), box.y - box.height * pad],
    [box.x + box.width * (1 + pad), box.y + box.height * (1 + pad)],
    [box.x - box.width * pad, box.y + box.height * (1 + pad)],
  ]
  return corners.every(([rawX, rawY]) => {
    const x = clamp(rawX, 0, 1)
    const y = clamp(rawY, 0, 1)
    return x >= rect.x - 1e-5 && x <= rect.x + rect.width + 1e-5 &&
      y >= rect.y - 1e-5 && y <= rect.y + rect.height + 1e-5 &&
      maskPoint((x - rect.x) / rect.width, (y - rect.y) / rect.height, shape)
  })
}

function regionCoverage(box: NormBox, rect: NormBox, shape: string): number {
  if (shape === 'rect') {
    const w = Math.max(0, Math.min(box.x + box.width, rect.x + rect.width) - Math.max(box.x, rect.x))
    const h = Math.max(0, Math.min(box.y + box.height, rect.y + rect.height) - Math.max(box.y, rect.y))
    return (w * h) / (box.width * box.height)
  }
  let count = 0
  for (let y = 0; y < 12; y++) for (let x = 0; x < 12; x++) {
    const px = box.x + (box.width * (x + 0.5)) / 12
    const py = box.y + (box.height * (y + 0.5)) / 12
    if (px >= rect.x && px <= rect.x + rect.width && py >= rect.y && py <= rect.y + rect.height &&
      maskPoint((px - rect.x) / rect.width, (py - rect.y) / rect.height, shape)) count++
  }
  return count / 144
}

interface Coverage { importance: number; edge: number; saliency: number; structure: number; baseline: number; objects: number; visibleArea: number }

function coverage(analysis: CollageAnalysis, maps: AnalysisMaps, rect: NormBox, shape: string): Coverage {
  const n = analysis.gridSize
  const x0 = clamp(Math.ceil(rect.x * n - 0.5), 0, n)
  const y0 = clamp(Math.ceil(rect.y * n - 0.5), 0, n)
  const x1 = clamp(Math.floor((rect.x + rect.width) * n - 0.5) + 1, 0, n)
  const y1 = clamp(Math.floor((rect.y + rect.height) * n - 0.5) + 1, 0, n)
  let visible = 0
  const sums = new Map<MapName, number>()
  if (shape === 'rect') {
    const s = n + 1
    for (const [name, map] of maps) {
      sums.set(name, map.total !== 0
        ? (map.integral[y1 * s + x1]! - map.integral[y0 * s + x1]! - map.integral[y1 * s + x0]! + map.integral[y0 * s + x0]!) / map.total
        : 1)
    }
    visible = (x1 - x0) * (y1 - y0)
  } else {
    for (const [name] of maps) sums.set(name, 0)
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
      if (maskPoint(((x + 0.5) / n - rect.x) / rect.width, ((y + 0.5) / n - rect.y) / rect.height, shape)) {
        visible++
        for (const [name, map] of maps) sums.set(name, sums.get(name)! + map.values[y * n + x]!)
      }
    }
    for (const [name, map] of maps) sums.set(name, map.total !== 0 ? sums.get(name)! / map.total : 1)
  }
  return {
    importance: sums.get('importance')!,
    edge: sums.get('edgeEnergy')!,
    saliency: sums.get('saliency')!,
    structure: sums.get('structureField')!,
    baseline: sums.get('baselineField')!,
    objects: sums.get('objectField')!,
    visibleArea: visible / (n * n),
  }
}

function maskPoint(x: number, y: number, shape: string): boolean {
  if (shape === 'ellipse') return ((x - 0.5) / 0.5) ** 2 + ((y - 0.5) / 0.5) ** 2 <= 1
  if (shape === 'round') {
    const r = 0.12
    const cx = clamp(x, r, 1 - r)
    const cy = clamp(y, r, 1 - r)
    return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r + 1e-6
  }
  return true
}

type MapName = 'importance' | 'edgeEnergy' | 'saliency' | 'structureField' | 'baselineField' | 'objectField'
interface MapData { values: Float64Array; integral: Float64Array; total: number }
type AnalysisMaps = Array<[MapName, MapData]>

/** The six integral maps are built once per analysis object. */
const mapsCache = new WeakMap<CollageAnalysis, AnalysisMaps>()

function mapsFor(analysis: CollageAnalysis): AnalysisMaps {
  const cached = mapsCache.get(analysis)
  if (cached) return cached
  const built = fieldsOf(analysis).map(([name, values]) => [name, mapData(analysis.gridSize, values)] as [MapName, MapData])
  mapsCache.set(analysis, built)
  return built
}

function mapData(gridSize: number, values: Float64Array): MapData {
  const s = gridSize + 1
  const integral = new Float64Array(s * s)
  let total = 0
  for (let y = 0; y < gridSize; y++) for (let x = 0; x < gridSize; x++) {
    const v = values[y * gridSize + x]!
    total += v
    integral[(y + 1) * s + x + 1] = v + integral[y * s + x + 1]! + integral[(y + 1) * s + x]! - integral[y * s + x]!
  }
  return { values, integral, total }
}

function clamp(value: number, low: number, high: number): number {
  return Math.max(low, Math.min(high, value))
}

const ENVELOPE_STRENGTH = 0.5
const CONTEXT_CLASS_IDS = new Set([56, 57, 60])
const BOUNDED_OBJECT_CLASS_IDS = new Set([39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 73, 74, 75])
const HALVES: NormBox[] = [
  { x: 0, y: 0, width: 1, height: 0.5 },
  { x: 0, y: 0.5, width: 1, height: 0.5 },
  { x: 0, y: 0, width: 0.5, height: 1 },
  { x: 0.5, y: 0, width: 0.5, height: 1 },
]
