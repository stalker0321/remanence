import { describe, expect, it } from 'vitest'
import { textHeight } from '../src/collage/measure'

describe('text height', () => {
  it('matches the heights Android measures on its bundled faces', () => {
    // Rows from Android's CollageTextMeasurer (Robolectric, native graphics, density 1).
    expect([1, 2, 3].map((n) => textHeight(n, 20, 23))).toEqual([20, 43, 66])
    expect([1, 2, 3].map((n) => textHeight(n, 14, 16))).toEqual([14, 30, 46])
    expect([1, 2, 3].map((n) => textHeight(n, 11, 20))).toEqual([11, 31, 51])
  })
})
