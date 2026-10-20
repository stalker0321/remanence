/** T398 test-only real crypto material, copied from scanEngine.page.tsx at 0aa84ff8.
 * Committed protocol golden keys are SYNTHETIC, never real account custody.
 */
import type { RecipientSnapshot } from '../../src/create/recipient'
import { layoutCreatorNote } from '../../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../../src/collage/frozenRenderedPlan'

import type { TextMeasurer } from '../../src/collage/prepare'
import { styleById } from '../../src/collage/styles'
import { freezeSelection, type CreatorFreeze } from '../../src/create/creatorFreeze'
import type { DraftPhoto } from '../../src/create/photos'
import { bytesToHex } from '../../src/protocol/bytes'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import hpkeVector from '../../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../../protocol/fixtures/publish-signature-v1.json'
import { keysetFromJson, type JsonKeyset } from '../keysetJson'
import '../../src/design/tokens.css'

export const SENDER_ID = '11111111-2222-4333-8444-555555555555'
export const SENDER_BUNDLE = '22222222-3333-4444-8555-666666666666'
export const SENDER_HANDLE = 'sergio'
export const RECIPIENT_ID = '33333333-4444-4555-8666-777777777777'
export const RECIPIENT_BUNDLE = '44444444-5555-4666-8777-888888888888'
const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
export const NOTE_TEXT = 'hello from the recognition index proof'

const HPKE_PUBLIC = keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)
export const HPKE_PRIVATE = keysetFromJson(hpkeVector.fixed_private_keyset_json as JsonKeyset)
export const SIGNING_PRIVATE = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PUBLIC = keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)

export function recipientSnapshot(): RecipientSnapshot {
  return {
    userId: RECIPIENT_ID, handle: 'ada', keyBundleId: RECIPIENT_BUNDLE,
    suite: SUITE, protocolVersion: 1,
    encryptionPublicKeyset: b64(HPKE_PUBLIC), signingPublicKeyset: b64(SIGNING_PUBLIC),
    directoryVersion: '0'.repeat(16), self: false,
  }
}

export function senderSnapshot(): RecipientSnapshot {
  return {
    userId: SENDER_ID, handle: SENDER_HANDLE, keyBundleId: SENDER_BUNDLE,
    suite: SUITE, protocolVersion: 1,
    encryptionPublicKeyset: b64(HPKE_PUBLIC), signingPublicKeyset: b64(SIGNING_PUBLIC),
    directoryVersion: '0'.repeat(16), self: false,
  }
}

const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
}

async function canvasJpeg(width: number, height: number, seed: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  const random = mulberry32(seed)
  for (let i = 0; i < 80; i++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    context.fillRect(random() * width, random() * height, 20, 20)
  }
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.9))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

export async function jpeg(id: string, width: number, height: number): Promise<DraftPhoto> {
  const seed = 1000 + id.split('').reduce((sum, char) => sum + char.charCodeAt(0), 0)
  return { id, bytes: await canvasJpeg(width, height, seed), width, height, contentHash: '' }
}

export async function buildFreeze(photos: readonly DraftPhoto[], note: string, generation: number): Promise<CreatorFreeze> {
  const withHashes = await Promise.all(photos.map(async (photo) => ({ ...photo, contentHash: bytesToHex(await sha256(photo.bytes)) })))
  const source = withHashes.map((photo) => ({ contentId: photo.id, width: photo.width, height: photo.height, contentHash: photo.contentHash }))
  const byId = new Map(source.map((spec) => [spec.contentId, spec]))
  const style = styleById('press')!
  const view = previewCandidates({ id: 'creator', sender: SENDER_HANDLE, photos: source, note }, undefined, plannerFromTextMeasurer(measurer, 640, 640, style.note))[0]!
  const scene = creatorScene({
    view, styleId: 'press', senderHandle: SENDER_HANDLE, noteText: note,
    photos: new Map(source.map((spec) => [spec.contentId, { width: spec.width, height: spec.height }])),
  })
  const noteLayout = scene.note
    ? layoutCreatorNote(
        scene.note.text,
        { font: scene.note.family, weight: scene.note.weight, italic: scene.note.italic, size: scene.note.size, line: scene.note.line },
        scene.note.region,
        (line) => measurer.measure({ text: line, family: scene.note!.family, size: scene.note!.size, weight: scene.note!.weight, italic: scene.note!.italic, line: scene.note!.line, maxWidth: Number.POSITIVE_INFINITY, maxHeight: Number.POSITIVE_INFINITY, softWrap: false }).width,
      )
    : null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId: 'press', styleVersion: style.version })
  const result = freezeSelection({
    branchId: view.branch, styleId: 'press', styleVersion: style.version, senderHandle: SENDER_HANDLE,
    slots: view.slots.map((slot, index) => {
      const spec = byId.get(slot.photoId)!
      return { contentId: slot.photoId, ordinal: index, contentHash: spec.contentHash, widthPx: spec.width, heightPx: spec.height, crop: { ...slot.crop } }
    }),
    note, noteRegion: scene.note ? { ...scene.note.region } : null, noteFits: true, musicSelected: false,
    ownerId: SENDER_ID, sessionGeneration: generation,
    recipient: { userId: RECIPIENT_ID, keyBundleId: RECIPIENT_BUNDLE, directoryVersion: '0'.repeat(16) },
    renderedKey: 'rk-1', renderedPlan, createdAtMs: 1,
  })
  if (!result.ok) throw new Error('freeze failed')
  return result.frozen
}

function mulberry32(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state |= 0
    state = (state + 0x6d2b79f5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export async function texturedQuadJpeg(seed: number, width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  const quad = [
    { x: 100, y: 80 }, { x: 540, y: 60 }, { x: 560, y: 400 }, { x: 80, y: 420 },
  ]
  context.save()
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.clip()
  const random = mulberry32(seed)
  for (let i = 0; i < 260; i++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    context.fillRect(quad[0]!.x + random() * 480, quad[0]!.y + random() * 340, 4 + random() * 22, 4 + random() * 22)
  }
  context.restore()
  context.lineWidth = 6
  context.strokeStyle = '#ffffff'
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.stroke()
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}


