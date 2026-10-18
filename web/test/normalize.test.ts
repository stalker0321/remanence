import { describe, expect, it } from 'vitest'
import limits from '../../protocol/fixtures/limits-v1.json'
import type { DecodedUpright, PhotoCodec } from '../src/create/normalize'
import {
  NORMALIZED_PHOTO_MAX_LONG_EDGE_PX,
  NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES,
  NORMALIZED_PHOTO_MEDIA_TYPE,
  NORMALIZED_PHOTO_QUALITY_LADDER,
  PhotoIntake,
  PhotoNormalizer,
  SOURCE_PHOTO_MAX_BYTES,
  SOURCE_PHOTO_MAX_EDGE_PX,
  planNormalization,
} from '../src/create/normalize'
import { PHOTO_COUNT_MAX, PHOTO_COUNT_MIN, PHOTO_MAX_PLAINTEXT_BYTES, PhotoDraft } from '../src/create/photos'
import { protocolLimitsMatchFixture } from '../src/protocol/limits'

function soiBytes(size: number, fill = 7): Uint8Array {
  const bytes = new Uint8Array(size).fill(fill)
  bytes[0] = 0xff
  bytes[1] = 0xd8
  return bytes
}

interface FakeScript {
  width: number
  height: number
  failDecode?: boolean
  failEncode?: boolean
  badBytes?: boolean
  /** Encoded size per quality; missing quality falls back to `size`. */
  sizes?: Partial<Record<number, number>>
  size?: number
}

class FakeCodec implements PhotoCodec {
  decoded = 0
  encoded: { width: number; height: number; quality: number }[] = []
  released = 0
  constructor(private readonly script: FakeScript) {}
  async decode(bytes: Uint8Array): Promise<DecodedUpright> {
    void bytes
    this.decoded += 1
    if (this.script.failDecode) throw new Error('nope')
    const { width, height } = this.script
    const self = this
    return {
      width,
      height,
      async encodeJpeg(w, h, q): Promise<Uint8Array> {
        self.encoded.push({ width: w, height: h, quality: q })
        if (self.script.failEncode) throw new Error('nope')
        const size = self.script.sizes?.[q] ?? self.script.size ?? 1024
        const bytes = soiBytes(size, q & 0xff)
        if (self.script.badBytes && bytes.length > 2) {
          bytes[0] = 0x00
          bytes[1] = 0x00
        }
        return bytes
      },
      release(): void {
        self.released += 1
      },
    }
  }
}

describe('photo normalization contract', () => {
  it('locks protocol numbers to limits-v1.json', () => {
    expect(NORMALIZED_PHOTO_MEDIA_TYPE).toBe(limits.normalized_photo_media_type)
    expect(NORMALIZED_PHOTO_MAX_LONG_EDGE_PX).toBe(limits.normalized_photo_max_long_edge_px)
    expect(NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES).toBe(limits.normalized_photo_max_plaintext_bytes)
    expect(PHOTO_COUNT_MIN).toBe(limits.photo_count_min)
    expect(PHOTO_COUNT_MAX).toBe(limits.photo_count_max)
    expect(PHOTO_MAX_PLAINTEXT_BYTES).toBe(limits.normalized_photo_max_plaintext_bytes)
    expect(protocolLimitsMatchFixture()).toBe(true)
    expect([...NORMALIZED_PHOTO_QUALITY_LADDER]).toEqual([90, 80, 70, 55, 40])
  })

  it('plans dimensions: downscale only, never upscale, exact fit', () => {
    expect(planNormalization(4000, 2000)).toEqual({ width: 2560, height: 1280 })
    expect(planNormalization(2000, 4000)).toEqual({ width: 1280, height: 2560 })
    expect(planNormalization(2560, 100)).toEqual({ width: 2560, height: 100 })
    expect(planNormalization(1200, 800)).toEqual({ width: 1200, height: 800 })
    // Odd scale rounds like Android (Math.round, minimum 1).
    expect(planNormalization(2559, 100)).toEqual({ width: 2559, height: 100 })
    expect(planNormalization(0, 10)).toBeNull()
    expect(planNormalization(-1, 10)).toBeNull()
    expect(planNormalization(Number.NaN, 10)).toBeNull()
    expect(planNormalization(10.5, 10)).toBeNull()
    expect(planNormalization(SOURCE_PHOTO_MAX_EDGE_PX + 1, 10)).toBeNull()
    expect(planNormalization(10, SOURCE_PHOTO_MAX_EDGE_PX + 1)).toBeNull()
  })

  it('rejects undecodable input with an explicit reason', async () => {
    const normalizer = new PhotoNormalizer(new FakeCodec({ width: 4, height: 4, failDecode: true }))
    expect(await normalizer.normalize(soiBytes(64))).toEqual({ ok: false, reason: 'decode' })
    expect(await normalizer.normalize(new Uint8Array(0))).toEqual({ ok: false, reason: 'decode' })
  })

  it('refuses oversized source input before decode', async () => {
    const codec = new FakeCodec({ width: 4, height: 4 })
    const normalizer = new PhotoNormalizer(codec)
    expect(await normalizer.normalize(new Uint8Array(SOURCE_PHOTO_MAX_BYTES + 1))).toEqual({
      ok: false,
      reason: 'oversize',
    })
    expect(codec.decoded).toBe(0)
  })

  it('steps the quality ladder until the budget fits', async () => {
    const codec = new FakeCodec({
      width: 1200,
      height: 800,
      sizes: { 90: NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES + 1, 80: NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES - 8 },
    })
    const normalizer = new PhotoNormalizer(codec)
    const result = await normalizer.normalize(soiBytes(64))
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error('unreachable')
    expect(result.photo.quality).toBe(80)
    expect(result.photo.bytes.byteLength).toBeLessThanOrEqual(NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES)
    expect(result.photo.width).toBe(1200)
    expect(result.photo.height).toBe(800)
    expect(codec.encoded.map((call) => call.quality)).toEqual([90, 80])
    expect(codec.released).toBe(1)
  })

  it('fails closed when no ladder step fits', async () => {
    const codec = new FakeCodec({ width: 100, height: 100, size: NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES + 1 })
    const normalizer = new PhotoNormalizer(codec)
    expect(await normalizer.normalize(soiBytes(64))).toEqual({ ok: false, reason: 'budget' })
    expect(codec.encoded.map((call) => call.quality)).toEqual([90, 80, 70, 55, 40])
  })

  it('rejects unusable encoder output and releases the pixels', async () => {
    const codec = new FakeCodec({ width: 100, height: 100, badBytes: true })
    const normalizer = new PhotoNormalizer(codec)
    expect(await normalizer.normalize(soiBytes(64))).toEqual({ ok: false, reason: 'decode' })
    expect(codec.released).toBe(1)
  })

  it('hashes normalized bytes as 64 lowercase hex, stable and content-bound', async () => {
    const first = new PhotoNormalizer(new FakeCodec({ width: 8, height: 8, size: 512 }))
    const again = new PhotoNormalizer(new FakeCodec({ width: 8, height: 8, size: 512 }))
    const other = new PhotoNormalizer(new FakeCodec({ width: 8, height: 8, size: 513 }))
    const a = await first.normalize(soiBytes(64))
    const b = await again.normalize(soiBytes(64))
    const c = await other.normalize(soiBytes(64))
    if (!a.ok || !b.ok || !c.ok) throw new Error('unreachable')
    expect(a.photo.contentHash).toMatch(/^[0-9a-f]{64}$/)
    expect(a.photo.contentHash).toBe(b.photo.contentHash)
    expect(a.photo.contentHash).not.toBe(c.photo.contentHash)
  })

  it('validates constructor budgets like Android companions', () => {
    const codec = new FakeCodec({ width: 4, height: 4 })
    expect(() => new PhotoNormalizer(codec, { maxLongEdge: 0 })).toThrow(RangeError)
    expect(() => new PhotoNormalizer(codec, { maxBytes: -1 })).toThrow(RangeError)
    expect(() => new PhotoNormalizer(codec, { ladder: [] })).toThrow(RangeError)
    expect(() => new PhotoNormalizer(codec, { ladder: [80, 90] })).toThrow(RangeError)
  })
})

function deferred<T = void>(): { promise: Promise<T>; resolve: (value: T | PromiseLike<T>) => void } {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((ok) => {
    resolve = ok
  })
  return { promise, resolve }
}

function immediateFile(size = 64): { size: number; arrayBuffer(): Promise<ArrayBuffer> } {
  return { size, arrayBuffer: async () => new ArrayBuffer(size) }
}

/**
 * Totally gated codec: `encodeJpeg` suspends until the test resolves it.
 * Polling `encodeStarted` stages a genuine mid-normalize drop — the fence
 * dies while the normalizer is suspended, not before work starts.
 */
function midFlightCodec(): {
  codec: PhotoCodec
  encodeGate: { promise: Promise<Uint8Array>; resolve: (value: Uint8Array | PromiseLike<Uint8Array>) => void }
  started: () => boolean
} {
  let encodeStarted = false
  const encodeGate = deferred<Uint8Array>()
  return {
    codec: {
      async decode(): Promise<DecodedUpright> {
        return {
          width: 8,
          height: 8,
          async encodeJpeg(): Promise<Uint8Array> {
            encodeStarted = true
            return encodeGate.promise
          },
          release(): void {},
        }
      },
    },
    encodeGate,
    started: () => encodeStarted,
  }
}

async function untilStarted(started: () => boolean): Promise<void> {
  while (!started()) await new Promise((resolve) => setTimeout(resolve, 0))
}

function intakePhoto(size = 256): { bytes: Uint8Array; width: number; height: number; contentHash: string } {
  return { bytes: soiBytes(size), width: 8, height: 8, contentHash: 'ab'.repeat(32) }
}

describe('photo intake fencing and order', () => {
  it('drops pending output when the owner changes mid-flight', async () => {
    const mid = midFlightCodec()
    const normalizer = new PhotoNormalizer(mid.codec)
    const draft = new PhotoDraft()
    const intake = new PhotoIntake(normalizer, draft)
    const fence = { owner: 'owner-a', generation: 1, confirmed: { id: 1 }, mounted: true }
    const pending = intake.addFiles([immediateFile()], () => fence)
    await untilStarted(mid.started)
    fence.owner = 'owner-b'
    mid.encodeGate.resolve(soiBytes(256))
    const outcome = await pending
    expect(outcome.completed).toBe(false)
    expect(outcome.added).toBe(0)
    expect(draft.photos()).toHaveLength(0)
  })

  it('drops pending output on unmount and on explicit cancel', async () => {
    for (const action of ['dispose', 'cancel'] as const) {
      const mid = midFlightCodec()
      const normalizer = new PhotoNormalizer(mid.codec)
      const draft = new PhotoDraft()
      const intake = new PhotoIntake(normalizer, draft)
      const fence = { owner: 'owner-a', generation: 1, confirmed: { id: 1 }, mounted: true }
      const pending = intake.addFiles([immediateFile()], () => fence)
      await untilStarted(mid.started)
      if (action === 'dispose') intake.dispose()
      else intake.cancel()
      mid.encodeGate.resolve(soiBytes(256))
      const outcome = await pending
      expect(outcome.completed).toBe(false)
      expect(draft.photos()).toHaveLength(0)
    }
  })

  it('drops pending output when the recipient confirmation is replaced', async () => {
    const mid = midFlightCodec()
    const normalizer = new PhotoNormalizer(mid.codec)
    const draft = new PhotoDraft()
    const intake = new PhotoIntake(normalizer, draft)
    let confirmed: unknown = { id: 1 }
    const pending = intake.addFiles([immediateFile()], () => ({
      owner: 'owner-a',
      generation: 1,
      confirmed,
      mounted: true,
    }))
    await untilStarted(mid.started)
    confirmed = null
    mid.encodeGate.resolve(soiBytes(256))
    const outcome = await pending
    expect(outcome.completed).toBe(false)
    expect(draft.photos()).toHaveLength(0)
  })

  it('serializes concurrent picks, preserving order and the 3..5 bound', async () => {
    const order: string[] = []
    const codec: PhotoCodec = {
      async decode(): Promise<DecodedUpright> {
        return {
          width: 8,
          height: 8,
          async encodeJpeg(): Promise<Uint8Array> {
            return soiBytes(256)
          },
          release(): void {},
        }
      },
    }
    const normalizer = new PhotoNormalizer(codec)
    const draft = new PhotoDraft()
    const intake = new PhotoIntake(normalizer, draft)
    const fence = () => ({ owner: 'owner-a', generation: 1, confirmed: { id: 1 }, mounted: true })
    const file = (name: string) => ({
      size: 64,
      arrayBuffer: async () => {
        order.push(name)
        return new ArrayBuffer(64)
      },
    })
    const first = intake.addFiles([file('a'), file('b'), file('c')], fence)
    const second = intake.addFiles([file('d'), file('e'), file('f')], fence)
    const [one, two] = await Promise.all([first, second])
    expect(one).toEqual({ added: 3, refused: false, completed: true })
    expect(two.added).toBe(2)
    expect(two.refused).toBe(true)
    expect(two.completed).toBe(true)
    expect(order).toEqual(['a', 'b', 'c', 'd', 'e', 'f'])
    expect(draft.photos()).toHaveLength(5)
    expect(draft.accepted()).toHaveLength(5)
  })

  it('counts refusals without stopping the pick', async () => {
    const calls = { decode: 0 }
    const codec: PhotoCodec = {
      async decode(): Promise<DecodedUpright> {
        calls.decode += 1
        if (calls.decode === 1) throw new Error('undecodable')
        return {
          width: 8,
          height: 8,
          async encodeJpeg(): Promise<Uint8Array> {
            return soiBytes(256)
          },
          release(): void {},
        }
      },
    }
    const normalizer = new PhotoNormalizer(codec)
    const draft = new PhotoDraft()
    const intake = new PhotoIntake(normalizer, draft)
    const fence = () => ({ owner: 'owner-a', generation: 1, confirmed: { id: 1 }, mounted: true })
    const outcome = await intake.addFiles(
      [
        { size: 64, arrayBuffer: async () => new ArrayBuffer(64) },
        { size: 64, arrayBuffer: async () => new ArrayBuffer(64) },
      ],
      fence,
    )
    expect(outcome).toEqual({ added: 1, refused: true, completed: true })
  })

  it('carries normalized dimensions and hash into the draft intake path', async () => {
    const photo = intakePhoto()
    const draft = new PhotoDraft()
    const intake = new PhotoIntake(
      new PhotoNormalizer({
        async decode(): Promise<DecodedUpright> {
          return {
            width: photo.width,
            height: photo.height,
            async encodeJpeg(): Promise<Uint8Array> {
              return photo.bytes
            },
            release(): void {},
          }
        },
      }),
      draft,
    )
    const outcome = await intake.addFiles([{ size: 64, arrayBuffer: async () => new ArrayBuffer(64) }], () => ({
      owner: 'owner-a',
      generation: 1,
      confirmed: { id: 1 },
      mounted: true,
    }))
    expect(outcome).toEqual({ added: 1, refused: false, completed: true })
    expect(draft.photos()[0]).toMatchObject({ width: 8, height: 8, contentHash: 'ab'.repeat(32) })
  })
})
