import { describe, expect, it } from 'vitest'
import { browserCamera, LocalScan, type CameraPort, type StillCapture } from '../src/scan/camera'
import { admitScan, considerCapture, ScanGrant, unavailableRecognition } from '../src/scan/grant'
import type { IncomingCatalog } from '../src/sync/incoming'
import { SessionStore } from '../src/session/session'

const ACCOUNT = '20212223-2425-2627-2829-2a2b2c2d2e2f'
const BUNDLE = '40414243-4445-4647-4849-4a4b4c4d4e4f'
const CAPSULE = '00010203-0405-0607-0809-0a0b0c0d0e0f'
const capture: StillCapture = { bytes: new Uint8Array([1, 2, 3]), width: 4, height: 5 }

function unlocked(): SessionStore {
  const session = new SessionStore()
  session.signIn({
    accountId: ACCOUNT,
    bundleId: BUNDLE,
    handle: null,
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  return session
}

function catalog(): IncomingCatalog {
  return {
    hasMore: false,
    nextCursor: null,
    items: [
      {
        capsuleId: CAPSULE,
        senderUserId: '10111213-1415-1617-1819-1a1b1c1d1e1f',
        recipientUserId: ACCOUNT,
        senderKeyBundleId: '30313233-3435-3637-3839-3a3b3c3d3e3f',
        recipientKeyBundleId: BUNDLE,
        envelopeCiphertext: new Uint8Array([1]),
        statementSha256: new Uint8Array(32),
        blobs: [],
      },
    ],
  }
}

describe('scan grant', () => {
  it('does not admit a picture when recognition is unavailable or ambiguous', async () => {
    const session = unlocked()
    const view = session.getSnapshot()
    await expect(considerCapture(view, unavailableRecognition(), capture, catalog())).rejects.toMatchObject({
      code: 'RECOGNITION_UNAVAILABLE',
    })
    await expect(considerCapture(view, { match: () => Promise.resolve({ kind: 'none' }) }, capture, catalog())).rejects.toMatchObject({
      code: 'NO_MATCH',
    })
    await expect(considerCapture(view, { match: () => Promise.resolve({ kind: 'many' }) }, capture, catalog())).rejects.toMatchObject({
      code: 'AMBIGUOUS',
    })
    expect(session.getSnapshot().phase).toBe('keys-unlocked')
  })

  it('lets the recipient choose among capsules the same capture matched', async () => {
    const OTHER = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
    const FOREIGN = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
    const two = catalog()
    two.items.push({ ...two.items[0]!, capsuleId: OTHER, senderUserId: '50515253-5455-5657-5859-5a5b5c5d5e5f' })
    const session = unlocked()
    const view = session.getSnapshot()
    const many = { match: () => Promise.resolve({ kind: 'many' as const, capsuleIds: [CAPSULE, OTHER, FOREIGN] }) }
    await expect(considerCapture(view, many, capture, two)).rejects.toMatchObject({ code: 'AMBIGUOUS', choices: [CAPSULE, OTHER] })
    await expect(considerCapture(view, many, capture, two, undefined, FOREIGN)).rejects.toMatchObject({ code: 'AMBIGUOUS' })
    const grant = await considerCapture(view, many, capture, two, undefined, OTHER)
    expect(grant.allows(view, OTHER)).toBe(true)
    expect(grant.allows(view, CAPSULE)).toBe(false)
    const single = { match: () => Promise.resolve({ kind: 'many' as const, capsuleIds: [CAPSULE] }) }
    await expect(considerCapture(view, single, capture, two, undefined, CAPSULE)).rejects.toMatchObject({ code: 'AMBIGUOUS' })
  })

  it('admits only the matched capsule for this unlocked account', async () => {
    const session = unlocked()
    const grant = await considerCapture(
      session.getSnapshot(),
      { match: () => Promise.resolve({ kind: 'one', capsuleId: CAPSULE }) },
      capture,
      catalog(),
    )
    expect(session.getSnapshot().phase).toBe('keys-unlocked')
    expect(grant.allows(session.getSnapshot(), CAPSULE)).toBe(true)
    expect(grant.allows(session.getSnapshot(), 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee')).toBe(false)
    admitScan(session, grant)
    expect(session.getSnapshot().phase).toBe('postcard-matched')
    expect(grant.allows(session.getSnapshot(), CAPSULE)).toBe(true)
    session.signOut()
    expect(grant.allows(session.getSnapshot(), CAPSULE)).toBe(false)
    expect(ScanGrant.issue(ACCOUNT, CAPSULE).allows(unlocked().getSnapshot(), 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee')).toBe(false)
  })

  it('holds a still on the device and releases it without a network port', async () => {
    let stopped = 0
    const camera: CameraPort = {
      async open() {
        return {
          preview: {} as HTMLVideoElement,
          capture: () => Promise.resolve(capture),
          stop: () => { stopped += 1 },
        }
      },
    }
    const scan = new LocalScan()
    await expect(scan.shoot(camera)).resolves.toBe(capture)
    expect(scan.held()?.bytes).toEqual(capture.bytes)
    scan.release()
    expect(stopped).toBe(1)
    expect(scan.held()).toBeNull()
    await expect(browserCamera({} as Document).open()).rejects.toMatchObject({ code: 'CAMERA_UNAVAILABLE' })
  })

  it('stops a camera that resolves after the scan was cancelled', async () => {
    let stopped = 0
    let release: (session: { preview: HTMLVideoElement; capture: () => Promise<StillCapture>; stop: () => void }) => void = () => {}
    const camera: CameraPort = {
      open: () => new Promise((resolve) => {
        release = resolve
      }),
    }
    const scan = new LocalScan()
    const controller = new AbortController()
    const pending = scan.shoot(camera, controller.signal)
    controller.abort()
    release({
      preview: {} as HTMLVideoElement,
      capture: () => Promise.reject(new Error('late')),
      stop: () => { stopped += 1 },
    })
    await expect(pending).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(stopped).toBe(1)
    expect(scan.held()).toBeNull()
  })
})
