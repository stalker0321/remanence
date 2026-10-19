/**
 * T330 unit gate: descriptor correspondence stage.
 *
 * Part A anchors the pure stages to the committed native-source contract
 * (SiftRootSiftMatcher.kt + its tests; hand vectors copied from that
 * source, honestly labeled — no Android execution claim). Part B exercises
 * the pinned-bridge lifecycle/contract with a FAKE matcher runtime (not
 * actual WASM acceptance; the connected browser proof runs the real one).
 */
import { describe, expect, it, vi } from 'vitest'
import {
  DescriptorCorrespondenceError,
  matchDescriptors,
  passesStrictRatioForCorrespondence,
  rootSiftRowForCorrespondence,
  rootSiftRowsForCorrespondence,
  type CorrespondenceResult,
} from '../src/scan/descriptorCorrespondence'
import type { CvDMatch, PinnedMatcherRuntime, PinnedKnnResult } from '../src/scan/opencvMatcherPort'
import { PinnedMatcherError } from '../src/scan/opencvMatcherPort'
import { rint } from '../src/scan/siftExtractor'
import { serializeSiftFingerprint, type SiftFingerprint, type SiftFingerprintKeypoint } from '../src/scan/siftFingerprint'

function keypoint(xMicro: number, yMicro: number): SiftFingerprintKeypoint {
  return { xMicro, yMicro, scaleMicro: 1, angleCentiDegrees: 0, responseQuantized: 1, octave: 0 }
}

function fingerprintWith(rows: Uint8Array[], keypoints?: SiftFingerprintKeypoint[]): SiftFingerprint {
  const points = keypoints ?? rows.map((_row, index) => keypoint(100_000 + index * 100_000, 100_000))
  return {
    profileId: 'postcard-sift-rootsift-v1',
    canonicalWidthPx: 320,
    canonicalHeightPx: 200,
    coarseHash64: 0x1020304050607080n,
    keypoints: points,
    quantizedSiftDescriptors: rows,
  }
}

function bits(value: number): number {
  const buffer = new DataView(new ArrayBuffer(4))
  buffer.setFloat32(0, value, true)
  return buffer.getUint32(0, true)
}

function fakeMatcher(result: PinnedKnnResult | (() => Promise<PinnedKnnResult>)): PinnedMatcherRuntime {
  return {
    kind: 'pinned-opencv',
    knnMatch2: async () => (typeof result === 'function' ? result() : result),
  }
}

function match(query: SiftFingerprint, reference: SiftFingerprint, matcher: PinnedMatcherRuntime | null, signal?: AbortSignal): Promise<CorrespondenceResult> {
  return matchDescriptors(query, reference, { matcher: matcher ?? undefined, matcherFactory: matcher ? undefined : async () => { throw new PinnedMatcherError('unavailable') }, signal })
}

describe('A — native-source-anchored pure stages', () => {
  it('RootSIFT float32-before-sqrt matches the committed four golden raw bits', () => {
    // Native SiftRootSiftMatcherTest.rootSiftMatchesFloat32GoldenVectorExactly
    // input bytes {1,2,127,0xFF} + expected bits {0x3D50C061,0x3D939C0E,0x3F130828,0x3F5057E7}.
    const raw = new Uint8Array(128)
    raw[0] = 1
    raw[1] = 2
    raw[2] = 127
    raw[3] = 0xff
    const rooted = rootSiftRowForCorrespondence(raw)
    expect(rooted).not.toBeNull()
    expect(bits(rooted![0]!)).toBe(0x3d50c061)
    expect(bits(rooted![1]!)).toBe(0x3d939c0e)
    expect(bits(rooted![2]!)).toBe(0x3f130828)
    expect(bits(rooted![3]!)).toBe(0x3f5057e7)
    // Caller input is never mutated.
    expect(raw[0]).toBe(1)
    expect(raw[3]).toBe(0xff)
  })

  it('zero-sum rows are omitted and original indices stay stable', () => {
    const zero = new Uint8Array(128)
    const nonzero = new Uint8Array(128)
    nonzero[5] = 9
    const fingerprint = fingerprintWith([zero, nonzero, zero])
    const rows = rootSiftRowsForCorrespondence(fingerprint)
    expect(rows.length).toBe(1)
    expect(rows[0]!.originalIndex).toBe(1)
  })

  it('strict ratio boundary, zero/NaN/negative/infinity fail closed', () => {
    expect(passesStrictRatioForCorrespondence(0.74, 1)).toBe(true)
    expect(passesStrictRatioForCorrespondence(0.75, 1)).toBe(false)
    expect(passesStrictRatioForCorrespondence(0.7499999, 1)).toBe(true)
    expect(passesStrictRatioForCorrespondence(0, 1)).toBe(true)
    expect(passesStrictRatioForCorrespondence(1, 0)).toBe(false)
    expect(passesStrictRatioForCorrespondence(Number.NaN, 1)).toBe(false)
    expect(passesStrictRatioForCorrespondence(1, Number.NaN)).toBe(false)
    expect(passesStrictRatioForCorrespondence(-1, 1)).toBe(false)
    expect(passesStrictRatioForCorrespondence(1, -1)).toBe(false)
    expect(passesStrictRatioForCorrespondence(Number.POSITIVE_INFINITY, 1)).toBe(false)
  })

  it('fewer than two KNN candidates never ratio-match (native ratioMatches contract)', async () => {
    const fingerprint = fingerprintWith([new Uint8Array(128).fill(3)])
    const single: CvDMatch[] = [{ queryIdx: 0, trainIdx: 0, distance: 0.1 }]
    const result = await match(fingerprint, fingerprint, fakeMatcher({
      forward: [single],
      reverse: [single],
    }))
    expect(result.diagnostics.forwardRatioMatches).toBe(0)
    expect(result.diagnostics.reverseRatioMatches).toBe(0)
    expect(result.diagnostics.noEvidence).toBe('no-ratio-matches')
    expect(result.pairs.length).toBe(0)
  })

  it('all-zero storage-valid rows produce no-usable-rows without touching the matcher', async () => {
    const zeros = fingerprintWith([new Uint8Array(128), new Uint8Array(128)])
    const knn = vi.fn()
    const result = await match(zeros, zeros, { kind: 'pinned-opencv', knnMatch2: knn })
    expect(knn).not.toHaveBeenCalled()
    expect(result.diagnostics.noEvidence).toBe('no-usable-rows')
    expect(result.diagnostics.usableQueryRows).toBe(0)
    expect(result.diagnostics.rawQueryRows).toBe(2)
  })

  it('reciprocal reduction maps local indices back to ORIGINAL indices and sorts deterministically', async () => {
    // Three usable query rows, three reference rows. Forward passes ratio for
    // (0→1) and (1→0) plus a NON-reciprocal (2→2); reverse passes (1→0) and
    // (0→1). Unique pairs must be the two reciprocal ones, sorted by
    // (distance, queryIndex, referenceIndex), original indices preserved.
    const make = (fill: number): Uint8Array => new Uint8Array(128).fill(fill)
    const query = fingerprintWith([make(5), make(6), make(7)])
    const reference = fingerprintWith([make(9), make(10), make(11)])
    const forward: CvDMatch[][] = [
      [{ queryIdx: 0, trainIdx: 1, distance: 0.10 }, { queryIdx: 0, trainIdx: 2, distance: 0.9 }],
      [{ queryIdx: 1, trainIdx: 0, distance: 0.20 }, { queryIdx: 1, trainIdx: 2, distance: 0.9 }],
      [{ queryIdx: 2, trainIdx: 2, distance: 0.30 }, { queryIdx: 2, trainIdx: 0, distance: 0.9 }],
    ]
    const reverse: CvDMatch[][] = [
      [{ queryIdx: 0, trainIdx: 1, distance: 0.20 }, { queryIdx: 0, trainIdx: 2, distance: 0.9 }],
      [{ queryIdx: 1, trainIdx: 0, distance: 0.10 }, { queryIdx: 1, trainIdx: 2, distance: 0.9 }],
      [{ queryIdx: 2, trainIdx: 0, distance: 0.99 }, { queryIdx: 2, trainIdx: 1, distance: 0.98 }],
    ]
    const result = await match(query, reference, fakeMatcher({ forward, reverse }))
    expect(result.diagnostics.forwardRatioMatches).toBe(3)
    expect(result.diagnostics.reverseRatioMatches).toBe(2)
    expect(result.diagnostics.reciprocalMatches).toBe(2)
    expect(result.diagnostics.noEvidence).toBe('insufficient-unique-pairs')
    expect(result.pairs.map((pair) => [pair.queryIndex, pair.referenceIndex, pair.distance])).toEqual([
      [0, 1, 0.1],
      [1, 0, 0.2],
    ])
  })

  it('deduplicates only query pixels (ties-to-even rint) and keeps reference repeats', async () => {
    // canonicalWidth=320: xMicro 3750 → 1.2 → rint 1; xMicro 4375 → 1.4 →
    // rint 1 (same query pixel). rint half-ties are ties-to-even.
    expect(rint(2.5)).toBe(2)
    expect(rint(1.5)).toBe(2)
    expect(rint(3.5)).toBe(4)
    const make = (fill: number): Uint8Array => new Uint8Array(128).fill(fill)
    const query = fingerprintWith(
      [make(4), make(5)],
      [keypoint(3_750, 100_000), keypoint(4_375, 100_000)],
    )
    // Reference keypoints 0 and 1 round to the SAME reference pixel too;
    // reference-side repeats must NOT be deduplicated.
    const reference = fingerprintWith(
      [make(8), make(9)],
      [keypoint(156_250, 100_000), keypoint(156_260, 100_000)],
    )
    const forward: CvDMatch[][] = [
      [{ queryIdx: 0, trainIdx: 0, distance: 0.10 }, { queryIdx: 0, trainIdx: 1, distance: 0.9 }],
      [{ queryIdx: 1, trainIdx: 1, distance: 0.05 }, { queryIdx: 1, trainIdx: 0, distance: 0.9 }],
    ]
    const reverse: CvDMatch[][] = [
      [{ queryIdx: 0, trainIdx: 0, distance: 0.10 }, { queryIdx: 0, trainIdx: 1, distance: 0.9 }],
      [{ queryIdx: 1, trainIdx: 1, distance: 0.05 }, { queryIdx: 1, trainIdx: 0, distance: 0.9 }],
    ]
    const result = await match(query, reference, fakeMatcher({ forward, reverse }))
    // Both query locals map to rounded pixel (1, 20); after the (distance)
    // sort the better q1 pair survives and q0 is dropped by QUERY-only dedup.
    expect(result.diagnostics.reciprocalMatches).toBe(2)
    expect(result.pairs.length).toBe(1)
    expect(result.pairs[0]!.queryIndex).toBe(1)
    expect(result.pairs[0]!.referenceIndex).toBe(1)
    expect(result.pairs[0]!.distance).toBe(0.05)
  })

  it('equal distances sort by queryIndex then referenceIndex', async () => {
    const make = (fill: number): Uint8Array => new Uint8Array(128).fill(fill)
    const query = fingerprintWith([make(1), make(2)], [keypoint(100_000, 100_000), keypoint(400_000, 100_000)])
    const reference = fingerprintWith([make(3), make(4)], [keypoint(100_000, 100_000), keypoint(400_000, 100_000)])
    const forward: CvDMatch[][] = [
      [{ queryIdx: 0, trainIdx: 1, distance: 0.2 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }],
      [{ queryIdx: 1, trainIdx: 0, distance: 0.2 }, { queryIdx: 1, trainIdx: 1, distance: 0.9 }],
    ]
    const reverse: CvDMatch[][] = [
      [{ queryIdx: 0, trainIdx: 1, distance: 0.2 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }],
      [{ queryIdx: 1, trainIdx: 0, distance: 0.2 }, { queryIdx: 1, trainIdx: 1, distance: 0.9 }],
    ]
    const result = await match(query, reference, fakeMatcher({ forward, reverse }))
    expect(result.pairs.map((pair) => [pair.queryIndex, pair.referenceIndex])).toEqual([
      [0, 1],
      [1, 0],
    ])
  })
})

describe('B — pinned-bridge lifecycle/contract with a fake runtime', () => {
  it('rejects invalid fingerprint shapes before any matcher call', async () => {
    const valid = fingerprintWith([new Uint8Array(128).fill(2)])
    const knn = vi.fn()
    const invalid = { ...valid, profileId: 'wrong-profile' } as SiftFingerprint
    await expect(match(invalid, valid, { kind: 'pinned-opencv', knnMatch2: knn })).rejects.toMatchObject({ code: 'shape' })
    expect(knn).not.toHaveBeenCalled()
  })

  it('missing runtime refuses unavailable (engine remains refused, no fake success)', async () => {
    const valid = fingerprintWith([new Uint8Array(128).fill(2)])
    await expect(matchDescriptors(valid, valid, {})).rejects.toMatchObject({ code: 'unavailable' })
  })

  it('a failing matcher factory propagates a typed redacted refusal', async () => {
    const valid = fingerprintWith([new Uint8Array(128).fill(2)])
    await expect(matchDescriptors(valid, valid, {
      matcherFactory: async () => { throw new PinnedMatcherError('native-contract') },
    })).rejects.toMatchObject({ code: 'native-contract' })
  })

  it('aborted signal refuses cancelled before the matcher runs', async () => {
    const valid = fingerprintWith([new Uint8Array(128).fill(2)])
    const knn = vi.fn()
    const controller = new AbortController()
    controller.abort()
    await expect(matchDescriptors(valid, valid, {
      matcher: { kind: 'pinned-opencv', knnMatch2: knn },
      signal: controller.signal,
    })).rejects.toMatchObject({ code: 'cancelled' })
    expect(knn).not.toHaveBeenCalled()
  })

  it('an abort landing while the matcher promise is pending refuses cancelled and yields no pairs', async () => {
    const valid = fingerprintWith([new Uint8Array(128).fill(2)])
    const controller = new AbortController()
    let release!: (value: PinnedKnnResult) => void
    const gate = new Promise<PinnedKnnResult>((resolve) => { release = resolve })
    const pending = matchDescriptors(valid, valid, {
      matcher: { kind: 'pinned-opencv', knnMatch2: () => gate },
      signal: controller.signal,
    }).then(
      (result) => ({ kind: 'result' as const, result }),
      (error: unknown) => ({ kind: 'error' as const, error }),
    )
    controller.abort()
    release({ forward: [[{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }]], reverse: [[{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }]] })
    const outcome = await pending
    expect(outcome.kind).toBe('error')
    if (outcome.kind === 'error') expect(outcome.error).toBeInstanceOf(DescriptorCorrespondenceError)
  })

  it('caller descriptor rows are never mutated or wiped by the stage', async () => {
    const make = (fill: number): Uint8Array => new Uint8Array(128).fill(fill)
    const query = fingerprintWith([make(3)])
    const reference = fingerprintWith([make(4)])
    const queryCopy = query.quantizedSiftDescriptors[0]!.slice()
    const referenceCopy = reference.quantizedSiftDescriptors[0]!.slice()
    await match(query, reference, fakeMatcher({
      forward: [[{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }]],
      reverse: [[{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }]],
    }))
    expect(query.quantizedSiftDescriptors[0]).toEqual(queryCopy)
    expect(reference.quantizedSiftDescriptors[0]).toEqual(referenceCopy)
  })

  it('invalid KNN row counts and out-of-range indices refuse native-contract', async () => {
    const valid = fingerprintWith([new Uint8Array(128).fill(2), new Uint8Array(128).fill(3)])
    const badRows: CvDMatch[][] = [[{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 1, distance: 0.2 }]]
    await expect(match(valid, valid, fakeMatcher({ forward: badRows, reverse: badRows })))
      .rejects.toMatchObject({ code: 'native-contract' })
    const badIndex: CvDMatch[][] = [
      [{ queryIdx: 0, trainIdx: 5, distance: 0.1 }, { queryIdx: 0, trainIdx: 6, distance: 0.2 }],
      [{ queryIdx: 1, trainIdx: 0, distance: 0.1 }, { queryIdx: 1, trainIdx: 1, distance: 0.2 }],
    ]
    await expect(match(valid, valid, fakeMatcher({ forward: badIndex, reverse: badIndex })))
      .rejects.toMatchObject({ code: 'native-contract' })
  })

  it('serialization round trip keeps the validated shape accepted (format-3 reuse)', async () => {
    const fingerprint = fingerprintWith([new Uint8Array(128).fill(8)])
    const wire = serializeSiftFingerprint(fingerprint)
    expect(wire.length).toBeGreaterThan(0)
    const knn = vi.fn(async () => ({
      forward: [[{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }]],
      reverse: [[{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 0, distance: 0.9 }]],
    }))
    const result = await match(fingerprint, fingerprint, { kind: 'pinned-opencv', knnMatch2: knn })
    expect(knn).toHaveBeenCalledTimes(1)
    expect(result.diagnostics.usableQueryRows).toBe(1)
    expect(result.diagnostics.noEvidence).toBe('insufficient-unique-pairs')
    expect(result.pairs.length).toBe(1)
  })
})
