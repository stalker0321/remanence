import { describe, expect, it } from 'vitest'
import signatureVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { signPublishStatement } from '../src/crypto/publish'
import { arrayBuffer } from '../src/protocol/bytes'
import { buildPublishStatement } from '../src/protocol/publishStatement'
import { uuidToBytes } from '../src/protocol/uuid'
import { SessionStore } from '../src/session/session'
import type { IncomingCapsule } from '../src/sync/incoming'
import {
  COMPLETE_INCOMING_GUARDRAILS,
  fetchCompleteIncoming,
  incomingMetadataBytes,
  incomingRecognitionCiphertextBytes,
  isCanonicalIncomingCursor,
  wipeIncomingCatalog,
} from '../src/sync/incomingAll'
import { keysetFromJson, type JsonKeyset } from './keysetJson'

const SENDER = '10111213-1415-1617-1819-1a1b1c1d1e1f'
const RECIPIENT = '20212223-2425-2627-2829-2a2b2c2d2e2f'
const SENDER_BUNDLE = '30313233-3435-3637-3839-3a3b3c3d3e3f'
const RECIPIENT_BUNDLE = '40414243-4445-4647-4849-4a4b4c4d4e4f'
const SIGNING_PRIVATE = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const SIGNING_PUBLIC = b64(keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset))

function capsuleId(index: number): string {
  return `00000000-0000-0000-0000-${(index + 1).toString(16).padStart(12, '0')}`
}

function blobId(index: number, slot: number): string {
  return `00000000-0000-0000-0000-${(index * 10 + slot + 1).toString(16).padStart(12, '0')}`
}

const digest = (fill: number) => new Uint8Array(32).fill(fill)

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes)))
}

function base64url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

/** Canonical server-compatible version-1 cursor: 25 bytes -> 34 base64url chars. */
function cursorFor(sequence: number): string {
  const payload = new Uint8Array(25)
  payload[0] = 1
  new DataView(payload.buffer).setBigInt64(1, BigInt(sequence), false)
  payload.set(uuidToBytes(capsuleId(sequence)), 9)
  return base64url(payload)
}

async function signedItem(index: number, recognitionSize = 101): Promise<Record<string, unknown>> {
  const id = capsuleId(index)
  const artifacts = [
    { blobId: uuidToBytes(blobId(index, 0)), kind: 'RECOGNITION_MANIFEST' as const, ordinal: -1, ciphertextSize: recognitionSize, ciphertextSha256: digest(0x11) },
    { blobId: uuidToBytes(blobId(index, 1)), kind: 'CONTENT_MANIFEST' as const, ordinal: -1, ciphertextSize: 202, ciphertextSha256: digest(0x22) },
    { blobId: uuidToBytes(blobId(index, 2)), kind: 'PHOTO' as const, ordinal: 0, ciphertextSize: 303, ciphertextSha256: digest(0x33) },
    { blobId: uuidToBytes(blobId(index, 3)), kind: 'PHOTO' as const, ordinal: 1, ciphertextSize: 304, ciphertextSha256: digest(0x34) },
    { blobId: uuidToBytes(blobId(index, 4)), kind: 'PHOTO' as const, ordinal: 2, ciphertextSize: 305, ciphertextSha256: digest(0x35) },
  ]
  const statement = buildPublishStatement({
    capsuleId: uuidToBytes(id),
    senderUserId: uuidToBytes(SENDER),
    recipientUserId: uuidToBytes(RECIPIENT),
    senderKeyBundleId: uuidToBytes(SENDER_BUNDLE),
    recipientKeyBundleId: uuidToBytes(RECIPIENT_BUNDLE),
    createdAtEpochSeconds: 0n,
    artifacts,
  })
  const statementSha = await sha256(statement)
  const signature = await signPublishStatement(SIGNING_PRIVATE, statement)
  const envelope = new Uint8Array([9, 9, 9])
  const envelopeSha = await sha256(envelope)
  return {
    capsule_id: id,
    sender_user_id: SENDER,
    recipient_user_id: RECIPIENT,
    sender_key_bundle_id: SENDER_BUNDLE,
    recipient_key_bundle_id: RECIPIENT_BUNDLE,
    protocol_version: 1,
    ready_at: '2023-11-14T22:13:20Z',
    signed_publish_statement: {
      statement: b64(statement),
      statement_sha256: b64(statementSha),
      signature: b64(signature),
    },
    recipient_envelope: {
      recipient_key_bundle_id: RECIPIENT_BUNDLE,
      ciphertext: b64(envelope),
      ciphertext_size: envelope.length,
      ciphertext_sha256: b64(envelopeSha),
    },
    blobs: [
      { blob_id: blobId(index, 0), kind: 'RECOGNITION_MANIFEST', ordinal: null, ciphertext_size: recognitionSize, ciphertext_sha256: b64(digest(0x11)) },
      { blob_id: blobId(index, 1), kind: 'CONTENT_MANIFEST', ordinal: null, ciphertext_size: 202, ciphertext_sha256: b64(digest(0x22)) },
      { blob_id: blobId(index, 2), kind: 'PHOTO', ordinal: 0, ciphertext_size: 303, ciphertext_sha256: b64(digest(0x33)) },
      { blob_id: blobId(index, 3), kind: 'PHOTO', ordinal: 1, ciphertext_size: 304, ciphertext_sha256: b64(digest(0x34)) },
      { blob_id: blobId(index, 4), kind: 'PHOTO', ordinal: 2, ciphertext_size: 305, ciphertext_sha256: b64(digest(0x35)) },
    ],
  }
}

function page(items: unknown[], hasMore: boolean, nextCursor: string | null): Record<string, unknown> {
  return { items, has_more: hasMore, next_cursor: nextCursor }
}

function transportFor(pages: Map<string | null, Record<string, unknown>>): { transport: ApiTransport; incoming: ApiRequest[] } {
  const incoming: ApiRequest[] = []
  const signing = {
    key_bundle_id: SENDER_BUNDLE,
    user_id: SENDER,
    protocol_version: 1,
    status: 'ACTIVE',
    signing_public_keyset: SIGNING_PUBLIC,
  }
  return {
    incoming,
    transport: {
      request(input) {
        if (input.path === '/v1/capsules/incoming') {
          incoming.push(input)
          const cursor = input.query?.cursor ?? null
          const found = pages.get(cursor)
          return Promise.resolve(found ? { status: 200, body: found } : { status: 404, body: null })
        }
        if (input.path === `/v1/directory/key-bundles/${SENDER_BUNDLE}`) {
          return Promise.resolve({ status: 200, body: signing })
        }
        return Promise.resolve({ status: 404, body: null })
      },
    },
  }
}

function session(): SessionStore {
  const store = new SessionStore()
  store.signIn({
    accountId: RECIPIENT,
    bundleId: RECIPIENT_BUNDLE,
    handle: 'ada',
    accessToken: 'a',
    refreshToken: 'r',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  store.unlock()
  return store
}

describe('T353 complete incoming collector', () => {
  it('normalizes a single terminal non-null server cursor only after exhaustion', async () => {
    const item = await signedItem(0)
    const terminal = cursorFor(0)
    const { transport, incoming } = transportFor(new Map([[null, page([item], false, terminal)]]))
    const result = await fetchCompleteIncoming(session(), transport)
    expect(result.hasMore).toBe(false)
    expect(result.nextCursor).toBeNull()
    expect(result.items.map((entry) => entry.capsuleId)).toEqual([capsuleId(0)])
    expect(incoming).toHaveLength(1)
    expect(incoming[0]!.query).toEqual({ limit: '25' })
    expect(isCanonicalIncomingCursor(terminal)).toBe(true)
    wipeIncomingCatalog(result.items)
  })

  it('preserves server order across pages and sends the canonical cursor', async () => {
    const first = await signedItem(0)
    const second = await signedItem(1)
    const c1 = cursorFor(1)
    const { transport, incoming } = transportFor(
      new Map([
        [null, page([first], true, c1)],
        [c1, page([second], false, cursorFor(2))],
      ]),
    )
    const result = await fetchCompleteIncoming(session(), transport)
    expect(result.items.map((entry) => entry.capsuleId)).toEqual([capsuleId(0), capsuleId(1)])
    expect(incoming.map((call) => call.query?.cursor ?? null)).toEqual([null, c1])
    expect(incoming.every((call) => call.query?.limit === '25')).toBe(true)
    wipeIncomingCatalog(result.items)
  })

  it('accepts an empty terminal page that echoes the requested cursor', async () => {
    const item = await signedItem(0)
    const c1 = cursorFor(0)
    const { transport } = transportFor(
      new Map([
        [null, page([item], true, c1)],
        [c1, page([], false, c1)],
      ]),
    )
    const result = await fetchCompleteIncoming(session(), transport)
    expect(result.items.map((entry) => entry.capsuleId)).toEqual([capsuleId(0)])
    expect(result.nextCursor).toBeNull()
    wipeIncomingCatalog(result.items)
  })

  it('refuses an A-B-A cursor replay before issuing it again, even with distinct IDs', async () => {
    const a = cursorFor(1)
    const b = cursorFor(2)
    const entries = await Promise.all([0, 1, 2].map((index) => signedItem(index)))
    const { transport, incoming } = transportFor(
      new Map([
        [null, page([entries[0]], true, a)],
        [a, page([entries[1]], true, b)],
        [b, page([entries[2]], true, a)],
      ]),
    )
    await expect(fetchCompleteIncoming(session(), transport)).rejects.toMatchObject({ code: 'INCOMPLETE' })
    expect(incoming.map((call) => call.query?.cursor ?? null)).toEqual([null, a, b])
  })

  it('rechecks the captured owner after a rejected page await', async () => {
    const first = await signedItem(0)
    const next = cursorFor(1)
    const store = session()
    let calls = 0
    const transport: ApiTransport = {
      request(input) {
        if (input.path === '/v1/capsules/incoming') {
          calls += 1
          if (calls === 1) return Promise.resolve({ status: 200, body: page([first], true, next) })
          store.lock()
          return Promise.reject(new Error('controlled page failure'))
        }
        return Promise.resolve({
          status: 200,
          body: {
            key_bundle_id: SENDER_BUNDLE,
            user_id: SENDER,
            protocol_version: 1,
            status: 'ACTIVE',
            signing_public_keyset: SIGNING_PUBLIC,
          },
        })
      },
    }
    await expect(fetchCompleteIncoming(store, transport)).rejects.toMatchObject({ code: 'STALE' })
    expect(calls).toBe(2)
  })

  it('refuses loops/duplicates/empty-more/malformed/missing pages without partial output', async () => {
    const first = await signedItem(0)
    const second = await signedItem(1)
    const c1 = cursorFor(0)
    const cases: Array<[Map<string | null, Record<string, unknown>>, string]> = [
      [new Map([[null, page([first], true, c1)]]), 'SYNC_REJECTED'],
      [new Map([[null, page([first], true, c1)], [c1, page([second], true, c1)]]), 'INCOMPLETE'],
      [new Map([[null, page([first], true, c1)], [c1, page([first], false, cursorFor(1))]]), 'INCOMPLETE'],
      [new Map([[null, page([], true, c1)]]), 'INCOMPLETE'],
      [new Map([[null, page([first], true, 'not-a-cursor')]]), 'INCOMPLETE'],
    ]
    for (const [pages, code] of cases) {
      const { transport } = transportFor(pages)
      await expect(fetchCompleteIncoming(session(), transport)).rejects.toMatchObject({ code })
    }
  })

  it('refuses the recognition budget before any download', async () => {
    const cap = COMPLETE_INCOMING_GUARDRAILS.maxRecognitionCiphertextBytes
    expect(33 * 1_048_576).toBeGreaterThan(cap)
    const items = await Promise.all(Array.from({ length: 33 }, (_, index) => signedItem(index, 1_048_576)))
    const c1 = cursorFor(25)
    const { transport } = transportFor(
      new Map([
        [null, page(items.slice(0, 25), true, c1)],
        [c1, page(items.slice(25), false, cursorFor(33))],
      ]),
    )
    await expect(fetchCompleteIncoming(session(), transport)).rejects.toMatchObject({ code: 'INCOMPLETE' })
  })

  it('refuses more than the request guardrail before exhausting', async () => {
    const pages = new Map<string | null, Record<string, unknown>>()
    let cursor: string | null = null
    for (let index = 0; index < COMPLETE_INCOMING_GUARDRAILS.maxRequests; index++) {
      const item = await signedItem(index)
      const next = cursorFor(index + 1)
      pages.set(cursor, page([item], true, next))
      cursor = next
    }
    const { transport } = transportFor(pages)
    await expect(fetchCompleteIncoming(session(), transport)).rejects.toMatchObject({ code: 'INCOMPLETE' })
  })

  it('refuses cancellation after a late page and wipes owned helper buffers', async () => {
    const item = await signedItem(0)
    const c1 = cursorFor(0)
    const controller = new AbortController()
    let resolvePage!: (body: Record<string, unknown>) => void
    const transport: ApiTransport = {
      request(input) {
        if (input.path === '/v1/capsules/incoming') {
          return new Promise<ApiResult>((resolve) => {
            resolvePage = (body) => resolve({ status: 200, body })
          })
        }
        return Promise.resolve({
          status: 200,
          body: {
            key_bundle_id: SENDER_BUNDLE,
            user_id: SENDER,
            protocol_version: 1,
            status: 'ACTIVE',
            signing_public_keyset: SIGNING_PUBLIC,
          },
        })
      },
    }
    const pending = fetchCompleteIncoming(session(), transport, controller.signal)
    controller.abort()
    resolvePage(page([item], true, c1))
    await expect(pending).rejects.toMatchObject({ code: 'CANCELLED' })

    const capsule: IncomingCapsule = {
      capsuleId: capsuleId(0),
      senderUserId: SENDER,
      recipientUserId: RECIPIENT,
      senderKeyBundleId: SENDER_BUNDLE,
      recipientKeyBundleId: RECIPIENT_BUNDLE,
      envelopeCiphertext: new Uint8Array([1, 2, 3]),
      statementSha256: new Uint8Array(32).fill(7),
      blobs: [],
    }
    wipeIncomingCatalog([capsule])
    expect(capsule.envelopeCiphertext.every((byte) => byte === 0)).toBe(true)
    expect(capsule.statementSha256.every((byte) => byte === 0)).toBe(true)
  })

  it('accounts metadata deterministically and validates canonical cursors', () => {
    const capsule: IncomingCapsule = {
      capsuleId: capsuleId(0),
      senderUserId: SENDER,
      recipientUserId: RECIPIENT,
      senderKeyBundleId: SENDER_BUNDLE,
      recipientKeyBundleId: RECIPIENT_BUNDLE,
      envelopeCiphertext: new Uint8Array(3),
      statementSha256: new Uint8Array(32),
      blobs: [
        {
          blobId: blobId(0, 0),
          kind: 'RECOGNITION_MANIFEST',
          ordinal: null,
          ciphertextSize: 101,
          ciphertextSha256: b64(digest(0x11)),
        },
      ],
    }
    const strings = [
      capsule.capsuleId,
      capsule.senderUserId,
      capsule.recipientUserId,
      capsule.senderKeyBundleId,
      capsule.recipientKeyBundleId,
      capsule.blobs[0]!.blobId,
      capsule.blobs[0]!.kind,
      capsule.blobs[0]!.ciphertextSha256,
    ]
    const encoder = new TextEncoder()
    const expected = 3 + 32 + strings.reduce((total, value) => total + encoder.encode(value).length, 0) + 8
    expect(incomingMetadataBytes(capsule)).toBe(expected)
    expect(incomingRecognitionCiphertextBytes(capsule)).toBe(101)
    expect(isCanonicalIncomingCursor(cursorFor(0))).toBe(true)
    expect(isCanonicalIncomingCursor('A'.repeat(34))).toBe(false)
    expect(isCanonicalIncomingCursor('short')).toBe(false)
  })
})
