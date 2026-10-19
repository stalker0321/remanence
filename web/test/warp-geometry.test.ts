import { describe, expect, it } from 'vitest'
import { planWarp, targetCorners, WARP_LONG_EDGE_PX } from '../src/scan/warpGeometry'
import { rint } from '../src/scan/siftExtractor'
import type { Point2D } from '../src/scan/cornerGeometry'

const LAND: [Point2D, Point2D, Point2D, Point2D] = [
  { x: 100, y: 100 },
  { x: 380, y: 100 },
  { x: 380, y: 300 },
  { x: 100, y: 300 },
]

describe('T316 warp geometry', () => {
  it('maps the long edge to 1600 with a ties-even short edge', () => {
    // Long edge 280px, short edge 200px: 200*1600/280 = 1142.857... -> 1143.
    const quad: [Point2D, Point2D, Point2D, Point2D] = [
      { x: 10, y: 10 },
      { x: 290, y: 10 },
      { x: 290, y: 210 },
      { x: 10, y: 210 },
    ]
    const plan = planWarp(quad)
    expect(plan.landscape).toBe(true)
    expect(plan.targetWidth).toBe(WARP_LONG_EDGE_PX)
    expect(plan.targetHeight).toBe(1143)
  })

  it('rounds halves to even like Kotlin (accepted rint, not Math.round)', () => {
    expect(rint(1600.5)).toBe(1600)
    expect(rint(1601.5)).toBe(1602)
    expect(rint(2.5)).toBe(2)
    expect(rint(3.5)).toBe(4)
    expect(Math.round(1600.5)).toBe(1601)
  })

  it('rotates a portrait quad [1,2,3,0] into canonical landscape', () => {
    const portrait: [Point2D, Point2D, Point2D, Point2D] = [
      { x: 100, y: 100 },
      { x: 300, y: 100 },
      { x: 300, y: 380 },
      { x: 100, y: 380 },
    ]
    const plan = planWarp(portrait)
    expect(plan.landscape).toBe(false)
    expect(plan.source).toEqual([portrait[1], portrait[2], portrait[3], portrait[0]])
    expect(plan.targetWidth).toBe(WARP_LONG_EDGE_PX)
    expect(plan.targetHeight).toBeLessThan(WARP_LONG_EDGE_PX)
    expect(plan.targetWidth * plan.targetHeight).toBeLessThanOrEqual(16_000_000)
  })

  it('keeps an already-landscape quad in authored order', () => {
    const plan = planWarp(LAND)
    expect(plan.landscape).toBe(true)
    expect(plan.source).toEqual(LAND)
  })

  it('uses width-1/height-1 destination corners', () => {
    expect(targetCorners(1600, 1143)).toEqual([
      { x: 0, y: 0 },
      { x: 1599, y: 0 },
      { x: 1599, y: 1142 },
      { x: 0, y: 1142 },
    ])
  })

  it('refuses invalid quads instead of warping', () => {
    expect(() =>
      planWarp([
        { x: 0, y: 0 },
        { x: 10, y: 10 },
        { x: 0, y: 10 },
        { x: 10, y: 0 },
      ]),
    ).toThrow()
    expect(() =>
      planWarp([
        { x: 5, y: 5 },
        { x: 5, y: 5 },
        { x: 6, y: 5 },
        { x: 5, y: 6 },
      ]),
    ).toThrow()
  })
})
