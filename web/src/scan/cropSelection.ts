/**
 * T316 — pure crop selection for the genuine sender localization path. Mirrors
 * the accepted Android `PostcardGuideGeometry` / `PostcardCropSelector` /
 * `CandidateRanker`: credible contour filter, profile-weighted ranking with
 * guide proximity, and the labelled bounded guide fallback when no contour is
 * credible. No native calls; all inputs are plain data.
 */

import { CORNER_EPSILON, signedArea, validateQuad, type Point2D } from './cornerGeometry'

export const GUIDE_LANDSCAPE_ASPECT = 1.5
export const GUIDE_PORTRAIT_ASPECT = 2 / 3
export const GUIDE_MAX_WIDTH_FRACTION = 0.86
export const GUIDE_MAX_HEIGHT_FRACTION = 0.78

export const MIN_CARD_AREA_RATIO = 0.35
export const MIN_RECTANGULARITY = 0.8
export const ASPECT_RATIO_MIN = 1.15
export const ASPECT_RATIO_MAX = 2.2
export const MIN_CONTOUR_CONFIDENCE = 0.7

export const CONFIDENCE_AREA_WEIGHT = 0.4
export const CONFIDENCE_RECTANGULARITY_WEIGHT = 0.3
export const CONFIDENCE_EDGE_SUPPORT_WEIGHT = 0.2
export const CONFIDENCE_GUIDE_PROXIMITY_WEIGHT = 0.1

const MAX_GUIDE_DISTANCE_FRACTION = 0.25

/** One detected quadrilateral candidate (corners canonically ordered). */
export interface CropCandidate {
  readonly corners: readonly [Point2D, Point2D, Point2D, Point2D]
  readonly areaRatio: number
  readonly rectangularity: number
  readonly edgeSupport?: number
}

export interface RankedCrop {
  readonly candidate: CropCandidate
  readonly confidence: number
}

export type ProposalSource = 'LEGACY_CONTOUR' | 'GUIDE_FALLBACK'

export interface CropSelection {
  readonly candidate: CropCandidate
  readonly source: ProposalSource
  readonly confidence: number | null
  readonly usedGuideFallback: boolean
}

/** Frame-independent normalized guide rectangle (shared overlay/crop math). */
export function normalizedGuideRect(frameWidth: number, frameHeight: number): {
  left: number
  top: number
  right: number
  bottom: number
} {
  if (!(frameWidth > 0) || !(frameHeight > 0)) throw new Error('invalid frame dimensions')
  const guideAspect = frameWidth >= frameHeight ? GUIDE_LANDSCAPE_ASPECT : GUIDE_PORTRAIT_ASPECT
  const maxWidth = frameWidth * GUIDE_MAX_WIDTH_FRACTION
  const maxHeight = frameHeight * GUIDE_MAX_HEIGHT_FRACTION
  let width = maxWidth
  let height = width / guideAspect
  if (height > maxHeight) {
    height = maxHeight
    width = height * guideAspect
  }
  return {
    left: (frameWidth - width) / 2 / frameWidth,
    top: (frameHeight - height) / 2 / frameHeight,
    right: (frameWidth + width) / 2 / frameWidth,
    bottom: (frameHeight + height) / 2 / frameHeight,
  }
}

/** Guide overlay center in image coordinates (for proximity ranking). */
export function guideCenter(frameWidth: number, frameHeight: number): Point2D {
  const rect = normalizedGuideRect(frameWidth, frameHeight)
  return {
    x: ((rect.left + rect.right) / 2) * frameWidth,
    y: ((rect.top + rect.bottom) / 2) * frameHeight,
  }
}

/** Bounded centered guide corners in image coordinates (ordered TL/TR/BR/BL). */
export function guideCorners(frameWidth: number, frameHeight: number): [Point2D, Point2D, Point2D, Point2D] {
  const rect = normalizedGuideRect(frameWidth, frameHeight)
  return [
    { x: rect.left * frameWidth, y: rect.top * frameHeight },
    { x: rect.right * frameWidth, y: rect.top * frameHeight },
    { x: rect.right * frameWidth, y: rect.bottom * frameHeight },
    { x: rect.left * frameWidth, y: rect.bottom * frameHeight },
  ]
}

/** Credibility gate: thresholds, in-frame, valid quad, aspect range. */
export function isCredible(candidate: CropCandidate, frameWidth: number, frameHeight: number): boolean {
  if (!Number.isFinite(candidate.areaRatio) || !Number.isFinite(candidate.rectangularity)) return false
  if (candidate.areaRatio < MIN_CARD_AREA_RATIO) return false
  if (candidate.rectangularity < MIN_RECTANGULARITY) return false
  if (candidate.corners.length !== 4) return false
  if (candidate.corners.some((c) => c.x < 0 || c.x > frameWidth || c.y < 0 || c.y > frameHeight)) return false
  if (validateQuad(candidate.corners).valid !== true) return false
  const edges = [0, 1, 2, 3].map((i) => {
    const a = candidate.corners[i]!
    const b = candidate.corners[(i + 1) % 4]!
    return Math.hypot(b.x - a.x, b.y - a.y)
  })
  const horizontal = (edges[0]! + edges[2]!) / 2
  const vertical = (edges[1]! + edges[3]!) / 2
  const longEdge = Math.max(horizontal, vertical)
  const shortEdge = Math.min(horizontal, vertical)
  if (!Number.isFinite(longEdge) || !Number.isFinite(shortEdge) || shortEdge <= 0) return false
  const aspect = longEdge / shortEdge
  return aspect >= ASPECT_RATIO_MIN && aspect <= ASPECT_RATIO_MAX
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value))
}

function guideProximity(candidate: CropCandidate, guide: Point2D, frameDiagonal: number): number {
  const cx = (candidate.corners[0]!.x + candidate.corners[1]!.x + candidate.corners[2]!.x + candidate.corners[3]!.x) / 4
  const cy = (candidate.corners[0]!.y + candidate.corners[1]!.y + candidate.corners[2]!.y + candidate.corners[3]!.y) / 4
  const distance = Math.hypot(cx - guide.x, cy - guide.y)
  return clamp01(1 - distance / (frameDiagonal * MAX_GUIDE_DISTANCE_FRACTION))
}

/**
 * Profile-weighted ranking, deterministic descending confidence. The guide is
 * always present in this producer, so the denominator is 1 (kept explicit to
 * match the reference renormalization).
 */
export function rankCandidates(
  candidates: readonly CropCandidate[],
  frameWidth: number,
  frameHeight: number,
): RankedCrop[] {
  if (!(frameWidth > 0) || !(frameHeight > 0)) throw new Error('invalid frame dimensions')
  const frameDiagonal = Math.hypot(frameWidth, frameHeight)
  const guide = guideCenter(frameWidth, frameHeight)
  return candidates
    .map((candidate) => {
      const areaScore = Math.min(candidate.areaRatio / MIN_CARD_AREA_RATIO, 1)
      const rectScore = clamp01((candidate.rectangularity - 0.7) / 0.3)
      const edge = candidate.edgeSupport == null ? candidate.rectangularity : Math.min(1, Math.max(0, candidate.edgeSupport))
      const guideScore = guideProximity(candidate, guide, frameDiagonal)
      const confidence = clamp01(
        CONFIDENCE_AREA_WEIGHT * areaScore +
          CONFIDENCE_RECTANGULARITY_WEIGHT * rectScore +
          CONFIDENCE_EDGE_SUPPORT_WEIGHT * clamp01(edge) +
          CONFIDENCE_GUIDE_PROXIMITY_WEIGHT * guideScore,
      )
      return { candidate, confidence }
    })
    .sort((a, b) => b.confidence - a.confidence)
}

/** Best credible contour at/above confidence, else the labelled guide fallback. */
export function selectCrop(
  candidates: readonly CropCandidate[],
  frameWidth: number,
  frameHeight: number,
): CropSelection {
  if (!(frameWidth > 0) || !(frameHeight > 0)) throw new Error('invalid frame dimensions')
  const credible = candidates.filter((c) => isCredible(c, frameWidth, frameHeight))
  const selected = rankCandidates(credible, frameWidth, frameHeight).find((r) => r.confidence >= MIN_CONTOUR_CONFIDENCE)
  if (selected) {
    return { candidate: selected.candidate, source: 'LEGACY_CONTOUR', confidence: selected.confidence, usedGuideFallback: false }
  }
  const corners = guideCorners(frameWidth, frameHeight)
  const areaRatio = Math.abs(signedArea(corners)) / (frameWidth * frameHeight)
  return {
    candidate: { corners, areaRatio, rectangularity: 1 },
    source: 'GUIDE_FALLBACK',
    confidence: null,
    usedGuideFallback: true,
  }
}

export { CORNER_EPSILON }
