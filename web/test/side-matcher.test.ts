import { describe, expect, it, vi } from 'vitest'
import { MIN_GEOMETRY_PAIRS, matchSide, SideMatchError } from '../src/scan/sideMatcher'
import {
  DescriptorCorrespondenceError,
  type CorrespondenceNoEvidence,
  type CorrespondencePair,
  type CorrespondenceResult,
} from '../src/scan/descriptorCorrespondence'
import { GeometryEstimatorError } from '../src/scan/geometryEstimator'
import type { GeometryEvaluationResult } from '../src/scan/geometryEvaluation'
import type { EstimateAndEvaluateInput } from '../src/scan/geometryEstimator'
import {
  createPinnedMatcherRuntime,
  PinnedMatcherError,
  type PinnedMatcherRuntime,
} from '../src/scan/opencvMatcherPort'
import type { RawCv } from '../src/scan/opencvRuntime'
import type { SiftFingerprint, SiftFingerprintKeypoint } from '../src/scan/siftFingerprint'

function keypoint(xMicro: number, yMicro: number): SiftFingerprintKeypoint {
  return { xMicro, yMicro, scaleMicro: 1000, angleCentiDegrees: 0, responseQuantized: 1000, octave: 0 }
}

function fingerprint(width: number, height: number, keypoints: SiftFingerprintKeypoint[]): SiftFingerprint {
  return {
    profileId: 'postcard-sift-rootsift-v1',
    canonicalWidthPx: width,
    canonicalHeightPx: height,
    coarseHash64: 0n,
    keypoints,
    quantizedSiftDescriptors: keypoints.map((_, index) => new Uint8Array(128).fill(1 + index)),
  }
}

function pair(queryIndex: number, referenceIndex: number): CorrespondencePair {
  return { queryIndex, referenceIndex, distance: 1 }
}

function correspondence(
  pairs: CorrespondencePair[],
  uniqueMatches: number,
  noEvidence: CorrespondenceNoEvidence | null = null,
): CorrespondenceResult {
  return {
    pairs,
    diagnostics: {
      rawQueryRows: 10,
      rawReferenceRows: 10,
      usableQueryRows: 10,
      usableReferenceRows: 10,
      forwardRatioMatches: uniqueMatches,
      reverseRatioMatches: uniqueMatches,
      reciprocalMatches: uniqueMatches,
      uniqueMatches,
      noEvidence,
    },
  }
}

function geometry(overrides: Partial<GeometryEvaluationResult> = {}): GeometryEvaluationResult {
  return {
    found: true,
    accepted: false,
    matrix: null,
    inlierIndices: [],
    inlierRatio: 0,
    medianErrorPx: -1,
    referenceCoverage: -1,
    supportAreaPx2: 0,
    supportEdgeRatio: 0,
    failure: 'NONE',
    ...overrides,
  }
}

function spreadKeypoints(count: number): SiftFingerprintKeypoint[] {
  return Array.from({ length: count }, (_, index) => {
    const column = index % 5
    const row = Math.floor(index / 5)
    return keypoint(Math.round((0.05 + column * 0.2) * 1_000_000), Math.round((0.05 + row * 0.2) * 1_000_000))
  })
}

const NO_MATCHER: PinnedMatcherRuntime = { kind: 'pinned-opencv', knnMatch2: async () => ({ forward: [], reverse: [] }) }

describe('T339/T341 connected side pipeline — >=6 gate and early no-evidence', () => {
  it('calls the estimator only for >=6 unique pairs', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(6))
    const reference = fingerprint(1600, 1200, spreadKeypoints(6))
    const pairs = Array.from({ length: 6 }, (_, index) => pair(index, index))
    const estimate = vi.fn(async () => geometry({ accepted: true, matrix: [1, 0, 0, 0, 1, 0, 0, 0, 1], inlierIndices: pairs.map((_, index) => index), inlierRatio: 1, medianErrorPx: 0 }))
    const result = await matchSide(query, reference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async () => correspondence(pairs, 6),
      estimateGeometry: estimate,
    })
    expect(MIN_GEOMETRY_PAIRS).toBe(6)
    expect(estimate).toHaveBeenCalledTimes(1)
    expect(result.geometry).not.toBeNull()
    expect(result.diagnostics.geometryAttempted).toBe(true)
  })

  it('five unique pairs skip the estimator and return truthful sentinels', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(5))
    const reference = fingerprint(1600, 1200, spreadKeypoints(5))
    const pairs = Array.from({ length: 5 }, (_, index) => pair(index, index))
    const estimate = vi.fn(async () => geometry())
    const result = await matchSide(query, reference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async () => correspondence(pairs, 5, 'insufficient-unique-pairs'),
      estimateGeometry: estimate,
    })
    expect(estimate).not.toHaveBeenCalled()
    expect(result.geometry).toBeNull()
    expect(result.diagnostics.failure).toBe('INSUFFICIENT_UNIQUE_PAIRS')
    expect(result.signals).toMatchObject({
      ratioMutualMatches: 5,
      ransacInliers: 0,
      inlierRatio: 0,
      spatialCoverage: 0,
      occupiedGridCells: 0,
      medianInlierErrorNormalized: 1,
      homographyPlausible: false,
    })
    expect(result.report.weakGatePassed).toBe(false)
    expect(result.report.strongGatePassed).toBe(false)
  })

  it('no-ratio / no-reciprocal / no-usable-rows map to their native failures', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(1))
    const reference = fingerprint(1600, 1200, spreadKeypoints(1))
    for (const [noEvidence, expected] of [
      ['no-usable-rows', 'NO_USABLE_ROWS'],
      ['no-ratio-matches', 'NO_RATIO_MATCHES'],
      ['no-reciprocal-matches', 'NO_RECIPROCAL_MATCHES'],
    ] as const) {
      const result = await matchSide(query, reference, {
        matcherFactory: async () => NO_MATCHER,
        matchCorrespondence: async () => correspondence([], 0, noEvidence),
        estimateGeometry: async () => geometry(),
      })
      expect(result.diagnostics.failure).toBe(expected)
      expect(result.report.weakGatePassed).toBe(false)
    }
  })
})

describe('T341 connected side pipeline — pixel mapping and policy', () => {
  it('maps DOUBLE pixels from the owned snapshots in correspondence order', async () => {
    const query = fingerprint(1600, 1200, [
      keypoint(0, 0),
      keypoint(500000, 500000),
      keypoint(200000, 300000),
      keypoint(400000, 600000),
      keypoint(700000, 800000),
      keypoint(900000, 100000),
    ])
    const reference = fingerprint(800, 600, [
      keypoint(100000, 200000),
      keypoint(300000, 400000),
      keypoint(500000, 600000),
      keypoint(700000, 800000),
      keypoint(900000, 100000),
      keypoint(200000, 300000),
    ])
    const pairs = Array.from({ length: 6 }, (_, index) => pair(index, index))
    let seen: EstimateAndEvaluateInput | null = null
    const result = await matchSide(query, reference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async () => correspondence(pairs, 6),
      estimateGeometry: async (input) => {
        seen = { ...input, referencePoints: input.referencePoints.map((point) => ({ ...point })), queryPoints: input.queryPoints.map((point) => ({ ...point })) }
        return geometry({ accepted: false, failure: 'INVALID_HOMOGRAPHY' })
      },
    })
    expect(seen!.referenceWidthPx).toBe(800)
    expect(seen!.referenceHeightPx).toBe(600)
    expect(seen!.referencePoints.slice(0, 2)).toEqual([
      { x: 0.1 * 800, y: 0.2 * 600 },
      { x: 0.3 * 800, y: 0.4 * 600 },
    ])
    expect(seen!.queryPoints.slice(0, 2)).toEqual([
      { x: 0, y: 0 },
      { x: 0.5 * 1600, y: 0.5 * 1200 },
    ])
    expect(result.diagnostics.failure).toBe('INVALID_HOMOGRAPHY')
  })

  it('geometry refusal cannot produce weak/strong acceptance', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(20))
    const reference = fingerprint(1600, 1200, spreadKeypoints(20))
    const pairs = Array.from({ length: 20 }, (_, index) => pair(index, index))
    const result = await matchSide(query, reference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async () => correspondence(pairs, 25),
      estimateGeometry: async () =>
        geometry({ accepted: false, matrix: [1, 0, 0, 0, 1, 0, 0, 0, 1], inlierIndices: pairs.map((_, index) => index), inlierRatio: 1, medianErrorPx: 0 }),
    })
    expect(result.signals.homographyPlausible).toBe(false)
    expect(result.report.weakGatePassed).toBe(false)
    expect(result.report.strongGatePassed).toBe(false)
  })

  it('successful accepted geometry passes the unchanged policy to strong', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(20))
    const reference = fingerprint(1600, 1200, spreadKeypoints(20))
    const pairs = Array.from({ length: 20 }, (_, index) => pair(index, index))
    const result = await matchSide(query, reference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async () => correspondence(pairs, 25),
      estimateGeometry: async () =>
        geometry({ accepted: true, matrix: [1, 0, 0, 0, 1, 0, 0, 0, 1], inlierIndices: pairs.map((_, index) => index), inlierRatio: 0.5, medianErrorPx: 1.6 }),
    })
    expect(result.signals.homographyPlausible).toBe(true)
    expect(result.report.weakGatePassed).toBe(true)
    expect(result.report.strongGatePassed).toBe(true)
    expect(result.diagnostics.geometryAccepted).toBe(true)
  })
})

describe('T341 connected side pipeline — ownership, abort/fence, faults', () => {
  it('later caller mutation cannot affect geometry/scoring; caller rows are not wiped', async () => {
    const callerQuery = fingerprint(1600, 1200, [keypoint(100000, 200000), keypoint(300000, 400000)])
    const callerReference = fingerprint(800, 600, [keypoint(100000, 200000), keypoint(300000, 400000)])
    const before = callerQuery.quantizedSiftDescriptors[0]!.slice()
    let seen: EstimateAndEvaluateInput | null = null
    await matchSide(callerQuery, callerReference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async () => {
        callerQuery.keypoints[0]!.xMicro = 999999
        callerQuery.canonicalWidthPx = 1
        callerQuery.quantizedSiftDescriptors[0]!.fill(7)
        return correspondence([pair(0, 0), pair(1, 1), pair(0, 1), pair(1, 0), pair(0, 0), pair(1, 1)], 6)
      },
      estimateGeometry: async (input) => {
        seen = { ...input, referencePoints: input.referencePoints.map((point) => ({ ...point })), queryPoints: input.queryPoints.map((point) => ({ ...point })) }
        return geometry({ accepted: false })
      },
    })
    expect(seen!.referenceWidthPx).toBe(800)
    expect(seen!.queryPoints[0]).toEqual({ x: 0.1 * 1600, y: 0.2 * 1200 })
    expect(callerQuery.quantizedSiftDescriptors[0]![0]).toBe(7)
    expect(callerQuery.quantizedSiftDescriptors[0]).not.toEqual(before)
  })

  it('wipes owned snapshot descriptor rows on success and on failure', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(6))
    const reference = fingerprint(1600, 1200, spreadKeypoints(6))
    let captured: SiftFingerprint[] = []
    const capture = (snapshotQuery: SiftFingerprint, snapshotReference: SiftFingerprint): void => {
      captured = [snapshotQuery, snapshotReference]
    }
    await matchSide(query, reference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async (q, r) => {
        capture(q, r)
        return correspondence(Array.from({ length: 6 }, (_, index) => pair(index, index)), 6)
      },
      estimateGeometry: async () => geometry({ accepted: false }),
    })
    for (const fingerprintSnapshot of captured) {
      for (const row of fingerprintSnapshot.quantizedSiftDescriptors) expect(row.every((byte) => byte === 0)).toBe(true)
    }
    expect(query.quantizedSiftDescriptors[0]!.some((byte) => byte !== 0)).toBe(true)

    let capturedFailure: SiftFingerprint[] = []
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => NO_MATCHER,
        matchCorrespondence: async (q, r) => {
          capturedFailure = [q, r]
          throw new DescriptorCorrespondenceError('internal')
        },
      }),
    ).rejects.toMatchObject({ code: 'internal' })
    for (const fingerprintSnapshot of capturedFailure) {
      for (const row of fingerprintSnapshot.quantizedSiftDescriptors) expect(row.every((byte) => byte === 0)).toBe(true)
    }
  })

  it('refuses pre-await on abort or a false fence', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(1))
    const reference = fingerprint(1600, 1200, spreadKeypoints(1))
    const controller = new AbortController()
    controller.abort()
    await expect(matchSide(query, reference, { signal: controller.signal })).rejects.toMatchObject({ code: 'cancelled' })
    await expect(matchSide(query, reference, { fence: () => false })).rejects.toMatchObject({ code: 'stale' })
  })

  it('refuses mid-pipeline abort/fence after the awaited correspondence and geometry', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(6))
    const reference = fingerprint(1600, 1200, spreadKeypoints(6))
    const pairs = Array.from({ length: 6 }, (_, index) => pair(index, index))
    const controller = new AbortController()
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => NO_MATCHER,
        matchCorrespondence: async () => {
          controller.abort()
          return correspondence(pairs, 6)
        },
        signal: controller.signal,
      }),
    ).rejects.toMatchObject({ code: 'cancelled' })

    let owner = true
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => NO_MATCHER,
        matchCorrespondence: async () => {
          owner = false
          return correspondence(pairs, 6)
        },
        fence: () => owner,
      }),
    ).rejects.toMatchObject({ code: 'stale' })

    const controller2 = new AbortController()
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => NO_MATCHER,
        matchCorrespondence: async () => correspondence(pairs, 6),
        estimateGeometry: async () => {
          controller2.abort()
          return geometry({ accepted: false })
        },
        signal: controller2.signal,
      }),
    ).rejects.toMatchObject({ code: 'cancelled' })
  })

  it('distinguishes redacted unavailable / contract / internal faults', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(6))
    const reference = fingerprint(1600, 1200, spreadKeypoints(6))
    const run = (overrides: Parameters<typeof matchSide>[2]) => matchSide(query, reference, { matcherFactory: async () => NO_MATCHER, ...overrides })
    await expect(
      run({ matchCorrespondence: async () => { throw new DescriptorCorrespondenceError('unavailable') }, estimateGeometry: async () => geometry() }),
    ).rejects.toMatchObject({ code: 'unavailable' })
    await expect(
      run({ matchCorrespondence: async () => { throw new DescriptorCorrespondenceError('shape') }, estimateGeometry: async () => geometry() }),
    ).rejects.toMatchObject({ code: 'contract' })
    await expect(
      run({ matchCorrespondence: async () => correspondence(Array.from({ length: 6 }, (_, index) => pair(index, index)), 6), estimateGeometry: async () => { throw new GeometryEstimatorError('unavailable') } }),
    ).rejects.toMatchObject({ code: 'unavailable' })
    await expect(
      run({ matchCorrespondence: async () => correspondence(Array.from({ length: 6 }, (_, index) => pair(index, index)), 6), estimateGeometry: async () => { throw new GeometryEstimatorError('contract') } }),
    ).rejects.toMatchObject({ code: 'contract' })
  })

  it('refuses malformed fingerprints as a contract error before any await', async () => {
    const query = fingerprint(1600, 1200, [keypoint(0, 0)])
    const reference = { ...fingerprint(1600, 1200, [keypoint(0, 0)]), quantizedSiftDescriptors: [] as Uint8Array[] }
    await expect(matchSide(query, reference)).rejects.toBeInstanceOf(SideMatchError)
    await expect(matchSide(query, reference)).rejects.toMatchObject({ code: 'contract' })
  })

  it('returns copied correspondence/geometry diagnostics, not internal references', async () => {
    const query = fingerprint(1600, 1200, spreadKeypoints(6))
    const reference = fingerprint(1600, 1200, spreadKeypoints(6))
    const pairs = Array.from({ length: 6 }, (_, index) => pair(index, index))
    const produced = correspondence(pairs, 6)
    const matrix = [1, 0, 0, 0, 1, 0, 0, 0, 1]
    const result = await matchSide(query, reference, {
      matcherFactory: async () => NO_MATCHER,
      matchCorrespondence: async () => produced,
      estimateGeometry: async () => geometry({ accepted: true, matrix, inlierIndices: pairs.map((_, index) => index), inlierRatio: 0.5, medianErrorPx: 1.6 }),
    })
    expect(result.correspondence.pairs).not.toBe(produced.pairs)
    expect(result.correspondence.pairs[0]).not.toBe(produced.pairs[0])
    expect(result.geometry!.matrix).not.toBe(matrix)
    expect(result.geometry!.matrix).toEqual(matrix)
  })
})

function zeroRowsFingerprint(): SiftFingerprint {
  const base = fingerprint(1600, 1200, spreadKeypoints(6))
  return { ...base, quantizedSiftDescriptors: base.quantizedSiftDescriptors.map(() => new Uint8Array(128)) }
}

describe('T341 actual pinned-bridge fault provenance through real correspondence', () => {
  const query = fingerprint(1600, 1200, spreadKeypoints(6))
  const reference = fingerprint(1600, 1200, spreadKeypoints(6))

  it('real correspondence short-circuits genuine zero rows before loading any matcher', async () => {
    let loaded = 0
    const result = await matchSide(zeroRowsFingerprint(), zeroRowsFingerprint(), {
      matcherFactory: async () => {
        loaded += 1
        throw new Error('must not load')
      },
    })
    expect(result.diagnostics.failure).toBe('NO_USABLE_ROWS')
    expect(loaded).toBe(0)
  })

  it('actual createPinnedMatcherRuntime unavailable refusal survives real correspondence', async () => {
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => createPinnedMatcherRuntime({ Mat: class {} } as unknown as RawCv),
      }),
    ).rejects.toMatchObject({ code: 'unavailable' })
  })

  it('actual knnMatch2 native-contract refusal survives real correspondence', async () => {
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => ({
          kind: 'pinned-opencv',
          knnMatch2: async () => {
            throw new PinnedMatcherError('native-contract')
          },
        }),
      }),
    ).rejects.toMatchObject({ code: 'contract' })
  })

  it('actual knnMatch2 internal refusal stays internal', async () => {
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => ({
          kind: 'pinned-opencv',
          knnMatch2: async () => {
            throw new PinnedMatcherError('internal')
          },
        }),
      }),
    ).rejects.toMatchObject({ code: 'internal' })
  })

  it('unknown native failure stays internal', async () => {
    await expect(
      matchSide(query, reference, {
        matcherFactory: async () => ({
          kind: 'pinned-opencv',
          knnMatch2: async () => {
            throw new Error('unexpected')
          },
        }),
      }),
    ).rejects.toMatchObject({ code: 'internal' })
  })

  it('abort during a native refusal keeps cancelled precedence', async () => {
    const controller = new AbortController()
    await expect(
      matchSide(query, reference, {
        signal: controller.signal,
        matcherFactory: async () => ({
          kind: 'pinned-opencv',
          knnMatch2: async () => {
            controller.abort()
            throw new PinnedMatcherError('native-contract')
          },
        }),
      }),
    ).rejects.toMatchObject({ code: 'cancelled' })
  })
})
