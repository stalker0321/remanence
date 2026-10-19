import { describe, expect, it } from 'vitest'
import { wrapOpenCvRuntime } from '../src/scan/opencvRuntime'
import type { CvMat } from '../src/scan/opencvPort'

type RawCv = Parameters<typeof wrapOpenCvRuntime>[0]

const CV_8UC1 = 0
const CV_32F = 5
const INTER_AREA = 3

interface Log {
  created: string[]
  deleted: string[]
}

interface Controls {
  resizeThrows?: boolean
  convertThrows?: boolean
  dctThrows?: boolean
  detectThrows?: boolean
  computeThrows?: boolean
  pushBackThrows?: boolean
  maskThrows?: boolean
  setThrows?: boolean
}

/**
 * Minimal fake `cv` module. It records handle creation/deletion so the runtime
 * bridge's ownership contract can be checked: a throwing allocation or native
 * operation must still release everything it already owns. Not a detector.
 */
function buildCv(controls: Controls = {}): { cv: RawCv; log: Log } {
  const log: Log = { created: [], deleted: [] }
  class FakeMat {
    rows = 1
    cols = 1
    data = new Uint8Array(4)
    data32F = new Float32Array(4)
    constructor() {
      if (controls.maskThrows) throw new Error('mat constructor failed')
      log.created.push('mat')
      if (controls.setThrows) (this.data as unknown as { set: () => void }).set = () => {
        throw new Error('set failed')
      }
    }
    type(): number {
      return CV_8UC1
    }
    delete(): void {
      log.deleted.push('mat')
    }
    convertTo(): void {
      if (controls.convertThrows) throw new Error('convert failed')
    }
  }
  class FakeKeyPointVector {
    constructor() {
      log.created.push('vector')
    }
    size(): number {
      return 0
    }
    get(): unknown {
      return {}
    }
    push_back(): void {
      if (controls.pushBackThrows) throw new Error('push_back failed')
    }
    delete(): void {
      log.deleted.push('vector')
    }
  }
  class FakeSift {
    detect(): void {
      if (controls.detectThrows) throw new Error('detect failed')
    }
    compute(): void {
      if (controls.computeThrows) throw new Error('compute failed')
    }
    delete(): void {
      log.deleted.push('sift')
    }
  }
  const cv = {
    Mat: FakeMat,
    Size: class {
      constructor(
        readonly width: number,
        readonly height: number,
      ) {}
    },
    KeyPointVector: FakeKeyPointVector,
    SIFT: FakeSift,
    CV_8UC1,
    CV_32F,
    INTER_AREA,
    resize: (): void => {
      if (controls.resizeThrows) throw new Error('resize failed')
    },
    dct: (): void => {
      if (controls.dctThrows) throw new Error('dct failed')
    },
  } as unknown as RawCv
  return { cv, log }
}

/** An unlogged input Mat (caller-owned) with an optionally throwing convertTo. */
function sourceMat(controls: Controls = {}): CvMat {
  return {
    rows: 1,
    cols: 1,
    data: new Uint8Array(4),
    data32F: new Float32Array(4),
    type: () => CV_8UC1,
    delete: () => undefined,
    convertTo: (): void => {
      if (controls.convertThrows) throw new Error('convert failed')
    },
  } as CvMat
}

function siftParams() {
  return { nfeatures: 0, octaveLayers: 3, contrastThreshold: 0.018, edgeThreshold: 12, sigma: 1.6 }
}

describe('T312 runtime bridge ownership on throwing native operations', () => {
  it('releases the Mat when setting caller gray fails', () => {
    const { cv, log } = buildCv({ setThrows: true })
    const runtime = wrapOpenCvRuntime(cv)
    expect(() => runtime.matFromGray(new Uint8Array(4), 2, 2)).toThrow()
    expect(log.created).toEqual(['mat'])
    expect(log.deleted).toEqual(['mat'])
  })

  it('releases the destination when resize fails', () => {
    const { cv, log } = buildCv({ resizeThrows: true })
    const runtime = wrapOpenCvRuntime(cv)
    expect(() => runtime.resizeArea(sourceMat(), 8)).toThrow()
    expect(log.created).toEqual(['mat'])
    expect(log.deleted).toEqual(['mat'])
  })

  it('releases the destination when convertTo fails', () => {
    const { cv, log } = buildCv()
    const runtime = wrapOpenCvRuntime(cv)
    expect(() => runtime.convertToFloat(sourceMat({ convertThrows: true }))).toThrow()
    expect(log.created).toEqual(['mat'])
    expect(log.deleted).toEqual(['mat'])
  })

  it('releases the destination when dct fails', () => {
    const { cv, log } = buildCv({ dctThrows: true })
    const runtime = wrapOpenCvRuntime(cv)
    expect(() => runtime.dct(sourceMat())).toThrow()
    expect(log.created).toEqual(['mat'])
    expect(log.deleted).toEqual(['mat'])
  })

  it('releases the keypoint vector when the mask allocation fails', () => {
    const { cv, log } = buildCv({ maskThrows: true })
    const sift = wrapOpenCvRuntime(cv).createSift(siftParams())
    expect(() => sift.detect(sourceMat())).toThrow()
    expect(log.created).toEqual(['vector'])
    expect(log.deleted).toEqual(['vector'])
  })

  it('releases the vector and mask when detect fails', () => {
    const { cv, log } = buildCv({ detectThrows: true })
    const sift = wrapOpenCvRuntime(cv).createSift(siftParams())
    expect(() => sift.detect(sourceMat())).toThrow()
    expect(log.created).toEqual(['vector', 'mat'])
    expect(log.deleted).toEqual(['mat', 'vector'])
  })

  it('releases the descriptors and vector when compute fails', () => {
    const { cv, log } = buildCv({ computeThrows: true })
    const sift = wrapOpenCvRuntime(cv).createSift(siftParams())
    expect(() => sift.compute(sourceMat(), [])).toThrow()
    expect(log.created).toEqual(['vector', 'mat'])
    expect(log.deleted).toEqual(['mat', 'vector'])
  })

  it('releases the vector when pushing a keypoint fails before the descriptor exists', () => {
    const { cv, log } = buildCv({ pushBackThrows: true })
    const sift = wrapOpenCvRuntime(cv).createSift(siftParams())
    expect(() => sift.compute(sourceMat(), [{ pt: { x: 1, y: 1 }, size: 1, angle: 0, response: 1, octave: 0 }])).toThrow()
    expect(log.created).toEqual(['vector'])
    expect(log.deleted).toEqual(['vector'])
  })
})
