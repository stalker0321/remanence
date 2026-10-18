import { describe, expect, it } from 'vitest'
import publishVector from '../../protocol/fixtures/publish-statement-v1.json'
import signatureVector from '../../protocol/fixtures/publish-signature-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { signPublishStatement, verifyPublishStatement } from '../src/crypto/publish'
import { arrayBuffer, bytesToHex, concat, hexToBytes, utf8 } from '../src/protocol/bytes'
import { ProtocolError } from '../src/protocol/errors'
import { limits } from '../src/protocol/limits'
import {
  buildPublishStatement,
  type PublishStatementArtifact,
  type PublishStatementInput,
  type PublishStatementKind,
} from '../src/protocol/publishStatement'
import { uuidToBytes } from '../src/protocol/uuid'
import { SessionStore } from '../src/session/session'
import { fetchIncoming } from '../src/sync/incoming'
import { keysetFromJson, type JsonKeyset } from './keysetJson'

const GOLDEN = publishVector.expected_deterministic_hex
const GOLDEN_TIMESTAMP_FIELD = '3880e2cfaa06'
const PRIVATE_KEYSET = keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset)
const PUBLIC_KEYSET = keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)

function fixtureArtifacts(): PublishStatementArtifact[] {
  return publishVector.artifacts.map((artifact) => ({
    blobId: uuidToBytes(artifact.blob_id),
    kind: artifact.kind as PublishStatementKind,
    ordinal: artifact.ordinal,
    ciphertextSize: artifact.ciphertext_size,
    ciphertextSha256: hexToBytes(artifact.ciphertext_sha256_hex),
  }))
}

function fixtureInput(
  createdAtEpochSeconds: bigint = BigInt(publishVector.created_at_epoch_seconds),
  artifacts: PublishStatementArtifact[] = fixtureArtifacts(),
): PublishStatementInput {
  return {
    capsuleId: uuidToBytes(publishVector.capsule_id),
    senderUserId: uuidToBytes(publishVector.sender_user_id),
    recipientUserId: uuidToBytes(publishVector.recipient_user_id),
    senderKeyBundleId: uuidToBytes(publishVector.sender_key_bundle_id),
    recipientKeyBundleId: uuidToBytes(publishVector.recipient_key_bundle_id),
    createdAtEpochSeconds,
    artifacts,
  }
}

function expectRefusal(input: PublishStatementInput): void {
  expect(() => buildPublishStatement(input)).toThrow(ProtocolError)
}

function permutations<T>(items: T[]): T[][] {
  if (items.length <= 1) return [items.slice()]
  const out: T[][] = []
  for (let index = 0; index < items.length; index++) {
    const rest = items.slice(0, index).concat(items.slice(index + 1))
    for (const tail of permutations(rest)) out.push([items[index]!, ...tail])
  }
  return out
}

function scripted(handle: (input: ApiRequest) => ApiResult): ApiTransport {
  return {
    request(input) {
      return Promise.resolve(handle(input))
    },
  }
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

describe('publish statement encoder', () => {
  it('reproduces the exact 400-byte publish-statement-v1 golden', () => {
    const bytes = buildPublishStatement(fixtureInput())
    expect(bytes.length).toBe(400)
    expect(bytesToHex(bytes)).toBe(GOLDEN)
  })

  it('is order-independent across all 120 binding permutations and never mutates the caller list', () => {
    const all = permutations(fixtureArtifacts())
    expect(all.length).toBe(120)
    for (const artifacts of all) {
      const orderBefore = artifacts.map((artifact) => bytesToHex(artifact.blobId))
      const bytes = buildPublishStatement(fixtureInput(BigInt(publishVector.created_at_epoch_seconds), artifacts))
      expect(bytesToHex(bytes)).toBe(GOLDEN)
      expect(artifacts.map((artifact) => bytesToHex(artifact.blobId))).toEqual(orderBefore)
    }
  })

  it('emits exact int64 two-complement bytes across the full signed64 range and omits zero', () => {
    const min = -(2n ** 63n)
    const max = (2n ** 63n) - 1n
    expect(bytesToHex(buildPublishStatement(fixtureInput(min)))).toBe(
      GOLDEN.replace(GOLDEN_TIMESTAMP_FIELD, `38${'80'.repeat(9)}01`),
    )
    expect(bytesToHex(buildPublishStatement(fixtureInput(-1n)))).toBe(
      GOLDEN.replace(GOLDEN_TIMESTAMP_FIELD, `38${'ff'.repeat(9)}01`),
    )
    expect(bytesToHex(buildPublishStatement(fixtureInput(0n)))).toBe(GOLDEN.replace(GOLDEN_TIMESTAMP_FIELD, ''))
    expect(bytesToHex(buildPublishStatement(fixtureInput(1n)))).toBe(GOLDEN.replace(GOLDEN_TIMESTAMP_FIELD, '3801'))
    expect(bytesToHex(buildPublishStatement(fixtureInput(max)))).toBe(
      GOLDEN.replace(GOLDEN_TIMESTAMP_FIELD, `38${'ff'.repeat(8)}7f`),
    )
  })

  it('refuses out-of-range and non-bigint timestamps', () => {
    expectRefusal(fixtureInput(2n ** 63n))
    expectRefusal(fixtureInput(-(2n ** 63n) - 1n))
    expectRefusal({ ...fixtureInput(), createdAtEpochSeconds: 1700000000 as never })
  })

  it('signs the produced golden bytes with the existing Ed25519 signer and verifies', async () => {
    const statement = buildPublishStatement(fixtureInput())
    expect(bytesToHex(statement)).toBe(GOLDEN)
    const signature = await signPublishStatement(PRIVATE_KEYSET, statement)
    expect(bytesToHex(signature)).toBe(signatureVector.expected_signature_hex)
    await verifyPublishStatement(PUBLIC_KEYSET, statement, signature)
  })

  it('keeps the raw statement hash distinct from the domain-prefixed signature input', async () => {
    const statement = buildPublishStatement(fixtureInput())
    const raw = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(statement)))
    const prefixed = new Uint8Array(
      await crypto.subtle.digest('SHA-256', arrayBuffer(concat([utf8('postmark/publish/v1'), statement]))),
    )
    expect(bytesToHex(raw)).not.toBe(bytesToHex(prefixed))
  })

  it('refuses bad IDs/hashes/kinds/ordinals/duplicates/cardinality/sizes', () => {
    expectRefusal({ ...fixtureInput(), capsuleId: new Uint8Array(15) })
    expectRefusal({ ...fixtureInput(), senderKeyBundleId: new Uint8Array(17) })

    const badSha = fixtureArtifacts()
    badSha[0]!.ciphertextSha256 = new Uint8Array(31)
    expectRefusal(fixtureInput(undefined, badSha))

    const badKind = fixtureArtifacts()
    ;(badKind[0]! as unknown as { kind: string }).kind = 'BOGUS'
    expectRefusal(fixtureInput(undefined, badKind))

    const nonPhotoOrdinal = fixtureArtifacts()
    nonPhotoOrdinal[3]!.ordinal = 0 // RECOGNITION_MANIFEST must be -1
    expectRefusal(fixtureInput(undefined, nonPhotoOrdinal))

    const photoMinusOne = fixtureArtifacts()
    photoMinusOne[0]!.ordinal = -1 // PHOTO must be 0..4
    expectRefusal(fixtureInput(undefined, photoMinusOne))

    const photoFive = fixtureArtifacts()
    photoFive[0]!.ordinal = 5
    expectRefusal(fixtureInput(undefined, photoFive))

    const duplicateOrdinal = fixtureArtifacts()
    duplicateOrdinal[2]!.ordinal = 1
    expectRefusal(fixtureInput(undefined, duplicateOrdinal))

    const gapOrdinal = fixtureArtifacts()
    gapOrdinal[2]!.ordinal = 3
    expectRefusal(fixtureInput(undefined, gapOrdinal))

    const duplicateBlob = fixtureArtifacts()
    duplicateBlob[1]!.blobId = duplicateBlob[0]!.blobId.slice()
    expectRefusal(fixtureInput(undefined, duplicateBlob))

    expectRefusal(fixtureInput(undefined, fixtureArtifacts().filter((artifact) => artifact.kind !== 'CONTENT_MANIFEST')))
    expectRefusal(fixtureInput(undefined, fixtureArtifacts().filter((artifact) => artifact.kind !== 'PHOTO' || artifact.ordinal > 1)))
    expectRefusal(fixtureInput(undefined, fixtureArtifacts().concat(fixtureArtifacts().slice(0, 1))))
  })

  it('refuses zero/negative/fractional/NaN and over-cap ciphertext sizes', () => {
    for (const size of [0, -1, 1.5, Number.NaN]) {
      const artifacts = fixtureArtifacts()
      artifacts[0]!.ciphertextSize = size
      expectRefusal(fixtureInput(undefined, artifacts))
    }
    const caps: Array<[PublishStatementKind, number]> = [
      ['RECOGNITION_MANIFEST', limits.recognitionCiphertext],
      ['CONTENT_MANIFEST', limits.contentCiphertext],
      ['PHOTO', limits.photoCiphertext],
    ]
    for (const [kind, cap] of caps) {
      const artifacts = fixtureArtifacts()
      const target = artifacts.find((artifact) => artifact.kind === kind)!
      target.ciphertextSize = cap + 1
      expectRefusal(fixtureInput(undefined, artifacts))
    }
  })
})

describe('actual receiver boundary (unchanged fetchIncoming)', () => {
  it('accepts the builder output for the normal fixture timestamp via the private bindStatement', async () => {
    const statement = buildPublishStatement(fixtureInput())
    const signature = await signPublishStatement(PRIVATE_KEYSET, statement)
    const page = await signedPageFor(statement, signature)
    const session = new SessionStore()
    signIn(session)
    const catalog = await fetchIncoming(session, incomingTransport(page))
    expect(catalog.items).toHaveLength(1)
    expect(catalog.items[0]?.capsuleId).toBe(publishVector.capsule_id)
    const expectedHash = new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(statement)))
    expect(bytesToHex(catalog.items[0]!.statementSha256)).toBe(bytesToHex(expectedHash))
  })

  it('refuses tampered blob metadata at the unchanged receiver', async () => {
    const statement = buildPublishStatement(fixtureInput())
    const signature = await signPublishStatement(PRIVATE_KEYSET, statement)
    const page = await signedPageFor(statement, signature)
    const item = (page.items as Record<string, unknown>[])[0]!
    const blobs = item.blobs as Record<string, unknown>[]
    const photo = blobs[2]!
    photo.ciphertext_size = 304
    const session = new SessionStore()
    signIn(session)
    await expect(fetchIncoming(session, incomingTransport(page))).rejects.toMatchObject({ code: 'SYNC_REJECTED' })
  })

  it('accepts the full signed64 timestamp range at the real receiver (absent = 0)', async () => {
    // T283: root field 7 is read as signed64; absent (protobuf default 0) is
    // admitted. This is reader conformance, not a client clock/backend policy.
    const timestamps = [
      -(2n ** 63n),
      -1n,
      0n,
      1n,
      BigInt(publishVector.created_at_epoch_seconds),
      2n ** 32n,
      (2n ** 63n) - 1n,
    ]
    for (const timestamp of timestamps) {
      const statement = buildPublishStatement(fixtureInput(timestamp))
      const signature = await signPublishStatement(PRIVATE_KEYSET, statement)
      const page = await signedPageFor(statement, signature)
      const session = new SessionStore()
      signIn(session)
      const catalog = await fetchIncoming(session, incomingTransport(page))
      expect(catalog.items).toHaveLength(1)
      expect(catalog.items[0]?.capsuleId).toBe(publishVector.capsule_id)
    }
  })
})
