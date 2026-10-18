import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
import { mountRect } from '../src/collage/mount'

describe('canonical integer mount (Android floor parity)', () => {
  it('floors every component exactly like floor(x).toInt()', () => {
    expect(mountRect({ x: 10.9, y: 101.2, width: 143.8, height: 213.5 })).toEqual({
      x: 10,
      y: 101,
      width: 143,
      height: 213,
    })
    expect(mountRect({ x: 12, y: 4, width: 340, height: 75 })).toEqual({ x: 12, y: 4, width: 340, height: 75 })
    expect(mountRect({ x: 16, y: 573.999, width: 328.0001, height: 30 })).toEqual({ x: 16, y: 573, width: 328, height: 30 })
    // floor, not round: .99 and .5 must both drop.
    expect(mountRect({ x: 1.99, y: 2.5, width: 3.5, height: 4.99 })).toEqual({ x: 1, y: 2, width: 3, height: 4 })
  })

  it('refuses non-finite components', () => {
    expect(() => mountRect({ x: Number.NaN, y: 0, width: 10, height: 10 })).toThrowError(CollageError)
    expect(() => mountRect({ x: 0, y: Number.POSITIVE_INFINITY, width: 10, height: 10 })).toThrowError(CollageError)
    expect(() => mountRect({ x: 0, y: 0, width: Number.NEGATIVE_INFINITY, height: 10 })).toThrowError(CollageError)
    expect(() => mountRect({ x: 0, y: 0, width: 10, height: Number.NaN })).toThrowError(CollageError)
  })

  it('refuses rects that floor to a degenerate/non-positive size', () => {
    expect(() => mountRect({ x: 10, y: 10, width: 0.9, height: 10 })).toThrowError(CollageError)
    expect(() => mountRect({ x: 10, y: 10, width: 10, height: 0 })).toThrowError(CollageError)
    expect(() => mountRect({ x: -0.5, y: 10, width: 10, height: 10 })).toThrowError(CollageError)
    expect(() => mountRect({ x: 10, y: -0.001, width: 10, height: 10 })).toThrowError(CollageError)
  })

  it('refuses rects outside the 360x640 canvas', () => {
    expect(() => mountRect({ x: 350, y: 0, width: 11, height: 10 })).toThrowError(CollageError)
    expect(() => mountRect({ x: 0, y: 631, width: 10, height: 10 })).toThrowError(CollageError)
  })
})
