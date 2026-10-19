/**
 * T326 unit gate: the in-memory Send attempt coordinator.
 *
 * Real freeze + accepted `encodeExpression`/`buildFrozenExpression` for the
 * candidateId rule; FAKE seal/publish (pure DI seams on `SendPorts`) for the
 * outcome matrix; committed Android GENEX04 golden bytes anchor the
 * `ber1-` + sha256/32 derivation rule.
 */
import { describe, expect, it, vi } from 'vitest'
import hpkeVector from '../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { layoutCreatorNote } from '../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { jpegDimensionFixture } from '../src/collage/jpeg'
import { encodeExpression } from '../src/collage/codec'
import * as frozenExpressionModule from '../src/collage/frozenExpression'
import { buildFrozenExpression } from '../src/collage/frozenExpression'
import type { TextMeasurer } from '../src/collage/prepare'
import { styleById } from '../src/collage/styles'
import { signPublishStatement } from '../src/crypto/publish'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import { freezeSelection, type CreatorFreeze } from '../src/create/creatorFreeze'
import type { DraftPhoto } from '../src/create/photos'
import type { RecipientSnapshot } from '../src/create/recipient'
import { sealCapsule, SealCapsuleError, type SealCapsuleInput, type SealedCapsule } from '../src/create/sealCapsule'
import { publishCapsule, PublishCapsuleError } from '../src/sync/publishCapsule'
import type { CropProvenance } from '../src/scan/frontStillProducer'
import type { FrontStill } from '../src/create/front'
import type { FrontFingerprintValue } from '../src/create/frontFingerprint'
import { bytesToHex } from '../src/protocol/bytes'
import { SessionStore } from '../src/session/session'
import { keysetFromJson, type JsonKeyset } from './keysetJson'
import { goldenBytes } from './sheets'
import {
  SendAttemptController,
  deriveCandidateId,
  nextCreateVisitEpoch,
  runSendAttempt,
  sendPreflight,
  type SendOutcome,
  type SendPortsFull,
  type SendVisit,
} from '../src/create/sendAttempt'

const SENDER_ID = '11111111-2222-4333-8444-555555555555'
const SENDER_BUNDLE = '22222222-3333-4444-8555-666666666666'
const SENDER_HANDLE = 'sergio'
const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
const DIRECTORY_VERSION = '0'.repeat(16)
const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
const NOTE = 'hello'
const EPOCH = 3n
const CAPSULE_ID = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'

const HPKE_PUBLIC = keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)
const SIGNING_PRIVATE = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PUBLIC = keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)

const RECIPIENT_SNAPSHOT: RecipientSnapshot = {
  userId: RECIPIENT_ID,
  handle: 'ada',
  keyBundleId: RECIPIENT_BUNDLE,
  suite: SUITE,
  protocolVersion: 1,
  encryptionPublicKeyset: b64(HPKE_PUBLIC),
  signingPublicKeyset: b64(SIGNING_PUBLIC),
  directoryVersion: DIRECTORY_VERSION,
  self: false,
}

const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource).then((digest) => new Uint8Array(digest))
}

async function realPhoto(id: string, width: number, height: number): Promise<DraftPhoto> {
  const bytes = jpegDimensionFixture(width, height)
  return { id, bytes, width, height, contentHash: bytesToHex(await sha256(bytes)) }
}

function buildFreeze(photos: readonly DraftPhoto[], note: string | null, sessionGeneration: number): CreatorFreeze {
  const source = photos.map((photo) => ({ contentId: photo.id, width: photo.width, height: photo.height, contentHash: photo.contentHash }))
  const byId = new Map(source.map((spec) => [spec.contentId, spec]))
  const style = styleById('press')!
  const view = previewCandidates({ id: 'creator', sender: SENDER_HANDLE, photos: source, note }, undefined, plannerFromTextMeasurer(measurer, 640, 640, style.note))[0]!
  const scene = creatorScene({
    view,
    styleId: 'press',
    senderHandle: SENDER_HANDLE,
    noteText: note,
    photos: new Map(source.map((spec) => [spec.contentId, { width: spec.width, height: spec.height }])),
  })
  const noteLayout = scene.note
    ? layoutCreatorNote(
        scene.note.text,
        { font: scene.note.family, weight: scene.note.weight, italic: scene.note.italic, size: scene.note.size, line: scene.note.line },
        scene.note.region,
        (line) =>
          measurer.measure({
            text: line,
            family: scene.note!.family,
            size: scene.note!.size,
            weight: scene.note!.weight,
            italic: scene.note!.italic,
            line: scene.note!.line,
            maxWidth: Number.POSITIVE_INFINITY,
            maxHeight: Number.POSITIVE_INFINITY,
            softWrap: false,
          }).width,
      )
    : null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId: 'press', styleVersion: style.version })
  const result = freezeSelection({
    branchId: view.branch,
    styleId: 'press',
    styleVersion: style.version,
    senderHandle: SENDER_HANDLE,
    slots: view.slots.map((slot, index) => {
      const spec = byId.get(slot.photoId)!
      return { contentId: slot.photoId, ordinal: index, contentHash: spec.contentHash, widthPx: spec.width, heightPx: spec.height, crop: { ...slot.crop } }
    }),
    note,
    noteRegion: scene.note ? { ...scene.note.region } : null,
    noteFits: true,
    musicSelected: false,
    ownerId: SENDER_ID,
    sessionGeneration,
    recipient: { userId: RECIPIENT_ID, keyBundleId: RECIPIENT_BUNDLE, directoryVersion: DIRECTORY_VERSION },
    renderedKey: 'rk-1',
    renderedPlan,
    createdAtMs: 1,
  })
  if (!result.ok) throw new Error('freeze failed')
  return result.frozen
}

const PROVENANCE: CropProvenance = {
  source: 'recognition' as CropProvenance['source'],
  corners: [{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 100, y: 100 }, { x: 0, y: 100 }],
  areaRatio: 0.5,
  rectangularity: 0.9,
  confidence: 1,
  workingWidth: 640,
  workingHeight: 480,
  warpedWidth: 320,
  warpedHeight: 320,
}

async function buildVisit() {
  const photos = [await realPhoto('p0', 1000, 1000), await realPhoto('p1', 1080, 1080), await realPhoto('p2', 1160, 1160)]
  const session = new SessionStore('en')
  session.signIn({
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    handle: SENDER_HANDLE,
    accessToken: 'sender-access',
    refreshToken: 'sender-refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  const caps: KeyCapabilities = {
    accountId: SENDER_ID,
    bundleId: SENDER_BUNDLE,
    openEnvelope: async () => { throw new Error('unused') },
    decryptArtifact: async () => { throw new Error('unused') },
    sign: (statement) => signPublishStatement(SIGNING_PRIVATE, statement),
    lock: () => {},
  }
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), caps)
  const freeze = buildFreeze(photos, NOTE, session.getSnapshot().generation)
  const still: FrontStill = { bytes: Uint8Array.from([1, 2, 3]), width: 10, height: 20, userId: RECIPIENT_ID, keyBundleId: RECIPIENT_BUNDLE, directoryVersion: DIRECTORY_VERSION }
  const fingerprint: FrontFingerprintValue = {
    wire: Uint8Array.from({ length: 32 }, (_value, index) => index),
    profileId: 'profile-1',
    provenance: PROVENANCE,
    recipient: { userId: RECIPIENT_ID, keyBundleId: RECIPIENT_BUNDLE, directoryVersion: DIRECTORY_VERSION },
  }
  const snapshot = session.getSnapshot()
  const visit: SendVisit = {
    epoch: EPOCH,
    capsuleId: CAPSULE_ID,
    account: snapshot.accountId,
    bundleId: snapshot.bundleId,
    generation: snapshot.generation,
    keysReady: true,
    recipient: RECIPIENT_SNAPSHOT,
    photos,
    noteText: NOTE,
    musicSelected: false,
    freeze,
    still,
    fingerprint,
    mounted: true,
  }
  const current: {
    recipient: RecipientSnapshot
    photos: DraftPhoto[]
    noteText: string
    musicSelected: boolean
    freeze: CreatorFreeze
    still: FrontStill
    fingerprint: FrontFingerprintValue
    mounted: boolean
  } = {
    recipient: RECIPIENT_SNAPSHOT,
    photos: photos.slice(),
    noteText: NOTE,
    musicSelected: false,
    freeze,
    still,
    fingerprint,
    mounted: true,
  }
  const ports: SendPortsFull = {
    session,
    keys,
    transport: { request: async () => { throw new Error('unused') }, uploadBytes: async () => { throw new Error('unused') } } as unknown as SendPortsFull['transport'],
    seal: async () => { throw new Error('seal seam unset') },
    publish: async () => { throw new Error('publish seam unset') },
    recipientCurrent: () => current.recipient,
    photosCurrent: () => current.photos,
    noteTextCurrent: () => current.noteText,
    musicSelectedCurrent: () => current.musicSelected,
    freezeCurrent: () => current.freeze,
    stillCurrent: () => current.still,
    fingerprintCurrent: () => current.fingerprint,
    mountedCurrent: () => current.mounted,
  }
  return { visit, ports, current, session, keys, photos, freeze, still, fingerprint }
}

const FAKE_SEALED = { capsuleId: CAPSULE_ID, senderUserId: SENDER_ID, senderKeyBundleId: SENDER_BUNDLE } as unknown as SealedCapsule

async function fakeSeal(input: SealCapsuleInput): Promise<SealedCapsule> {
  return { ...FAKE_SEALED, _input: input } as unknown as SealedCapsule
}

describe('nextCreateVisitEpoch', () => {
  it('is a strictly increasing nonnegative bigint counter', () => {
    const a = nextCreateVisitEpoch()
    const b = nextCreateVisitEpoch()
    expect(typeof a).toBe('bigint')
    expect(a >= 0n).toBe(true)
    expect(b > a).toBe(true)
  })
})

describe('candidateId derivation', () => {
  it('anchors the ber1-sha256/32 rule to the committed Android GENEX04 golden bytes', async () => {
    const golden = goldenBytes('genex04')
    const digest = await sha256(golden)
    expect(`ber1-${bytesToHex(digest).slice(0, 32)}`).toBe('ber1-09bc10f351b7e6548dc473a59e524c4f')
  })

  it('derives from the accepted encoder over buildFrozenExpression, not a duplicate serializer', async () => {
    const { visit } = await buildVisit()
    const derived = await deriveCandidateId(visit.freeze, visit.epoch)
    const built = await buildFrozenExpression(visit.freeze, visit.epoch)
    if (!built.ok) throw new Error('build failed')
    const encoded = encodeExpression(built.expression)
    const digest = await sha256(encoded)
    expect(derived).toBe(`ber1-${bytesToHex(digest).slice(0, 32)}`)
    expect(derived).toMatch(/^ber1-[0-9a-f]{32}$/)
  })
})

describe('sendPreflight', () => {
  it('admits a coherent ready visit', async () => {
    const { visit, ports } = await buildVisit()
    expect(sendPreflight(visit, ports)).toEqual({ ok: true })
  })

  it('refuses invalid captured freeze, music, note, photos, and unmount without consuming an attempt', async () => {
    const { visit, ports, current } = await buildVisit()
    // sendPreflight validates the CAPTURED visit; fences validate CURRENT
    // values. An invalid captured freeze (wrong grammar) is a local refusal.
    const badFreeze = { ...visit.freeze, grammarId: 'g4' } as unknown as CreatorFreeze
    expect(sendPreflight({ ...visit, freeze: badFreeze }, ports)).toEqual({ ok: false, reason: 'freeze' })
    expect(sendPreflight({ ...visit, musicSelected: true }, ports)).toEqual({ ok: false, reason: 'music' })
    expect(sendPreflight({ ...visit, noteText: '   ' }, ports)).toEqual({ ok: false, reason: 'note' })
    expect(sendPreflight({ ...visit, photos: visit.photos.slice(0, 2) }, ports)).toEqual({ ok: false, reason: 'photos' })
    expect(sendPreflight({ ...visit, mounted: false }, ports)).toEqual({ ok: false, reason: 'mounted' })
    current.mounted = false
    expect(sendPreflight(visit, ports)).toEqual({ ok: false, reason: 'mounted' })
  })
})

describe('runSendAttempt', () => {
  it('happy path: exactly one seal and one publish with the captured visit values', async () => {
    const { visit, ports } = await buildVisit()
    let sealedRef: SealedCapsule | null = null
    const seal = vi.fn(async (input: SealCapsuleInput) => {
      const sealed = await fakeSeal(input)
      sealedRef = sealed
      return sealed
    })
    const publish = vi.fn(async (_input: { capsule: SealedCapsule; idempotencyKey: string }) => ({ capsuleId: CAPSULE_ID, readyAt: '2026-10-17T00:00:10Z', locator: { code: 'ABCDE', hint: 'ada' } }))
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'sent', readyAt: '2026-10-17T00:00:10Z', locator: { code: 'ABCDE', hint: 'ada' } })
    expect(seal).toHaveBeenCalledTimes(1)
    expect(publish).toHaveBeenCalledTimes(1)
    const sealInput = seal.mock.calls[0]![0]
    expect(sealInput.capsuleId).toBe(CAPSULE_ID)
    expect(sealInput.epoch).toBe(EPOCH)
    expect(sealInput.placeLabel).toBeNull()
    expect(sealInput.frontFingerprint).toBe(visit.fingerprint.wire)
    const now = BigInt(Math.floor(Date.now() / 1000))
    expect(sealInput.createdAtEpochSeconds <= now).toBe(true)
    expect(sealInput.createdAtEpochSeconds >= now - 2n).toBe(true)
    expect(sealInput.candidateId).toBe(await deriveCandidateId(visit.freeze, visit.epoch))
    const publishInput = publish.mock.calls[0]![0]
    expect(publishInput.idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(publishInput.capsule).toBe(sealedRef)
    expect(sealedRef).not.toBeNull()
  })

  it('missing freeze: refused preflight with zero seal and zero publish', async () => {
    const { visit, ports, current } = await buildVisit()
    const seal = vi.fn(fakeSeal)
    const publish = vi.fn()
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    // The captured freeze itself is invalid (not a mid-flight invalidation:
    // the current freeze still references the same object, so the fence
    // stays green and preflight is the honest outcome).
    const badFreeze = { ...visit.freeze, grammarId: 'g4' } as unknown as CreatorFreeze
    visit.freeze = badFreeze
    current.freeze = badFreeze
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'refused', stage: 'preflight', code: 'freeze' })
    expect(seal).not.toHaveBeenCalled()
    expect(publish).not.toHaveBeenCalled()
  })

  it('lock mid-attempt: stale with zero publish', async () => {
    const { visit, ports, session } = await buildVisit()
    const seal = vi.fn(fakeSeal)
    const publish = vi.fn()
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    session.lock()
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'stale' })
    expect(seal).not.toHaveBeenCalled()
    expect(publish).not.toHaveBeenCalled()
  })

  it('recipient change after capture: stale before any network', async () => {
    const { visit, ports, current } = await buildVisit()
    const seal = vi.fn(fakeSeal)
    const publish = vi.fn()
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    current.recipient = { ...RECIPIENT_SNAPSHOT, directoryVersion: '9'.repeat(16) }
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'stale' })
    expect(seal).not.toHaveBeenCalled()
    expect(publish).not.toHaveBeenCalled()
  })

  it('unmount mid-attempt (mountedCurrent false): stale before any network', async () => {
    const { visit, ports, current } = await buildVisit()
    const seal = vi.fn(fakeSeal)
    const publish = vi.fn()
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    current.mounted = false
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'stale' })
    expect(seal).not.toHaveBeenCalled()
    expect(publish).not.toHaveBeenCalled()
  })

  it('seal failure: refused seal, zero publish', async () => {
    const { visit, ports } = await buildVisit()
    const publish = vi.fn()
    ports.seal = (async () => { throw new SealCapsuleError('recipient') }) as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'refused', stage: 'seal', code: 'recipient' })
    expect(publish).not.toHaveBeenCalled()
  })

  it('publish preflight refusal: refused preflight (nothing left the device)', async () => {
    const { visit, ports } = await buildVisit()
    ports.seal = fakeSeal as unknown as SendPortsFull['seal']
    ports.publish = (async () => { throw new PublishCapsuleError('preflight', 'phase') }) as unknown as SendPortsFull['publish']
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'refused', stage: 'preflight', code: 'phase' })
  })

  it('draft problem-response: refused draft', async () => {
    const { visit, ports } = await buildVisit()
    ports.seal = fakeSeal as unknown as SendPortsFull['seal']
    ports.publish = (async () => { throw new PublishCapsuleError('draft', 'protocol', 422, 'problem') }) as unknown as SendPortsFull['publish']
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'refused', stage: 'draft', code: 'problem' })
  })

  it('lost finalize: unknown, never success', async () => {
    const { visit, ports } = await buildVisit()
    ports.seal = fakeSeal as unknown as SendPortsFull['seal']
    ports.publish = (async () => { throw new PublishCapsuleError('finalize', 'transport') }) as unknown as SendPortsFull['publish']
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'unknown', stage: 'finalize', code: 'transport' })
  })

  it('invalidation during publication: aborted signal + fences produce stale, and no retry publish', async () => {
    const { visit, ports, current } = await buildVisit()
    const seal = vi.fn(fakeSeal)
    const publish = vi.fn(async () => {
      // Simulate the click-time abort landing while the upload/finalize
      // exchange is in flight AND the Create visit dies at the same moment.
      current.mounted = false
      return { capsuleId: CAPSULE_ID, readyAt: '2026-10-17T00:00:10Z', locator: null }
    })
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'stale' })
    expect(publish).toHaveBeenCalledTimes(1)
  })
})

describe('SendAttemptController', () => {
  it('admits exactly one attempt per visit; double-click and post-terminal begin are refused', async () => {
    const { visit, ports } = await buildVisit()
    ports.seal = fakeSeal as unknown as SendPortsFull['seal']
    ports.publish = (async () => ({ capsuleId: CAPSULE_ID, readyAt: '2026-10-17T00:00:10Z', locator: null })) as unknown as SendPortsFull['publish']
    const phases: string[] = []
    const controller = new SendAttemptController(() => ports, () => true, (phase) => phases.push(phase))
    const { mounted: _mounted, ...clickVisit } = visit
    expect(controller.begin(clickVisit)).toBe(true)
    expect(controller.begin(clickVisit)).toBe(false)
    const outcome = await controller.run()
    expect(outcome.kind).toBe('sent')
    expect(controller.phase).toBe('terminal')
    expect(controller.begin(clickVisit)).toBe(false)
    expect(phases).toEqual(['working', 'terminal'])
  })

  it('unready visit: begin with preflight-failing values still refuses honestly at run', async () => {
    const { visit, ports, current } = await buildVisit()
    const seal = vi.fn(fakeSeal)
    const publish = vi.fn()
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    const badFreeze = { ...visit.freeze, grammarId: 'g4' } as unknown as CreatorFreeze
    visit.freeze = badFreeze
    current.freeze = badFreeze
    const phases: string[] = []
    const controller = new SendAttemptController(() => ports, () => true, (phase) => phases.push(phase))
    const { mounted: _mounted, ...clickVisit } = visit
    expect(controller.begin(clickVisit)).toBe(true)
    const outcome: SendOutcome = await controller.run()
    expect(outcome).toEqual({ kind: 'refused', stage: 'preflight', code: 'freeze' })
    expect(seal).not.toHaveBeenCalled()
    expect(publish).not.toHaveBeenCalled()
  })

  it('invalidate while working aborts the in-flight attempt to stale with one publish call at most', async () => {
    const { visit, ports, current } = await buildVisit()
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    const seal = vi.fn(fakeSeal)
    const publish = vi.fn(async () => {
      await gate
      return { capsuleId: CAPSULE_ID, readyAt: '2026-10-17T00:00:10Z', locator: null }
    })
    ports.seal = seal as unknown as SendPortsFull['seal']
    ports.publish = publish as unknown as SendPortsFull['publish']
    const controller = new SendAttemptController(() => ports, () => true, () => {})
    const { mounted: _mounted, ...clickVisit } = visit
    expect(controller.begin(clickVisit)).toBe(true)
    const running = controller.run()
    // Invalidate only AFTER the publication request is in flight.
    for (let i = 0; i < 200 && publish.mock.calls.length === 0; i++) {
      await new Promise((resolve) => setTimeout(resolve, 5))
    }
    expect(publish).toHaveBeenCalledTimes(1)
    controller.invalidate()
    current.mounted = false
    release()
    const outcome = await running
    expect(outcome).toEqual({ kind: 'stale' })
    expect(publish).toHaveBeenCalledTimes(1)
    expect(seal).toHaveBeenCalledTimes(1)
  })
})

describe('R2 original-criterion corrections', () => {
  it('a note edit during the FIRST real draft request stops all subsequent real publisher uploads and finalize', async () => {
    const { visit, ports, current } = await buildVisit()
    let uploads = 0
    let finalizes = 0
    ports.seal = sealCapsule
    ports.publish = publishCapsule
    ports.transport = {
      request: async (input) => {
        if (input.path === '/v1/capsules') {
          const draft = input.body as { capsule_id: string; blobs: Array<{ blob_id: string }> }
          // The CURRENT note changes while the first draft request is pending
          // (the Create note path synchronously invalidates the attempt; here
          // the current-value fence alone must stop every following request).
          current.noteText = `${NOTE} edited during the draft request`
          return {
            status: 201,
            body: {
              capsule_id: draft.capsule_id,
              state: 'DRAFT',
              draft_expires_at: '2026-10-20T00:00:00Z',
              blobs: draft.blobs.map((blob) => ({ blob_id: blob.blob_id, state: 'DECLARED' })),
            },
          }
        }
        finalizes += 1
        return { status: 201, body: { capsule_id: CAPSULE_ID, state: 'READY', ready_at: '2026-10-19T11:00:00Z' } }
      },
      requestBytes: async () => { throw new Error('not used') },
      uploadBytes: async () => { uploads += 1; return { status: 204, body: null } },
    }
    const outcome = await runSendAttempt(visit, ports, new AbortController().signal)
    expect(outcome).toEqual({ kind: 'stale' })
    expect({ uploads, finalizes }).toEqual({ uploads: 0, finalizes: 0 })
  })

  it('deriveCandidateId wipes the builder-returned canonical expression bytes on success', async () => {
    const { visit } = await buildVisit()
    const built = await buildFrozenExpression(visit.freeze, visit.epoch)
    if (!built.ok) throw new Error('expression build failed')
    expect(built.bytes.some((byte) => byte !== 0)).toBe(true)
    const spy = vi.spyOn(frozenExpressionModule, 'buildFrozenExpression').mockResolvedValueOnce(built)
    try {
      const id = await deriveCandidateId(visit.freeze, visit.epoch)
      expect(id).toMatch(/^ber1-[0-9a-f]{32}$/)
      expect(built.bytes.every((byte) => byte === 0)).toBe(true)
    } finally {
      spy.mockRestore()
      built.bytes.fill(0)
    }
  })
})
