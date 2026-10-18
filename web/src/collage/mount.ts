import { CollageError } from './errors'
import { CANVAS_HEIGHT, CANVAS_WIDTH, type Rect } from './model'

/**
 * T252 — canonical integer mount for creator geometry.
 *
 * The wire codec encodes every destination/provenance/note rect coordinate
 * with `CanonicalWriter.int` (`web/src/collage/codec.ts` `writePlacements` /
 * `writeRect`), which REJECTS fractions. The accepted Android producer mounts
 * the SAME way before sealing, with a component-wise `floor(x).toInt()`:
 *   - `AllFamilyCandidateProducer.kt:245-248` — mounted photo `x/y/width/height`
 *   - `AllFamilyV4Adapter.kt:90-93`           — provenance `x/y/width/height`
 *   - `AllFamilyV4Adapter.kt:118-121`         — note region `x/y/width/height`
 *
 * T233's `grammar5Plans` legitimately emits fractional tile rects, so this
 * conversion is applied at the creator MOUNT boundary — BEFORE the crop window
 * is derived from the cell and BEFORE paint/freeze capture — so the displayed
 * integer destination, its derived source window, and the frozen local snapshot
 * are all the same integers the wire encoder accepts. The generic `Rect` /
 * model / codec stay unchanged: nothing is floored inside the codec and a held
 * freeze is never re-quantized.
 *
 * Refuses a non-finite component, a rect that floors to a degenerate
 * (non-positive) size, or one outside the 360x640 canvas — mirroring
 * `validateResolved`'s `fitsCanvas` gate so an unmountable plan fails closed
 * instead of painting/wire-encoding an unrepresentable rect.
 */
export function mountRect(rect: Rect): Rect {
  const mounted: Rect = {
    x: Math.floor(rect.x),
    y: Math.floor(rect.y),
    width: Math.floor(rect.width),
    height: Math.floor(rect.height),
  }
  if (
    !Number.isInteger(mounted.x) ||
    !Number.isInteger(mounted.y) ||
    !Number.isInteger(mounted.width) ||
    !Number.isInteger(mounted.height) ||
    mounted.x < 0 ||
    mounted.y < 0 ||
    mounted.width <= 0 ||
    mounted.height <= 0 ||
    mounted.x + mounted.width > CANVAS_WIDTH ||
    mounted.y + mounted.height > CANVAS_HEIGHT
  ) {
    throw new CollageError('REJECTED')
  }
  return mounted
}
