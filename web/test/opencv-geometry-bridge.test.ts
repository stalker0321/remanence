import { describe, expect, it } from 'vitest'
import type { RawCv } from '../src/scan/opencvRuntime'
import {
  estimateHomographyObservations,
  GeometryBridgeError,
  GEOMETRY_NATIVE_PARAMETERS,
} from '../src/scan/opencvGeometryPort'
import { evaluateGeometryObservation } from '../src/scan/geometryEvaluation'

const CV_32FC2 = 13
const CV_64F = 6
const USAC_MAGSAC = 38
const H_VALUES = [1, 0, 17, 0, 1, 11, 0, 0, 1]

interface Controls {
  homography?: FakeMat | null
  hRows?: number
  hCols?: number
  hType?: number
  hEmpty?: boolean
  hGetterThrows?: boolean
  maskEmpty?: boolean
  maskRows?: number
  maskCols?: number
  maskBytes?: number[]
  nativeThrows?: boolean
  secondPointViewThrows?: boolean
  badSecondPointShape?: boolean
  deleteThrowsFor?: string
}

interface Log {
  created: string[]
  deleted: string[]
  seeds: number[]
}

class FakeMat {
  rows: number
  cols: number
  typeId: number
  label: string
  data: Uint8Array
  data32F: Float32Array
  data64F?: Float64Array
  controls: Controls = {}
  log: Log = { created: [], deleted: [], seeds: [] }
  constructor(rows = 0, cols = 0, type = 0, label = 'mat') {
    this.rows = rows
    this.cols = cols
    this.typeId = type
    this.label = label
    this.data = new Uint8Array(rows * cols)
    this.data32F = new Float32Array(type === CV_32FC2 ? rows * cols * 2 : rows * cols)
  }
  type(): number {
    return this.typeId
  }
  empty(): boolean {
    return this.rows * this.cols === 0
  }
  total(): number {
    return this.rows * this.cols
  }
  delete(): void {
    if (this.controls.deleteThrowsFor === this.label) throw new Error('delete failed')
    this.log.deleted.push(this.label)
  }
}

interface FakeCv {
  cv: RawCv
  log: Log
  calls: unknown[][]
  captured: { ref: number[]; query: number[] }
}

function buildCv(controls: Controls = {}, pointCount = 6): FakeCv {
  const log: Log = { created: [], deleted: [], seeds: [] }
  let index = 0
  class Mat extends FakeMat {
    constructor(rows = 0, cols = 0, type = 0) {
      super(rows, cols, type, `m${index++}`)
      this.controls = controls
      this.log = log
      log.created.push(this.label)
      if (this.label === 'm1' && (controls.badSecondPointShape || controls.secondPointViewThrows)) {
        if (controls.secondPointViewThrows) {
          Object.defineProperty(this, 'data32F', {
            get() {
              throw new Error('view failed')
            },
          })
        } else {
          this.data32F = new Float32Array(1)
        }
      }
    }
  }
  const calls: unknown[][] = []
  const captured: { ref: number[]; query: number[] } = { ref: [], query: [] }
  const cv = {
    Mat,
    CV_32FC2,
    CV_64F,
    USAC_MAGSAC,
    setRNGSeed(seed: number) {
      log.seeds.push(seed)
    },
    findHomography(...args: unknown[]) {
      calls.push(args)
      const refArg = args[0] as FakeMat
      const queryArg = args[1] as FakeMat
      captured.ref = refArg.data32F ? Array.from(refArg.data32F) : []
      captured.query = queryArg.data32F ? Array.from(queryArg.data32F) : []
      if (controls.nativeThrows) throw new Error('native failed')
      const H =
        controls.homography === null
          ? null
          : (controls.homography ??
            new Mat(controls.hRows ?? 3, controls.hCols ?? 3, controls.hType ?? CV_64F))
      if (H instanceof FakeMat) {
        H.controls = controls
        H.log = log
        if (controls.hEmpty) {
          H.rows = 0
          H.cols = 0
        }
        if (!controls.hGetterThrows) H.data64F = Float64Array.from(H_VALUES)
      }
      const mask = args[4] as FakeMat
      if (mask instanceof FakeMat) {
        mask.rows = controls.maskEmpty ? 0 : (controls.maskRows ?? pointCount)
        mask.cols = controls.maskEmpty ? 0 : (controls.maskCols ?? 1)
        mask.data = Uint8Array.from(controls.maskEmpty ? [] : (controls.maskBytes ?? new Array(pointCount).fill(1)))
      }
      return H
    },
  } as unknown as RawCv
  return { cv, log, calls, captured }
}

const REF = [
  { x: 30, y: 20 },
  { x: 160, y: 20 },
  { x: 290, y: 20 },
  { x: 30, y: 180 },
  { x: 160, y: 180 },
  { x: 290, y: 180 },
]
const QUERY = REF.map((p) => ({ x: p.x + 17, y: p.y + 11 }))

describe('T337 leaf homography bridge', () => {
  it('calls the exact native 7-arg form in order and returns copied observations', () => {
    const { cv, calls, log, captured } = buildCv()
    const observations = estimateHomographyObservations(cv, REF, QUERY)
    expect(log.seeds).toEqual([42])
    expect(calls).toHaveLength(1)
    const args = calls[0]!
    expect(args).toHaveLength(7)
    expect(args[0]).toBeInstanceOf(FakeMat)
    expect(args[1]).toBeInstanceOf(FakeMat)
    expect(args[2]).toBe(USAC_MAGSAC)
    expect(args[3]).toBe(4.0)
    expect(args[4]).toBeInstanceOf(FakeMat)
    expect(args[5]).toBe(3000)
    expect(args[6]).toBe(0.999)
    // Capture happens inside findHomography, before cleanup wipes the buffers.
    expect(captured.ref.slice(0, 4)).toEqual([30, 20, 160, 20])
    expect(captured.query.slice(0, 2)).toEqual([47, 31])
    expect(observations.homography).toEqual({
      empty: false,
      rows: 3,
      cols: 3,
      type: 6,
      values: H_VALUES,
    })
    expect(observations.mask).toEqual({ empty: false, rows: 6, cols: 1, type: 0, bytes: [1, 1, 1, 1, 1, 1] })
    expect(log.deleted.sort()).toEqual(log.created.sort())
  })

  it('preserves an empty homography as an empty observation (not a throw)', () => {
    const { cv } = buildCv({ homography: null })
    const observations = estimateHomographyObservations(cv, REF, QUERY)
    expect(observations.homography.empty).toBe(true)
    expect(observations.homography.values).toBeNull()
    const evaluated = evaluateGeometryObservation({
      referencePoints: REF,
      queryPoints: QUERY,
      referenceWidthPx: 320,
      referenceHeightPx: 200,
      homography: observations.homography,
      mask: observations.mask,
    })
    expect(evaluated.failure).toBe('HOMOGRAPHY_EMPTY')
  })

  it('returns truthful wrong-shape/wrong-type/unreadable H observations for T333', () => {
    const shapeObs = estimateHomographyObservations(buildCv({ hRows: 2 }).cv, REF, QUERY)
    expect(shapeObs.homography.rows).toBe(2)
    expect(shapeObs.homography.values).toBeNull()
    const shapeEval = evaluateGeometryObservation({
      referencePoints: REF,
      queryPoints: QUERY,
      referenceWidthPx: 320,
      referenceHeightPx: 200,
      homography: shapeObs.homography,
      mask: shapeObs.mask,
    })
    expect(shapeEval.failure).toBe('INVALID_HOMOGRAPHY')

    const typeObs = estimateHomographyObservations(buildCv({ hType: 5 }).cv, REF, QUERY)
    expect(typeObs.homography.type).toBe(5)
    expect(typeObs.homography.values).toBeNull()

    const emptyObs = estimateHomographyObservations(buildCv({ hEmpty: true }).cv, REF, QUERY)
    expect(emptyObs.homography.empty).toBe(true)
  })

  it('returns truthful wrong/empty mask observations', () => {
    const emptyObs = estimateHomographyObservations(buildCv({ maskEmpty: true }).cv, REF, QUERY)
    expect(emptyObs.mask.empty).toBe(true)
    expect(emptyObs.mask.bytes).toBeNull()

    const badObs = estimateHomographyObservations(
      buildCv({ maskRows: 5, maskCols: 1, maskBytes: [1, 1, 1, 1, 1] }).cv,
      REF,
      QUERY,
    )
    expect(badObs.mask.rows).toBe(5)
    expect(badObs.mask.bytes).toBeNull()
  })

  it('wipes and deletes a partial second point Mat on shape/view failure', () => {
    const shape = buildCv({ badSecondPointShape: true })
    expect(() => estimateHomographyObservations(shape.cv, REF, QUERY)).toThrow(GeometryBridgeError)
    expect(shape.log.created.length).toBe(shape.log.deleted.length)

    const view = buildCv({ secondPointViewThrows: true })
    expect(() => estimateHomographyObservations(view.cv, REF, QUERY)).toThrow(GeometryBridgeError)
    expect(view.log.created.length).toBe(view.log.deleted.length)
  })

  it('attempts every delete when one delete throws', () => {
    const throwing = buildCv({ deleteThrowsFor: 'm1' })
    expect(() => estimateHomographyObservations(throwing.cv, REF, QUERY)).toThrow(GeometryBridgeError)
    expect(throwing.log.deleted).toContain('m0')
    expect(throwing.log.deleted).toContain('m2')
    expect(throwing.log.deleted).not.toContain('m1')
  })

  it('maps a native throw to a typed redacted failure and cleans up', () => {
    const native = buildCv({ nativeThrows: true })
    expect(() => estimateHomographyObservations(native.cv, REF, QUERY)).toThrow(GeometryBridgeError)
    expect(native.log.created.length).toBe(native.log.deleted.length)
  })

  it('rejects inconsistent/nonfinite/out-of-range points without calling native', () => {
    const { cv, calls } = buildCv()
    expect(() => estimateHomographyObservations(cv, REF, QUERY.slice(1))).toThrow(GeometryBridgeError)
    expect(() =>
      estimateHomographyObservations(cv, [...REF.slice(1), { x: Number.NaN, y: 0 }], QUERY),
    ).toThrow(GeometryBridgeError)
    expect(() => estimateHomographyObservations(cv, REF.slice(0, 3), QUERY.slice(0, 3))).toThrow(GeometryBridgeError)
    expect(calls).toHaveLength(0)
  })

  it('does not mutate caller point arrays', () => {
    const { cv } = buildCv()
    const ref = REF.map((p) => ({ ...p }))
    const query = QUERY.map((p) => ({ ...p }))
    const before = JSON.stringify([ref, query])
    estimateHomographyObservations(cv, ref, query)
    expect(JSON.stringify([ref, query])).toBe(before)
  })

  it('uses the source-provenance native parameters', () => {
    expect(GEOMETRY_NATIVE_PARAMETERS).toEqual({
      seed: 42,
      ransacReprojectionThresholdPx: 4,
      maxIterations: 3000,
      confidence: 0.999,
      usacMagsac: 38,
    })
  })
})
