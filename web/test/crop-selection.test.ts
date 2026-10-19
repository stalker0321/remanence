import { describe, expect, it } from 'vitest'
import {
  GUIDE_MAX_WIDTH_FRACTION,
  isCredible,
  normalizedGuideRect,
  rankCandidates,
  selectCrop,
  type CropCandidate,
} from '../src/scan/cropSelection'

function rect(w: number, h: number, x = 0, y = 0): CropCandidate {
  return {
    corners: [
      { x, y },
      { x: x + w, y },
      { x: x + w, y: y + h },
      { x, y: y + h },
    ],
    areaRatio: (w * h) / (1600 * 1200),
    rectangularity: 0.95,
  }
}

describe('T316 crop selection', () => {
  it('fits a 3:2 guide in landscape and 2:3 in portrait within bounded fractions', () => {
    const land = normalizedGuideRect(1600, 1200)
    const lw = (land.right - land.left) * 1600
    const lh = (land.bottom - land.top) * 1200
    expect(lw / lh).toBeCloseTo(1.5, 12)
    expect(lw / 1600).toBeLessThanOrEqual(GUIDE_MAX_WIDTH_FRACTION + 1e-12)
    expect(lh / 1200).toBeLessThanOrEqual(0.78 + 1e-12)
    const port = normalizedGuideRect(1200, 1600)
    const pw = (port.right - port.left) * 1200
    const ph = (port.bottom - port.top) * 1600
    expect(pw / ph).toBeCloseTo(2 / 3, 12)
    expect(pw / 1200).toBeLessThanOrEqual(GUIDE_MAX_WIDTH_FRACTION + 1e-12)
    expect(ph / 1600).toBeLessThanOrEqual(0.78 + 1e-12)
  })

  it('rejects non-credible contours at the profile thresholds', () => {
    const good = rect(1200, 800, 200, 200)
    expect(isCredible(good, 1600, 1200)).toBe(true)
    expect(isCredible({ ...good, areaRatio: 0.34 }, 1600, 1200)).toBe(false)
    expect(isCredible({ ...good, rectangularity: 0.79 }, 1600, 1200)).toBe(false)
    expect(isCredible({ ...good, rectangularity: Number.NaN }, 1600, 1200)).toBe(false)
    // Too skinny: 1400x700 in a 1600x1200 frame is aspect 2.0 within 1.15..2.20.
    expect(isCredible(rect(1400, 700, 100, 100), 1600, 1200)).toBe(true)
    // Too square-ish narrow: aspect below the minimum.
    expect(isCredible(rect(500, 480, 100, 100), 1600, 1200)).toBe(false)
    // Out of frame.
    const oob: CropCandidate = {
      ...good,
      corners: [
        { x: -5, y: 200 },
        { x: 1200, y: 200 },
        { x: 1200, y: 1000 },
        { x: -5, y: 1000 },
      ],
    }
    expect(isCredible(oob, 1600, 1200)).toBe(false)
  })

  it('ranks by area/rectangularity/guide proximity with profile weights', () => {
    const frameW = 1600
    const frameH = 1200
    // Same geometry, different quality: the stronger rectangle must win.
    const strong = rect(1200, 800, 200, 200)
    const weak: CropCandidate = { ...rect(1200, 800, 200, 200), rectangularity: 0.81, areaRatio: 0.36 }
    const ranked = rankCandidates([weak, strong], frameW, frameH)
    expect(ranked[0]!.candidate).toBe(strong)
    expect(ranked[1]!.candidate).toBe(weak)
    // Deterministic ties: identical candidates keep input order.
    const twin = rankCandidates([strong, { ...strong }], frameW, frameH)
    expect(twin[0]!.confidence).toBe(twin[1]!.confidence)
  })

  it('selects the best credible contour with LEGACY_CONTOUR provenance', () => {
    const strong = rect(1200, 800, 200, 200)
    const selected = selectCrop([rect(400, 300, 50, 50), strong], 1600, 1200)
    expect(selected.source).toBe('LEGACY_CONTOUR')
    expect(selected.usedGuideFallback).toBe(false)
    expect(selected.candidate).toBe(strong)
    expect(selected.confidence).toBeGreaterThanOrEqual(0.7)
  })

  it('falls back to a labelled bounded guide when nothing is credible', () => {
    const none = selectCrop([], 1600, 1200)
    expect(none.source).toBe('GUIDE_FALLBACK')
    expect(none.usedGuideFallback).toBe(true)
    expect(none.confidence).toBeNull()
    expect(none.candidate.rectangularity).toBe(1)
    const widths = [none.candidate.corners[1]!.x - none.candidate.corners[0]!.x]
    expect(widths[0]! / 1600).toBeLessThanOrEqual(GUIDE_MAX_WIDTH_FRACTION + 1e-9)
    const portrait = selectCrop([rect(100, 90, 10, 10)], 1200, 1600)
    expect(portrait.source).toBe('GUIDE_FALLBACK')
    const gh = portrait.candidate.corners[3]!.y - portrait.candidate.corners[0]!.y
    expect(gh / 1600).toBeLessThanOrEqual(0.78 + 1e-9)
  })

  it('rejects below-confidence contours in favour of the guide', () => {
    // Credible geometry (area 0.36, rect 0.80, aspect ok) but far from the
    // guide centre: confidence 0.4 + 0.1 + 0.16 + ~0.02 < 0.70.
    const far: CropCandidate = {
      corners: [
        { x: 0, y: 0 },
        { x: 1000, y: 0 },
        { x: 1000, y: 700 },
        { x: 0, y: 700 },
      ],
      areaRatio: (1000 * 700) / (1600 * 1200),
      rectangularity: 0.8,
    }
    expect(isCredible(far, 1600, 1200)).toBe(true)
    const selected = selectCrop([far], 1600, 1200)
    expect(selected.source).toBe('GUIDE_FALLBACK')
  })
})
