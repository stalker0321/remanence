import { arrayBuffer, concat, utf8 } from '../protocol/bytes'
import { protoBytes, protoVarint, zigzag32 } from '../protocol/proto'
import { uuidToBytes } from '../protocol/uuid'
import type { IncomingBlob } from '../sync/incoming'

/** Hand-rolled delivery_v2 wire, field order as in delivery_v2.proto. */
const KIND = { 1: 'RECOGNITION_MANIFEST', 2: 'CONTENT_MANIFEST', 3: 'PHOTO' } as const
const KIND_VALUE = { RECOGNITION_MANIFEST: 1, CONTENT_MANIFEST: 2, PHOTO: 3 } as const

export function domainInput(name: string, message: Uint8Array): Uint8Array {
  return concat([utf8(name), Uint8Array.of(0), message])
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes)))
}

export const statementHash = sha256

export function uuidText(bytes: Uint8Array): string {
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

interface RawField { field: number; value: number | Uint8Array }

/** Tolerates repeated fields, which `readMessage` refuses on purpose. */
function fieldsOf(bytes: Uint8Array): RawField[] {
  const fields: RawField[] = []
  let pos = 0
  const varint = (): number => {
    let value = 0
    let factor = 1
    for (let i = 0; i < 9; i++) {
      if (pos >= bytes.length) throw new Error('PROTO_REJECTED')
      const piece = bytes[pos++] as number
      value += (piece & 127) * factor
      if ((piece & 128) === 0) {
        if (!Number.isSafeInteger(value)) throw new Error('PROTO_REJECTED')
        return value
      }
      factor *= 128
    }
    throw new Error('PROTO_REJECTED')
  }
  while (pos < bytes.length) {
    const tag = varint()
    const field = Math.floor(tag / 8)
    if (tag % 8 === 0) fields.push({ field, value: varint() })
    else if (tag % 8 === 2) {
      const length = varint()
      if (length > bytes.length - pos) throw new Error('PROTO_REJECTED')
      fields.push({ field, value: bytes.slice(pos, pos + length) })
      pos += length
    } else throw new Error('PROTO_REJECTED')
  }
  return fields
}

function bytesOf(fields: RawField[], field: number): Uint8Array {
  const found = fields.find((item) => item.field === field)?.value
  if (!(found instanceof Uint8Array)) throw new Error('PROTO_REJECTED')
  return found
}

function numberOf(fields: RawField[], field: number, fallback = 0): number {
  const found = fields.find((item) => item.field === field)?.value
  if (found === undefined) return fallback
  if (typeof found !== 'number') throw new Error('PROTO_REJECTED')
  return found
}

export interface PendingStatement {
  capsuleId: string
  senderUserId: string
  senderKeyBundleId: string
  deliveryTargetId: string
  addressCommitment: Uint8Array
  keyCommitment: Uint8Array
  serviceKeyId: string
  claimExpiresAt: number
  blobs: IncomingBlob[]
}

export function parseStatement(raw: Uint8Array): PendingStatement {
  const fields = fieldsOf(raw)
  if (numberOf(fields, 1) !== 2 || numberOf(fields, 10) !== 1) throw new Error('PROTO_REJECTED')
  const blobs: IncomingBlob[] = fields.filter((item) => item.field === 12).map((item) => {
    if (!(item.value instanceof Uint8Array)) throw new Error('PROTO_REJECTED')
    const artifact = fieldsOf(item.value)
    const kind = KIND[numberOf(artifact, 2) as keyof typeof KIND]
    if (!kind) throw new Error('PROTO_REJECTED')
    const zig = numberOf(artifact, 3)
    const ordinal = (zig >>> 1) ^ -(zig & 1)
    return {
      blobId: uuidText(bytesOf(artifact, 1)),
      kind,
      ordinal: kind === 'PHOTO' ? ordinal : null,
      ciphertextSize: numberOf(artifact, 4),
      ciphertextSha256: [...bytesOf(artifact, 5)].map((byte) => byte.toString(16).padStart(2, '0')).join(''),
    }
  })
  return {
    capsuleId: uuidText(bytesOf(fields, 2)),
    senderUserId: uuidText(bytesOf(fields, 3)),
    senderKeyBundleId: uuidText(bytesOf(fields, 4)),
    deliveryTargetId: uuidText(bytesOf(fields, 5)),
    addressCommitment: bytesOf(fields, 6),
    keyCommitment: bytesOf(fields, 7),
    claimExpiresAt: numberOf(fields, 9),
    serviceKeyId: uuidText(bytesOf(fields, 11)),
    blobs,
  }
}

export interface ClaimBinding {
  capsuleId: string
  deliveryTargetId: string
  statementSha256: Uint8Array
  addressCommitment: Uint8Array
  recipientUserId: string
  recipientKeyBundleId: string
  recipientKeysetSha256: Uint8Array
  claimId: string
  serviceKeyId: string
}

export function parseBinding(raw: Uint8Array): ClaimBinding {
  const fields = fieldsOf(raw)
  if (numberOf(fields, 1) !== 1 || numberOf(fields, 2) !== 2) throw new Error('PROTO_REJECTED')
  return {
    capsuleId: uuidText(bytesOf(fields, 3)),
    deliveryTargetId: uuidText(bytesOf(fields, 4)),
    statementSha256: bytesOf(fields, 5),
    addressCommitment: bytesOf(fields, 6),
    recipientUserId: uuidText(bytesOf(fields, 7)),
    recipientKeyBundleId: uuidText(bytesOf(fields, 8)),
    recipientKeysetSha256: bytesOf(fields, 9),
    claimId: uuidText(bytesOf(fields, 10)),
    serviceKeyId: uuidText(bytesOf(fields, 13)),
  }
}

export interface ChallengeContext { challengeId: Uint8Array; capsuleId: string; deliveryTargetId: string; recipientUserId: string; recipientKeyBundleId: string; sessionId: Uint8Array; nonceSha256: Uint8Array }

export function parseChallenge(raw: Uint8Array): ChallengeContext {
  const fields = fieldsOf(raw)
  return {
    challengeId: bytesOf(fields, 1),
    capsuleId: uuidText(bytesOf(fields, 2)),
    deliveryTargetId: uuidText(bytesOf(fields, 3)),
    recipientUserId: uuidText(bytesOf(fields, 4)),
    recipientKeyBundleId: uuidText(bytesOf(fields, 5)),
    sessionId: bytesOf(fields, 6),
    nonceSha256: bytesOf(fields, 7),
  }
}

export function claimIntent(input: {
  operationId: string; capsuleId: string; deliveryTargetId: string; statementSha256: Uint8Array; proofId: string
  challenge: ChallengeContext; nonce: Uint8Array; nonceSha256: Uint8Array
}): Uint8Array {
  return concat([
    protoVarint(1, 1),
    protoBytes(2, uuidToBytes(input.operationId)),
    protoBytes(3, uuidToBytes(input.capsuleId)),
    protoBytes(4, uuidToBytes(input.deliveryTargetId)),
    protoBytes(5, input.statementSha256),
    protoBytes(6, uuidToBytes(input.proofId)),
    protoBytes(7, input.challenge.challengeId),
    protoBytes(8, uuidToBytes(input.challenge.recipientUserId)),
    protoBytes(9, uuidToBytes(input.challenge.recipientKeyBundleId)),
    protoBytes(10, input.challenge.sessionId),
    protoBytes(11, input.nonce),
    protoBytes(12, input.nonceSha256),
    // ADR-020: proving the number links it, so later postcards skip all of this.
    protoVarint(13, 1),
  ])
}

export function envelopeContext(input: {
  capsuleId: string; deliveryTargetId: string; senderUserId: string; recipientUserId: string; recipientKeyBundleId: string
  statementSha256: Uint8Array; bindingSha256: Uint8Array
}): Uint8Array {
  return concat([
    protoVarint(1, 2),
    protoBytes(2, uuidToBytes(input.capsuleId)),
    protoBytes(3, uuidToBytes(input.deliveryTargetId)),
    protoBytes(4, uuidToBytes(input.senderUserId)),
    protoBytes(5, uuidToBytes(input.recipientUserId)),
    protoBytes(6, uuidToBytes(input.recipientKeyBundleId)),
    protoBytes(7, input.statementSha256),
    protoBytes(8, input.bindingSha256),
  ])
}

export function envelopePlaintext(context: Uint8Array, keyset: Uint8Array, opening: Uint8Array): Uint8Array {
  return concat([protoBytes(1, context), protoBytes(2, keyset), protoBytes(3, opening)])
}

export function parseEnvelopePlaintext(raw: Uint8Array): { context: Uint8Array; keyset: Uint8Array; opening: Uint8Array } {
  const fields = fieldsOf(raw)
  return { context: bytesOf(fields, 1), keyset: bytesOf(fields, 2), opening: bytesOf(fields, 3) }
}

export const CLAIMED_ENVELOPE_DOMAIN = 'postmark/claimed-envelope/v2'
export const CLAIM_CHALLENGE_DOMAIN = 'postmark/claim-challenge/v1'
export const CLAIM_INTENT_DOMAIN = 'postmark/claim-intent/v2'
export const CLAIM_BINDING_DOMAIN = 'postmark/claim-binding/v1'
export const PENDING_PUBLISH_DOMAIN = 'postmark/pending-publish/v2'
const DELIVERY_KEY_DOMAIN = 'postmark/delivery-key/v1'
const DELIVERY_ADDRESS_DOMAIN = 'postmark/delivery-address/v1'
const ARTIFACT_V2_DOMAIN = 'postmark/artifact/v2'

export function keyCommitment(capsuleId: string, deliveryTargetId: string, keyset: Uint8Array): Promise<Uint8Array> {
  return sha256(domainInput(DELIVERY_KEY_DOMAIN, concat([
    protoVarint(1, 1),
    protoBytes(2, uuidToBytes(capsuleId)),
    protoBytes(3, uuidToBytes(deliveryTargetId)),
    protoBytes(4, keyset),
  ])))
}

export function addressCommitment(opening: Uint8Array): Promise<Uint8Array> {
  return sha256(domainInput(DELIVERY_ADDRESS_DOMAIN, opening))
}

export function artifactAadV2(input: {
  capsuleId: string; blobId: string; kind: keyof typeof KIND_VALUE; ordinal: number; senderUserId: string; deliveryTargetId: string
}): Uint8Array {
  return domainInput(ARTIFACT_V2_DOMAIN, concat([
    protoVarint(1, 2),
    protoBytes(2, uuidToBytes(input.capsuleId)),
    protoBytes(3, uuidToBytes(input.blobId)),
    protoVarint(4, KIND_VALUE[input.kind]),
    protoVarint(5, zigzag32(input.ordinal)),
    protoBytes(6, uuidToBytes(input.senderUserId)),
    protoBytes(7, uuidToBytes(input.deliveryTargetId)),
  ]))
}
