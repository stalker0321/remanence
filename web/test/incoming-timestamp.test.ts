import { describe, expect, it } from 'vitest'
import publishVector from '../../protocol/fixtures/publish-statement-v1.json'
import signatureVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { signPublishStatement } from '../src/crypto/publish'
import { arrayBuffer, bytesToHex, concat, hexToBytes } from '../src/protocol/bytes'
import { buildPublishStatement, type PublishStatementInput } from '../src/protocol/publishStatement'
import { uuidToBytes } from '../src/protocol/uuid'
import { SessionStore } from '../src/session/session'
import { fetchIncoming } from '../src/sync/incoming'
import { keysetFromJson, type JsonKeyset } from './keysetJson'

const PRIVATE_KEYSET = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)

function fixtureInput(createdAtEpochSeconds: bigint): PublishStatementInput {
  return {
    capsuleId: uuidToBytes(publishVector.capsule_id),
    senderUserId: uuidToBytes(publishVector.sender_user_id),
    recipientUserId: uuidToBytes(publishVector.recipient_user_id),
    senderKeyBundleId: uuidToBytes(publishVector.sender_key_bundle_id),
    recipientKeyBundleId: uuidToBytes(publishVector.recipient_key_bundle_id),
    createdAtEpochSeconds,
    artifacts: publishVector.artifacts.map((artifact) => ({
      blobId: uuidToBytes(artifact.blob_id),
      kind: artifact.kind as 'RECOGNITION_MANIFEST' | 'CONTENT_MANIFEST' | 'PHOTO',
      ordinal: artifact.ordinal,
      ciphertextSize: artifact.ciphertext_size,
      ciphertextSha256: hexToBytes(artifact.ciphertext_sha256_hex),
    })),
  }
}

function scripted(handle: (input: ApiRequest) => ApiResult): ApiTransport {
  return { request: (input) => Promise.resolve(handle(input)) }
}

function signIn(session: SessionStore): void {
  session.signIn({
    accountId: publishVector.recipient_user_id,
    bundleId: publishVector.recipient_key_bundle_id,
    handle: 'ada',
    accessToken: 'incoming-access',
    refreshToken: 'incoming-refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
}

async function signedPageFor(statement: Uint8Array, signature: Uint8Array): Promise<Record<string, unknown>> {
  const statementSha = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(statement)))
  const envelope = new Uint8Array([9, 9, 9])
  const envelopeSha = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(envelope)))
  return {
    items: [
      {
        capsule_id: publishVector.capsule_id,
        sender_user_id: publishVector.sender_user_id,
        recipient_user_id: publishVector.recipient_user_id,
        sender_key_bundle_id: publishVector.sender_key_bundle_id,
        recipient_key_bundle_id: publishVector.recipient_key_bundle_id,
        protocol_version: 1,
        ready_at: '2023-11-14T22:13:20Z',
        signed_publish_statement: {
          statement: b64(statement),
          statement_sha256: b64(statementSha),
          signature: b64(signature),
        },
        recipient_envelope: {
          recipient_key_bundle_id: publishVector.recipient_key_bundle_id,
          ciphertext: b64(envelope),
          ciphertext_size: envelope.length,
          ciphertext_sha256: b64(envelopeSha),
        },
        blobs: publishVector.artifacts.map((artifact) => ({
          blob_id: artifact.blob_id,
          kind: artifact.kind,
          ordinal: artifact.kind === 'PHOTO' ? artifact.ordinal : null,
          ciphertext_size: artifact.ciphertext_size,
          ciphertext_sha256: b64(hexToBytes(artifact.ciphertext_sha256_hex)),
        })),
      },
    ],
    has_more: false,
    next_cursor: null,
  }
}

function incomingTransport(page: Record<string, unknown>): ApiTransport {
  const signing = b64(keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset))
  return scripted((input) => {
    if (input.path === '/v1/capsules/incoming') return { status: 200, body: page }
    return {
      status: 200,
      body: {
        key_bundle_id: publishVector.sender_key_bundle_id,
        user_id: publishVector.sender_user_id,
        suite: 'tink',
        protocol_version: 1,
        encryption_public_keyset: signing,
        signing_public_keyset: signing,
        status: 'ACTIVE',
        created_at: '2023-11-14T22:13:20Z',
      },
    }
  })
}

async function expectReceiverRejects(statement: Uint8Array): Promise<void> {
  const signature = await signPublishStatement(PRIVATE_KEYSET, statement)
  const page = await signedPageFor(statement, signature)
  const session = new SessionStore()
  signIn(session)
  await expect(fetchIncoming(session, incomingTransport(page))).rejects.toMatchObject({ code: 'SYNC_REJECTED' })
}

// Timestamp 1 -> field 7 is exactly `38 01` at offsets 92..93; the first
// binding begins at offset 94. Guard these offsets so the fixture stays honest.
const BUILT = buildPublishStatement(fixtureInput(1n))
if (BUILT[92] !== 0x38 || BUILT[93] !== 0x01 || BUILT[94] !== 0x42) throw new Error('unexpected fixture layout')
if (BUILT[114] !== 0x10 || BUILT[116] !== 0x18 || BUILT[118] !== 0x20) throw new Error('unexpected first binding layout')

const OVER_UINT32 = Uint8Array.of(0x80, 0x80, 0x80, 0x80, 0x10) // 2^32

function replaceTimestamp(field: Uint8Array): Uint8Array {
  return concat([BUILT.slice(0, 92), field, BUILT.slice(94)])
}

function patchFirstBinding(offset: number, tag: number, value: Uint8Array, delta: number): Uint8Array {
  // The first binding's kind/ordinal/size values are all one byte, so the
  // original tag+value spans two bytes at `offset`.
  const patched = concat([BUILT.slice(0, offset), Uint8Array.of(tag), value, BUILT.slice(offset + 2)])
  patched[95] = 0x3a + delta
  return patched
}

describe('incoming root timestamp signed64 reader (T283)', () => {
  it('still accepts the normal baseline statement', async () => {
    const statement = buildPublishStatement(fixtureInput(BigInt(publishVector.created_at_epoch_seconds)))
    const signature = await signPublishStatement(PRIVATE_KEYSET, statement)
    const page = await signedPageFor(statement, signature)
    const session = new SessionStore()
    signIn(session)
    const catalog = await fetchIncoming(session, incomingTransport(page))
    expect(catalog.items).toHaveLength(1)
    expect(bytesToHex(catalog.items[0]!.statementSha256)).toBe(
      bytesToHex(new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(statement)))),
    )
  })

  it('refuses a timestamp varint longer than 10 bytes', async () => {
    await expectReceiverRejects(replaceTimestamp(Uint8Array.of(0x38, ...new Array(10).fill(0x80), 0x01)))
  })

  it('refuses a timestamp that overflows 64 bits (10th payload byte > 1)', async () => {
    await expectReceiverRejects(replaceTimestamp(Uint8Array.of(0x38, ...new Array(9).fill(0xff), 0x02)))
  })

  it('refuses a truncated timestamp at end of input', async () => {
    await expectReceiverRejects(concat([BUILT.slice(0, 92), Uint8Array.of(0x38, 0x80, 0x80)]))
  })

  it('refuses timestamp field 7 on the wrong wire type', async () => {
    await expectReceiverRejects(replaceTimestamp(Uint8Array.of(0x3a, 0x01, 0x00)))
  })

  it('refuses duplicate timestamp field 7 (same wire and mixed wire)', async () => {
    await expectReceiverRejects(replaceTimestamp(Uint8Array.of(0x38, 0x01, 0x38, 0x02)))
    await expectReceiverRejects(replaceTimestamp(Uint8Array.of(0x38, 0x01, 0x3a, 0x01, 0x00)))
  })

  it('keeps the shared uint32 bound for root protocol_version', async () => {
    const patched = concat([Uint8Array.of(0x08), OVER_UINT32, BUILT.slice(2)])
    await expectReceiverRejects(patched)
  })

  it('keeps the shared uint32 bound for nested kind/ordinal/size', async () => {
    await expectReceiverRejects(patchFirstBinding(114, 0x10, OVER_UINT32, 4)) // kind
    await expectReceiverRejects(patchFirstBinding(116, 0x18, OVER_UINT32, 4)) // ordinal
    await expectReceiverRejects(patchFirstBinding(118, 0x20, OVER_UINT32, 4)) // ciphertext size
  })

  it('refuses a nested unexpected field 7 in an ArtifactBinding', async () => {
    const patched = concat([BUILT.slice(0, 116), Uint8Array.of(0x38, 0x01), BUILT.slice(118)])
    await expectReceiverRejects(patched)
  })
})
