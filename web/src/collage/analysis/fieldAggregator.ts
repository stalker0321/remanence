import { GRID_SIZE, type AnalysisFace, type AnalysisObject, type AnalysisProtectedRegion, type CollageAnalysis, type NormBox } from './cropSolver'

/**
 * Port of Android `FieldAggregator`: the frozen `import-field-3.3` field
 * assembly (`runtime/tools/legacy_features.py:74-99`) from the pixel maps and
 * the detector outputs to the `CollageAnalysis` the crop solver reads.
 * Everything accumulates in float64; the six output maps are packed like
 * `np.round(a, 4)` (half-even).
 */
export interface ImportFieldPrimitives {
  sourceWidth: number
  sourceHeight: number
  faces: AnalysisFace[]
  objects: AnalysisObject[]
  saliency: Float64Array
  edgeEnergy: Float64Array
  contrast: Float64Array
  structureField: Float64Array
  /** Contour-order, clamped structure-tip boxes. */
  tipBoxes: NormBox[]
}

export function aggregateFields(p: ImportFieldPrimitives): CollageAnalysis {
  const grid = GRID_SIZE
  const cells = grid * grid
  if (!(p.sourceWidth > 0 && p.sourceHeight > 0)) throw new Error('source dimensions must be positive')
  for (const values of [p.saliency, p.edgeEnergy, p.contrast, p.structureField]) {
    if (values.length !== cells || !values.every(Number.isFinite)) throw new Error('field maps must be 48x48 and finite')
  }
  const center = centerField(grid)
  const base = new Float64Array(cells)
  for (let c = 0; c < cells; c++) {
    base[c] = 0.015 + 0.34 * p.saliency[c]! + 0.25 * p.edgeEnergy[c]! + 0.12 * p.contrast[c]! + 0.12 * p.structureField[c]! + 0.025 * center[c]!
  }
  const objectField = new Float64Array(cells)
  const faceField = new Float64Array(cells)
  const regions: AnalysisProtectedRegion[] = []
  for (const tip of p.tipBoxes) regions.push({ kind: 'structure-tip', box: tip, confidence: 0.4, critical: false })
  for (const object of p.objects) {
    const g = gaussianField(object.box, 0.06, grid)
    for (let c = 0; c < cells; c++) objectField[c] = Math.max(objectField[c]!, g[c]! * object.confidence)
    regions.push({ kind: 'object', box: object.box, confidence: object.confidence, critical: false })
  }
  for (const face of p.faces) {
    const g = gaussianField(face.box, 0.25, grid)
    for (let c = 0; c < cells; c++) faceField[c] = Math.max(faceField[c]!, g[c]! * face.confidence)
    regions.push({ kind: 'face', box: face.box, confidence: face.confidence, critical: false })
    const b0 = Math.max(0, face.box.x - face.box.width * 0.7)
    const b1 = Math.max(0, face.box.y - face.box.height * 0.5)
    const context = {
      x: b0,
      y: b1,
      width: Math.min(1, face.box.x + face.box.width * 1.7) - b0,
      height: Math.min(1, face.box.y + face.box.height * 3.2) - b1,
    }
    const gb = gaussianField(context, 0.05, grid)
    for (let c = 0; c < cells; c++) base[c] = base[c]! + gb[c]! * 0.08
    regions.push({ kind: 'face-context', box: context, confidence: 0.3, critical: false })
  }
  const tipIndices = regions
    .map((region, index) => ({ region, index }))
    .filter(({ region }) => region.kind === 'structure-tip' && region.box.x > 0.08 && region.box.x < 0.9)
    .map(({ index }) => index)
  // Stable ascending box.y.
  const ordered = tipIndices
    .map((index, order) => ({ index, order }))
    .sort((a, b) => regions[a.index]!.box.y - regions[b.index]!.box.y || a.order - b.order)
    .map(({ index }) => index)
  const personFocus = p.faces.length > 5 || p.faces.some((face) => face.box.width * face.box.height > 0.005)
  for (const index of ordered.slice(0, 2)) regions[index] = { ...regions[index]!, critical: !personFocus }
  const packed = (values: (c: number) => number) => Float64Array.from({ length: cells }, (_, c) => round4(values(c)))
  return {
    width: p.sourceWidth,
    height: p.sourceHeight,
    faces: p.faces.slice(),
    objects: p.objects.slice(),
    protectedRegions: regions,
    importance: packed((c) => base[c]! + 0.65 * objectField[c]! + 1.2 * faceField[c]!),
    baselineField: packed((c) => base[c]! + 1.2 * faceField[c]!),
    objectField: packed((c) => objectField[c]!),
    saliency: packed((c) => p.saliency[c]!),
    edgeEnergy: packed((c) => p.edgeEnergy[c]!),
    structureField: packed((c) => p.structureField[c]!),
    gridSize: grid,
  }
}

export function gaussianField(box: NormBox, expand: number, grid: number): Float64Array {
  const out = new Float64Array(grid * grid)
  const sigmaX = Math.max(box.width * (0.5 + expand), 0.025)
  const sigmaY = Math.max(box.height * (0.5 + expand), 0.025)
  const cx = box.x + box.width / 2
  const cy = box.y + box.height / 2
  for (let j = 0; j < grid; j++) {
    const yy = j / grid + 0.5 / grid
    for (let i = 0; i < grid; i++) {
      const xx = i / grid + 0.5 / grid
      const ex = (xx - cx) / sigmaX
      const ey = (yy - cy) / sigmaY
      out[j * grid + i] = Math.exp(-0.5 * ex * ex - 0.5 * ey * ey)
    }
  }
  return out
}

function centerField(grid: number): Float64Array {
  const out = new Float64Array(grid * grid)
  for (let j = 0; j < grid; j++) {
    const yy = j / grid + 0.5 / grid
    for (let i = 0; i < grid; i++) {
      const xx = i / grid + 0.5 / grid
      out[j * grid + i] = Math.exp(-((xx - 0.5) * (xx - 0.5) + (yy - 0.5) * (yy - 0.5)) / 0.18)
    }
  }
  return out
}

/** `np.round(v, 4)`: scale, round half to even, descale. */
export function round4(value: number): number {
  const scaled = value * 10_000
  const floor = Math.floor(scaled)
  const diff = scaled - floor
  const even = diff === 0.5 ? (floor % 2 === 0 ? floor : floor + 1) : Math.round(scaled)
  return even / 10_000
}
