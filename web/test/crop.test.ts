import { describe, expect, it } from 'vitest'
import {
  CROP_ASPECT_TOLERANCE,
  centerCoverBase,
  clampEditorCrop,
  clampTransform,
  correctCrop,
  correctEditorCrop,
  editorGesture,
  editorWindow,
  panDelta,
  panLimitPercent,
  sourceWindow,
  toCropWindow,
} from '../src/collage/crop'

const IDENTITY = { z: 1, xPercent: 0, yPercent: 0 }

describe('fixed-frame clamp parity', () => {
  it('clamps zoom then pans with the frozen values', () => {
    expect(clampTransform(1.25, 200, -200)).toEqual({ z: 1.25, xPercent: 12.5, yPercent: -12.5 })
    expect(clampTransform(1.0, 5, -5)).toEqual({ z: 1, xPercent: 0, yPercent: 0 })
    expect(clampTransform(0.5, 0.2, -0.2)).toEqual({ z: 1, xPercent: 0, yPercent: 0 })
    expect(clampTransform(2.0, 0, 0)).toEqual({ z: 1.25, xPercent: 0, yPercent: 0 })
    expect(panLimitPercent(1)).toBe(0)
    expect(panLimitPercent(1.25)).toBe(12.5)
    expect(clampTransform(1.125, 6.25, -6.3)).toEqual({ z: 1.125, xPercent: 6.25, yPercent: -6.25 })
  })

  it('locks pan to exactly +0 at z=1', () => {
    const atOne = clampTransform(1.0, 5.0, -5.0)
    expect(atOne).not.toBeNull()
    expect(atOne?.xPercent).toBe(0)
    expect(atOne?.yPercent).toBe(0)
    expect(Object.is(atOne?.xPercent, -0)).toBe(false)
    expect(Object.is(atOne?.yPercent, -0)).toBe(false)
  })

  it('rejects non-finite input instead of propagating NaN', () => {
    expect(clampTransform(NaN, 0, 0)).toBeNull()
    expect(clampTransform(1.1, NaN, 0)).toBeNull()
    expect(clampTransform(1.1, 0, Infinity)).toBeNull()
    expect(sourceWindow({ x: 0, y: 0, width: 1, height: 1 }, { z: NaN, xPercent: 0, yPercent: 0 })).toBeNull()
  })

  it('maps the four direction buttons', () => {
    expect(panDelta('left')).toEqual({ xPercent: -1, yPercent: 0 })
    expect(panDelta('right')).toEqual({ xPercent: 1, yPercent: 0 })
    expect(panDelta('up')).toEqual({ xPercent: 0, yPercent: -1 })
    expect(panDelta('down')).toEqual({ xPercent: 0, yPercent: 1 })
  })
})

describe('source window inversion', () => {
  const base = { x: 0, y: 0, width: 1, height: 1 }

  it('returns the approved crop exactly for identity', () => {
    expect(sourceWindow(base, IDENTITY)).toEqual(base)
  })

  it('moves the window opposite the photo with the frozen 0.46/0.54 pivots', () => {
    const atIdentity = sourceWindow(base, IDENTITY)
    expect(atIdentity).not.toBeNull()
    const photoRight = sourceWindow(base, { z: 1.25, xPercent: 5, yPercent: 0 })
    const photoLeft = sourceWindow(base, { z: 1.25, xPercent: -5, yPercent: 0 })
    const photoDown = sourceWindow(base, { z: 1.25, xPercent: 0, yPercent: 5 })
    expect(photoRight).not.toBeNull()
    expect(photoLeft).not.toBeNull()
    expect(photoDown).not.toBeNull()
    const centerX = (window: { x: number; width: number }): number => window.x + window.width / 2
    const centerY = (window: { y: number; height: number }): number => window.y + window.height / 2
    expect(centerX(photoRight!)).toBeLessThan(centerX(atIdentity!))
    expect(centerX(photoRight!)).toBeCloseTo(0.46, 12)
    expect(centerX(photoLeft!)).toBeGreaterThan(centerX(atIdentity!))
    expect(centerX(photoLeft!)).toBeCloseTo(0.54, 12)
    expect(centerY(photoDown!)).toBeLessThan(centerY(atIdentity!))
    expect(centerY(photoDown!)).toBeCloseTo(0.46, 12)
  })

  it('is reproducible and narrows absolutely by 1/z', () => {
    const first = sourceWindow(base, { z: 1.17, xPercent: -4, yPercent: 3 })
    const second = sourceWindow(base, { z: 1.17, xPercent: -4, yPercent: 3 })
    expect(first).toEqual(second)
    expect(first?.width).toBeCloseTo(base.width / 1.17, 12)
  })
})

describe('source-bounded editor', () => {
  const base = { x: 0.2, y: 0.1, width: 0.4, height: 0.6 }

  it('drags the frame in the finger direction', () => {
    const transform = editorGesture(base, IDENTITY, 0.15, 0.1, 1.0, 0.4, 0.4)
    expect(transform).not.toBeNull()
    const window = editorWindow(base, transform!)
    expect(window?.x).toBeCloseTo(0.35, 9)
    expect(window?.y).toBeCloseTo(0.2, 9)
    expect(window?.width).toBeCloseTo(0.4, 9)
    expect(window?.height).toBeCloseTo(0.6, 9)
  })

  it('reaches both source edges but never escapes them', () => {
    for (const delta of [-100, 100]) {
      const transform = editorGesture(base, IDENTITY, delta, delta, 1.0, 0.4, 0.4)
      expect(transform).not.toBeNull()
      const window = editorWindow(base, transform!)
      expect(window?.x).toBeCloseTo(delta < 0 ? 0 : 0.6, 9)
      expect(window?.y).toBeCloseTo(delta < 0 ? 0 : 0.4, 9)
      expect(window!.x).toBeGreaterThanOrEqual(-1e-9)
      expect(window!.y).toBeGreaterThanOrEqual(-1e-9)
      expect(window!.x + window!.width).toBeLessThanOrEqual(1.000000001)
      expect(window!.y + window!.height).toBeLessThanOrEqual(1.000000001)
    }
  })

  it('keeps the pinch centroid fixed with the same aspect', () => {
    const transform = editorGesture(base, IDENTITY, 0, 0, 1.2, 0.3, 0.25)
    expect(transform).not.toBeNull()
    const window = editorWindow(base, transform!)
    expect(window!.x + window!.width * 0.25).toBeCloseTo(0.3, 9)
    expect(window!.y + window!.height * 0.25).toBeCloseTo(0.25, 9)
    expect(window!.width / window!.height).toBeCloseTo(base.width / base.height, 9)
    expect(window!.width).toBeCloseTo(base.width / 1.2, 9)
  })

  it('recovers area outside the initial crop and stops at source bounds', () => {
    const transform = editorGesture(base, IDENTITY, 0, 0, 0.1, 0.4, 0.4)
    expect(transform).not.toBeNull()
    expect(transform?.z).toBeCloseTo(0.6, 9)
    const window = editorWindow(base, transform!)
    expect(window?.height).toBeCloseTo(1, 9)
    expect(window?.y).toBeCloseTo(0, 9)
    expect(window!.width).toBeGreaterThan(base.width)
  })

  it('accumulates repeated gestures without changing aspect', () => {
    let transform = { ...IDENTITY }
    for (let i = 0; i < 10; i++) {
      const next = editorGesture(base, transform, 0.01, 0.005, 1.01, 0.4, 0.4)
      expect(next).not.toBeNull()
      transform = next!
    }
    expect(transform.z).toBeCloseTo(Math.pow(1.01, 10), 9)
    const window = editorWindow(base, transform)
    expect(window!.x).toBeGreaterThan(base.x)
    expect(window!.width / window!.height).toBeCloseTo(base.width / base.height, 9)
  })

  it('rejects invalid input and keeps extreme finite values bounded', () => {
    expect(clampEditorCrop(base, { z: NaN, xPercent: 0, yPercent: 0 })).toBeNull()
    expect(editorGesture(base, IDENTITY, 0, 0, 0, 0.5, 0.5)).toBeNull()
    expect(clampEditorCrop({ ...base, width: -1 }, IDENTITY)).toBeNull()
    const transform = clampEditorCrop(base, { z: 500, xPercent: 1e6, yPercent: -1e6 })
    expect(transform).not.toBeNull()
    expect(transform?.z).toBe(1.25)
    const window = editorWindow(base, transform!)
    expect(window!.x).toBeGreaterThanOrEqual(-1e-9)
    expect(window!.y).toBeGreaterThanOrEqual(-1e-9)
    expect(window!.x + window!.width).toBeLessThanOrEqual(1.000000001)
    expect(window!.y + window!.height).toBeLessThanOrEqual(1.000000001)
  })
})

describe('center-cover starting base', () => {
  it('covers the cell with aspect preserved, centered, max side 1', () => {
    const cover = centerCoverBase(2000, 1000, 1, 1)
    expect(cover).not.toBeNull()
    expect(Math.max(cover!.width, cover!.height)).toBeCloseTo(1, 12)
    expect(cover!.x + cover!.width / 2).toBeCloseTo(0.5, 12)
    expect(cover!.y + cover!.height / 2).toBeCloseTo(0.5, 12)
    const pixelAspect = (cover!.width * 2000) / (cover!.height * 1000)
    expect(Math.abs(pixelAspect - 1) / 1).toBeLessThanOrEqual(CROP_ASPECT_TOLERANCE)
    const square = centerCoverBase(1000, 1000, 340, 248)
    expect(square).not.toBeNull()
    expect(square!.x).toBe(0)
    expect(square!.width).toBe(1)
    expect(square!.height).toBeCloseTo(248 / 340, 12)
    expect(square!.y).toBeCloseTo((1 - 248 / 340) / 2, 12)
  })

  it('refuses non-finite or non-positive dimensions', () => {
    expect(centerCoverBase(0, 1000, 1, 1)).toBeNull()
    expect(centerCoverBase(1000, -2, 1, 1)).toBeNull()
    expect(centerCoverBase(NaN, 1000, 1, 1)).toBeNull()
    expect(centerCoverBase(1000, 1000, 1, 0)).toBeNull()
  })
})

describe('sealed quantization', () => {
  it('maps the full source to the full unit square', () => {
    expect(toCropWindow({ x: 0, y: 0, width: 1, height: 1 })).toEqual({ x: 0, y: 0, width: 10000, height: 10000 })
  })

  it('clamps the right/bottom edge so x+width never exceeds 10000', () => {
    expect(toCropWindow({ x: 0.99995, y: 0, width: 0.001, height: 1 })).toEqual({ x: 10000, y: 0, width: 0, height: 10000 })
  })

  it('round-trips a typical window within 1e-4', () => {
    const base = { x: 0.1, y: 0.2, width: 0.3, height: 0.4 }
    const ints = toCropWindow(base)
    expect(ints).toEqual({ x: 1000, y: 2000, width: 3000, height: 4000 })
    const back = { x: ints.x / 10000, y: ints.y / 10000, width: ints.width / 10000, height: ints.height / 10000 }
    expect(Math.abs(back.x - base.x)).toBeLessThanOrEqual(1e-4)
    expect(Math.abs(back.y - base.y)).toBeLessThanOrEqual(1e-4)
    expect(Math.abs(back.width - base.width)).toBeLessThanOrEqual(1e-4)
    expect(Math.abs(back.height - base.height)).toBeLessThanOrEqual(1e-4)
  })
})

describe('geometry gates', () => {
  const slot = { width: 340, height: 248 }
  const base = { x: 0, y: (1 - 248 / 340) / 2, width: 1, height: 248 / 340 }

  it('admits identity with the exact approved window', () => {
    const result = correctCrop(base, slot, 1000, 1000, IDENTITY)
    expect(result.accepted).toBe(true)
    if (result.accepted) expect(result.window).toEqual(base)
  })

  it('rejects non-finite values first', () => {
    const result = correctCrop(base, slot, 1000, 1000, { z: NaN, xPercent: 0, yPercent: 0 })
    expect(result).toEqual({ accepted: false, reason: 'NONFINITE_VALUE' })
  })

  it('rejects zoom outside [1, 1.25]', () => {
    expect(correctCrop(base, slot, 1000, 1000, { z: 1.3, xPercent: 0, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'ZOOM_OUT_OF_DOMAIN',
    })
    expect(correctCrop(base, slot, 1000, 1000, { z: 0.9, xPercent: 0, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'ZOOM_OUT_OF_DOMAIN',
    })
  })

  it('rejects pans beyond the limit and never re-clamps', () => {
    expect(correctCrop(base, slot, 1000, 1000, { z: 1, xPercent: 1, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'PAN_BEYOND_LIMIT',
    })
    expect(correctCrop(base, slot, 1000, 1000, { z: 1.25, xPercent: 13, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'PAN_BEYOND_LIMIT',
    })
    const clamped = clampTransform(1.25, 13, 0)
    expect(clamped).not.toBeNull()
    expect(correctCrop(base, slot, 1000, 1000, clamped!)).toEqual({
      accepted: true,
      window: expect.anything(),
    })
  })

  it('rejects invalid source dimensions and slots', () => {
    expect(correctCrop(base, slot, 0, 1000, IDENTITY)).toEqual({ accepted: false, reason: 'INVALID_SOURCE_DIMENSIONS' })
    expect(correctCrop(base, { width: 0, height: 248 }, 1000, 1000, IDENTITY)).toEqual({
      accepted: false,
      reason: 'INVALID_SLOT',
    })
  })

  it('rejects base crops outside the source', () => {
    expect(correctCrop({ x: 0.6, y: 0, width: 0.5, height: 1 }, slot, 1000, 1000, IDENTITY)).toEqual({
      accepted: false,
      reason: 'BASE_CROP_OUT_OF_SOURCE',
    })
    expect(correctCrop({ x: 0.1, y: 0.1, width: -0.2, height: 0.5 }, slot, 1000, 1000, IDENTITY)).toEqual({
      accepted: false,
      reason: 'BASE_CROP_OUT_OF_SOURCE',
    })
    expect(correctCrop({ x: -0.05, y: 0, width: 0.5, height: 0.5 }, slot, 1000, 1000, IDENTITY)).toEqual({
      accepted: false,
      reason: 'BASE_CROP_OUT_OF_SOURCE',
    })
  })

  it('rejects a frame-aspect mismatch within 5e-3', () => {
    const portraitBase = { x: 0, y: 0, width: 167 / 1000, height: 1 }
    expect(correctCrop(portraitBase, slot, 1000, 1000, IDENTITY)).toEqual({
      accepted: false,
      reason: 'FRAME_ASPECT_MISMATCH',
    })
  })

  it('derives the window absolutely from the approved crop', () => {
    const stepped = correctCrop(base, slot, 1000, 1000, { z: 1.1, xPercent: 5, yPercent: 0 })
    expect(stepped.accepted).toBe(true)
    if (stepped.accepted) {
      expect(stepped.window.width).toBeCloseTo(base.width / 1.1, 12)
      expect(stepped.window.height).toBeCloseTo(base.height / 1.1, 12)
    }
  })
})

describe('composed editor domain (stored transform clamped first)', () => {
  const slot = { width: 340, height: 248 }
  const base = { x: 0, y: (1 - 248 / 340) / 2, width: 1, height: 248 / 340 }

  it('treats a z=1 pan as a safe no-op instead of emptying candidates', () => {
    const still = correctEditorCrop(base, slot, 1000, 1000, { z: 1, xPercent: 50, yPercent: -50 })
    expect(still.accepted).toBe(true)
    if (!still.accepted) return
    expect(still.transform).toEqual({ z: 1, xPercent: 0, yPercent: 0 })
    expect(still.window).toEqual(base)
  })

  it('clamps the zoom floor to 1 instead of widening the search', () => {
    const zoomedOut = correctEditorCrop(base, slot, 1000, 1000, { z: 0.5, xPercent: 0, yPercent: 0 })
    expect(zoomedOut.accepted).toBe(true)
    if (!zoomedOut.accepted) return
    expect(zoomedOut.transform.z).toBe(1)
    expect(zoomedOut.window).toEqual(base)
  })

  it('keeps repeated pans and zoom-downs inside the allowed set', () => {
    let transform = { z: 1, xPercent: 0, yPercent: 0 }
    for (let i = 0; i < 20; i++) {
      transform = { z: 1.05, xPercent: transform.xPercent + 2.5, yPercent: transform.yPercent - 2.5 }
      const admitted = correctEditorCrop(base, slot, 1000, 1000, transform)
      expect(admitted.accepted).toBe(true)
    }
    const zoomed = correctEditorCrop(base, slot, 1000, 1000, { z: 0.2, xPercent: 0, yPercent: 0 })
    expect(zoomed.accepted).toBe(true)
  })

  it('rejects non-finite input before any clamping', () => {
    expect(correctEditorCrop(base, slot, 1000, 1000, { z: NaN, xPercent: 0, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'NONFINITE_VALUE',
    })
  })

  it('still enforces source, slot, base, and aspect gates', () => {
    expect(correctEditorCrop(base, slot, 0, 1000, { z: 1, xPercent: 0, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'INVALID_SOURCE_DIMENSIONS',
    })
    expect(correctEditorCrop(base, { width: 0, height: 248 }, 1000, 1000, { z: 1, xPercent: 0, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'INVALID_SLOT',
    })
    expect(correctEditorCrop({ x: 0.6, y: 0, width: 0.5, height: 1 }, slot, 1000, 1000, { z: 1, xPercent: 0, yPercent: 0 })).toEqual({
      accepted: false,
      reason: 'BASE_CROP_OUT_OF_SOURCE',
    })
    expect(
      correctEditorCrop({ x: 0, y: 0, width: 167 / 1000, height: 1 }, slot, 1000, 1000, { z: 1, xPercent: 0, yPercent: 0 }),
    ).toEqual({ accepted: false, reason: 'FRAME_ASPECT_MISMATCH' })
  })
})
