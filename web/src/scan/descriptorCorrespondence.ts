import {
  validateSiftFingerprintShape,
  type SiftFingerprint,
} from './siftFingerprint'
import { rint } from './siftExtractor'
import type { CvDMatch, PinnedMatcherRuntime } from './opencvMatcherPort'
import { PinnedMatcherError } from './opencvMatcherPort'

/**
 * T330 — the CORRESPONDENCE stage of the native `SiftRootSiftMatcher`
 * (`android/core/recognition/.../SiftRootSiftMatcher.kt`), ported around the
 * EXISTING pinned OpenCV BFMatcher (root runtime probe confirmed the
 * committed vendor exports; the connected proof executes real knnMatch).
 *
 * Stage boundary (CONFIRMED from native source): valid format-3 inputs →
 * RootSIFT float32 rows → BFMatcher L2 knnMatch K=2 both directions →
 * strict ratio → reciprocal reduction → original-index mapping →
 * deterministic sort → query-pixel-only (ties-to-even rint) dedup →
 * technical pairs + bounded scalar diagnostics. Geometry (USAC_MAGSAC
 * RANSAC / MIN_GEOMETRY_PAIRS evaluation), profile/policy scoring, engine
 * activation, grants and admission are OUT of this module; a >=6 unique
 * result only means geometry would still be required — it is NOT a match,
 * PASS or acceptance claim.
 *
 * Native semantics preserved exactly (source-anchored):
 * - RootSIFT: descriptor bytes read UNSIGNED; integer L1 sum; each value
 *   divided then square-rooted in FLOAT32
 *   (`Math.fround(Math.sqrt(Math.fround(v / sum)))`, lead correction);
 *   zero-sum rows are omitted (original index kept); non-finite roots are
 *   an internal failure. Caller fingerprints/rows are never mutated.
 * - Ratio: native strict `best < 0.75 * second` over the DMatch distances
 *   promoted to JS Number; fail-closed on non-finite/negative distances,
 *   second==0, or fewer than two candidates (`RATIO_THRESHOLD` technical
 *   constant already in the native matcher companion).
 * - Reciprocal: reverse keyed by reference-local index; keep forward iff
 *   the reverse partner maps back to the same query-local index; map
 *   local → ORIGINAL fingerprint indices; sort by
 *   (distance, queryIndex, referenceIndex).
 * - Dedup: rounded query pixels only
 *   (`rint(xMicro/1e6*canonicalWidthPx)` ties-to-even via the accepted
 *   extractor `rint`); reference-side repeats are kept (native comment).
 *
 * OWNERSHIP: rooted Float32 buffers are owned here and wiped on every
 * path; they never appear in successful output. Optional `signal` is
 * checked before/after the runtime await and before return; a late
 * runtime resolution never yields stale pairs.
 */
export type DescriptorCorrespondenceErrorCode =
  | 'unavailable'
  | 'cancelled'
  | 'shape'
  | 'internal'
  | 'native-contract'

/** Bounded, redacted refusal. Never carries rows/points/distances. */
export class DescriptorCorrespondenceError extends Error {
  constructor(readonly code: DescriptorCorrespondenceErrorCode) {
    super(code)
    this.name = 'DescriptorCorrespondenceError'
  }
}

export interface CorrespondencePair {
  readonly queryIndex: number
  readonly referenceIndex: number
  readonly distance: number
}

export type CorrespondenceNoEvidence =
  | 'no-usable-rows'
  | 'no-ratio-matches'
  | 'no-reciprocal-matches'
  | 'insufficient-unique-pairs'

export interface CorrespondenceDiagnostics {
  readonly rawQueryRows: number
  readonly rawReferenceRows: number
  readonly usableQueryRows: number
  readonly usableReferenceRows: number
  readonly forwardRatioMatches: number
  readonly reverseRatioMatches: number
  readonly reciprocalMatches: number
  readonly uniqueMatches: number
  /** Technical no-evidence reason (pre-geometry mirror of the native enum). */
  readonly noEvidence: CorrespondenceNoEvidence | null
}

export interface CorrespondenceResult {
  readonly pairs: readonly CorrespondencePair[]
  readonly diagnostics: CorrespondenceDiagnostics
}

export interface RootedRow {
  readonly originalIndex: number
  readonly values: Float32Array
}

/** FLOAT32-before-sqrt RootSIFT row (lead-corrected native math). */
export function rootSiftRowForCorrespondence(row: Uint8Array): Float32Array | null {
  if (row.length !== 128) throw new DescriptorCorrespondenceError('shape')
  let sum = 0
  for (let index = 0; index < row.length; index++) sum += row[index]! & 0xff
  if (sum <= 0) return null
  const rooted = new Float32Array(128)
  for (let index = 0; index < row.length; index++) {
    const normalized = Math.fround((row[index]! & 0xff) / sum)
    const root = Math.fround(Math.sqrt(normalized))
    if (!Number.isFinite(root)) throw new DescriptorCorrespondenceError('internal')
    rooted[index] = root
  }
  return rooted
}

export function rootSiftRowsForCorrespondence(fingerprint: SiftFingerprint): RootedRow[] {
  const rows: RootedRow[] = []
  for (let index = 0; index < fingerprint.quantizedSiftDescriptors.length; index++) {
    const values = rootSiftRowForCorrespondence(fingerprint.quantizedSiftDescriptors[index]!)
    if (values !== null) rows.push({ originalIndex: index, values })
  }
  return rows
}

/** Native strict ratio predicate over promoted distances (fail-closed). */
export function passesStrictRatioForCorrespondence(best: number, second: number): boolean {
  if (!Number.isFinite(best) || !Number.isFinite(second) || best < 0 || second < 0 || second === 0) {
    return false
  }
  return best < 0.75 * second
}

interface LocalMatch {
  readonly queryLocalIndex: number
  readonly referenceLocalIndex: number
  readonly distance: number
}

function ratioMatchesForCorrespondence(
  rows: readonly (readonly CvDMatch[])[],
  anchorCount: number,
  trainCount: number,
  reverseDirection: boolean,
): LocalMatch[] {
  if (rows.length !== anchorCount) throw new DescriptorCorrespondenceError('native-contract')
  const matches: LocalMatch[] = []
  for (let anchor = 0; anchor < rows.length; anchor++) {
    const candidates = rows[anchor]!
    if (candidates.length < 2) continue
    const best = candidates[0]!
    const second = candidates[1]!
    if (
      best.queryIdx !== anchor
      || best.trainIdx < 0
      || best.trainIdx >= trainCount
      || second.trainIdx < 0
      || second.trainIdx >= trainCount
    ) {
      throw new DescriptorCorrespondenceError('native-contract')
    }
    if (!passesStrictRatioForCorrespondence(best.distance, second.distance)) continue
    if (reverseDirection) {
      matches.push({ queryLocalIndex: best.trainIdx, referenceLocalIndex: anchor, distance: best.distance })
    } else {
      matches.push({ queryLocalIndex: anchor, referenceLocalIndex: best.trainIdx, distance: best.distance })
    }
  }
  return matches
}

function roundedQueryPixel(fingerprint: SiftFingerprint, queryIndex: number): string {
  const keypoint = fingerprint.keypoints[queryIndex]!
  const x = rint((keypoint.xMicro / 1_000_000) * fingerprint.canonicalWidthPx)
  const y = rint((keypoint.yMicro / 1_000_000) * fingerprint.canonicalHeightPx)
  return `${x},${y}`
}

/**
 * Synchronous per-original-index snapshot of every consumed query pixel key
 * (scope 3): after any await, reduction must NEVER reread the caller's
 * keypoints/dimensions. Caller buffers are untouched.
 */
export function queryPixelKeysForCorrespondence(fingerprint: SiftFingerprint): string[] {
  const keys: string[] = new Array(fingerprint.keypoints.length)
  for (let index = 0; index < fingerprint.keypoints.length; index++) {
    keys[index] = roundedQueryPixel(fingerprint, index)
  }
  return keys
}

function reduceCorrespondence(
  queryPixelKeys: readonly string[],
  queryRows: readonly RootedRow[],
  referenceRows: readonly RootedRow[],
  forward: readonly LocalMatch[],
  reverse: readonly LocalMatch[],
): { pairs: CorrespondencePair[]; reciprocalCount: number } {
  const reverseByReference = new Map<number, LocalMatch>()
  for (const match of reverse) reverseByReference.set(match.referenceLocalIndex, match)
  const reciprocal: LocalMatch[] = []
  for (const match of forward) {
    const partner = reverseByReference.get(match.referenceLocalIndex)
    if (partner?.queryLocalIndex === match.queryLocalIndex) reciprocal.push(match)
  }
  const mapped: CorrespondencePair[] = reciprocal.map((match) => ({
    queryIndex: queryRows[match.queryLocalIndex]!.originalIndex,
    referenceIndex: referenceRows[match.referenceLocalIndex]!.originalIndex,
    distance: match.distance,
  }))
  mapped.sort((left, right) => {
    if (left.distance !== right.distance) return left.distance - right.distance
    if (left.queryIndex !== right.queryIndex) return left.queryIndex - right.queryIndex
    return left.referenceIndex - right.referenceIndex
  })
  // Deduplicate ONLY rounded query pixels (from the pre-await snapshot);
  // reference-side repeats are kept.
  const seenQueryPixels = new Set<string>()
  const unique = mapped.filter((pair) => {
    const key = queryPixelKeys[pair.queryIndex]!
    if (seenQueryPixels.has(key)) return false
    seenQueryPixels.add(key)
    return true
  })
  return { pairs: unique, reciprocalCount: reciprocal.length }
}

export interface MatchDescriptorsDeps {
  /** Production default: the shared pinned runtime module bridge. */
  readonly matcher?: PinnedMatcherRuntime
  /** Lazy production bridge (e.g. async runtime load), checked against `signal`. */
  readonly matcherFactory?: () => Promise<PinnedMatcherRuntime>
  readonly signal?: AbortSignal
}

function diagnostics(
  rawQueryRows: number,
  rawReferenceRows: number,
  usableQueryRows: number,
  usableReferenceRows: number,
  forwardRatio: number,
  reverseRatio: number,
  reciprocal: number,
  unique: number,
  noEvidence: CorrespondenceNoEvidence | null,
): CorrespondenceDiagnostics {
  return {
    rawQueryRows,
    rawReferenceRows,
    usableQueryRows,
    usableReferenceRows,
    forwardRatioMatches: forwardRatio,
    reverseRatioMatches: reverseRatio,
    reciprocalMatches: reciprocal,
    uniqueMatches: unique,
    noEvidence,
  }
}

/**
 * Runs the correspondence stage over two accepted format-3 fingerprints.
 * Pure technical evidence only: never grants, admits, scores or activates
 * an engine; `unavailableRecognition` remains the App default.
 */
export async function matchDescriptors(
  query: SiftFingerprint,
  reference: SiftFingerprint,
  deps: MatchDescriptorsDeps = {},
): Promise<CorrespondenceResult> {
  const { signal } = deps
  if (signal?.aborted) throw new DescriptorCorrespondenceError('cancelled')
  try {
    validateSiftFingerprintShape(query)
    validateSiftFingerprintShape(reference)
  } catch {
    throw new DescriptorCorrespondenceError('shape')
  }

  // Snapshot + RootSIFT conversion synchronously (BEFORE any await); caller
  // rows/keypoints/dimensions are never reread afterwards or mutated.
  const queryRawCount = query.quantizedSiftDescriptors.length
  const referenceRawCount = reference.quantizedSiftDescriptors.length
  const queryPixelKeys = queryPixelKeysForCorrespondence(query)
  const queryRows = rootSiftRowsForCorrespondence(query)
  const referenceRows = rootSiftRowsForCorrespondence(reference)
  const emptyResult = (noEvidence: CorrespondenceNoEvidence): CorrespondenceResult => ({
    pairs: [],
    diagnostics: diagnostics(
      queryRawCount,
      referenceRawCount,
      queryRows.length,
      referenceRows.length,
      0,
      0,
      0,
      0,
      noEvidence,
    ),
  })
  try {
    if (queryRows.length === 0 || referenceRows.length === 0) return emptyResult('no-usable-rows')

    let matcher: PinnedMatcherRuntime | null = deps.matcher ?? null
    if (matcher === null && deps.matcherFactory !== undefined) {
      if (signal?.aborted) throw new DescriptorCorrespondenceError('cancelled')
      matcher = await deps.matcherFactory()
      if (signal?.aborted) throw new DescriptorCorrespondenceError('cancelled')
    }
    if (matcher === null) throw new DescriptorCorrespondenceError('unavailable')

    const knn = await matcher.knnMatch2(
      queryRows.map((row) => row.values),
      referenceRows.map((row) => row.values),
    )
    if (signal?.aborted) throw new DescriptorCorrespondenceError('cancelled')

    const forward = ratioMatchesForCorrespondence(knn.forward, queryRows.length, referenceRows.length, false)
    const reverse = ratioMatchesForCorrespondence(knn.reverse, referenceRows.length, queryRows.length, true)
    const reduced = reduceCorrespondence(queryPixelKeys, queryRows, referenceRows, forward, reverse)

    let noEvidence: CorrespondenceNoEvidence | null = null
    if (reduced.pairs.length === 0 && forward.length === 0 && reverse.length === 0) {
      noEvidence = 'no-ratio-matches'
    } else if (reduced.pairs.length === 0) {
      noEvidence = 'no-reciprocal-matches'
    } else if (reduced.pairs.length < 6) {
      // Mirrors native MIN_GEOMETRY_PAIRS: geometry would still be required;
      // this is NOT a match/acceptance claim.
      noEvidence = 'insufficient-unique-pairs'
    }
    if (signal?.aborted) throw new DescriptorCorrespondenceError('cancelled')
    return {
      pairs: reduced.pairs,
      diagnostics: diagnostics(
        queryRawCount,
        referenceRawCount,
        queryRows.length,
        referenceRows.length,
        forward.length,
        reverse.length,
        reduced.reciprocalCount,
        reduced.pairs.length,
        noEvidence,
      ),
    }
  } finally {
    for (const row of queryRows) row.values.fill(0)
    for (const row of referenceRows) row.values.fill(0)
  }
}

/** Re-export for callers mapping bridge failures. */
export { PinnedMatcherError }
