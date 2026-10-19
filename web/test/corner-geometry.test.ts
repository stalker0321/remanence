import { describe, expect, it } from 'vitest'
import {
  isConvex,
  orderClockwiseFromTopLeft,
  selfIntersects,
  signedArea,
  validateQuad,
  type Point2D,
} from '../src/scan/cornerGeometry'

const RECT: Point2D[] = [
  { x: 0, y: 0 },
  { x: 100, y: 0 },
  { x: 100, y: 50 },
  { x: 0, y: 50 },
]

describe('T316 corner ordering and validation', () => {
  it('orders a rectangle clockwise from the top-left', () => {
    expect(orderClockwiseFromTopLeft(RECT)).toEqual([
      { x: 0, y: 0 },
      { x: 100, y: 0 },
      { x: 100, y: 50 },
      { x: 0, y: 50 },
    ])
  })

  it('is invariant to input order', () => {
    const shuffled = [RECT[2]!, RECT[0]!, RECT[3]!, RECT[1]!]
    expect(orderClockwiseFromTopLeft(shuffled)).toEqual(orderClockwiseFromTopLeft(RECT))
  })

  it('rejects wrong counts and duplicate corners', () => {
    expect(() => orderClockwiseFromTopLeft(RECT.slice(0, 3))).toThrow()
    expect(() => orderClockwiseFromTopLeft([RECT[0]!, RECT[1]!, RECT[2]!, RECT[0]!])).toThrow()
  })

  it('reports positive clockwise area', () => {
    expect(signedArea(RECT)).toBe(5000)
    expect(signedArea([...RECT].reverse())).toBe(-5000)
  })

  it('detects convex quads and rejects notched ones', () => {
    expect(isConvex(RECT)).toBe(true)
    const notched: Point2D[] = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 5, y: 4 },
      { x: 0, y: 10 },
    ]
    expect(isConvex(notched)).toBe(false)
  })

  it('detects self-intersecting bowties', () => {
    const bowtie: Point2D[] = [
      { x: 0, y: 0 },
      { x: 10, y: 10 },
      { x: 0, y: 10 },
      { x: 10, y: 0 },
    ]
    expect(selfIntersects(bowtie)).toBe(true)
    expect(selfIntersects(RECT)).toBe(false)
  })

  it('gates quads with typed reasons', () => {
    expect(validateQuad(RECT)).toEqual({ valid: true, ordered: RECT })
    expect(validateQuad(RECT.slice(0, 3))).toEqual({ valid: false, reason: 'WRONG_POINT_COUNT' })
    expect(validateQuad([RECT[0]!, RECT[1]!, RECT[2]!, RECT[0]!])).toEqual({ valid: false, reason: 'DUPLICATE_CORNERS' })
    expect(
      validateQuad([
        { x: 0, y: 0 },
        { x: 10, y: 10 },
        { x: 0, y: 10 },
        { x: 10, y: 0 },
      ]),
    ).toEqual({ valid: false, reason: 'SELF_INTERSECTING' })
    expect(
      validateQuad([
        { x: 0, y: 0 },
        { x: 10, y: 0 },
        { x: 5, y: 4 },
        { x: 0, y: 10 },
      ]),
    ).toEqual({ valid: false, reason: 'NOT_CONVEX' })
    expect(
      validateQuad([
        { x: 0, y: 0 },
        { x: 1, y: 0 },
        { x: 1, y: 1 },
        { x: 0, y: 1 },
      ]),
    ).toEqual({ valid: false, reason: 'DEGENERATE_AREA' })
  })
})
