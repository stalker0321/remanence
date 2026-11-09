import { baseMetrics, solveCropDetailed, type CollageAnalysis, type CropAdmissionPolicy, type CropRejectReason } from './cropSolver'
import type { Plan } from '../planGrammars'

/**
 * Port of Android `AndroidCollageCropMatrixSource` and the `FrozenResolve`
 * assignment walk: which photo may sit in which tile, at what cost, and the
 * cheapest complete matching of photos to tiles (`grammars.js:129-155`).
 */

export interface MatrixRequest {
  slotWidth: number
  slotHeight: number
  shape: string
  zooms: number[]
  assignmentRole?: string | undefined
}

export interface MatrixCell {
  feasible: boolean
  reason?: CropRejectReason
  cost?: number
}

/**
 * One (photo, tile) cell: feasible when the frozen base crop is safe and the
 * role and face-size gates pass; cost is
 * `|log(sourceAspect/slotAspect)|*0.14 + (1-importance)*0.4 + roleCost + facePixelPenalty`.
 * `anyObject` is dataset-wide: whether any photo is an object photo.
 */
export function matrixCell(analysis: CollageAnalysis, anyObject: boolean, request: MatrixRequest, policy: CropAdmissionPolicy = 'STRICT'): MatrixCell {
  const slot = { width: request.slotWidth, height: request.slotHeight, shape: request.shape, zooms: request.zooms }
  const metrics = baseMetrics(analysis, slot, policy)
  if (metrics === null) {
    const solved = solveCropDetailed(analysis, slot, policy)
    return { feasible: false, reason: solved.kind === 'rejected' ? solved.reason : 'NO_CROP_CANDIDATE' }
  }
  const faces = analysis.faces.filter((face) => face.box.width * face.box.height > 0.003)
  const hasFace = faces.length > 0
  if (request.assignmentRole === 'human-anchor' && !hasFace) return { feasible: false }
  if (request.assignmentRole === 'object-anchor' && anyObject && !isObjectPhoto(analysis)) return { feasible: false }
  const facePixels = faces.map((face) => Math.min(
    (face.box.width / metrics.rect.width) * request.slotWidth,
    (face.box.height / metrics.rect.height) * request.slotHeight,
  ))
  if (facePixels.some((pixels) => pixels < FACE_PIXEL_FLOOR)) return { feasible: false }
  let roleCost = 0
  if (request.assignmentRole === 'object-anchor' && hasImportantObject(analysis)) roleCost = -0.15
  if (request.assignmentRole === 'vertical-anchor' && analysis.width / analysis.height < 1) roleCost = -0.1
  const fit = Math.abs(Math.log((analysis.width / analysis.height) / (request.slotWidth / request.slotHeight))) * 0.14
  const facePenalty = hasFace ? Math.max(0, 30 - Math.min(...facePixels)) * 0.01 : 0
  return { feasible: true, cost: fit + (1 - metrics.importance) * 0.4 + roleCost + facePenalty }
}

/** A photo with a clear object and no face. */
export function isObjectPhoto(analysis: CollageAnalysis | null): boolean {
  if (analysis === null) return false
  if (analysis.faces.some((face) => face.box.width * face.box.height > 0.003)) return false
  return hasImportantObject(analysis)
}

/** The site's g22 gate, the same rule the matrix uses (`w*h > .003`). */
export function hasProminentFace(analysis: CollageAnalysis): boolean {
  return analysis.faces.some((face) => face.box.width * face.box.height > 0.003)
}

function hasImportantObject(analysis: CollageAnalysis): boolean {
  return analysis.objects.some((object) =>
    !MATRIX_CONTEXT_CLASS_IDS.has(object.classId) && object.confidence > 0.48 && object.box.width * object.box.height > 0.06)
}

const MATRIX_CONTEXT_CLASS_IDS = new Set([0, 56, 57, 60])
const FACE_PIXEL_FLOOR = 11

export const ASSIGNMENT_FAILURE = 'No complete safe source-to-slot matching at readable photo sizes'

export interface Assignment {
  failure: string | null
  mode: 'source-order-control' | 'complete-safe-matching' | null
  /** Photo id per tile, in tile order. */
  compositionOrder: string[]
  feasibleAssignments: number
  cost: number | null
  earlyReadability: boolean
}

export function assignmentOk(assignment: Assignment): boolean {
  return assignment.failure === null && !assignment.earlyReadability
}

/**
 * Frozen `assignPhotos`: `matrix[tile][photo]` is the cell cost or null; the
 * cheapest complete matching wins (the first found on a tie within 1e-9).
 * `lockOrder` keeps the authored order.
 */
export function assign(plan: Pick<Plan, 'tiles'>, photoIds: string[], matrix: Array<Array<number | null>>, lockOrder: boolean): Assignment {
  if (plan.tiles.some((tile) => tile.rect.width < 84 || tile.rect.height < 84 || tile.rect.width * tile.rect.height < 9500)) {
    return { failure: null, mode: null, compositionOrder: [], feasibleAssignments: 0, cost: null, earlyReadability: true }
  }
  const n = photoIds.length
  let bestPath: number[] | null = null
  let bestCost = 0
  let visits = 0
  const path: number[] = []
  const step = (i: number, used: number, cost: number): void => {
    if (i === n) {
      visits++
      if (bestPath === null || cost < bestCost - 1e-9) {
        bestPath = path.slice()
        bestCost = cost
      }
      return
    }
    for (let j = 0; j < n; j++) {
      if ((used & (1 << j)) !== 0) continue
      if (lockOrder && i !== j) continue
      const cell = matrix[i]?.[j]
      if (cell === null || cell === undefined) continue
      path.push(j)
      step(i + 1, used | (1 << j), cost + cell)
      path.pop()
    }
  }
  step(0, 0, 0)
  const solved = bestPath as number[] | null
  if (solved === null) {
    return { failure: ASSIGNMENT_FAILURE, mode: null, compositionOrder: [], feasibleAssignments: visits, cost: null, earlyReadability: false }
  }
  return {
    failure: null,
    mode: lockOrder ? 'source-order-control' : 'complete-safe-matching',
    compositionOrder: solved.map((index) => photoIds[index]!),
    feasibleAssignments: visits,
    cost: Math.round(bestCost * 10_000) / 10_000,
    earlyReadability: false,
  }
}
