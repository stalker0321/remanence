export function stepIndex(index: number, length: number, delta: number): number {
  if (length <= 0) return 0
  return Math.min(length - 1, Math.max(0, index + delta))
}

/**
 * A bounded horizontal swipe: ignore vertical/ambiguous gestures and short
 * movement. Negative dx (finger left) advances to the next original photo.
 */
export function swipeStep(startX: number, startY: number, endX: number, endY: number, threshold = 40): -1 | 0 | 1 {
  const dx = endX - startX
  const dy = endY - startY
  if (Math.abs(dx) < threshold || Math.abs(dx) <= Math.abs(dy) * 1.5) return 0
  return dx < 0 ? 1 : -1
}
