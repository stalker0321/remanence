import { describe, expect, it } from 'vitest'
import { FrontFingerprintHold } from '../src/create/frontFingerprint'
import { FrontHold, type FrontStill } from '../src/create/front'
import { RecipientDraft, type RecipientSnapshot } from '../src/create/recipient'
import { SessionStore } from '../src/session/session'
import { FrontStillError } from '../src/scan/frontStillProducer'
import type { CvContourRuntime } from '../src/scan/opencvContour'
import type { CvMat } from '../src/scan/opencvPort'
import type { CameraPort } from '../src/scan/camera'

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const SIGN_IN = {
  accountId: ACCOUNT,
  bundleId: BUNDLE,
  handle: 'sergio',
  accessToken: 'access-token-value',
  refreshToken: 'refresh-token-value',
  accessExpiresAt: '2026-10-17T00:00:00Z',
  refreshExpiresAt: '2026-11-17T00:00:00Z',
}

function jpegWithDims(width: number, height: number, pad = 64): Uint8Array {
  const bytes = new Uint8Array(pad)
  bytes[0] = 0xff
  bytes[1] = 0xd8
  bytes[2] = 0xff
  bytes[3] = 0xe0
  bytes[4] = 0x00
  bytes[5] = 0x10
  bytes.set([0x4a, 0x46, 0x49, 0x46, 0x00], 7)
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

function snapshot(): RecipientSnapshot {
  return {
    userId: ACCOUNT,
    handle: 'sergio',
    keyBundleId: BUNDLE,
    suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519',
    protocolVersion: 1,
    encryptionPublicKeyset: 'x'.repeat(10),
    signingPublicKeyset: 'y'.repeat(10),
    directoryVersion: '0'.repeat(16),
    self: false,
  }
}

function setupSession(): { store: SessionStore; recipient: RecipientDraft } {
  const store = new SessionStore('en')
  store.signIn(SIGN_IN)
  store.unlock()
  const recipient = new RecipientDraft()
  recipient.attach(store)
  const ticket = recipient.nextTicket(ACCOUNT)
  recipient.offer(ticket, ACCOUNT, snapshot())
  recipient.confirm()
  return { store, recipient }
}

function fakeCamera(jpeg: Uint8Array): CameraPort {
  return {
    open: async () => ({
      preview: {} as HTMLVideoElement,
      capture: async () => ({ bytes: jpeg.slice(), width: 320, height: 240 }),
      stop: () => undefined,
    }),
  }
}

async function takeStill(hold: FrontHold, snap: RecipientSnapshot): Promise<FrontStill> {
  await hold.open(fakeCamera(jpegWithDims(320, 240)))
  return hold.take(snap)
}

function mat(rows: number, cols: number, type: number, data: Uint8Array, data32F: Float32Array, deleted: CvMat[]): CvMat {
  const handle: CvMat = {
    rows,
    cols,
    data,
    data32F,
    type: () => type,
    delete: () => {
      deleted.push(handle)
    },
  }
  return handle
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

const SIFT_OK = {
  detections: [{ x: 800, y: 600, size: 20, angle: 0, response: 10, octave: 0 }],
  descriptorRows: [new Array(128).fill(3)],
  dctValues: Array.from({ length: 64 }, (_, i) => i),
}

function makeRuntime(deleted: CvMat[] = []): CvContourRuntime {
  const runtime = {
    CV_8UC1: 0,
    CV_32F: 5,
    INTER_AREA: 3,
    matFromGray: (gray: Uint8Array) => mat(1, gray.length, 0, gray.slice(), new Float32Array(0), deleted),
    createEmptyMat: () => mat(0, 0, 0, new Uint8Array(0), new Float32Array(0), deleted),
    matFromRgba: (bytes: Uint8Array, width: number, height: number) =>
      mat(height, width, 24, bytes.slice(), new Float32Array(0), deleted),
    detectQuadCandidates: () => [{ ...BIG_QUAD }],
    warpRgbaQuad: (_rgba: CvMat, _src: unknown, _dst: unknown, dstWidth: number, dstHeight: number) => ({
      bytes: new Uint8Array(dstWidth * dstHeight * 4).fill(7),
      width: dstWidth,
      height: dstHeight,
    }),
    createSift: () => ({
      detect: () =>
        SIFT_OK.detections.map((d) => ({ pt: { x: d.x, y: d.y }, size: d.size, angle: d.angle, response: d.response, octave: d.octave })),
      compute: (_gray: CvMat, points: readonly unknown[]) => {
        const flat = new Float32Array(points.length * 128)
        for (let row = 0; row < points.length; row++) flat.set(SIFT_OK.descriptorRows[0]!, row * 128)
        return {
          descriptors: mat(points.length, 128, 5, new Uint8Array(0), flat, deleted),
          keypoints: points.map((point) => point),
        }
      },
      delete: () => undefined,
    }),
    resizeArea: (_src: CvMat, size: number) => mat(size, size, 0, new Uint8Array(size * size).fill(3), new Float32Array(size * size), deleted),
    convertToFloat: (src: CvMat) => {
      const out = new Float32Array(src.data.length)
      for (let i = 0; i < out.length; i++) out[i] = src.data[i] ?? 0
      return mat(src.rows, src.cols, 5, new Uint8Array(0), out, deleted)
    },
    dct: () => mat(8, 8, 5, new Uint8Array(0), Float32Array.from(SIFT_OK.dctValues), deleted),
  } as unknown as CvContourRuntime
  return runtime
}

const frameOf = (width = 320, height = 240) => ({ rgba: new Uint8Array(width * height * 4).fill(9), width, height })

function reason(promise: Promise<unknown>): Promise<string> {
  return promise.then(
    () => 'ok',
    (error) => (error instanceof FrontStillError ? error.code : `other:${String(error)}`),
  )
}

describe('T320 front fingerprint holder', () => {
  it('stores a produced wire for the current visit and returns it', async () => {
    const { store, recipient } = setupSession()
    const hold = new FrontHold()
    const still = await takeStill(hold, recipient.confirmed()!)
    const fpHold = new FrontFingerprintHold()
    const fp = await fpHold.run(still, {
      store,
      recipient,
      hold,
      mounted: () => true,
      runtime: makeRuntime(),
      decode: async () => frameOf(),
    })
    expect(fp.profileId).toBe('postcard-sift-rootsift-v1')
    expect(fp.recipient).toEqual({ userId: ACCOUNT, keyBundleId: BUNDLE, directoryVersion: '0'.repeat(16) })
    expect(fpHold.current()).toBe(fp)
    expect(fp.wire.length).toBeGreaterThan(0)
    hold.close()
  })

  it('refuses when the session generation changes and wipes the held wire', async () => {
    const { store, recipient } = setupSession()
    const hold = new FrontHold()
    const still = await takeStill(hold, recipient.confirmed()!)
    const fpHold = new FrontFingerprintHold()
    const fp = await fpHold.run(still, {
      store,
      recipient,
      hold,
      mounted: () => true,
      runtime: makeRuntime(),
      decode: async () => frameOf(),
    })
    const bytes = fp.wire
    store.lock()
    expect(fpHold.current()).toBeNull()
    expect(Array.from(bytes).every((byte) => byte === 0)).toBe(true)
    hold.close()
  })

  it('refuses when the recipient is restarted', async () => {
    const { store, recipient } = setupSession()
    const hold = new FrontHold()
    const still = await takeStill(hold, recipient.confirmed()!)
    const fpHold = new FrontFingerprintHold()
    await fpHold.run(still, { store, recipient, hold, mounted: () => true, runtime: makeRuntime(), decode: async () => frameOf() })
    expect(fpHold.current()).not.toBeNull()
    recipient.restart()
    expect(fpHold.current()).toBeNull()
    hold.close()
  })

  it('refuses a run for a still the hold no longer has', async () => {
    const { store, recipient } = setupSession()
    const hold = new FrontHold()
    const stillA = await takeStill(hold, recipient.confirmed()!)
    const fpHold = new FrontFingerprintHold()
    // Retake replaces the held still; the old still is stale immediately.
    await takeStill(hold, recipient.confirmed()!)
    await expect(
      reason(fpHold.run(stillA, { store, recipient, hold, mounted: () => true, runtime: makeRuntime(), decode: async () => frameOf() })),
    ).resolves.toBe('stale')
    expect(fpHold.current()).toBeNull()
    hold.close()
  })

  it('aborts in-flight work on clear and stores nothing', async () => {
    const { store, recipient } = setupSession()
    const hold = new FrontHold()
    const still = await takeStill(hold, recipient.confirmed()!)
    const fpHold = new FrontFingerprintHold()
    let resolveDecode!: (frame: { rgba: Uint8Array; width: number; height: number }) => void
    const deferred = new Promise<{ rgba: Uint8Array; width: number; height: number }>((resolve) => {
      resolveDecode = resolve
    })
    const pending = fpHold.run(still, {
      store,
      recipient,
      hold,
      mounted: () => true,
      runtime: makeRuntime(),
      decode: () => deferred,
    })
    fpHold.clear()
    resolveDecode(frameOf())
    await expect(reason(pending)).resolves.toBe('stale')
    expect(fpHold.current()).toBeNull()
    hold.close()
  })

  it('a late completion never erases a newer ready result', async () => {
    const { store, recipient } = setupSession()
    const hold = new FrontHold()
    const still = await takeStill(hold, recipient.confirmed()!)
    const fpHold = new FrontFingerprintHold()
    let resolveFirst!: (frame: { rgba: Uint8Array; width: number; height: number }) => void
    const deferred = new Promise<{ rgba: Uint8Array; width: number; height: number }>((resolve) => {
      resolveFirst = resolve
    })
    const first = fpHold.run(still, {
      store,
      recipient,
      hold,
      mounted: () => true,
      runtime: makeRuntime(),
      decode: () => deferred,
    })
    const second = await fpHold.run(still, {
      store,
      recipient,
      hold,
      mounted: () => true,
      runtime: makeRuntime(),
      decode: async () => frameOf(),
    })
    resolveFirst(frameOf())
    await expect(reason(first)).resolves.toBe('stale')
    expect(fpHold.current()).toBe(second)
    expect(second.wire.length).toBeGreaterThan(0)
    hold.close()
  })

  it('clear wipes a stored wire and drops it', async () => {
    const { store, recipient } = setupSession()
    const hold = new FrontHold()
    const still = await takeStill(hold, recipient.confirmed()!)
    const fpHold = new FrontFingerprintHold()
    const fp = await fpHold.run(still, {
      store,
      recipient,
      hold,
      mounted: () => true,
      runtime: makeRuntime(),
      decode: async () => frameOf(),
    })
    const bytes = fp.wire
    fpHold.clear()
    expect(fpHold.current()).toBeNull()
    expect(bytes.every((byte) => byte === 0)).toBe(true)
    hold.close()
  })
})
