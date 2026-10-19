import { describe, expect, it } from 'vitest'
import type { RawCv } from '../src/scan/opencvRuntime'
import {
  estimateAndEvaluateGeometry,
  GeometryEstimatorError,
} from '../src/scan/geometryEstimator'
import type { GeometryPixelPoint } from '../src/scan/geometryEvaluation'

const CV_32FC2 = 13
const CV_64F = 6
const USAC_MAGSAC = 38

class Mat {
  rows: number
  cols: number
  typeId: number
  data: Uint8Array
  data32F: Float32Array
  data64F?: Float64Array
  constructor(rows = 0, cols = 0, type = 0) {
    this.rows = rows
    this.cols = cols
    this.typeId = type
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
    /* fake release */
  }
}

function fakeCv(options: { onCall?: () => void; nativeThrows?: boolean } = {}): RawCv {
  const cv = {
    Mat,
    CV_32FC2,
    CV_64F,
    USAC_MAGSAC,
    setRNGSeed() {
      /* recorded by call ordering in the leaf test */
    },
    findHomography(...args: unknown[]) {
      options.onCall?.()
      if (options.nativeThrows) throw new Error('native failed')
      const H = new Mat(3, 3, CV_64F)
      H.data64F = Float64Array.from([1, 0, 17, 0, 1, 11, 0, 0, 1])
      const mask = args[4] as Mat
      mask.rows = 6
      mask.cols = 1
      mask.data = Uint8Array.from(new Array(6).fill(1))
      return H
    },
  } as unknown as RawCv
  return cv
}

const REF: GeometryPixelPoint[] = [
  { x: 30, y: 20 },
  { x: 160, y: 20 },
  { x: 290, y: 20 },
  { x: 30, y: 180 },
  { x: 160, y: 180 },
  { x: 290, y: 180 },
]
const QUERY = REF.map((p) => ({ x: p.x + 17, y: p.y + 11 }))

const base = {
  referencePoints: REF,
  queryPoints: QUERY,
  referenceWidthPx: 320,
  referenceHeightPx: 200,
}

describe('T337 geometry estimator wrapper', () => {
  it('runs the real bridge then T333 evaluation with exact diagnostics', async () => {
    const result = await estimateAndEvaluateGeometry(base, { loadModule: async () => fakeCv() })
    expect(result.failure).toBe('NONE')
    expect(result.accepted).toBe(true)
    expect(result.medianErrorPx).toBe(0)
    expect(result.referenceCoverage).toBeCloseTo((260 * 160) / (320 * 200), 12)
    expect(result.supportAreaPx2).toBeCloseTo(41600, 9)
    expect(result.supportEdgeRatio).toBeCloseTo(1.625, 12)
    expect(result.matrix).toEqual([1, 0, 17, 0, 1, 11, 0, 0, 1])
  })

  it('snapshots coordinates/dimensions before the load await', async () => {
    let release!: (cv: RawCv) => void
    const pending = new Promise<RawCv>((resolve) => {
      release = resolve
    })
    const ref = REF.map((p) => ({ ...p }))
    const query = QUERY.map((p) => ({ ...p }))
    const calling = estimateAndEvaluateGeometry(
      { referencePoints: ref, queryPoints: query, referenceWidthPx: 320, referenceHeightPx: 200 },
      { loadModule: () => pending },
    )
    // Mutate the caller's objects after the synchronous snapshot, before load.
    ref[0]!.x = 999
    ref.push({ x: 1, y: 1 })
    query[1]!.y = -50
    release(fakeCv())
    const result = await calling
    expect(result.supportAreaPx2).toBeCloseTo(41600, 9)
    expect(result.referenceCoverage).toBeCloseTo(0.65, 12)
  })

  it('honours pre-abort without loading', async () => {
    const controller = new AbortController()
    controller.abort()
    let loaded = false
    await expect(
      estimateAndEvaluateGeometry(
        { ...base, signal: controller.signal },
        { loadModule: async () => ((loaded = true), fakeCv()) },
      ),
    ).rejects.toMatchObject({ code: 'cancelled' })
    expect(loaded).toBe(false)
  })

  it('maps an aborted deferred load to cancelled, not unavailable', async () => {
    const controller = new AbortController()
    let reject!: (error: unknown) => void
    const pending = new Promise<RawCv>((_resolve, failing) => {
      reject = failing
    })
    const calling = estimateAndEvaluateGeometry(
      { ...base, signal: controller.signal },
      { loadModule: () => pending },
    )
    controller.abort()
    reject(new Error('load failed'))
    await expect(calling).rejects.toMatchObject({ code: 'cancelled' })
  })

  it('fails closed when the signal aborts after the native call', async () => {
    const controller = new AbortController()
    await expect(
      estimateAndEvaluateGeometry(
        { ...base, signal: controller.signal },
        { loadModule: async () => fakeCv({ onCall: () => controller.abort() }) },
      ),
    ).rejects.toMatchObject({ code: 'cancelled' })
  })

  it('maps a generic load failure to unavailable and a native throw to internal', async () => {
    await expect(
      estimateAndEvaluateGeometry(base, { loadModule: async () => Promise.reject(new Error('boom')) }),
    ).rejects.toMatchObject({ code: 'unavailable' })
    await expect(
      estimateAndEvaluateGeometry(base, { loadModule: async () => fakeCv({ nativeThrows: true }) }),
    ).rejects.toMatchObject({ code: 'internal' })
  })

  it('rejects invalid inputs before loading', async () => {
    let loaded = false
    const load = async () => ((loaded = true), fakeCv())
    await expect(
      estimateAndEvaluateGeometry({ ...base, queryPoints: QUERY.slice(1) }, { loadModule: load }),
    ).rejects.toMatchObject({ code: 'input' })
    await expect(
      estimateAndEvaluateGeometry(
        { ...base, referencePoints: [...REF.slice(1), { x: Number.NaN, y: 0 }] },
        { loadModule: load },
      ),
    ).rejects.toMatchObject({ code: 'input' })
    await expect(
      estimateAndEvaluateGeometry(
        { ...base, referencePoints: REF.slice(0, 3), queryPoints: QUERY.slice(0, 3) },
        { loadModule: load },
      ),
    ).rejects.toMatchObject({ code: 'input' })
    expect(loaded).toBe(false)
  })

  it('throws a typed error class only', async () => {
    await expect(
      estimateAndEvaluateGeometry(base, { loadModule: async () => Promise.reject(new Error('x')) }),
    ).rejects.toBeInstanceOf(GeometryEstimatorError)
  })
})
