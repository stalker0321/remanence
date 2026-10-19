import type { RawCv } from './opencvRuntime'

/**
 * T330 — typed bridge over the EXISTING pinned OpenCV.js runtime for the
 * descriptor-correspondence KNN stage only. Root's actual-runtime probe on
 * the committed vendor assets confirmed `BFMatcher` /
 * `DescriptorMatcher.knnMatch` / `DMatchVector` / `VectorVector` exports and
 * `new cv.BFMatcher(cv.NORM_L2, false)` construction; this module binds that
 * surface carefully (constructor/symbol presence alone is not proof — the
 * connected browser proof executes real knnMatch). No distance kernel is
 * rewritten in JS, no vendor/whitelist/dependency change, no duplicate
 * loader: callers obtain the raw module from the shared `loadOpenCvModule`.
 *
 * OWNERSHIP: every Mat/matcher/DMatch-vector handle this bridge creates is
 * owned here — CV_32F buffers are wiped, then every handle is released on
 * success, contract refusal, native throw and cancellation, preserving the
 * typed redacted primary failure (cleanup still attempts the rest).
 */

export interface CvDMatch {
  readonly queryIdx: number
  readonly trainIdx: number
  readonly distance: number
}

/** Two-direction KNN-2 result with plain copied scalar matches. */
export interface PinnedKnnResult {
  readonly forward: readonly (readonly CvDMatch[])[]
  readonly reverse: readonly (readonly CvDMatch[])[]
}

export interface PinnedMatcherRuntime {
  readonly kind: 'pinned-opencv'
  knnMatch2(queryRows: readonly Float32Array[], referenceRows: readonly Float32Array[]): Promise<PinnedKnnResult>
}

/** Bounded, redacted bridge refusal. Never carries row/point data. */
export class PinnedMatcherError extends Error {
  constructor(readonly code: 'unavailable' | 'native-contract' | 'internal') {
    super(code)
    this.name = 'PinnedMatcherError'
  }
}

interface RawDMatch {
  queryIdx: number
  trainIdx: number
  distance: number
}

interface RawDMatchVector {
  size(): number
  get(index: number): RawDMatch
  delete?(): void
}

interface RawDMatchVectorVector {
  size(): number
  get(index: number): RawDMatchVector
  delete?(): void
}

interface RawMatcher {
  knnMatch(
    query: unknown,
    train: unknown,
    k: number,
  ): RawDMatchVectorVector
  delete?(): void
  clear?(): void
}

interface RawMatLike {
  rows: number
  cols: number
  data32F: Float32Array
  type(): number
  delete(): void
}

function releaseAll(actions: readonly (() => void)[]): void {
  for (const action of actions) {
    try {
      action()
    } catch {
      /* release is best-effort; every remaining handle is still attempted */
    }
  }
}

/** Copies DMatch scalars out of a nested vector; each row handle returned by
 * `parent.get(anchor)` is OWNED here and released (success AND throw) —
 * deleting the parent does NOT release returned child vectors (root proof). */
function copyKnnRows(
  vector: RawDMatchVectorVector,
  anchorCount: number,
  trainCount: number,
): CvDMatch[][] {
  if (typeof vector.size !== 'function' || typeof vector.get !== 'function') {
    throw new PinnedMatcherError('native-contract')
  }
  const rowCount = vector.size()
  if (rowCount !== anchorCount) throw new PinnedMatcherError('native-contract')
  const rows: CvDMatch[][] = []
  for (let anchor = 0; anchor < rowCount; anchor++) {
    const row = vector.get(anchor)
    if (row === undefined || row === null) throw new PinnedMatcherError('native-contract')
    try {
      if (typeof row.size !== 'function' || typeof row.get !== 'function') {
        throw new PinnedMatcherError('native-contract')
      }
      const count = row.size()
      if (count > 2) throw new PinnedMatcherError('native-contract')
      const copied: CvDMatch[] = []
      for (let index = 0; index < count; index++) {
        const match = row.get(index)
        if (
          typeof match?.queryIdx !== 'number'
          || typeof match.trainIdx !== 'number'
          || typeof match.distance !== 'number'
          || match.queryIdx !== anchor
          || match.trainIdx < 0
          || match.trainIdx >= trainCount
        ) {
          throw new PinnedMatcherError('native-contract')
        }
        copied.push({ queryIdx: match.queryIdx, trainIdx: match.trainIdx, distance: Number(match.distance) })
      }
      rows.push(copied)
    } finally {
      // The nested DMatchVector is a live OWNED handle; release it after the
      // scalar copy (or after any validation throw). DMatch scalars copied
      // above are plain numbers, not native handles.
      try {
        row.delete?.()
      } catch {
        /* best-effort release; remaining rows/parents are still attempted */
      }
    }
  }
  return rows
}

/**
 * Ownership begins IMMEDIATELY after the constructor succeeds: any later
 * shape/getter/view.set failure wipes the already-copied data and releases
 * the Mat in the same call (no bare delete, no abandoned partial allocation).
 * On success the Mat transfers to the caller's cleanup list.
 */
function matFromRows(cv: RawCv, rows: readonly Float32Array[]): RawMatLike {
  if (rows.length === 0) throw new PinnedMatcherError('native-contract')
  const width = rows[0]!.length
  const mat = new cv.Mat(rows.length, width, cv.CV_32F) as unknown as RawMatLike
  let transferred = false
  try {
    const view = mat.data32F
    if (view.length !== rows.length * width) throw new PinnedMatcherError('native-contract')
    let offset = 0
    for (const row of rows) {
      if (row.length !== width) throw new PinnedMatcherError('native-contract')
      view.set(row, offset)
      offset += width
    }
    transferred = true
    return mat
  } finally {
    if (!transferred) {
      try {
        mat.data32F.fill(0)
      } catch {
        /* best-effort wipe before release */
      }
      try {
        mat.delete()
      } catch {
        /* best-effort release */
      }
    }
  }
}

interface RawMatcherAny extends RawMatcher {
  knnMatch1?: {
    (query: unknown, train: unknown, k: number): RawDMatchVectorVector
    (query: unknown, train: unknown, out: unknown, k: number): void
  }
}

/**
 * Binds the ACTUAL embind knnMatch overload carefully: the committed runtime
 * exposes both `knnMatch` and `knnMatch1` (DescriptorMatcher prototype,
 * root probe) and the returned/out-param shapes are verified structurally
 * before use. The first working overload wins; every failed attempt is
 * cleaned up and the last error preserved for the typed refusal.
 */
function runKnnMatch(cv: RawCv, matcher: RawMatcherAny, a: unknown, b: unknown): RawDMatchVectorVector {
  const DMatchVectorVectorCtor = (cv as unknown as { DMatchVectorVector?: new () => RawDMatchVectorVector }).DMatchVectorVector
  const attempts: Array<() => RawDMatchVectorVector> = [
    () => matcher.knnMatch(a, b, 2),
    () => matcher.knnMatch1!(a, b, 2),
    () => {
      const out = new DMatchVectorVectorCtor!()
      try {
        ;(matcher as unknown as { knnMatch(q: unknown, t: unknown, out: unknown, k: number): void }).knnMatch(a, b, out, 2)
        return out
      } catch (error) {
        out.delete?.()
        throw error
      }
    },
    () => {
      const out = new DMatchVectorVectorCtor!()
      try {
        matcher.knnMatch1!(a, b, out, 2)
        return out
      } catch (error) {
        out.delete?.()
        throw error
      }
    },
  ]
  let last: unknown = null
  for (const attempt of attempts) {
    try {
      const result = attempt()
      if (result && typeof result.size === 'function' && typeof result.get === 'function') return result
      if (result && typeof (result as { delete?: () => void }).delete === 'function') {
        (result as { delete: () => void }).delete()
      }
      last = new Error('unexpected knn result shape')
    } catch (error) {
      last = error
    }
  }
  throw last instanceof Error ? last : new Error('knnMatch unavailable')
}

/**
 * Builds the pinned matcher runtime over the SHARED loaded raw module.
 * `knnMatch2` owns every handle it creates for the duration of one call.
 */
export function createPinnedMatcherRuntime(cv: RawCv): PinnedMatcherRuntime {
  if (typeof cv.Mat !== 'function' || typeof (cv as { BFMatcher?: unknown }).BFMatcher !== 'function') {
    throw new PinnedMatcherError('unavailable')
  }
  return {
    kind: 'pinned-opencv',
    async knnMatch2(queryRows, referenceRows) {
      if (queryRows.length === 0 || referenceRows.length === 0) {
        throw new PinnedMatcherError('native-contract')
      }
      const cleanup: (() => void)[] = []
      try {
        const queryMat = matFromRows(cv, queryRows)
        cleanup.push(() => {
          try {
            queryMat.data32F.fill(0)
          } catch {
            /* best-effort wipe before release */
          }
          queryMat.delete()
        })
        const referenceMat = matFromRows(cv, referenceRows)
        cleanup.push(() => {
          try {
            referenceMat.data32F.fill(0)
          } catch {
            /* best-effort wipe before release */
          }
          referenceMat.delete()
        })
        const Ctor = (cv as unknown as {
          BFMatcher: new (normType: number, crossCheck: boolean) => RawMatcher
        }).BFMatcher
        const matcher = new Ctor(cv.NORM_L2, false) as RawMatcherAny
        cleanup.push(() => {
          try {
            matcher.clear?.()
          } catch {
            /* best-effort */
          }
          matcher.delete?.()
        })
        // Bound to the ACTUAL embind overload(s) with structural result
        // verification; the connected proof executes the real knnMatch.
        const forwardVector = runKnnMatch(cv, matcher, queryMat, referenceMat)
        cleanup.push(() => {
          try {
            forwardVector.delete?.()
          } catch {
            /* best-effort */
          }
        })
        const reverseVector = runKnnMatch(cv, matcher, referenceMat, queryMat)
        cleanup.push(() => {
          try {
            reverseVector.delete?.()
          } catch {
            /* best-effort */
          }
        })
        const forward = copyKnnRows(forwardVector, queryRows.length, referenceRows.length)
        const reverse = copyKnnRows(reverseVector, referenceRows.length, queryRows.length)
        return { forward, reverse }
      } catch (error) {
        if (error instanceof PinnedMatcherError) throw error
        throw new PinnedMatcherError('internal')
      } finally {
        // Success AND failure: scalars are already copied out; every owned
        // handle is wiped/released (best-effort, all attempted).
        releaseAll(cleanup)
      }
    },
  }
}
