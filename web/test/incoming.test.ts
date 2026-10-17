import { describe, expect, it } from 'vitest'
import publishVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiRequest, ApiResult, ApiTransport, ByteTransport } from '../src/auth/http'
import { arrayBuffer, hexToBytes } from '../src/protocol/bytes'
import { SessionStore } from '../src/session/session'
import { downloadCiphertext, fetchIncoming, type IncomingCapsule } from '../src/sync/incoming'
import { keysetFromJson, type JsonKeyset } from './keysetJson'

const CAPSULE = '00010203-0405-0607-0809-0a0b0c0d0e0f'
const SENDER = '10111213-1415-1617-1819-1a1b1c1d1e1f'
const RECIPIENT = '20212223-2425-2627-2829-2a2b2c2d2e2f'
const SENDER_BUNDLE = '30313233-3435-3637-3839-3a3b3c3d3e3f'
const RECIPIENT_BUNDLE = '40414243-4445-4647-4849-4a4b4c4d4e4f'

function scripted(handle: (input: ApiRequest) => ApiResult): { transport: ApiTransport; calls: ApiRequest[] } {
  const calls: ApiRequest[] = []
  return {
    calls,
    transport: {
      request(input) {
        calls.push(input)
        return Promise.resolve(handle(input))
      },
    },
  }
}

async function signedPage(): Promise<Record<string, unknown>> {
  const statement = hexToBytes(publishVector.expected_deterministic_hex)
  const statementSha = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(statement)))
  const envelope = new Uint8Array([9, 9, 9])
  const envelopeSha = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(envelope)))
  const digest = (fill: number) => b64(new Uint8Array(32).fill(fill))
  const blob = (id: string, kind: string, ordinal: number | null, size: number, fill: number) => ({
    blob_id: id,
    kind,
    ordinal,
    ciphertext_size: size,
    ciphertext_sha256: digest(fill),
  })
  return {
    items: [
      {
        capsule_id: CAPSULE,
        sender_user_id: SENDER,
        recipient_user_id: RECIPIENT,
        sender_key_bundle_id: SENDER_BUNDLE,
        recipient_key_bundle_id: RECIPIENT_BUNDLE,
        protocol_version: 1,
        ready_at: '2023-11-14T22:13:20Z',
        signed_publish_statement: {
          statement: b64(statement),
          statement_sha256: b64(statementSha),
          signature: publishVector.expected_signature_base64url,
        },
        recipient_envelope: {
          recipient_key_bundle_id: RECIPIENT_BUNDLE,
          ciphertext: b64(envelope),
          ciphertext_size: envelope.length,
          ciphertext_sha256: b64(envelopeSha),
        },
        blobs: [
          blob('00000000-0000-0000-0000-0000000000a1', 'RECOGNITION_MANIFEST', null, 101, 0x11),
          blob('00000000-0000-0000-0000-0000000000b2', 'CONTENT_MANIFEST', null, 202, 0x22),
          blob('00000000-0000-0000-0000-0000000000c3', 'PHOTO', 0, 303, 0x33),
          blob('00000000-0000-0000-0000-0000000000d4', 'PHOTO', 1, 404, 0x44),
          blob('00000000-0000-0000-0000-0000000000e5', 'PHOTO', 2, 505, 0x55),
        ],
      },
    ],
    has_more: false,
    next_cursor: null,
  }
}

function signIn(session: SessionStore) {
  session.signIn({
    accountId: RECIPIENT,
    bundleId: RECIPIENT_BUNDLE,
    handle: 'ada',
    accessToken: 'incoming-access',
    refreshToken: 'incoming-refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
}

describe('incoming ciphertext', () => {
  it('keeps a signed page for this account and does not unlock or match it', async () => {
    const session = new SessionStore()
    signIn(session)
    const generation = session.getSnapshot().generation
    const page = await signedPage()
    const signing = b64(keysetFromJson(publishVector.fixed_public_keyset_json as JsonKeyset))
    const { transport, calls } = scripted((input) => {
      if (input.path === '/v1/capsules/incoming') return { status: 200, body: page }
      return {
        status: 200,
        body: {
          key_bundle_id: SENDER_BUNDLE,
          user_id: SENDER,
          suite: 'tink',
          protocol_version: 1,
          encryption_public_keyset: signing,
          signing_public_keyset: signing,
          status: 'ACTIVE',
          created_at: '2023-11-14T22:13:20Z',
        },
      }
    })
    const catalog = await fetchIncoming(session, transport)
    expect(catalog.items).toHaveLength(1)
    expect(catalog.items[0]?.capsuleId).toBe(CAPSULE)
    expect(catalog.items[0]?.envelopeCiphertext).toEqual(new Uint8Array([9, 9, 9]))
    expect(session.getSnapshot().phase).toBe('authenticated')
    expect(session.getSnapshot().generation).toBe(generation)
    expect(calls.map((call) => call.path)).toEqual([
      '/v1/capsules/incoming',
      `/v1/directory/key-bundles/${SENDER_BUNDLE}`,
    ])
    expect(calls.every((call) => call.token === 'incoming-access')).toBe(true)
  })

  it('rejects a page addressed to someone else or a statement that does not bind the blobs', async () => {
    const session = new SessionStore()
    signIn(session)
    const page = await signedPage()
    const item = (page.items as Record<string, unknown>[])[0]
    if (!item) throw new Error('missing item')
    item.recipient_user_id = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
    const wrongRecipient = scripted(() => ({ status: 200, body: page }))
    await expect(fetchIncoming(session, wrongRecipient.transport)).rejects.toMatchObject({ code: 'SYNC_REJECTED' })
    expect(wrongRecipient.calls.map((call) => call.path)).toEqual(['/v1/capsules/incoming'])
    const rebound = await signedPage()
    const blobs = ((rebound.items as Record<string, unknown>[])[0]?.blobs as Record<string, unknown>[])
    const photo = blobs[2]
    if (!photo) throw new Error('missing photo')
    photo.ciphertext_size = 304
    const mismatched = scripted(() => ({ status: 200, body: rebound }))
    await expect(fetchIncoming(session, mismatched.transport)).rejects.toMatchObject({ code: 'SYNC_REJECTED' })
    expect(session.getSnapshot().phase).toBe('authenticated')
  })

  it('checks a downloaded body against the declared hash and does not treat it as plaintext', async () => {
    const session = new SessionStore()
    signIn(session)
    const body = new Uint8Array([4, 5, 6, 7])
    const digest = b64(new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(body))))
    const item: IncomingCapsule = {
      capsuleId: CAPSULE,
      senderUserId: SENDER,
      recipientUserId: RECIPIENT,
      senderKeyBundleId: SENDER_BUNDLE,
      recipientKeyBundleId: RECIPIENT_BUNDLE,
      envelopeCiphertext: new Uint8Array([1]),
      blobs: [
        {
          blobId: '00000000-0000-0000-0000-0000000000c3',
          kind: 'PHOTO',
          ordinal: 0,
          ciphertextSize: body.length,
          ciphertextSha256: digest,
        },
      ],
    }
    const bytes: ByteTransport = {
      requestBytes: () => Promise.resolve({ status: 200, body: body.slice() }),
    }
    const downloaded = await downloadCiphertext(session, bytes, item, item.blobs[0]?.blobId ?? '')
    expect(downloaded).toEqual(body)
    expect(session.getSnapshot().phase).toBe('authenticated')
    const flipped = body.slice()
    flipped[0] = 0
    const tampered: ByteTransport = { requestBytes: () => Promise.resolve({ status: 200, body: flipped }) }
    await expect(downloadCiphertext(session, tampered, item, item.blobs[0]?.blobId ?? '')).rejects.toMatchObject({
      code: 'HASH_MISMATCH',
    })
  })
})
