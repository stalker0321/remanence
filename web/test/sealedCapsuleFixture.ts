/**
 * T304 TEST-ONLY fixture: a REAL `sealCapsule` output for publisher tests and the
 * browser gate. Portable (pure `crypto.subtle` + committed TEST golden keysets,
 * header-JPEG `jpegDimensionFixture` photos — NOT decoded photographs). It exposes
 * the sealed capsule plus the keys-ready sender session; it does not expose any
 * private key to production code.
 */
import hpkeVector from '../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { layoutCreatorNote } from '../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { jpegDimensionFixture } from '../src/collage/jpeg'
import type { TextMeasurer } from '../src/collage/prepare'
import { styleById } from '../src/collage/styles'
import { signPublishStatement } from '../src/crypto/publish'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import { freezeSelection, type CreatorFreeze } from '../src/create/creatorFreeze'
import type { DraftPhoto } from '../src/create/photos'
import type { RecipientSnapshot } from '../src/create/recipient'
import { sealCapsule, type SealedCapsule } from '../src/create/sealCapsule'
import { bytesToHex } from '../src/protocol/bytes'
import { SessionStore } from '../src/session/session'
import { keysetFromJson, type JsonKeyset } from './keysetJson'

export const SENDER_ID = '11111111-2222-4333-8444-555555555555'
export const SENDER_BUNDLE = '22222222-3333-4444-8555-666666666666'
export const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
export const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
export const CAPSULE_UUID = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'
export const SENDER_HANDLE = 'sergio'
export const DIRECTORY_VERSION = '0'.repeat(16)
export const CANDIDATE = 'cand-1'
export const DRAFT_KEY = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
export const PHOTO_BLOB_0 = '00000000-0000-0000-0000-0000000000e5'

const CREATED_AT = 1_755_000_000n
const EPOCH = 17n
const FP = Uint8Array.from({ length: 32 }, (_value, index) => index + 1)
const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'

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

export interface SealedCapsuleFixture {
  capsule: SealedCapsule
  session: SessionStore
  keys: MemoryKeys
}

/** Build a REAL `sealCapsule` output plus the keys-ready sender visit. */
export async function buildSealedCapsuleFixture(): Promise<SealedCapsuleFixture> {
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
    openEnvelope: async () => {
      throw new Error('unused')
    },
    decryptArtifact: async () => {
      throw new Error('unused')
    },
    sign: (statement) => signPublishStatement(SIGNING_PRIVATE, statement),
    lock: () => {},
  }
  const keys = new MemoryKeys()
  keys.hold(session.getSnapshot(), caps)
  const freeze = buildFreeze(photos, 'hello', session.getSnapshot().generation)
  const ordered = freeze.slots.map((slot) => photos.find((photo) => photo.id === slot.contentId)!)
  const capsule = await sealCapsule({
    session,
    keys,
    freeze,
    recipient: RECIPIENT_SNAPSHOT,
    photos: ordered,
    capsuleId: CAPSULE_UUID,
    candidateId: CANDIDATE,
    createdAtEpochSeconds: CREATED_AT,
    epoch: EPOCH,
    frontFingerprint: FP,
    placeLabel: null,
  })
  return { capsule, session, keys }
}
