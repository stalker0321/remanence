import { describe, expect, it } from 'vitest'
import { FrontHold } from '../src/create/front'
import type { RecipientSnapshot } from '../src/create/recipient'
import type { CameraPort, CameraSession } from '../src/scan/camera'
import { ScanError } from '../src/scan/errors'

const snapshot: RecipientSnapshot = {
  userId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
  handle: 'ada',
  keyBundleId: '22222222-2222-4222-8222-222222222222',
  suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519',
  protocolVersion: 1,
  encryptionPublicKeyset: 'CIabc123',
  signingPublicKeyset: 'CJxyz789',
  directoryVersion: '9f1c0d2e7a6b4c5d9f1c0d2e7a6b4c5d',
  self: false,
}

function port(capture: CameraSession['capture'], stop = () => {}): CameraPort {
  return {
    async open(signal) {
      if (signal?.aborted) throw new ScanError('CANCELLED')
      return { preview: {} as HTMLVideoElement, capture, stop }
    },
  }
}

describe('front still', () => {
  it('keeps one still for the confirmed recipient and zeroes it on close', async () => {
    const bytes = new Uint8Array([4, 5, 6])
    let stopped = 0
    const hold = new FrontHold()
    await hold.open(port(async () => ({ bytes, width: 2, height: 3 }), () => { stopped += 1 }))
    const still = await hold.take(snapshot)
    expect(still.userId).toBe(snapshot.userId)
    expect(still.keyBundleId).toBe(snapshot.keyBundleId)
    expect(still.directoryVersion).toBe(snapshot.directoryVersion)
    hold.retain(snapshot)
    expect(hold.still()?.bytes).toBe(bytes)
    hold.retain({ ...snapshot, keyBundleId: '33333333-3333-4333-8333-333333333333' })
    expect(hold.still()).toBeNull()
    expect(Array.from(bytes)).toEqual([0, 0, 0])
    expect(stopped).toBe(1)
  })

  it('stops a camera that arrives after cancel and drops a still closed during capture', async () => {
    const late = new FrontHold()
    let stopped = 0
    const controller = new AbortController()
    const opening = late.open({
      async open() {
        controller.abort()
        return {
          preview: {} as HTMLVideoElement,
          capture: async () => ({ bytes: new Uint8Array([1]), width: 1, height: 1 }),
          stop() { stopped += 1 },
        }
      },
    }, controller.signal)
    await expect(opening).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(stopped).toBe(1)
    expect(late.still()).toBeNull()

    const bytes = new Uint8Array([7, 7])
    const hold = new FrontHold()
    await hold.open(port(async () => {
      hold.close()
      return { bytes, width: 1, height: 1 }
    }))
    await expect(hold.take(snapshot)).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(hold.still()).toBeNull()
    expect(Array.from(bytes)).toEqual([0, 0])
  })
})