import { describe, expect, it } from "vitest"
import { stepIndex, swipeStep } from "../src/collage/viewerNavigation"

describe("original-photo viewer navigation", () => {
  it("clamps paging at both boundaries", () => {
    expect(stepIndex(0, 3, -1)).toBe(0)
    expect(stepIndex(2, 3, 1)).toBe(2)
    expect(stepIndex(0, 3, 1)).toBe(1)
    expect(stepIndex(2, 3, -1)).toBe(1)
    expect(stepIndex(0, 0, 1)).toBe(0)
  })

  it("accepts only bounded horizontal swipes", () => {
    expect(swipeStep(200, 300, 120, 305)).toBe(1)
    expect(swipeStep(120, 300, 200, 305)).toBe(-1)
    expect(swipeStep(200, 300, 170, 305)).toBe(0)
    expect(swipeStep(200, 300, 120, 420)).toBe(0)
  })

  it("does not page on a vertical scroll gesture", () => {
    expect(swipeStep(200, 300, 190, 420)).toBe(0)
    expect(swipeStep(200, 300, 210, 160)).toBe(0)
  })
})
