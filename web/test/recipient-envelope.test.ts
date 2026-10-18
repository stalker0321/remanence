import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import publishVector from '../../protocol/fixtures/publish-statement-v1.json'
import hpkeVector from '../../protocol/fixtures/recipient-envelope-v1.json'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { openGrantedPostcard } from '../src/collage/open'
import { decryptArtifact } from '../src/crypto/artifact'
import { encryptArtifact } from '../src/crypto/artifactEncrypt'
import { generateCapsuleKeyset } from '../src/crypto/capsuleKeyset'
import { openRecipientEnvelope } from '../src/crypto/hpke'
import { sealRecipientEnvelope } from '../src/crypto/hpkeSeal'
import * as recipientEnvelopeModule from '../src/crypto/recipientEnvelope'
import {
  buildRecipientEnvelopePlaintext,
  type RecipientEnvelopePlaintextInput,
} from '../src/crypto/recipientEnvelope'
import { arrayBuffer, concat, hexToBytes, utf8 } from '../src/protocol/bytes'
import { artifactAad, recipientEnvelopeInfo } from '../src/protocol/context'
import { ProtocolError } from '../src/protocol/errors'
import { fieldBytes, fieldNumber, readMessage } from '../src/protocol/proto'
import { uuidToBytes } from '../src/protocol/uuid'
import { ScanGrant } from '../src/scan/grant'
import { SessionStore } from '../src/session/session'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import type { IncomingCapsule } from '../src/sync/incoming'
import { fitText } from './sheets'
import { keysetFromJson } from './keysetJson'

const PUBLIC_KEYSET = keysetFromJson(hpkeVector.fixed_public_keyset_json)
const PRIVATE_KEYSET = keysetFromJson(hpkeVector.fixed_private_keyset_json)
const STATEMENT_BYTES = hexToBytes(publishVector.expected_deterministic_hex)
const CONTENT_BLOB_ID = '00000000-0000-0000-0000-0000000000b2'
const CONTENT = { blobId: CONTENT_BLOB_ID, ciphertext: new Uint8Array([1]) }

const ID_STRINGS = {
  capsuleId: publishVector.capsule_id,
  senderUserId: publishVector.sender_user_id,
  recipientUserId: publishVector.recipient_user_id,
  senderKeyBundleId: publishVector.sender_key_bundle_id,
  recipientKeyBundleId: publishVector.recipient_key_bundle_id,
}
const ID_BYTES = {
  capsuleId: uuidToBytes(ID_STRINGS.capsuleId),
  senderUserId: uuidToBytes(ID_STRINGS.senderUserId),
  recipientUserId: uuidToBytes(ID_STRINGS.recipientUserId),
  senderKeyBundleId: uuidToBytes(ID_STRINGS.senderKeyBundleId),
  recipientKeyBundleId: uuidToBytes(ID_STRINGS.recipientKeyBundleId),
}
let statementHash: Uint8Array

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes)))
}

function infoFor(): Uint8Array {
  return recipientEnvelopeInfo({
    capsuleId: ID_STRINGS.capsuleId,
    senderUserId: ID_STRINGS.senderUserId,
    recipientUserId: ID_STRINGS.recipientUserId,
    recipientKeyBundleId: ID_STRINGS.recipientKeyBundleId,
  })
}

function build(overrides: Partial<RecipientEnvelopePlaintextInput> = {}, keyset = generateCapsuleKeyset()): Uint8Array {
  return buildRecipientEnvelopePlaintext({
    ...ID_BYTES,
    capsuleAeadKeyset: keyset,
    publishStatementSha256: statementHash,
    ...overrides,
  })
}

function matched(): { session: SessionStore; keys: MemoryKeys; grant: ScanGrant } {
  const session = new SessionStore()
  session.signIn({
    accountId: ID_STRINGS.recipientUserId,
    bundleId: ID_STRINGS.recipientKeyBundleId,
    handle: 'ada',
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  session.matchPostcard()
  return { session, keys: new MemoryKeys(), grant: ScanGrant.issue(ID_STRINGS.recipientUserId, ID_STRINGS.capsuleId) }
}

function capabilities(decrypt: KeyCapabilities['decryptArtifact']): KeyCapabilities {
  return {
    accountId: ID_STRINGS.recipientUserId,
    bundleId: ID_STRINGS.recipientKeyBundleId,
    openEnvelope: (info, ciphertext) => openRecipientEnvelope(PRIVATE_KEYSET, info, ciphertext),
    decryptArtifact: decrypt,
    sign: async () => {
      throw new Error('sign is unused by openGrantedPostcard')
    },
    lock: () => {},
  }
}

function capsuleFor(envelopeCiphertext: Uint8Array): IncomingCapsule {
  return {
    capsuleId: ID_STRINGS.capsuleId,
    senderUserId: ID_STRINGS.senderUserId,
    recipientUserId: ID_STRINGS.recipientUserId,
    senderKeyBundleId: ID_STRINGS.senderKeyBundleId,
    recipientKeyBundleId: ID_STRINGS.recipientKeyBundleId,
    envelopeCiphertext,
    statementSha256: statementHash,
    blobs: [],
  }
}

beforeAll(async () => {
  statementHash = await sha256(STATEMENT_BYTES)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('recipient envelope plaintext builder', () => {
  it('exports only the production builder (no hook/observer)', () => {
    expect(Object.keys(recipientEnvelopeModule)).toEqual(['buildRecipientEnvelopePlaintext'])
  })

  it('serializes the exact schema fields 1..8, deterministically', () => {
    const keyset = generateCapsuleKeyset()
    const keysetBefore = keyset.slice()
    const bytes = build({}, keyset)
    const fields = readMessage(bytes, [1, 2, 3, 4, 5, 6, 7, 8])
    expect(fieldNumber(fields, 1)).toBe(1)
    expect(fieldBytes(fields, 2)).toEqual(ID_BYTES.capsuleId)
    expect(fieldBytes(fields, 3)).toEqual(ID_BYTES.senderUserId)
    expect(fieldBytes(fields, 4)).toEqual(ID_BYTES.recipientUserId)
    expect(fieldBytes(fields, 5)).toEqual(ID_BYTES.senderKeyBundleId)
    expect(fieldBytes(fields, 6)).toEqual(ID_BYTES.recipientKeyBundleId)
    expect(fieldBytes(fields, 7)).toEqual(keyset)
    expect(fieldBytes(fields, 8)).toEqual(statementHash)
    expect(sameBytes(build({}, keyset), build({}, keyset))).toBe(true)
    expect(sameBytes(keyset, keysetBefore)).toBe(true) // caller keyset never mutated
    keyset.fill(0)
    keysetBefore.fill(0)
  })

  it('refuses bad ID/hash lengths and an empty keyset', () => {
    expect(() => build({ capsuleId: new Uint8Array(15) })).toThrow(ProtocolError)
    expect(() => build({ senderUserId: new Uint8Array(17) })).toThrow(ProtocolError)
    expect(() => build({ publishStatementSha256: new Uint8Array(31) })).toThrow(ProtocolError)
    expect(() => build({ publishStatementSha256: new Uint8Array(33) })).toThrow(ProtocolError)
    expect(() => build({ capsuleAeadKeyset: new Uint8Array(0) })).toThrow(ProtocolError)
  })

  it('copies the keyset exactly once into the final output and wipes a partial output on failure', () => {
    const keyset = generateCapsuleKeyset()
    const copies: Uint8Array[] = []
    const realSet = Uint8Array.prototype.set
    vi.spyOn(Uint8Array.prototype, 'set').mockImplementation((function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
      if (source === keyset) copies.push(this)
      return realSet.call(this, source, offset ?? 0)
    }) as unknown as typeof Uint8Array.prototype.set)
    const output = build({}, keyset)
    expect(copies.length).toBe(1)
    expect(copies[0]).toBe(output)
    vi.restoreAllMocks()

    let partial: Uint8Array | null = null
    vi.spyOn(Uint8Array.prototype, 'set').mockImplementation((function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
      if (source === keyset) {
        partial = this
        throw new Error('serialization-failure')
      }
      return realSet.call(this, source, offset ?? 0)
    }) as unknown as typeof Uint8Array.prototype.set)
    expect(() => build({}, keyset)).toThrow('serialization-failure')
    expect(partial).not.toBeNull()
    expect((partial as unknown as Uint8Array).every((value) => value === 0)).toBe(true)
    expect(keyset.some((value) => value !== 0)).toBe(true) // caller keyset intact
    output.fill(0)
    keyset.fill(0)
  })

  it('owns a copy of the keyset: mutating the input after build does not change the output', () => {
    const keyset = generateCapsuleKeyset()
    const output = build({}, keyset)
    const before = output.slice()
    keyset.fill(0xee)
    expect(sameBytes(output, before)).toBe(true)
    output.fill(0)
    before.fill(0)
    keyset.fill(0)
  })

  it('uses the RAW deterministic statement hash, not the postmark/publish/v1-prefixed input', async () => {
    const raw = await sha256(STATEMENT_BYTES)
    const prefixed = await sha256(concat([utf8('postmark/publish/v1'), STATEMENT_BYTES]))
    expect(sameBytes(raw, prefixed)).toBe(false)
    const keyset = generateCapsuleKeyset()
    const output = build({ publishStatementSha256: raw }, keyset)
    const fields = readMessage(output, [1, 2, 3, 4, 5, 6, 7, 8])
    expect(fieldBytes(fields, 8)).toEqual(raw)
    output.fill(0)
    keyset.fill(0)
  })
})

describe('real receiver decoder boundary (unit-only fixture grant/session)', () => {
  it('runs private parseEnvelope + all six bindings, then reaches the decrypt sentinel with the exact keyset', async () => {
    const keyset = generateCapsuleKeyset()
    const plaintext = build({}, keyset)
    const envelope = await sealRecipientEnvelope(PUBLIC_KEYSET, infoFor(), plaintext)
    const { session, keys, grant } = matched()
    let decodedKeyset: Uint8Array | null = null
    let decryptCalls = 0
    keys.hold(session.getSnapshot(), capabilities((decoded) => {
      decryptCalls += 1
      decodedKeyset = decoded.slice()
      throw new ProtocolError('SENTINEL_BEFORE_DECRYPT')
    }))

    await expect(openGrantedPostcard(session, keys, grant, capsuleFor(envelope), CONTENT, [], fitText)).rejects.toMatchObject({
      code: 'SENTINEL_BEFORE_DECRYPT',
    })
    expect(decryptCalls).toBe(1)
    expect(decodedKeyset).not.toBeNull()
    expect(sameBytes(decodedKeyset!, keyset)).toBe(true)

    // The decoded keyset is a real usable AEAD key.
    const aad = artifactAad({
      capsuleId: ID_STRINGS.capsuleId,
      blobId: CONTENT_BLOB_ID,
      kind: 'PHOTO',
      ordinal: 0,
      senderUserId: ID_STRINGS.senderUserId,
      recipientUserId: ID_STRINGS.recipientUserId,
    })
    const plaintextForKeyset = utf8('decoded keyset is usable')
    const ciphertext = await encryptArtifact(decodedKeyset!, aad, plaintextForKeyset, 'PHOTO')
    expect(sameBytes(await decryptArtifact(decodedKeyset!, aad, ciphertext, 'PHOTO'), plaintextForKeyset)).toBe(true)

    decodedKeyset!.fill(0)
    keyset.fill(0)
  })

  it('stops each envelope field mismatch at the real binding with ZERO decrypt calls', async () => {
    const wrongUuid = uuidToBytes('99999999-9999-4999-8999-999999999999')
    const wrongHash = new Uint8Array(32).fill(0xab)
    const cases: Array<Partial<RecipientEnvelopePlaintextInput>> = [
      { capsuleId: wrongUuid },
      { senderUserId: wrongUuid },
      { recipientUserId: wrongUuid },
      { senderKeyBundleId: wrongUuid },
      { recipientKeyBundleId: wrongUuid },
      { publishStatementSha256: wrongHash },
    ]
    for (const override of cases) {
      const keyset = generateCapsuleKeyset()
      const plaintext = build(override, keyset)
      const envelope = await sealRecipientEnvelope(PUBLIC_KEYSET, infoFor(), plaintext)
      const { session, keys, grant } = matched()
      let decryptCalls = 0
      keys.hold(session.getSnapshot(), capabilities(() => {
        decryptCalls += 1
        throw new ProtocolError('UNEXPECTED_DECRYPT')
      }))
      await expect(openGrantedPostcard(session, keys, grant, capsuleFor(envelope), CONTENT, [], fitText)).rejects.toMatchObject({
        code: 'INTEGRITY',
      })
      expect(decryptCalls).toBe(0)
      keyset.fill(0)
    }
  })
})
