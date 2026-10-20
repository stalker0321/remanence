import { describe, expect, it } from 'vitest'
import {
  decodeJpegUpright,
  FrontStillError,
  produceFrontStillFingerprint,
  type OwnedRgbaFrame,
} from '../src/scan/frontStillProducer'
import { produceQueryFingerprint } from '../src/scan/queryFingerprint'
import type { StillCapture } from '../src/scan/camera'
import { parseSiftFingerprint } from '../src/scan/siftFingerprint'
import type { CvContourRuntime } from '../src/scan/opencvContour'
import type { CvMat } from '../src/scan/opencvPort'
import type { FrontStill } from '../src/create/front'

const CV_8UC1 = 0
const CV_8UC4 = 24
const CV_32F = 5
const DESCRIPTOR_BYTES = 128

/** Minimal SOF0 JPEG carrying the given stored dimensions (not decodable). */
function jpegWithDims(width: number, height: number, pad = 64): Uint8Array {
  const bytes = new Uint8Array(pad)
  // SOI.
  bytes[0] = 0xff
  bytes[1] = 0xd8
  // APP0 JFIF (marker + length 16 -> next segment at offset 20).
  bytes[2] = 0xff
  bytes[3] = 0xe0
  bytes[4] = 0x00
  bytes[5] = 0x10
  bytes.set([0x4a, 0x46, 0x49, 0x46, 0x00], 7)
  // SOF0 at offset 20: marker + length(11) + precision + height + width.
  bytes[20] = 0xff
  bytes[21] = 0xc0
  bytes[22] = 0x00
  bytes[23] = 0x0b
  bytes[24] = 0x08
  bytes[25] = (height >> 8) & 0xff
  bytes[26] = height & 0xff
  bytes[27] = (width >> 8) & 0xff
  bytes[28] = width & 0xff
  return bytes
}

function stillFor(jpeg: Uint8Array, overrides: Partial<FrontStill> = {}): FrontStill {
  return {
    bytes: jpeg,
    width: 320,
    height: 240,
    userId: 'user-1',
    keyBundleId: 'kb-1',
    directoryVersion: 'd-7',
    ...overrides,
  }
}

function reason(promise: Promise<unknown>): Promise<string> {
  return promise.then(
    () => 'ok',
    (error) => (error instanceof FrontStillError ? error.code : `other:${String(error)}`),
  )
}

interface FakeHandles {
  deleted: CvMat[]
}

function mat(rows: number, cols: number, type: number, data: Uint8Array, data32F: Float32Array, handles: FakeHandles): CvMat {
  const handle: CvMat = {
    rows,
    cols,
    data,
    data32F,
    type: () => type,
    delete: () => {
      handles.deleted.push(handle)
    },
  }
  return handle
}

interface FakeRuntimeOptions {
  quads?: Array<{ points: [{ x: number; y: number }, { x: number; y: number }, { x: number; y: number }, { x: number; y: number }]; quadArea: number; contourArea: number }>
  detectThrows?: boolean
  warpThrows?: boolean
  detections?: Array<{ x: number; y: number; size: number; angle: number; response: number; octave: number }>
  descriptorRows?: number[][]
  dctValues?: readonly number[]
}

/** Deterministic contour+SIFT runtime double (no native code). */
function makeFakeContourRuntime(options: FakeRuntimeOptions = {}, handles: FakeHandles = { deleted: [] }): CvContourRuntime {
  const detections = options.detections ?? []
  const descriptorRows = options.descriptorRows ?? []
  const dctValues = options.dctValues ?? new Array(64).fill(0)
  const runtime = {
    CV_8UC1,
    CV_32F,
    INTER_AREA: 3,
    matFromGray: (gray: Uint8Array) => mat(1, gray.length, CV_8UC1, gray.slice(), new Float32Array(0), handles),
    createEmptyMat: () => mat(0, 0, CV_8UC1, new Uint8Array(0), new Float32Array(0), handles),
    matFromRgba: (bytes: Uint8Array, width: number, height: number) =>
      mat(height, width, CV_8UC4, bytes.slice(), new Float32Array(0), handles),
    detectQuadCandidates: () => {
      if (options.detectThrows) throw new Error('detect failed')
      return (options.quads ?? []).map((q) => ({ ...q }))
    },
    warpRgbaQuad: (_rgba: CvMat, _src: unknown, _dst: unknown, dstWidth: number, dstHeight: number) => {
      if (options.warpThrows) throw new Error('warp failed')
      return { bytes: new Uint8Array(dstWidth * dstHeight * 4).fill(7), width: dstWidth, height: dstHeight }
    },
    createSift: () => ({
      detect: () => detections.map((d) => ({ pt: { x: d.x, y: d.y }, size: d.size, angle: d.angle, response: d.response, octave: d.octave })),
      compute: (_gray: CvMat, points: readonly unknown[]) => {
        const rows = points.map((_, row) => {
          const source = descriptorRows[row] ?? []
          const flat = new Float32Array(DESCRIPTOR_BYTES)
          for (let col = 0; col < DESCRIPTOR_BYTES; col++) flat[col] = source[col] ?? 0
          return flat
        })
        const flat = new Float32Array(rows.length * DESCRIPTOR_BYTES)
        rows.forEach((row, r) => flat.set(row, r * DESCRIPTOR_BYTES))
        return {
          descriptors: mat(rows.length, DESCRIPTOR_BYTES, CV_32F, new Uint8Array(0), flat, handles),
          keypoints: points.map((point) => point),
        }
      },
      delete: () => undefined,
    }),
    resizeArea: (_src: CvMat, size: number) => mat(size, size, CV_8UC1, new Uint8Array(size * size).fill(3), new Float32Array(size * size), handles),
    convertToFloat: (src: CvMat) => {
      const out = new Float32Array(src.data.length)
      for (let i = 0; i < out.length; i++) out[i] = src.data[i] ?? 0
      return mat(src.rows, src.cols, CV_32F, new Uint8Array(0), out, handles)
    },
    dct: () => mat(8, 8, CV_32F, new Uint8Array(0), Float32Array.from(dctValues), handles),
  } as unknown as CvContourRuntime
  return runtime
}

const BIG_QUAD = {
  points: [
    { x: 40, y: 30 },
    { x: 280, y: 30 },
    { x: 280, y: 210 },
    { x: 40, y: 210 },
  ] as [{ x: number; y: number }, { x: number; y: number }, { x: number; y: number }, { x: number; y: number }],
  quadArea: 240 * 180,
  contourArea: 240 * 180,
}

function workingFrame(width = 320, height = 240): OwnedRgbaFrame {
  return { rgba: new Uint8Array(width * height * 4).fill(9), width, height }
}

describe('T316 producer input capture and guards', () => {
  it('refuses empty owner fields before any await', async () => {
    const jpeg = jpegWithDims(320, 240)
    await expect(reason(produceFrontStillFingerprint(stillFor(jpeg, { userId: '' }), { fence: () => true, decode: async () => workingFrame() }))).resolves.toBe('dimensions')
    await expect(reason(produceFrontStillFingerprint(stillFor(jpeg, { keyBundleId: '' }), { fence: () => true, decode: async () => workingFrame() }))).resolves.toBe('dimensions')
  })

  it('refuses malformed and oversized sources before decode', async () => {
    const fence = () => true
    const decode = async () => workingFrame()
    await expect(reason(produceFrontStillFingerprint(stillFor(new Uint8Array([1, 2, 3])), { fence, decode }))).resolves.toBe('decode')
    await expect(reason(produceFrontStillFingerprint(stillFor(jpegWithDims(20000, 100)), { fence, decode }))).resolves.toBe('decode')
    await expect(reason(produceFrontStillFingerprint(stillFor(new Uint8Array(0)), { fence, decode }))).resolves.toBe('pixels')
  })

  it('refuses a stale fence and an aborted signal', async () => {
    const jpeg = jpegWithDims(320, 240)
    await expect(reason(produceFrontStillFingerprint(stillFor(jpeg), { fence: () => false, decode: async () => workingFrame() }))).resolves.toBe('stale')
    const controller = new AbortController()
    controller.abort()
    await expect(reason(produceFrontStillFingerprint(stillFor(jpeg), { fence: () => true, signal: controller.signal, decode: async () => workingFrame() }))).resolves.toBe('cancelled')
  })

  it('maps decode failures to decode and does not mutate caller bytes', async () => {
    const jpeg = jpegWithDims(320, 240)
    const snapshot = jpeg.slice()
    await expect(
      reason(produceFrontStillFingerprint(stillFor(jpeg), { fence: () => true, decode: async () => { throw new Error('boom') } })),
    ).resolves.toBe('decode')
    expect(Array.from(jpeg)).toEqual(Array.from(snapshot))
  })
})

describe('T316 producer localize-warp-extract', () => {
  const sift = {
    detections: [{ x: 800, y: 600, size: 20, angle: 0, response: 10, octave: 0 }],
    descriptorRows: [new Array(128).fill(3)],
    dctValues: Array.from({ length: 64 }, (_, i) => i),
  }

  it('extracts a genuine wire through a detected contour', async () => {
    const handles: FakeHandles = { deleted: [] }
    const runtime = makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }, handles)
    const frame = workingFrame()
    const fp = await produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), {
      fence: () => true,
      runtime,
      decode: async () => frame,
    })
    expect(fp.profileId).toBe('postcard-sift-rootsift-v1')
    expect(fp.recipient).toEqual({ userId: 'user-1', keyBundleId: 'kb-1', directoryVersion: 'd-7' })
    expect(fp.provenance.source).toBe('LEGACY_CONTOUR')
    expect(fp.provenance.workingWidth).toBe(320)
    expect(fp.provenance.workingHeight).toBe(240)
    expect(fp.provenance.warpedWidth).toBe(1600)
    // Quad long edge 240px, short edge 180px: 180*1600/240 = 1200 exactly.
    expect(fp.provenance.warpedHeight).toBe(1200)
    const parsed = parseSiftFingerprint(fp.wire)
    expect(parsed.keypoints).toHaveLength(1)
    expect(parsed.quantizedSiftDescriptors).toHaveLength(1)
    // Owned scratch is wiped; the caller frame and still bytes are untouched.
    expect(frame.rgba.every((b) => b === 0)).toBe(true)
    expect(handles.deleted.length).toBeGreaterThanOrEqual(3)
  })

  it('uses the labelled guide fallback when nothing is credible', async () => {
    const handles: FakeHandles = { deleted: [] }
    const runtime = makeFakeContourRuntime({ quads: [], ...sift }, handles)
    const fp = await produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), {
      fence: () => true,
      runtime,
      decode: async () => workingFrame(),
    })
    expect(fp.provenance.source).toBe('GUIDE_FALLBACK')
    expect(fp.provenance.warpedWidth).toBe(1600)
    expect(parseSiftFingerprint(fp.wire).keypoints.length).toBeGreaterThan(0)
  })

  it('refuses features when SIFT finds nothing usable', async () => {
    const runtime = makeFakeContourRuntime({ quads: [BIG_QUAD], detections: [], descriptorRows: [], dctValues: new Array(64).fill(0) })
    await expect(
      reason(produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), { fence: () => true, runtime, decode: async () => workingFrame() })),
    ).resolves.toBe('features')
  })

  it('maps native failures to crop and warp refusals', async () => {
    const cropFail = makeFakeContourRuntime({ detectThrows: true, ...sift })
    await expect(
      reason(produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), { fence: () => true, runtime: cropFail, decode: async () => workingFrame() })),
    ).resolves.toBe('crop')
    const warpFail = makeFakeContourRuntime({ quads: [BIG_QUAD], warpThrows: true, ...sift })
    await expect(
      reason(produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), { fence: () => true, runtime: warpFail, decode: async () => workingFrame() })),
    ).resolves.toBe('warp')
  })

  it('follows captured dimensions and pixels when the caller mutates during a deferred load', async () => {
    const handles: FakeHandles = { deleted: [] }
    const runtime = makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }, handles)
    let resolveRuntime!: (runtime: CvContourRuntime) => void
    const deferred = new Promise<CvContourRuntime>((resolve) => {
      resolveRuntime = resolve
    })
    const jpeg = jpegWithDims(320, 240)
    const still = stillFor(jpeg)
    const pending = produceFrontStillFingerprint(still, { fence: () => true, runtime: deferred, decode: async () => workingFrame() })
    // Mutate the caller frame after validation while the load is suspended.
    still.width = 1
    still.height = 1
    still.bytes = new Uint8Array([9, 9, 9])
    still.userId = 'intruder'
    resolveRuntime(runtime)
    const fp = await pending
    expect(fp.provenance.workingWidth).toBe(320)
    expect(fp.provenance.workingHeight).toBe(240)
    expect(fp.recipient.userId).toBe('user-1')
    expect(fp.provenance.warpedWidth).toBe(1600)
  })

  it('goes stale when the fence drops during a deferred decode', async () => {
    let ok = true
    let resolveFrame!: (frame: OwnedRgbaFrame) => void
    const deferred = new Promise<OwnedRgbaFrame>((resolve) => {
      resolveFrame = resolve
    })
    const runtime = makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift })
    const pending = produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), {
      fence: () => ok,
      runtime,
      decode: () => deferred,
    })
    ok = false
    resolveFrame(workingFrame())
    await expect(reason(pending)).resolves.toBe('stale')
  })

  it('categorises a rejected runtime load as unavailable', async () => {
    const runtime = makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift })
    let rejectRuntime!: (error: unknown) => void
    const deferred = new Promise<CvContourRuntime>((_resolve, reject) => {
      rejectRuntime = reject
    })
    const pending = produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), {
      fence: () => true,
      runtime: deferred,
      decode: async () => workingFrame(),
    })
    rejectRuntime(new Error('load failed'))
    await expect(reason(pending)).resolves.toBe('unavailable')
    void runtime
  })

  it('wipes the freshly serialized wire when the post-serialize fence refuses', async () => {
    const base = { quads: [BIG_QUAD], ...sift }
    // A success run first learns the exact wire length for the wipe evidence.
    const okFp = await produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), {
      fence: () => true,
      runtime: makeFakeContourRuntime(base),
      decode: async () => workingFrame(),
    })
    const wireLength = okFp.wire.length
    expect(wireLength).toBeGreaterThan(0)
    okFp.wire.fill(0)

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
      // Nine fence evaluations precede transfer on this path (entry, decode,
      // load, rgba, detect, plan, warp, extract, post-serialize); only the
      // post-serialize recheck must refuse, after the wire already exists.
      let calls = 0
      await expect(
        reason(
          produceFrontStillFingerprint(stillFor(jpegWithDims(320, 240)), {
            fence: () => ++calls < 9,
            runtime: makeFakeContourRuntime(base),
            decode: async () => workingFrame(),
          }),
        ),
      ).resolves.toBe('stale')
      expect(calls).toBe(9)
      const wireWipe = records.find((entry) => entry.before.length === wireLength && entry.before.some((byte) => byte !== 0))
      expect(wireWipe).toBeDefined()
      expect(wireWipe!.after.every((byte) => byte === 0)).toBe(true)
    } finally {
      ;(Uint8Array.prototype as { fill: unknown }).fill = originalFill
    }
  })

  it('wipes the exposed ImageData buffer after copying, keeping owned output correct', async () => {
    const exposed = new Uint8ClampedArray([10, 20, 30, 255, 40, 50, 60, 255, 70, 80, 90, 255, 1, 2, 3, 255])
    let closed = false
    const fakeBitmap = {
      width: 2,
      height: 2,
      close: () => {
        closed = true
      },
    }
    const fakeContext = {
      drawImage: () => undefined,
      getImageData: () => ({ data: exposed, width: 2, height: 2 }),
    }
    const fakeCanvas = {
      width: 0,
      height: 0,
      getContext: () => fakeContext,
    }
    const fakeDoc = { createElement: () => fakeCanvas } as unknown as Document
    const realCreateImageBitmap = (globalThis as { createImageBitmap?: unknown }).createImageBitmap
    ;(globalThis as { createImageBitmap?: unknown }).createImageBitmap = async () => fakeBitmap
    try {
      const frame = await decodeJpegUpright(fakeDoc, jpegWithDims(2, 2, 96))
      expect(frame.width).toBe(2)
      expect(frame.height).toBe(2)
      expect(Array.from(frame.rgba)).toEqual([10, 20, 30, 255, 40, 50, 60, 255, 70, 80, 90, 255, 1, 2, 3, 255])
      expect(exposed.every((byte) => byte === 0)).toBe(true)
      expect(closed).toBe(true)
      frame.rgba.fill(0)
    } finally {
      ;(globalThis as { createImageBitmap?: unknown }).createImageBitmap = realCreateImageBitmap
    }
    expect(fakeCanvas.width).toBe(0)
  })
})

describe('T343 identity-free receiving query producer', () => {
  const sift = {
    detections: [{ x: 800, y: 600, size: 20, angle: 0, response: 10, octave: 0 }],
    descriptorRows: [new Array(128).fill(3)],
    dctValues: Array.from({ length: 64 }, (_, i) => i),
  }

  function plainStill(jpeg: Uint8Array): StillCapture {
    return { bytes: jpeg, width: 320, height: 240 }
  }

  it('produces byte-identical wire/profile/provenance for sender and query', async () => {
    const jpeg = jpegWithDims(320, 240)
    const sender = await produceFrontStillFingerprint(stillFor(jpeg), {
      fence: () => true,
      runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
      decode: async () => workingFrame(),
    })
    const query = await produceQueryFingerprint(plainStill(jpeg), {
      fence: () => true,
      runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
      decode: async () => workingFrame(),
    })
    expect(Array.from(query.wire)).toEqual(Array.from(sender.wire))
    expect(query.profileId).toBe(sender.profileId)
    expect(query.provenance).toEqual(sender.provenance)
    expect('recipient' in query).toBe(false)
    expect('userId' in query || 'keyBundleId' in query || 'directoryVersion' in query).toBe(false)
    expect(query.wire.length).toBeGreaterThan(0)
  })

  it('snapshots the caller still bytes before the deferred decode', async () => {
    const jpeg = jpegWithDims(320, 240)
    const control = await produceQueryFingerprint(plainStill(jpeg), {
      fence: () => true,
      runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
      decode: async () => workingFrame(),
    })
    const source = plainStill(jpeg)
    let resolveFrame!: (frame: OwnedRgbaFrame) => void
    const deferred = new Promise<OwnedRgbaFrame>((resolve) => {
      resolveFrame = resolve
    })
    const pending = produceQueryFingerprint(source, {
      fence: () => true,
      runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
      decode: () => deferred,
    })
    source.bytes = new Uint8Array([9, 9, 9])
    source.width = 1
    source.height = 1
    resolveFrame(workingFrame())
    const query = await pending
    expect(Array.from(query.wire)).toEqual(Array.from(control.wire))
    expect(query.provenance.workingWidth).toBe(320)
    expect(query.provenance.workingHeight).toBe(240)
  })

  it('refuses stale/cancelled and leaves caller bytes unchanged', async () => {
    const jpeg = jpegWithDims(320, 240)
    const snapshot = jpeg.slice()
    const controller = new AbortController()
    controller.abort()
    await expect(
      produceQueryFingerprint(plainStill(jpeg), {
        fence: () => true,
        signal: controller.signal,
        runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
        decode: async () => workingFrame(),
      }),
    ).rejects.toMatchObject({ code: 'cancelled' })
    await expect(
      produceQueryFingerprint(plainStill(jpeg), {
        fence: () => false,
        runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
        decode: async () => workingFrame(),
      }),
    ).rejects.toMatchObject({ code: 'stale' })
    expect(Array.from(jpeg)).toEqual(Array.from(snapshot))
  })

  it('preserves the native empty-feature hard refusal for the query path', async () => {
    await expect(
      produceQueryFingerprint(plainStill(jpegWithDims(320, 240)), {
        fence: () => true,
        runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], detections: [], descriptorRows: [], dctValues: new Array(64).fill(0) }),
        decode: async () => workingFrame(),
      }),
    ).rejects.toMatchObject({ code: 'features' })
  })

  it('wipes the transferred wire when the sender wrapper late fence fails', async () => {
    const jpeg = jpegWithDims(320, 240)
    let calls = 0
    const control = await produceFrontStillFingerprint(stillFor(jpeg), {
      fence: () => {
        calls += 1
        return true
      },
      runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
      decode: async () => workingFrame(),
    })
    const wireLength = control.wire.length
    control.wire.fill(0)

    const fillRecords: Array<{ before: Uint8Array; after: Uint8Array }> = []
    const originalFill = Uint8Array.prototype.fill
    const installFill = originalFill as unknown as (value: number, start?: number, end?: number) => Uint8Array
    ;(Uint8Array.prototype as { fill: unknown }).fill = function (
      this: Uint8Array,
      value: number,
      start?: number,
      end?: number,
    ): Uint8Array {
      fillRecords.push({ before: this.slice(), after: this })
      return installFill.call(this, value, start, end)
    }
    try {
      let call = 0
      await expect(
        produceFrontStillFingerprint(stillFor(jpeg), {
          // The final (wrapper) fence evaluation after the core resolves fails.
          fence: () => {
            call += 1
            return call < calls
          },
          runtime: makeFakeContourRuntime({ quads: [BIG_QUAD], ...sift }),
          decode: async () => workingFrame(),
        }),
      ).rejects.toMatchObject({ code: 'stale' })
    } finally {
      ;(Uint8Array.prototype as { fill: unknown }).fill = originalFill
    }
    const wiped = fillRecords.some(
      (record) =>
        record.before.length === wireLength &&
        record.before.some((byte) => byte !== 0) &&
        record.after.every((byte) => byte === 0),
    )
    expect(wiped).toBe(true)
  })
})
