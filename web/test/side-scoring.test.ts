import { describe, expect, it } from 'vitest'
import {
  checkHomographyPlausibility,
  convexHullArea,
  evaluateSide,
  measureSpatialCoverage,
  normalizePixelHomography,
  scoreSide,
  SIDE_SCORING_PROFILE,
  SideScoringError,
  type SideMatchSignals,
} from '../src/scan/sideScoring'
import type { GeometryEvaluationResult } from '../src/scan/geometryEvaluation'
import type { CorrespondencePair } from '../src/scan/descriptorCorrespondence'
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
    quantizedSiftDescriptors: keypoints.map(() => new Uint8Array(128)),
  }
}

function geometry(overrides: Partial<GeometryEvaluationResult>): GeometryEvaluationResult {
  return {
    found: true,
    accepted: true,
    matrix: [1, 0, 0, 0, 1, 0, 0, 0, 1],
    inlierIndices: [],
    inlierRatio: 0.5,
    medianErrorPx: 1.6,
    referenceCoverage: 0.5,
    supportAreaPx2: 1000,
    supportEdgeRatio: 2,
    failure: 'NONE',
    ...overrides,
  }
}

function signals(overrides: Partial<SideMatchSignals>): SideMatchSignals {
  return {
    ratioMutualMatches: 20,
    ransacInliers: 20,
    inlierRatio: 0.5,
    spatialCoverage: 0.225,
    occupiedGridCells: 4,
    medianInlierErrorNormalized: 4 / 1600,
    homographyPlausible: true,
    ...overrides,
  }
}

const P00 = [0, 0] as const
const P10 = [1, 0] as const
const P11 = [1, 1] as const
const P01 = [0, 1] as const

describe('T339 spatial coverage (native min-binding)', () => {
  it('card-spanning corners yield a large hull and five cells', () => {
    const corners = [[0.05, 0.05], [0.95, 0.08], [0.92, 0.94], [0.07, 0.9], [0.5, 0.5]] as const
    const coverage = measureSpatialCoverage(corners, corners)
    expect(coverage.hullAreaNormalized).toBeGreaterThan(0.7)
    expect(coverage.occupiedGridCells).toBe(5)
  })

  it('binds both area and cells to the smaller side', () => {
    const distributed = [[0.02, 0.02], [0.98, 0.03], [0.97, 0.97], [0.03, 0.96]] as const
    const triangle = [[0.8, 0.8], [0.86, 0.82], [0.82, 0.87]] as const
    const coverage = measureSpatialCoverage(distributed, triangle)
    expect(coverage.hullAreaNormalized).toBeCloseTo(0.0019, 12)
    expect(coverage.occupiedGridCells).toBe(1)
    expect(coverage.hullAreaNormalized).toBe(coverage.referenceHullAreaNormalized)
  })

  it('uses Math.trunc (not floor), clamps >=gridSize, and skips negative cells', () => {
    expect(measureSpatialCoverage([[-0.1, 0.1]] as const, [[-0.1, 0.1]] as const).queryOccupiedCells).toBe(1)
    expect(measureSpatialCoverage([[1, 1]] as const, [[1, 1]] as const).queryOccupiedCells).toBe(1)
    expect(measureSpatialCoverage([[-0.3, -0.3]] as const, [[-0.3, -0.3]] as const).queryOccupiedCells).toBe(0)
  })

  it('duplicate points collapse and empty sides are zero', () => {
    const same = Array.from({ length: 9 }, () => [0.4, 0.4] as const)
    expect(measureSpatialCoverage(same, same).hullAreaNormalized).toBe(0)
    expect(measureSpatialCoverage(same, same).occupiedGridCells).toBe(1)
    expect(measureSpatialCoverage([], [])).toMatchObject({ hullAreaNormalized: 0, occupiedGridCells: 0 })
  })

  it('exact square hull is 1.0 and a 2-point side is 0', () => {
    expect(convexHullArea([P00, [0.5, 0] as const, P10, P11, P01, [0.25, 0.25] as const])).toBe(1)
    expect(convexHullArea([P00, P10])).toBe(0)
  })
})

describe('T339 homography plausibility (native gate)', () => {
  const zeroMedian = 5 / 1600 / 2

  it('identity and mild perspective pass', () => {
    expect(checkHomographyPlausibility([1, 0, 0, 0, 1, 0, 0, 0, 1], 0.001, zeroMedian).plausible).toBe(true)
    expect(checkHomographyPlausibility([1, 0, 0, 0, 1, 0, 0.0002, 0.0002, 0.9], 0.001, zeroMedian).plausible).toBe(true)
  })

  it('reflection fails orientation only', () => {
    const report = checkHomographyPlausibility([-0.9, 0.1, 1.1, 0.05, 0.8, 0.1, 0, 0, 1], 0.001, zeroMedian)
    expect(report.finiteQuad).toBe(true)
    expect(report.convexQuad).toBe(true)
    expect(report.orientationPreserved).toBe(false)
    expect(report.plausible).toBe(false)
  })

  it('area ratio 0.15/6 refuse and 0.20/5 are inclusive', () => {
    expect(checkHomographyPlausibility([6, 0, 0, 0, 1, 0, 0, 0, 1], 0.001, zeroMedian).mappedAreaRatio).toBe(6)
    expect(checkHomographyPlausibility([6, 0, 0, 0, 1, 0, 0, 0, 1], 0.001, zeroMedian).plausible).toBe(false)
    expect(checkHomographyPlausibility([1.5, 0, 0, 0, 0.1, 0, 0, 0, 1], 0.001, zeroMedian).mappedAreaRatio).toBeCloseTo(0.15, 12)
    expect(checkHomographyPlausibility([5, 0, 0, 0, 1, 0, 0, 0, 1], 0.001, zeroMedian).plausible).toBe(true)
    expect(checkHomographyPlausibility([0.2, 0, 0, 0, 1, 0, 0, 0, 1], 0.001, zeroMedian).plausible).toBe(true)
    expect(checkHomographyPlausibility([5.0001, 0, 0, 0, 1, 0, 0, 0, 1], 0.001, zeroMedian).plausible).toBe(false)
  })

  it('opposite-edge 4 is inclusive and >4 refuses', () => {
    expect(checkHomographyPlausibility([4, 0, 0, 0, 4, 0, 0, 3, 1], 0.001, zeroMedian).maxOppositeEdgeRatio).toBe(4)
    expect(checkHomographyPlausibility([4, 0, 0, 0, 4, 0, 0, 3, 1], 0.001, zeroMedian).plausible).toBe(true)
    expect(checkHomographyPlausibility([5, 0, 0, 0, 5, 0, 0, 4, 1], 0.001, zeroMedian).maxOppositeEdgeRatio).toBe(5)
    expect(checkHomographyPlausibility([5, 0, 0, 0, 5, 0, 0, 4, 1], 0.001, zeroMedian).plausible).toBe(false)
  })

  it('mixed w, singular w and degenerate projections fail closed', () => {
    const bowtie = checkHomographyPlausibility([1, 0, 0, 0, 1, 0, 1, 1, -0.8], 0.001, zeroMedian)
    expect(bowtie.finiteQuad).toBe(false)
    expect(bowtie.plausible).toBe(false)
    expect(checkHomographyPlausibility([1, 0, 0, 0, 1, 0, 1, 0, 0], 0.001, zeroMedian).finiteQuad).toBe(false)
    expect(checkHomographyPlausibility([1, 0, 0, 0, 0, 0, 0, 0, 1], 0.001, zeroMedian).plausible).toBe(false)
  })

  it('median above the limit rejects an otherwise perfect matrix', () => {
    const report = checkHomographyPlausibility([1, 0, 0, 0, 1, 0, 0, 0, 1], 0.01, 0.004)
    expect(report.medianErrorWithinLimit).toBe(false)
    expect(report.plausible).toBe(false)
  })
})

describe('T339 pixel -> normalized homography direction', () => {
  it('identity pixels with unequal dimensions scale by reference/query', () => {
    expect(normalizePixelHomography([1, 0, 0, 0, 1, 0, 0, 0, 1], 800, 600, 400, 300)).toEqual([
      0.5, 0, 0, 0, 0.5, 0, 0, 0, 1,
    ])
  })

  it('preserves the native Dquery^-1 * (Hpixels * Dreference) order', () => {
    expect(normalizePixelHomography([2, 0, 0, 0, 2, 0, 0, 0, 1], 800, 600, 400, 300)).toEqual([
      1, 0, 0, 0, 1, 0, 0, 0, 1,
    ])
  })
})

describe('T339 side scoring (native formulas/gates)', () => {
  it('half sub-scores give 0.5 and the exact weights give 0.5', () => {
    const report = scoreSide(signals({}))
    expect(report.countScore).toBe(0.5)
    expect(report.ratioScore).toBe(0.5)
    expect(report.coverageScore).toBe(0.5)
    expect(report.reprojectionScore).toBe(0.5)
    expect(report.sideScore).toBe(0.5)
  })

  it('perfect evidence gives 1.0', () => {
    expect(scoreSide(signals({ ransacInliers: 40, inlierRatio: 1, spatialCoverage: 1, medianInlierErrorNormalized: 0 })).sideScore).toBe(1)
  })

  it('weak gate passes exactly at every minimum and fails one below', () => {
    const atMin = signals({ ratioMutualMatches: 10, ransacInliers: 6, inlierRatio: 0.25, spatialCoverage: 0.1, occupiedGridCells: 3 })
    expect(scoreSide(atMin).weakGatePassed).toBe(true)
    expect(scoreSide({ ...atMin, ratioMutualMatches: 9 }).weakGatePassed).toBe(false)
    expect(scoreSide({ ...atMin, occupiedGridCells: 2 }).weakGatePassed).toBe(false)
    expect(scoreSide({ ...atMin, homographyPlausible: false }).weakGatePassed).toBe(false)
  })

  it('strong gate passes exactly at every minimum and fails one below', () => {
    const atMin = signals({ ratioMutualMatches: 20, ransacInliers: 15, inlierRatio: 0.35, spatialCoverage: 0.2, medianInlierErrorNormalized: 4 / 1600 })
    expect(scoreSide(atMin).strongGatePassed).toBe(true)
    expect(scoreSide({ ...atMin, medianInlierErrorNormalized: 4.0001 / 1600 }).strongGatePassed).toBe(false)
    expect(scoreSide({ ...atMin, ransacInliers: 14 }).strongGatePassed).toBe(false)
    expect(scoreSide({ ...atMin, homographyPlausible: false }).strongGatePassed).toBe(false)
  })
})

describe('T339 evaluateSide integration', () => {
  function pair(queryIndex: number, referenceIndex: number): CorrespondencePair {
    return { queryIndex, referenceIndex, distance: 1 }
  }

  it('uses uniqueMatches as ratioMutualMatches and inlierIndices.length as inliers', () => {
    const query = fingerprint(1600, 1200, [keypoint(0, 0), keypoint(1e6, 1e6)])
    const reference = fingerprint(800, 600, [keypoint(0, 0), keypoint(1e6, 1e6)])
    const result = evaluateSide({
      query,
      reference,
      pairs: [pair(0, 0), pair(1, 1)],
      uniqueMatches: 2,
      geometry: geometry({ inlierIndices: [0, 1], inlierRatio: 1, medianErrorPx: 1.6 }),
    })
    expect(result.signals.ratioMutualMatches).toBe(2)
    expect(result.signals.ransacInliers).toBe(2)
    expect(result.signals.homographyPlausible).toBe(true)
  })

  it('binds coverage to the smaller side', () => {
    const query = fingerprint(1600, 1200, [keypoint(0, 0), keypoint(1e6, 0), keypoint(1e6, 1e6), keypoint(0, 1e6)])
    const reference = fingerprint(1600, 1200, [keypoint(900000, 900000), keypoint(950000, 900000), keypoint(900000, 950000), keypoint(950000, 950000)])
    const result = evaluateSide({
      query,
      reference,
      pairs: [pair(0, 0), pair(1, 1), pair(2, 2), pair(3, 3)],
      uniqueMatches: 4,
      geometry: geometry({ inlierIndices: [0, 1, 2, 3], inlierRatio: 1 }),
    })
    expect(result.signals.spatialCoverage).toBe(result.diagnostics.referenceHullAreaNormalized)
    expect(result.signals.spatialCoverage).toBeLessThan(0.01)
    expect(result.signals.occupiedGridCells).toBe(result.diagnostics.referenceOccupiedCells)
  })

  it('empty inliers give zero coverage/cells and no plausible full card', () => {
    const query = fingerprint(1600, 1200, [keypoint(0, 0)])
    const reference = fingerprint(1600, 1200, [keypoint(0, 0)])
    const result = evaluateSide({
      query,
      reference,
      pairs: [],
      uniqueMatches: 0,
      geometry: geometry({ accepted: false, inlierIndices: [], inlierRatio: 0 }),
    })
    expect(result.signals.spatialCoverage).toBe(0)
    expect(result.signals.occupiedGridCells).toBe(0)
    expect(result.signals.homographyPlausible).toBe(false)
    expect(result.diagnostics.fullCardPlausibility).toBeNull()
  })

  it('unobservable median uses the 1.0 sentinel and loses error credit', () => {
    const query = fingerprint(1600, 1200, [keypoint(0, 0), keypoint(1e6, 1e6)])
    const reference = fingerprint(1600, 1200, [keypoint(0, 0), keypoint(1e6, 1e6)])
    const result = evaluateSide({
      query,
      reference,
      pairs: [pair(0, 0), pair(1, 1)],
      uniqueMatches: 25,
      geometry: geometry({ inlierIndices: [0, 1], inlierRatio: 1, medianErrorPx: -1 }),
    })
    expect(result.signals.medianInlierErrorNormalized).toBe(1)
    expect(result.report.reprojectionScore).toBe(0)
    expect(result.report.strongGatePassed).toBe(false)
  })

  it('does not mutate inputs and returns fresh output objects', () => {
    const query = fingerprint(1600, 1200, [keypoint(0, 0), keypoint(1e6, 1e6)])
    const reference = fingerprint(800, 600, [keypoint(0, 0), keypoint(1e6, 1e6)])
    const input = {
      query,
      reference,
      pairs: [pair(0, 0), pair(1, 1)],
      uniqueMatches: 2,
      geometry: geometry({ inlierIndices: [0, 1], inlierRatio: 1 }),
    }
    const snapshot = JSON.stringify(input, (_key, value) => (typeof value === 'bigint' ? `${value}` : value))
    const result = evaluateSide(input)
    expect(JSON.stringify(input, (_key, value) => (typeof value === 'bigint' ? `${value}` : value))).toBe(snapshot)
    expect(result.signals).not.toBe(input.geometry)
    expect(result.report).not.toBe(result.signals)
  })

  it('fails closed with a redacted typed error on malformed input', () => {
    const query = fingerprint(1600, 1200, [keypoint(0, 0)])
    const reference = fingerprint(1600, 1200, [keypoint(0, 0)])
    const badIndex = () => evaluateSide({ query, reference, pairs: [pair(0, 0)], uniqueMatches: 1, geometry: geometry({ inlierIndices: [5] }) })
    const badPair = () => evaluateSide({ query, reference, pairs: [pair(3, 0)], uniqueMatches: 1, geometry: geometry({ inlierIndices: [0] }) })
    const badRatio = () => evaluateSide({ query, reference, pairs: [pair(0, 0)], uniqueMatches: 1, geometry: geometry({ inlierIndices: [0], inlierRatio: Number.NaN }) })
    const badDims = () => evaluateSide({ query: fingerprint(0, 1200, [keypoint(0, 0)]), reference, pairs: [pair(0, 0)], uniqueMatches: 1, geometry: geometry({ inlierIndices: [0], accepted: true }) })
    for (const action of [badIndex, badPair, badRatio, badDims]) {
      try {
        action()
        throw new Error('expected refusal')
      } catch (error) {
        expect(error).toBeInstanceOf(SideScoringError)
        expect((error as SideScoringError).code).toBe('input')
        expect((error as Error).message).toBe('input')
      }
    }
  })

  it('exposes the frozen active profile constants', () => {
    expect(SIDE_SCORING_PROFILE.canonicalLongEdgePx).toBe(1600)
    expect(SIDE_SCORING_PROFILE.coverageGridSize).toBe(4)
    expect(Object.isFrozen(SIDE_SCORING_PROFILE)).toBe(true)
  })
})
