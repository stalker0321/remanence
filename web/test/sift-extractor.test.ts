import { describe, expect, it } from 'vitest'
import {
  candidateComparator,
  candidateFromKeyPoint,
  decodeOctave,
  extractSiftFingerprint,
  extractWithRuntime,
  hasMatcherUsableRows,
  packCoarseHash,
  quantizeKeypoint,
  quantizeRawSiftRow,
  rint,
  selectCandidates,
  SiftExtractionError,
  toGrayRgba,
  validateAndOwnFrame,
  type SiftCandidate,
} from '../src/scan/siftExtractor'
import type { CvKeyPoint, CvMat, CvRuntime, CvSift, SiftParameters } from '../src/scan/opencvPort'
import {
  DESCRIPTOR_BYTES,
  parseSiftFingerprint,
  serializeSiftFingerprint,
  SIFT_FINGERPRINT_PROFILE_ID,
} from '../src/scan/siftFingerprint'
import { canonicalFrame } from './fixtures/canonicalFrame'

const CV_8UC1 = 0
const CV_32F = 5
const INTER_AREA = 3

interface FakeOptions {
  detections?: readonly CvKeyPoint[]
  descriptorRows?: readonly number[][]
  dctValues?: readonly number[]
  onCompute?: () => void
  onDct?: () => void
  computeKeypointOverride?: readonly CvKeyPoint[]
  failDescriptorDelete?: boolean
}

interface WipeRecord {
  type: number
  dataZeroed: boolean
  floatZeroed: boolean
}

interface FakeRuntime {
  runtime: CvRuntime
  deleted: CvMat[]
  wipes: WipeRecord[]
  siftDeleted: () => number
}

function mat(
  rows: number,
  cols: number,
  type: number,
  data: Uint8Array,
  data32F: Float32Array,
  deleted: CvMat[],
  wipes: WipeRecord[],
  onDelete?: () => void,
): CvMat {
  const handle: CvMat = {
    rows,
    cols,
    data,
    data32F,
    type: () => type,
    delete: () => {
      wipes.push({
        type,
        dataZeroed: data.length === 0 || data.every((byte) => byte === 0),
        floatZeroed: data32F.length === 0 || data32F.every((value) => value === 0),
      })
      deleted.push(handle)
      onDelete?.()
    },
  }
  return handle
}

/** The exposed view was wiped by its actual element type before release. */
function wipedByType(wipes: readonly WipeRecord[]): boolean {
  return wipes.every((record) => {
    if (record.type === CV_8UC1) return record.dataZeroed
    if (record.type === CV_32F) return record.floatZeroed
    return true
  })
}

function makeFakeRuntime(options: FakeOptions = {}): FakeRuntime {
  const deleted: CvMat[] = []
  const wipes: WipeRecord[] = []
  const detections = options.detections ?? []
  const descriptorRows = options.descriptorRows ?? []
  const dctValues = options.dctValues ?? new Array(64).fill(0)
  let siftDeleteCount = 0

  const runtime: CvRuntime = {
    CV_8UC1,
    CV_32F,
    INTER_AREA,
    matFromGray: (gray) => mat(1, gray.length, CV_8UC1, gray.slice(), new Float32Array(0), deleted, wipes),
    createEmptyMat: () => mat(0, 0, CV_8UC1, new Uint8Array(0), new Float32Array(0), deleted, wipes),
    createSift: (_parameters: SiftParameters): CvSift => {
      let live = true
      return {
        detect: () => detections.map((point) => point),
        compute: (_gray, points) => {
          options.onCompute?.()
          const rows = options.computeKeypointOverride ?? points
          const flat = new Float32Array(rows.length * DESCRIPTOR_BYTES)
          for (let row = 0; row < rows.length; row++) {
            const source = descriptorRows[row] ?? []
            for (let col = 0; col < DESCRIPTOR_BYTES; col++) flat[row * DESCRIPTOR_BYTES + col] = source[col] ?? 0
          }
          return {
            descriptors: mat(
              rows.length,
              DESCRIPTOR_BYTES,
              CV_32F,
              new Uint8Array(0),
              flat,
              deleted,
              wipes,
              options.failDescriptorDelete
                ? () => {
                    throw new Error('cleanup boom')
                  }
                : undefined,
            ),
            keypoints: rows.map((point) => point),
          }
        },
        delete: () => {
          if (live) {
            live = false
            siftDeleteCount += 1
          }
        },
      }
    },
    resizeArea: (src, size) => {
      const values = new Uint8Array(size * size)
      for (let index = 0; index < values.length; index++) values[index] = src.data[index * 17] ?? 0
      return mat(size, size, CV_8UC1, values, new Float32Array(values.length), deleted, wipes)
    },
    convertToFloat: (src) => {
      const out = new Float32Array(src.data.length)
      for (let index = 0; index < out.length; index++) out[index] = src.data[index] ?? 0
      return mat(src.rows, src.cols, CV_32F, new Uint8Array(0), out, deleted, wipes)
    },
    dct: () => {
      options.onDct?.()
      return mat(8, 8, CV_32F, new Uint8Array(0), Float32Array.from(dctValues), deleted, wipes)
    },
  }
  return { runtime, deleted, wipes, siftDeleted: () => siftDeleteCount }
}

function kp(x: number, y: number, response: number, overrides: Partial<CvKeyPoint> = {}): CvKeyPoint {
  return { pt: { x, y }, size: overrides.size ?? 10, angle: overrides.angle ?? 90, response, octave: overrides.octave ?? 0 }
}

function reason(action: () => unknown): string {
  try {
    action()
    return 'ok'
  } catch (error) {
    return error instanceof SiftExtractionError ? error.code : 'other'
  }
}

describe('T312 frame ownership and grayscale', () => {
  it('rejects malformed frames with typed dimension/pixel refusals', () => {
    const good = canonicalFrame(8, 4)
    expect(reason(() => validateAndOwnFrame({ ...good, width: 0 }))).toBe('dimensions')
    expect(reason(() => validateAndOwnFrame({ ...good, width: 1.5 }))).toBe('dimensions')
    expect(reason(() => validateAndOwnFrame({ ...good, width: 100_001 }))).toBe('dimensions')
    expect(reason(() => validateAndOwnFrame({ ...good, height: -1 }))).toBe('dimensions')
    expect(reason(() => validateAndOwnFrame({ ...good, width: 5000, height: 4000, pixels: new Uint8Array(0) }))).toBe('dimensions')
    expect(reason(() => validateAndOwnFrame({ width: 4, height: 4, pixels: new Uint8Array(63) }))).toBe('pixels')
    expect(reason(() => validateAndOwnFrame({ width: 4, height: 4, pixels: new Uint8ClampedArray(64) as unknown as Uint8Array }))).toBe('pixels')
  })

  it('owns an independent copy of the caller pixels with the validated dimensions', () => {
    const frame = canonicalFrame(6, 6)
    const owned = validateAndOwnFrame(frame)
    expect(owned.pixels).not.toBe(frame.pixels)
    expect(Array.from(owned.pixels)).toEqual(Array.from(frame.pixels))
    expect(owned.width).toBe(6)
    expect(owned.height).toBe(6)
    owned.pixels[0] = 7
    expect(frame.pixels[0]).not.toBe(7)
    frame.pixels[1] = 9
    expect(owned.pixels[1]).not.toBe(9)
  })

  it('matches the Android integer grayscale floor and ignores alpha', () => {
    const rgba = Uint8Array.from([255, 255, 255, 0, 0, 0, 0, 255, 255, 0, 0, 128, 1, 1, 1, 255, 200, 100, 50, 7])
    const gray = toGrayRgba(rgba, 5, 1)
    expect(Array.from(gray)).toEqual([
      (299 * 255 + 587 * 255 + 114 * 255) / 1000 | 0,
      0,
      (299 * 255) / 1000 | 0,
      (299 + 587 + 114) / 1000 | 0,
      (299 * 200 + 587 * 100 + 114 * 50) / 1000 | 0,
    ])
  })

  it('decodes the signed low octave byte', () => {
    expect(decodeOctave(0)).toBe(0)
    expect(decodeOctave(127)).toBe(127)
    expect(decodeOctave(128)).toBe(-128)
    expect(decodeOctave(255)).toBe(-1)
    expect(decodeOctave(0x1ff)).toBe(-1)
  })

  it('maps candidate fields including decoded octave', () => {
    const candidate = candidateFromKeyPoint(kp(3, 4, 9, { size: 12, angle: 45, octave: 0xfe }), 7)
    expect(candidate).toEqual({ xPx: 3, yPx: 4, sizePx: 12, angleDegrees: 45, response: 9, encodedOctave: -2, sourceIndex: 7 })
  })
})

describe('T312 selection policy', () => {
  function candidate(xPx: number, yPx: number, response: number, index: number): SiftCandidate {
    return { xPx, yPx, sizePx: 10, angleDegrees: 0, response, encodedOctave: 0, sourceIndex: index }
  }

  it('refuses empty and invalid candidate sets', () => {
    expect(reason(() => selectCandidates(10, 10, []))).toBe('no-features')
    expect(reason(() => selectCandidates(10, 10, [candidate(5, 5, 1, -1)]))).toBe('shape')
    expect(reason(() => selectCandidates(10, 10, [{ ...candidate(5, 5, 1, 0), encodedOctave: 9 }]))).toBe('shape')
    expect(reason(() => selectCandidates(10, 10, [{ ...candidate(5, 5, 1, 0), angleDegrees: 360 }]))).toBe('shape')
    expect(reason(() => selectCandidates(10, 10, [{ ...candidate(5, 5, 1, 0), response: -1 }]))).toBe('shape')
  })

  it('caps each 6x6 cell at 45 and the global list at 1500', () => {
    const perCell = Array.from({ length: 60 }, (_, index) => candidate(1, 1, 60 - index, index))
    const selectedPerCell = selectCandidates(60, 60, perCell)
    expect(selectedPerCell).toHaveLength(45)

    const spread = Array.from({ length: 3600 }, (_, index) => candidate(index % 60, Math.floor(index / 60), 3600 - index, index))
    const selected = selectCandidates(60, 60, spread)
    expect(selected).toHaveLength(1500)
  })

  it('orders by response descending then geometry then source index', () => {
    const items = [candidate(5, 5, 2, 0), candidate(5, 5, 2, 1), candidate(5, 4, 2, 2), candidate(1, 1, 3, 3)]
    const sorted = [...items].map((c) => ({ candidate: c, cellX: (c.xPx / 10 * 6) | 0, cellY: (c.yPx / 10 * 6) | 0 }))
    sorted.sort(candidateComparator)
    expect(sorted.map((s) => s.candidate.sourceIndex)).toEqual([3, 2, 0, 1])
  })

  it('uses a half-open grid with a clamped outer cell', () => {
    const width = 60
    const edge = selectCandidates(width, width, [candidate(59, 59, 1, 0), candidate(width, width, 2, 1)])
    expect(edge.map((s) => [s.cellX, s.cellY])).toEqual([
      [5, 5],
      [5, 5],
    ])
  })
})

describe('T312 rounding and quantization', () => {
  it('rounds ties to even', () => {
    expect(rint(0.5)).toBe(0)
    expect(rint(1.5)).toBe(2)
    expect(rint(2.5)).toBe(2)
    expect(rint(3.5)).toBe(4)
    expect(rint(2.4)).toBe(2)
    expect(rint(2.6)).toBe(3)
    expect(rint(-0.5)).toBe(0)
    expect(rint(-1.5)).toBe(-2)
  })

  it('quantizes keypoint metadata with width normalization and clamping', () => {
    const base: SiftCandidate = { xPx: 0, yPx: 0, sizePx: 10, angleDegrees: 0, response: 0, encodedOctave: 0, sourceIndex: 0 }
    const keypoint = quantizeKeypoint({ ...base, xPx: 5, yPx: 10, sizePx: 20, angleDegrees: 359.999, response: 0.5 }, 10, 20)
    expect(keypoint.xMicro).toBe(500_000)
    expect(keypoint.yMicro).toBe(500_000)
    expect(keypoint.scaleMicro).toBe(1_000_000)
    expect(keypoint.angleCentiDegrees).toBe(35_999)
    expect(keypoint.responseQuantized).toBe(500_000)
    const clamped = quantizeKeypoint({ ...base, sizePx: 50, response: 2 }, 10, 10)
    expect(clamped.scaleMicro).toBe(1_000_000)
    expect(clamped.responseQuantized).toBe(1_000_000)
  })

  it('quantizes raw descriptor rows with ties-to-even and clamping', () => {
    const values = new Array<number>(DESCRIPTOR_BYTES).fill(254.5)
    values[0] = -5
    values[1] = 300
    values[2] = 0.5
    values[3] = 1.5
    const row = quantizeRawSiftRow(values)
    expect(row).toHaveLength(DESCRIPTOR_BYTES)
    expect([row[0], row[1], row[2], row[3], row[4]]).toEqual([0, 255, 0, 2, 254])
    expect(reason(() => quantizeRawSiftRow(new Array(DESCRIPTOR_BYTES).fill(Number.NaN)))).toBe('shape')
    expect(reason(() => quantizeRawSiftRow(new Array(3).fill(1)))).toBe('shape')
  })

  it('reports matcher-usable rows only when some byte is nonzero', () => {
    expect(hasMatcherUsableRows([new Uint8Array(DESCRIPTOR_BYTES), new Uint8Array(DESCRIPTOR_BYTES)])).toBe(false)
    const row = new Uint8Array(DESCRIPTOR_BYTES)
    row[7] = 1
    expect(hasMatcherUsableRows([new Uint8Array(DESCRIPTOR_BYTES), row])).toBe(true)
  })

  it('packs the 63 AC coefficients as strict-greater-than-median bits', () => {
    const values = Float64Array.from({ length: 64 }, (_, index) => index)
    // AC = 1..63; median == 32; bits are 1 for AC > 32 (i.e. 33..63 -> 31 ones).
    const packed = packCoarseHash(values)
    expect(packed).toBe((1n << 31n) - 1n)
    expect(reason(() => packCoarseHash(new Array(5).fill(0)))).toBe('shape')
    expect(reason(() => packCoarseHash(new Array(64).fill(Number.POSITIVE_INFINITY)))).toBe('shape')
  })
})

describe('T312 orchestration with a deterministic test double', () => {
  const settingsRuntime = () => makeFakeRuntime({ detections: [kp(5, 5, 3), kp(6, 6, 2)], descriptorRows: [[1], [2]], dctValues: Array.from({ length: 64 }, (_, i) => i) })

  it('assembles the accepted domain in selection order and releases every native object', () => {
    const { runtime, deleted, siftDeleted } = settingsRuntime()
    const frame = canonicalFrame(64, 64)
    const fingerprint = extractWithRuntime(runtime, frame.pixels, frame.width, frame.height)
    expect(fingerprint.profileId).toBe(SIFT_FINGERPRINT_PROFILE_ID)
    expect(fingerprint.canonicalWidthPx).toBe(64)
    expect(fingerprint.canonicalHeightPx).toBe(64)
    expect(fingerprint.keypoints).toHaveLength(2)
    expect(fingerprint.quantizedSiftDescriptors).toHaveLength(2)
    expect(fingerprint.quantizedSiftDescriptors.every((row) => row.length === DESCRIPTOR_BYTES)).toBe(true)
    const wire = serializeSiftFingerprint(fingerprint)
    expect(parseSiftFingerprint(wire).keypoints).toEqual(fingerprint.keypoints)
    expect(deleted.length).toBeGreaterThanOrEqual(5)
    expect(siftDeleted()).toBe(1)
  })

  it('refuses when the detector returns nothing', () => {
    const { runtime } = makeFakeRuntime({ detections: [] })
    const frame = canonicalFrame(32, 32)
    expect(reason(() => extractWithRuntime(runtime, frame.pixels, frame.width, frame.height))).toBe('no-features')
  })

  it('refuses a descriptor/keypoint count mismatch', () => {
    const runtime = makeFakeRuntime({
      detections: [kp(5, 5, 3), kp(6, 6, 2)],
      descriptorRows: [[1], [2]],
      computeKeypointOverride: [kp(5, 5, 3)],
    })
    const frame = canonicalFrame(32, 32)
    expect(reason(() => extractWithRuntime(runtime.runtime, frame.pixels, frame.width, frame.height))).toBe('shape')
  })

  it('honors an abort signal', () => {
    const { runtime } = makeFakeRuntime({ detections: [kp(5, 5, 3)], descriptorRows: [[1]] })
    const frame = canonicalFrame(32, 32)
    const controller = new AbortController()
    controller.abort()
    expect(reason(() => extractWithRuntime(runtime, frame.pixels, frame.width, frame.height, controller.signal))).toBe('aborted')
  })

  it('honors an abort signalled after the coarse-hash DCT stage', () => {
    const controller = new AbortController()
    const { runtime } = makeFakeRuntime({
      detections: [kp(5, 5, 3), kp(6, 6, 2)],
      descriptorRows: [[1], [2]],
      dctValues: Array.from({ length: 64 }, (_, i) => i),
      onDct: () => controller.abort(),
    })
    const frame = canonicalFrame(64, 64)
    expect(reason(() => extractWithRuntime(runtime, frame.pixels, frame.width, frame.height, controller.signal))).toBe('aborted')
  })

  it('wipes every exposed native Mat buffer by its actual type before release', () => {
    const { runtime, wipes, deleted } = settingsRuntime()
    const frame = canonicalFrame(64, 64)
    extractWithRuntime(runtime, frame.pixels, frame.width, frame.height)
    expect(deleted.length).toBeGreaterThanOrEqual(5)
    expect(wipes.length).toBe(deleted.length)
    expect(wipedByType(wipes)).toBe(true)
    // The descriptor Mat (CV_32F) really did hold nonzero data that got wiped.
    expect(wipes.some((record) => record.type === CV_32F && record.floatZeroed)).toBe(true)
  })

  it('refuses typed internal and does not transfer output when native cleanup fails', () => {
    const { runtime } = makeFakeRuntime({
      detections: [kp(5, 5, 3), kp(6, 6, 2)],
      descriptorRows: [[1], [2]],
      dctValues: Array.from({ length: 64 }, (_, i) => i),
      failDescriptorDelete: true,
    })
    const frame = canonicalFrame(64, 64)
    expect(reason(() => extractWithRuntime(runtime, frame.pixels, frame.width, frame.height))).toBe('internal')
  })

  it('preserves a genuine primary failure even when cleanup also fails', () => {
    const { runtime } = makeFakeRuntime({ detections: [], failDescriptorDelete: true })
    const frame = canonicalFrame(32, 32)
    expect(reason(() => extractWithRuntime(runtime, frame.pixels, frame.width, frame.height))).toBe('no-features')
  })
})

describe('T312 extraction entry point', () => {
  it('validates the frame before loading any runtime', async () => {
    await expect(extractSiftFingerprint({ width: 0, height: 0, pixels: new Uint8Array(0) })).rejects.toMatchObject({ code: 'dimensions' })
  })

  it('extracts with an injected runtime and serializes through the accepted codec', async () => {
    const { runtime } = makeFakeRuntime({ detections: [kp(5, 5, 3), kp(6, 6, 2)], descriptorRows: [[1], [2]], dctValues: Array.from({ length: 64 }, (_, i) => i) })
    const frame = canonicalFrame(64, 64)
    const fingerprint = await extractSiftFingerprint(frame, { runtime })
    const wire = serializeSiftFingerprint(fingerprint)
    expect(parseSiftFingerprint(wire).profileId).toBe(SIFT_FINGERPRINT_PROFILE_ID)
  })

  it('rejects an already-aborted signal', async () => {
    const { runtime } = makeFakeRuntime({ detections: [kp(5, 5, 3)], descriptorRows: [[1]] })
    const controller = new AbortController()
    controller.abort()
    await expect(extractSiftFingerprint(canonicalFrame(32, 32), { runtime, signal: controller.signal })).rejects.toMatchObject({ code: 'aborted' })
  })

  it('does not mutate and does not retain the caller pixel buffer', async () => {
    const { runtime } = makeFakeRuntime({ detections: [kp(5, 5, 3), kp(6, 6, 2)], descriptorRows: [[1], [2]] })
    const frame = canonicalFrame(32, 32)
    const snapshot = frame.pixels.slice()
    await extractSiftFingerprint(frame, { runtime })
    expect(Array.from(frame.pixels)).toEqual(Array.from(snapshot))
  })

  it('follows the captured dimensions and pixels when the caller frame mutates during a deferred load', async () => {
    const { runtime } = makeFakeRuntime({
      detections: [kp(5, 5, 3), kp(6, 6, 2)],
      descriptorRows: [[1], [2]],
      dctValues: Array.from({ length: 64 }, (_, i) => i),
    })
    const frame = canonicalFrame(64, 64)
    let resolveRuntime!: (runtime: CvRuntime) => void
    const deferred = new Promise<CvRuntime>((resolve) => {
      resolveRuntime = resolve
    })
    const pending = extractSiftFingerprint(frame, { runtime: deferred })
    // Mutate the caller frame after validation but while the load is suspended.
    const mutable = frame as { width: number; height: number; pixels: Uint8Array }
    mutable.width = 1
    mutable.height = 1
    mutable.pixels = new Uint8Array(4)
    resolveRuntime(runtime)
    const fingerprint = await pending
    // The captured 64x64 frame drove selection: coordinates x=5 would be out of
    // bounds for a 1-wide frame, so succeeding proves the snapshot drove extraction.
    expect(fingerprint.canonicalWidthPx).toBe(64)
    expect(fingerprint.canonicalHeightPx).toBe(64)
    expect(fingerprint.keypoints).toHaveLength(2)
  })

  it('categorises a rejected runtime load as aborted when the signal aborts during it', async () => {
    const controller = new AbortController()
    let rejectRuntime!: (error: unknown) => void
    const deferred = new Promise<CvRuntime>((_resolve, reject) => {
      rejectRuntime = reject
    })
    const frame = canonicalFrame(32, 32)
    const pending = extractSiftFingerprint(frame, { runtime: deferred, signal: controller.signal })
    controller.abort()
    rejectRuntime(new Error('load failed'))
    await expect(pending).rejects.toMatchObject({ code: 'aborted' })
  })

  it('refuses aborted when the signal aborts during a deferred runtime load that then resolves', async () => {
    const controller = new AbortController()
    let resolveRuntime!: (runtime: CvRuntime) => void
    const deferred = new Promise<CvRuntime>((resolve) => {
      resolveRuntime = resolve
    })
    const frame = canonicalFrame(32, 32)
    const pending = extractSiftFingerprint(frame, { runtime: deferred, signal: controller.signal })
    controller.abort()
    resolveRuntime(makeFakeRuntime({ detections: [kp(5, 5, 3)], descriptorRows: [[1]] }).runtime)
    await expect(pending).rejects.toMatchObject({ code: 'aborted' })
  })
})
