import { describe, expect, it } from 'vitest'
import {
  CAPTURE_QUALITY_NATIVE,
  CaptureQualityError,
  measureCaptureQualitySignals,
  validateCaptureQualityDimensions,
  type CaptureQualitySignals,
  type QualityMeanResult,
  type QualityRawCv,
  type QualityRawMat,
} from '../src/scan/opencvQualityPort'
import {
  CAPTURE_QUALITY_GATES,
  FRONT_ADMISSION_MIN_LAPLACIAN_VARIANCE,
  createCaptureQualityMeter,
  evaluateCaptureQualityGate,
  measureCaptureQuality,
  type CaptureQualityGateInput,
  type CaptureQualityGateOptions,
  type MeasureCaptureQualityOptions,
  type QualityReason,
} from '../src/scan/captureQuality'
import type { RawCv } from '../src/scan/opencvRuntime'

const CV_8UC1 = 0
const CV_32S = 4
const CV_64F = 6
const CV_8UC4 = 16
const COLOR_RGBA2GRAY = 11
const THRESH_BINARY = 0

interface FakeOptions {
  readonly failLaplacian?: boolean
  readonly failConnectedComponents?: boolean
  readonly failAllocationAt?: number
  readonly mulReturnsUndefined?: boolean
  readonly deleteThrowsAt?: number
  readonly viewThrowsAt?: { readonly index: number; readonly view: 'data' | 'data64F' | 'data32S' }
}

class FakeMat implements QualityRawMat {
  rows = 0
  cols = 0
  typeValue = CV_8UC1
  private _data = new Uint8Array(0)
  private _data64F = new Float64Array(0)
  private _data32S = new Int32Array(0)
  private _data32F = new Float32Array(0)
  deleted = false
  zeroBeforeDelete: boolean | null = null
  failView: FakeOptions['viewThrowsAt'] = undefined
  failDelete = false
  failMul = false
  registerChild: ((child: FakeMat) => void) | undefined = undefined
  readonly mulCalls: Array<{ readonly sameHandle: boolean; readonly scale: number }> = []

  constructor(rows = 0, cols = 0, type = CV_8UC1) {
    this.configure(rows, cols, type)
  }

  configure(rows: number, cols: number, type: number): void {
    this.rows = rows
    this.cols = cols
    this.typeValue = type
    const pixels = Math.max(0, rows * cols)
    if (type === CV_8UC1) {
      this._data = new Uint8Array(pixels)
      this._data64F = new Float64Array(0)
      this._data32S = new Int32Array(0)
      this._data32F = new Float32Array(0)
    } else if (type === CV_8UC4) {
      this._data = new Uint8Array(pixels * 4)
      this._data64F = new Float64Array(0)
      this._data32S = new Int32Array(0)
      this._data32F = new Float32Array(0)
    } else if (type === CV_64F) {
      this._data = new Uint8Array(pixels * 8)
      this._data64F = new Float64Array(pixels)
      this._data32S = new Int32Array(0)
      this._data32F = new Float32Array(0)
    } else if (type === CV_32S) {
      this._data = new Uint8Array(pixels * 4)
      this._data64F = new Float64Array(0)
      this._data32S = new Int32Array(pixels)
      this._data32F = new Float32Array(0)
    } else {
      throw new Error(`unsupported fake type ${type}`)
    }
  }

  private guard(view: 'data' | 'data64F' | 'data32S'): void {
    if (this.failView && this.failView.view === view) throw new Error('view getter failed')
  }

  get data(): Uint8Array {
    this.guard('data')
    return this._data
  }
  get data64F(): Float64Array {
    this.guard('data64F')
    return this._data64F
  }
  get data32S(): Int32Array {
    this.guard('data32S')
    return this._data32S
  }
  get data32F(): Float32Array {
    return this._data32F
  }

  type(): number {
    return this.typeValue
  }

  mul(other: QualityRawMat, scale: number): QualityRawMat {
    this.mulCalls.push({ sameHandle: other === this, scale })
    if (this.failMul) return undefined as unknown as QualityRawMat
    if (other !== this) throw new Error('fake mul expects the same handle')
    const squared = new FakeMat(this.rows, this.cols, CV_64F)
    this.registerChild?.(squared)
    const source = this._data64F
    const target = squared._data64F
    for (let index = 0; index < source.length; index++) target[index] = source[index]! * source[index]! * scale
    return squared
  }

  private viewsZero(): boolean {
    const views: Array<ArrayLike<number>> = []
    for (const key of ['data', 'data64F', 'data32S', 'data32F'] as const) {
      try {
        const view = (this as unknown as Record<string, ArrayLike<number>>)[key]
        if (view && typeof view.length === 'number') views.push(view)
      } catch {
        /* ignore unavailable view in the diagnostic */
      }
    }
    return views.every((view) => {
      for (let index = 0; index < view.length; index++) if (view[index] !== 0) return false
      return true
    })
  }

  delete(): void {
    if (this.failDelete) throw new Error('delete failed')
    this.zeroBeforeDelete = this.viewsZero()
    this.deleted = true
  }

  isDeleted(): boolean {
    return this.deleted
  }
}

class FakeCv implements QualityRawCv {
  readonly CV_8UC1 = CV_8UC1
  readonly CV_8UC4 = CV_8UC4
  readonly CV_64F = CV_64F
  readonly CV_32S = CV_32S
  readonly COLOR_RGBA2GRAY = COLOR_RGBA2GRAY
  readonly THRESH_BINARY = THRESH_BINARY
  readonly CC_STAT_AREA = 4
  readonly calls: Array<{ readonly name: string; readonly argc: number; readonly detail?: unknown }> = []
  readonly mats: FakeMat[] = []
  readonly Mat: new (rows?: number, cols?: number, type?: number) => QualityRawMat

  constructor(private readonly options: FakeOptions = {}) {
    const cv = this
    this.Mat = function (this: unknown, rows?: number, cols?: number, type?: number) {
      if (cv.options.failAllocationAt !== undefined && cv.mats.length === cv.options.failAllocationAt) {
        throw new Error('allocation failed')
      }
      const mat = new FakeMat(rows, cols, type)
      const index = cv.mats.length
      cv.mats.push(mat)
      mat.registerChild = (child) => {
        cv.mats.push(child)
        child.registerChild = mat.registerChild
      }
      if (cv.options.deleteThrowsAt === index) mat.failDelete = true
      if (cv.options.mulReturnsUndefined && index === 2) mat.failMul = true
      if (cv.options.viewThrowsAt && cv.options.viewThrowsAt.index === index) mat.failView = cv.options.viewThrowsAt
      return mat
    } as unknown as new (rows?: number, cols?: number, type?: number) => QualityRawMat
  }

  cvtColor(src: QualityRawMat, dst: QualityRawMat, code: number): void {
    this.calls.push({ name: 'cvtColor', argc: arguments.length, detail: code })
    if (code !== COLOR_RGBA2GRAY) throw new Error('unexpected cvtColor code')
    const target = dst as FakeMat
    target.configure(src.rows, src.cols, CV_8UC1)
    const rgba = src.data!
    for (let index = 0; index < target.rows * target.cols; index++) target.data[index] = rgba[index * 4]!
  }

  Laplacian(src: QualityRawMat, dst: QualityRawMat, ddepth: number): void {
    this.calls.push({ name: 'Laplacian', argc: arguments.length, detail: ddepth })
    if (this.options.failLaplacian) throw new Error('laplacian failed')
    if (ddepth !== CV_64F) throw new Error('unexpected Laplacian depth')
    const gray = src.data!
    let sum = 0
    for (let index = 0; index < gray.length; index++) sum += gray[index]!
    const mean = sum / gray.length
    const target = dst as FakeMat
    target.configure(src.rows, src.cols, CV_64F)
    const savedView = target.failView
    target.failView = undefined
    for (let index = 0; index < gray.length; index++) target.data64F[index] = gray[index]! - mean
    target.failView = savedView
  }

  mean(src: QualityRawMat): QualityMeanResult {
    this.calls.push({ name: 'mean', argc: arguments.length })
    const values = src.data64F!
    let sum = 0
    for (let index = 0; index < values.length; index++) sum += values[index]!
    return [sum / values.length, 0, 0, 0] as unknown as QualityMeanResult
  }

  threshold(src: QualityRawMat, dst: QualityRawMat, thresh: number, maxval: number, type: number): number {
    this.calls.push({ name: 'threshold', argc: arguments.length, detail: { thresh, maxval, type } })
    if (thresh !== CAPTURE_QUALITY_NATIVE.glareThreshold || maxval !== CAPTURE_QUALITY_NATIVE.glareMaxValue || type !== THRESH_BINARY) {
      throw new Error('unexpected threshold args')
    }
    const source = src.data!
    const target = dst as FakeMat
    target.configure(src.rows, src.cols, CV_8UC1)
    for (let index = 0; index < source.length; index++) target.data[index] = source[index]! > thresh ? maxval : 0
    return thresh
  }

  connectedComponentsWithStats(
    src: QualityRawMat,
    labels: QualityRawMat,
    stats: QualityRawMat,
    centroids: QualityRawMat,
  ): number {
    this.calls.push({ name: 'connectedComponentsWithStats', argc: arguments.length })
    if (this.options.failConnectedComponents) throw new Error('cc failed')
    const width = src.cols
    const height = src.rows
    const mask = src.data!
    const seen = new Uint8Array(width * height)
    const areas: number[] = [0]
    let labelCount = 1
    for (let start = 0; start < mask.length; start++) {
      if (mask[start] === 0 || seen[start] === 1) continue
      const label = labelCount++
      const stack = [start]
      seen[start] = 1
      let area = 0
      while (stack.length > 0) {
        const current = stack.pop()!
        area += 1
        const x = current % width
        const y = Math.floor(current / width)
        for (let dy = -1; dy <= 1; dy++) {
          for (let dx = -1; dx <= 1; dx++) {
            const nx = x + dx
            const ny = y + dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
            const next = ny * width + nx
            if (seen[next] === 0 && mask[next] !== 0) {
              seen[next] = 1
              stack.push(next)
            }
          }
        }
      }
      areas[label] = area
    }
    const background = width * height - areas.slice(1).reduce((sum, area) => sum + area, 0)
    areas[0] = background

    const labelsMat = labels as FakeMat
    labelsMat.configure(height, width, CV_32S)
    for (let index = 0; index < seen.length; index++) {
      labelsMat.data32S[index] = mask[index] === 0 ? 0 : 1
    }
    const statsMat = stats as FakeMat
    statsMat.configure(labelCount, CAPTURE_QUALITY_NATIVE.statsColumns, CV_32S)
    for (let label = 0; label < labelCount; label++) {
      statsMat.data32S[label * CAPTURE_QUALITY_NATIVE.statsColumns + CAPTURE_QUALITY_NATIVE.statsAreaColumn] = areas[label]!
    }
    const centroidsMat = centroids as FakeMat
    centroidsMat.configure(labelCount, 2, CV_64F)
    return labelCount
  }
}

function rgbaFromGray(values: readonly number[]): Uint8Array {
  const out = new Uint8Array(values.length * 4)
  for (let index = 0; index < values.length; index++) {
    const value = values[index]!
    out[index * 4] = value
    out[index * 4 + 1] = value
    out[index * 4 + 2] = value
    out[index * 4 + 3] = 255
  }
  return out
}

function brightL(): number[] {
  const values = new Array<number>(25).fill(128)
  values[0] = 250
  values[1] = 250
  values[5] = 250
  values[24] = 250
  return values
}

function syncError(fn: () => unknown): CaptureQualityError {
  try {
    fn()
  } catch (error) {
    return error as CaptureQualityError
  }
  throw new Error('expected a synchronous error')
}

function loader(cv: FakeCv): () => Promise<RawCv> {
  return async () => cv as unknown as RawCv
}

function assertClean(cv: FakeCv): void {
  expect(cv.mats.length).toBeGreaterThan(0)
  for (const mat of cv.mats) {
    expect(mat.deleted, 'handle deleted').toBe(true)
    expect(mat.zeroBeforeDelete, 'buffer wiped before delete').toBe(true)
  }
}

describe('T345 real capture-quality meter leaf', () => {
  it('runs the exact native op order/defaults and returns four copied scalars', () => {
    const cv = new FakeCv()
    const rgba = rgbaFromGray(brightL())
    const signals = measureCaptureQualitySignals(cv, rgba, 5, 5)
    expect(cv.calls.map((call) => `${call.name}:${call.argc}`)).toEqual([
      'cvtColor:3',
      'Laplacian:3',
      'mean:1',
      'mean:1',
      'threshold:5',
      'connectedComponentsWithStats:4',
    ])
    expect(cv.calls[0]!.detail).toBe(COLOR_RGBA2GRAY)
    expect(cv.calls[1]!.detail).toBe(CV_64F)
    expect(cv.calls[4]!.detail).toEqual({ thresh: 245, maxval: 255, type: THRESH_BINARY })
    expect(cv.mats.length).toBe(8)
    expect(cv.mats[2]!.mulCalls).toEqual([{ sameHandle: true, scale: 1.0 }])
    expect(Object.keys(signals).sort()).toEqual([
      'clippedWhiteFraction',
      'laplacianVariance',
      'largestGlareFraction',
      'nearBlackFraction',
    ])
    expect(Object.values(signals).every((value) => typeof value === 'number' && Number.isFinite(value))).toBe(true)
    assertClean(cv)
  })

  it('uniform gray has zero variance/exposure/glare; bright L keeps native counts', () => {
    const uniform = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray(new Array<number>(16).fill(128)), 4, 4)
    expect(uniform).toEqual({
      laplacianVariance: 0,
      nearBlackFraction: 0,
      clippedWhiteFraction: 0,
      largestGlareFraction: 0,
    })

    const bright = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray(brightL()), 5, 5)
    expect(bright.laplacianVariance).toBeGreaterThan(0)
    expect(bright.nearBlackFraction).toBe(0)
    expect(bright.clippedWhiteFraction).toBe(0)
    expect(bright.largestGlareFraction).toBeCloseTo(3 / 25, 15)
  })

  it('thresholds strictly above 245 and exposure at inclusive 40/252 boundaries', () => {
    const at245 = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray([245]), 1, 1)
    const at246 = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray([246]), 1, 1)
    expect(at245.largestGlareFraction).toBe(0)
    expect(at246.largestGlareFraction).toBe(1)

    const near = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray([40, 41]), 2, 1)
    expect(near.nearBlackFraction).toBe(0.5)
    const above = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray([41, 42]), 2, 1)
    expect(above.nearBlackFraction).toBe(0)

    const clip251 = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray([251]), 1, 1)
    const clip252 = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray([252]), 1, 1)
    expect(clip251.clippedWhiteFraction).toBe(0)
    expect(clip252.clippedWhiteFraction).toBe(1)
  })

  it('never mutates caller RGBA and never returns native views', () => {
    const cv = new FakeCv()
    const rgba = rgbaFromGray(brightL())
    const before = rgba.slice()
    const signals = measureCaptureQualitySignals(cv, rgba, 5, 5)
    expect(rgba).toEqual(before)
    for (const value of Object.values(signals)) expect(ArrayBuffer.isView(value)).toBe(false)
    assertClean(cv)
  })

  it('skips background area and uses the 5-column stats stride', () => {
    const signals = measureCaptureQualitySignals(new FakeCv(), rgbaFromGray(brightL()), 5, 5)
    // background area is 21 > foreground 3; using label 0 or a wrong stride would change this.
    expect(signals.largestGlareFraction).toBeCloseTo(3 / 25, 15)
  })

  it('refuses malformed dimensions/byte counts as redacted contract errors', () => {
    expect(() => validateCaptureQualityDimensions(0, 1)).toThrow(CaptureQualityError)
    expect(() => validateCaptureQualityDimensions(1.5, 1)).toThrow(CaptureQualityError)
    expect(() => validateCaptureQualityDimensions(5000, 5000)).toThrow(CaptureQualityError)
    expect(syncError(() => measureCaptureQualitySignals(new FakeCv(), new Uint8Array(3), 1, 1)).code).toBe('contract')
  })

  it('maps unknown native execution failures to internal and still cleans every handle', () => {
    const cv = new FakeCv({ failLaplacian: true })
    expect(syncError(() => measureCaptureQualitySignals(cv, rgbaFromGray(brightL()), 5, 5)).code).toBe('internal')
    expect(cv.mats.every((mat) => mat.deleted)).toBe(true)
  })

  it('refuses a non-Mat mul return as contract and cleans up', () => {
    const cv = new FakeCv({ mulReturnsUndefined: true })
    expect(syncError(() => measureCaptureQualitySignals(cv, rgbaFromGray(brightL()), 5, 5)).code).toBe('contract')
    expect(cv.mats.every((mat) => mat.deleted)).toBe(true)
  })

  it('cleans every handle on allocation/view/delete failures', () => {
    const allocation = new FakeCv({ failAllocationAt: 2 })
    expect(syncError(() => measureCaptureQualitySignals(allocation, rgbaFromGray(brightL()), 5, 5)).code).toBe('internal')
    expect(allocation.mats.length).toBe(2)
    expect(allocation.mats.every((mat) => mat.deleted)).toBe(true)

    const view = new FakeCv({ viewThrowsAt: { index: 2, view: 'data64F' } })
    expect(syncError(() => measureCaptureQualitySignals(view, rgbaFromGray(brightL()), 5, 5)).code).toBe('contract')
    expect(view.mats.every((mat) => mat.deleted)).toBe(true)

    const deletion = new FakeCv({ deleteThrowsAt: 1 })
    expect(syncError(() => measureCaptureQualitySignals(deletion, rgbaFromGray(brightL()), 5, 5)).code).toBe('internal')
    expect(deletion.mats.filter((mat) => mat.deleted).length).toBe(deletion.mats.length - 1)
  })
})

describe('T345 async default-loader wrapper', () => {
  it('snapshots caller bytes before the first await', async () => {
    const cv = new FakeCv()
    const rgba = rgbaFromGray(brightL())
    let resolveLoad: ((value: QualityRawCv) => void) | null = null
    const pending = measureCaptureQuality(rgba, 5, 5, {
      loadModule: () =>
        new Promise<RawCv>((resolve) => {
          resolveLoad = (value) => resolve(value as unknown as RawCv)
        }),
    })
    rgba.fill(0)
    resolveLoad!(cv)
    const signals = await pending
    expect(signals.largestGlareFraction).toBeCloseTo(3 / 25, 15)
    expect(rgba.every((value) => value === 0)).toBe(true)
    assertClean(cv)
  })

  it('checks abort/fence/load failure with redacted codes', async () => {
    const rgba = rgbaFromGray(brightL())
    const aborted = new AbortController()
    aborted.abort()
    await expect(
      measureCaptureQuality(rgba, 5, 5, { signal: aborted.signal, loadModule: loader(new FakeCv()) }),
    ).rejects.toMatchObject({ code: 'cancelled' })

    const controller = new AbortController()
    await expect(
      measureCaptureQuality(rgba, 5, 5, {
        signal: controller.signal,
        loadModule: async () => {
          controller.abort()
          return new FakeCv() as unknown as RawCv
        },
      }),
    ).rejects.toMatchObject({ code: 'cancelled' })

    await expect(
      measureCaptureQuality(rgba, 5, 5, { fence: () => false, loadModule: loader(new FakeCv()) }),
    ).rejects.toMatchObject({ code: 'stale' })

    await expect(
      measureCaptureQuality(rgba, 5, 5, {
        loadModule: async () => {
          throw new Error('no runtime')
        },
      }),
    ).rejects.toMatchObject({ code: 'unavailable' })
  })

  it('binds the synchronous meter factory to a loaded runtime', () => {
    const cv = new FakeCv()
    const meter = createCaptureQualityMeter(cv)
    const signals = meter.measure(rgbaFromGray([246]), 1, 1)
    expect(signals.largestGlareFraction).toBe(1)
    assertClean(cv)
  })
})

describe('T345 R2 async options snapshot', () => {
  it('keeps the original abort signal when the caller replaces options.signal', async () => {
    const original = new AbortController()
    const replacement = new AbortController()
    let rejectLoad!: (reason: unknown) => void
    const options: { signal: AbortSignal; loadModule: NonNullable<MeasureCaptureQualityOptions['loadModule']> } = {
      signal: original.signal,
      loadModule: () =>
        new Promise<RawCv>((_resolve, reject) => {
          rejectLoad = reject
        }),
    }
    const pending = measureCaptureQuality(new Uint8Array([128, 128, 128, 255]), 1, 1, options)
    options.signal = replacement.signal
    original.abort()
    rejectLoad(new Error('synthetic loader failure'))
    await expect(pending).rejects.toMatchObject({ code: 'cancelled' })
  })

  it('keeps the original owner fence when the caller replaces options.fence', async () => {
    let current = true
    let resolveLoad!: (cv: RawCv) => void
    const options: { fence: () => boolean; loadModule: NonNullable<MeasureCaptureQualityOptions['loadModule']> } = {
      fence: () => current,
      loadModule: () =>
        new Promise<RawCv>((resolve) => {
          resolveLoad = resolve
        }),
    }
    const pending = measureCaptureQuality(new Uint8Array([128, 128, 128, 255]), 1, 1, options)
    options.fence = () => true
    current = false
    resolveLoad({} as RawCv)
    await expect(pending).rejects.toMatchObject({ code: 'stale' })
  })

  it('evaluates the captured fence after a successful load before native work', async () => {
    const cv = new FakeCv()
    let current = true
    let resolveLoad!: (cv: RawCv) => void
    const pending = measureCaptureQuality(rgbaFromGray(brightL()), 5, 5, {
      fence: () => current,
      loadModule: () =>
        new Promise<RawCv>((resolve) => {
          resolveLoad = resolve
        }),
    })
    current = false
    resolveLoad(cv as unknown as RawCv)
    await expect(pending).rejects.toMatchObject({ code: 'stale' })
    expect(cv.calls.length).toBe(0)
    expect(cv.mats.length).toBe(0)
  })

  it('evaluates the captured abort after a successful load before native work', async () => {
    const cv = new FakeCv()
    const controller = new AbortController()
    let resolveLoad!: (cv: RawCv) => void
    const pending = measureCaptureQuality(rgbaFromGray(brightL()), 5, 5, {
      signal: controller.signal,
      loadModule: () =>
        new Promise<RawCv>((resolve) => {
          resolveLoad = resolve
        }),
    })
    resolveLoad(cv as unknown as RawCv)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ code: 'cancelled' })
    expect(cv.calls.length).toBe(0)
    expect(cv.mats.length).toBe(0)
  })

  it('keeps the original loader when the caller replaces options.loadModule', async () => {
    const cv = new FakeCv()
    let replacementCalled = false
    const options: { loadModule: NonNullable<MeasureCaptureQualityOptions['loadModule']> } = {
      loadModule: async () => cv as unknown as RawCv,
    }
    const pending = measureCaptureQuality(rgbaFromGray([246]), 1, 1, options)
    options.loadModule = async () => {
      replacementCalled = true
      throw new Error('replacement loader must not run')
    }
    const signals = await pending
    expect(signals.largestGlareFraction).toBe(1)
    expect(replacementCalled).toBe(false)
    assertClean(cv)
  })
})

function baseInput(overrides: Partial<CaptureQualityGateInput> = {}): CaptureQualityGateInput {
  return {
    signals: {
      laplacianVariance: 200,
      nearBlackFraction: 0,
      clippedWhiteFraction: 0,
      largestGlareFraction: 0,
    },
    detectedAreaRatio: 0.5,
    rectangularity: 1,
    ...overrides,
  }
}

function reasons(
  overrides: Partial<CaptureQualityGateInput> = {},
  options: CaptureQualityGateOptions = {},
): QualityReason[] {
  return evaluateCaptureQualityGate(baseInput(overrides), options)
}

describe('T345 pure advisory capture-quality gate', () => {
  it('uses the active FRONT 110 blur threshold, not legacy 80', () => {
    expect(FRONT_ADMISSION_MIN_LAPLACIAN_VARIANCE).toBe(110)
    expect(CAPTURE_QUALITY_GATES.profileMinLaplacianVariance).toBe(80)
    expect(reasons({ signals: { ...baseInput().signals, laplacianVariance: 109.999 } })).toEqual(['TOO_BLURRY'])
    expect(reasons({ signals: { ...baseInput().signals, laplacianVariance: 110 } })).toEqual([])
    expect(
      reasons({ signals: { ...baseInput().signals, laplacianVariance: 100 } }, { profile: 'legacy-profile' }),
    ).toEqual([])
  })

  it('applies strict native inequality at every quality boundary', () => {
    expect(reasons({ signals: { ...baseInput().signals, nearBlackFraction: 0.25 } })).toEqual([])
    expect(reasons({ signals: { ...baseInput().signals, nearBlackFraction: 0.2500001 } })).toEqual(['TOO_DARK'])
    expect(reasons({ signals: { ...baseInput().signals, clippedWhiteFraction: 0.2 } })).toEqual([])
    expect(reasons({ signals: { ...baseInput().signals, clippedWhiteFraction: 0.2000001 } })).toEqual([
      'GLARE_EXCESSIVE',
    ])
    expect(reasons({ signals: { ...baseInput().signals, largestGlareFraction: 0.12 } })).toEqual([])
    expect(reasons({ signals: { ...baseInput().signals, largestGlareFraction: 0.1200001 } })).toEqual([
      'GLARE_EXCESSIVE',
    ])
    expect(
      reasons({
        signals: { ...baseInput().signals, clippedWhiteFraction: 0.5, largestGlareFraction: 0.5 },
      }),
    ).toEqual(['GLARE_EXCESSIVE'])
  })

  it('mirrors area/rectangularity/optional aspect/short-edge native branches', () => {
    expect(reasons({ detectedAreaRatio: 0.35 })).toEqual([])
    expect(reasons({ detectedAreaRatio: 0.3499 })).toEqual(['CARD_TOO_SMALL'])
    expect(reasons({ detectedAreaRatio: Number.NaN })).toEqual(['CROP_UNCERTAIN'])
    expect(reasons({ detectedAreaRatio: -1 })).toEqual(['CROP_UNCERTAIN'])
    expect(reasons({ rectangularity: 0.8 })).toEqual([])
    expect(reasons({ rectangularity: 0.7999 })).toEqual(['ANGLE_UNCERTAIN'])
    expect(reasons({ rectangularity: Number.NaN })).toEqual(['CROP_UNCERTAIN'])

    expect(reasons({ cropAspectRatio: 1.15 })).toEqual([])
    expect(reasons({ cropAspectRatio: 1.14 })).toEqual(['ANGLE_UNCERTAIN'])
    expect(reasons({ cropAspectRatio: 2.2 })).toEqual([])
    expect(reasons({ cropAspectRatio: 2.21 })).toEqual(['ANGLE_UNCERTAIN'])
    expect(reasons({ cropAspectRatio: 1 / 1.2 })).toEqual([])
    expect(reasons({ cropAspectRatio: 0.5 })).toEqual([])
    expect(reasons({ cropAspectRatio: 0.4 })).toEqual(['ANGLE_UNCERTAIN'])
    expect(reasons({ cropAspectRatio: 0 })).toEqual(['CROP_UNCERTAIN'])
    expect(reasons({ cropAspectRatio: Number.NaN })).toEqual(['CROP_UNCERTAIN'])

    expect(reasons({ croppedShortEdgePx: 600 })).toEqual([])
    expect(reasons({ croppedShortEdgePx: 599 })).toEqual(['RESOLUTION_INSUFFICIENT'])
    expect(reasons({ croppedShortEdgePx: 0 })).toEqual(['CROP_UNCERTAIN'])

    expect(reasons({ cropAspectRatio: null, croppedShortEdgePx: null })).toEqual([])
  })

  it('returns a deterministic deduplicated native-order list and never FEATURES_INSUFFICIENT', () => {
    const result = reasons({
      signals: {
        laplacianVariance: 1,
        nearBlackFraction: 0.9,
        clippedWhiteFraction: 0.9,
        largestGlareFraction: 0.9,
      },
      detectedAreaRatio: Number.NaN,
      rectangularity: 0.5,
      cropAspectRatio: 0.4,
      croppedShortEdgePx: 100,
    })
    expect(result).toEqual([
      'CROP_UNCERTAIN',
      'ANGLE_UNCERTAIN',
      'RESOLUTION_INSUFFICIENT',
      'TOO_BLURRY',
      'TOO_DARK',
      'GLARE_EXCESSIVE',
    ])
    expect(result).not.toContain('FEATURES_INSUFFICIENT')
  })

  it('is advisory-only and refuses BACK/bad measurements', () => {
    const result = reasons({ signals: { ...baseInput().signals, laplacianVariance: 1 } })
    expect(result).toEqual(['TOO_BLURRY'])
    expect(typeof result).toBe('object')
    expect(syncError(() => evaluateCaptureQualityGate(baseInput(), { side: 'back' })).code).toBe('contract')
    expect(
      syncError(() =>
        evaluateCaptureQualityGate(
          baseInput({ signals: { laplacianVariance: Number.NaN } as unknown as CaptureQualitySignals }),
        ),
      ).code,
    ).toBe('contract')
  })
})
