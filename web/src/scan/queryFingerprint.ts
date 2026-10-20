/**
 * T343 — identity-free receiving-query entry over the shared sender pipeline
 * core. A plain camera `StillCapture` yields an owned canonical format-3
 * wire/profile/provenance with NO recipient/owner metadata. This is a thin,
 * non-divergent alias of `produceLocalizedFingerprint` (same decode/exact-fit/
 * contour/guide/warp/SIFT/serializer path and identical failure precedence);
 * the sender wrapper and its public output shape are unchanged.
 *
 * Query QUALITY measurement/admission is NOT implemented here (T342 confirmed
 * it is advisory in Android; a later bounded probe/port owns the pixel meter).
 * No engine/Scan/grant/index/pagination activation.
 */

import type { StillCapture } from './camera'
import {
  produceLocalizedFingerprint,
  type LocalizedFingerprint,
  type ProduceFrontStillOptions,
} from './frontStillProducer'

export type QueryFingerprint = LocalizedFingerprint
export type ProduceQueryFingerprintOptions = ProduceFrontStillOptions

/** Produce an owned canonical query wire from a plain camera still. */
export function produceQueryFingerprint(
  source: StillCapture,
  options: ProduceFrontStillOptions,
): Promise<QueryFingerprint> {
  return produceLocalizedFingerprint(source, options)
}
