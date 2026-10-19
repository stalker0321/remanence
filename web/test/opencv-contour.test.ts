import { describe, expect, it } from 'vitest'
import { wrapOpenCvRuntime } from '../src/scan/opencvRuntime'

const CV_8UC1 = 0
const CV_8UC4 = 24
const CV_32FC2 = 13
const CV_32SC2 = 12

interface Log {
  created: string[]
  deleted: string[]
}

interface Controls {
  setThrows?: boolean
  cannyThrows?: boolean
  warpThrows?: boolean
  findThrows?: boolean
  poisonTransformDelete?: boolean
}

/** Minimal fake `cv` module recording handle lifetimes for the contour path. */
function buildCv(controls: Controls = {}): { cv: Parameters<typeof wrapOpenCvRuntime>[0]; log: Log } {
  const log: Log = { created: [], deleted: [] }
  class FakeMat {
    rows = 1
    cols = 1
    typeId = CV_8UC1
    data: Uint8Array = new Uint8Array(16)
    data32S = new Int32Array(0)
    label = 'mat'
    constructor() {
      log.created.push(this.label)
      if (controls.setThrows) {
        this.data = {
          set: () => {
            throw new Error('set failed')
          },
          fill: () => undefined,
          length: 16,
        } as unknown as Uint8Array
      }
    }
    type(): number {
      return this.typeId
    }
    delete(): void {
      if (controls.poisonTransformDelete && this.label === 'transform') throw new Error('delete failed')
      log.deleted.push(this.label)
    }
  }
  class FakeVec {
    items: FakeMat[] = []
    constructor() {
      log.created.push('vector')
    }
    size(): number {
      return this.items.length
    }
    get(index: number): FakeMat {
      return this.items[index]!
    }
    delete(): void {
      log.deleted.push('vector')
    }
  }
  const cv = {
    Mat: FakeMat,
    MatVector: FakeVec,
    Size: class {
      constructor(
        readonly width: number,
        readonly height: number,
      ) {}
    },
    matFromArray: (rows: number, cols: number, type: number, array: number[]) => {
      const mat = new FakeMat()
      mat.rows = rows
      mat.cols = cols
      mat.typeId = type
      void array
      return mat
    },
    CV_8UC1,
    CV_8UC4,
    CV_32F: 5,
    CV_32SC2,
    CV_32FC2,
    COLOR_RGBA2GRAY: 11,
    RETR_EXTERNAL: 0,
    CHAIN_APPROX_SIMPLE: 2,
    MORPH_RECT: 0,
    MORPH_CLOSE: 3,
    INTER_AREA: 3,
    cvtColor: (): void => undefined,
    GaussianBlur: (): void => undefined,
    mean: () => ({ 0: 100 }),
    Canny: (): void => {
      if (controls.cannyThrows) throw new Error('canny failed')
    },
    getStructuringElement: () => new FakeMat(),
    morphologyEx: (): void => undefined,
    findContours: (_src: unknown, contours: FakeVec, _hier: unknown): void => {
      if (controls.findThrows) throw new Error('find failed')
      const contour = new FakeMat()
      contour.typeId = CV_32SC2
      contour.data32S = new Int32Array([10, 10, 50, 10, 50, 40, 10, 40])
      contours.items.push(contour)
    },
    approxPolyDP: (_curve: unknown, approx: FakeMat): void => {
      approx.rows = 4
      approx.cols = 1
      approx.typeId = CV_32SC2
      approx.data32S = new Int32Array([10, 10, 50, 10, 50, 40, 10, 40])
    },
    isContourConvex: () => true,
    contourArea: () => 1200,
    arcLength: () => 140,
    getPerspectiveTransform: () => {
      const mat = new FakeMat()
      mat.label = 'transform'
      return mat
    },
    warpPerspective: (_src: unknown, dst: FakeMat, _t: unknown, size: { width: number; height: number }): void => {
      if (controls.warpThrows) throw new Error('warp failed')
      dst.rows = size.height
      dst.cols = size.width
      dst.data = new Uint8Array(size.width * size.height * 4).fill(7)
    },
    KeyPointVector: FakeVec,
    SIFT: class {},
    dct: (): void => undefined,
    resize: (): void => undefined,
    convertTo: (): void => undefined,
  } as unknown as Parameters<typeof wrapOpenCvRuntime>[0]
  return { cv, log }
}

describe('T316 contour bridge ownership', () => {
  it('builds an RGBA mat and releases it when the pixel copy fails', () => {
    const { cv, log } = buildCv({ setThrows: true })
    const runtime = wrapOpenCvRuntime(cv)
    expect(() => runtime.matFromRgba(new Uint8Array(2 * 2 * 4), 2, 2)).toThrow()
    expect(log.created).toEqual(['mat'])
    expect(log.deleted).toEqual(['mat'])
  })

  it('detects one quad and releases every native handle', () => {
    const { cv, log } = buildCv()
    const runtime = wrapOpenCvRuntime(cv)
    const rgba = { rows: 60, cols: 80, type: () => CV_8UC4, data: new Uint8Array(80 * 60 * 4), data32F: new Float32Array(0), delete: () => undefined }
    const quads = runtime.detectQuadCandidates(rgba, 80, 60)
    expect(quads).toHaveLength(1)
    expect(quads[0]!.points).toEqual([
      { x: 10, y: 10 },
      { x: 50, y: 10 },
      { x: 50, y: 40 },
      { x: 10, y: 40 },
    ])
    // Every created native handle was released exactly once.
    expect(log.deleted.length).toBe(log.created.length)
    expect(log.deleted).toContain('vector')
  })

  it('releases allocated handles when Canny throws', () => {
    const { cv, log } = buildCv({ cannyThrows: true })
    const runtime = wrapOpenCvRuntime(cv)
    const rgba = { rows: 60, cols: 80, type: () => CV_8UC4, data: new Uint8Array(80 * 60 * 4), data32F: new Float32Array(0), delete: () => undefined }
    expect(() => runtime.detectQuadCandidates(rgba, 80, 60)).toThrow()
    expect(log.deleted.length).toBe(log.created.length)
    expect(log.deleted).not.toContain('vector')
  })

  it('warps to the target size and releases transform and destination', () => {
    const { cv, log } = buildCv()
    const runtime = wrapOpenCvRuntime(cv)
    const rgba = { rows: 60, cols: 80, type: () => CV_8UC4, data: new Uint8Array(80 * 60 * 4), data32F: new Float32Array(0), delete: () => undefined }
    const src = [
      { x: 10, y: 10 },
      { x: 50, y: 10 },
      { x: 50, y: 40 },
      { x: 10, y: 40 },
    ] as const
    const dst = [
      { x: 0, y: 0 },
      { x: 99, y: 0 },
      { x: 99, y: 49 },
      { x: 0, y: 49 },
    ] as const
    const warped = runtime.warpRgbaQuad(rgba, [...src], [...dst], 100, 50)
    expect(warped.width).toBe(100)
    expect(warped.height).toBe(50)
    expect(warped.bytes.length).toBe(100 * 50 * 4)
    expect(log.deleted.length).toBe(log.created.length)
  })

  it('releases allocated handles when warpPerspective throws', () => {
    const { cv, log } = buildCv({ warpThrows: true })
    const runtime = wrapOpenCvRuntime(cv)
    const rgba = { rows: 60, cols: 80, type: () => CV_8UC4, data: new Uint8Array(80 * 60 * 4), data32F: new Float32Array(0), delete: () => undefined }
    const quad = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
      { x: 0, y: 10 },
    ] as const
    expect(() => runtime.warpRgbaQuad(rgba, [...quad], [...quad], 10, 10)).toThrow()
    expect(log.deleted.length).toBe(log.created.length)
  })

  it('wipes the copied warp output when a native release fails after the copy', () => {
    const { cv, log } = buildCv({ poisonTransformDelete: true })
    const runtime = wrapOpenCvRuntime(cv)
    const rgba = { rows: 60, cols: 80, type: () => CV_8UC4, data: new Uint8Array(80 * 60 * 4), data32F: new Float32Array(0), delete: () => undefined }
    const quad = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
      { x: 0, y: 10 },
    ] as const
    interface FillRecord {
      before: Uint8Array
      after: Uint8Array
    }
    const records: FillRecord[] = []
    const originalFill = Uint8Array.prototype.fill
    const installFill = originalFill as unknown as (value: number, start?: number, end?: number) => Uint8Array
    ;(Uint8Array.prototype as { fill: unknown }).fill = function (
      this: Uint8Array,
      value: number,
      start?: number,
      end?: number,
    ): Uint8Array {
      records.push({ before: this.slice(), after: this })
      return installFill.call(this, value, start, end)
    }
    try {
      // The transform delete throws during strict release AFTER dst.data was
      // copied; the caller never receives the copy, so the bridge must wipe it.
      expect(() => runtime.warpRgbaQuad(rgba, [...quad], [...quad], 10, 10)).toThrow()
      // All remaining handles were still attempted: src/dst points + dst.
      expect(log.deleted).toEqual(['mat', 'mat', 'mat'])
      // Both the 400-byte nonzero dst buffer AND the 400-byte nonzero copy
      // were zeroed (R2 code wipes only dst: exactly one such record there).
      const zeroedCopies = records.filter(
        (entry) =>
          entry.before.length === 400 &&
          entry.before.some((byte) => byte !== 0) &&
          entry.after.every((byte) => byte === 0),
      )
      expect(zeroedCopies).toHaveLength(2)
    } finally {
      ;(Uint8Array.prototype as { fill: unknown }).fill = originalFill
    }
  })
})
