import { concat, utf8 } from './bytes'
import { ProtocolError } from './errors'
import { protoBytes, protoVarint, zigzag32 } from './proto'
import { uuidToBytes } from './uuid'

export const ARTIFACT_AAD_PREFIX = 'postmark/artifact/v1'
export const ENVELOPE_INFO_PREFIX = 'postmark/envelope/v1'
export const PUBLISH_PREFIX = 'postmark/publish/v1'

export const artifactKinds = ['RECOGNITION_MANIFEST', 'CONTENT_MANIFEST', 'PHOTO'] as const
export type ArtifactKind = (typeof artifactKinds)[number]

const kindValue: Record<ArtifactKind, number> = {
  RECOGNITION_MANIFEST: 1,
  CONTENT_MANIFEST: 2,
  PHOTO: 3,
}

export interface ArtifactContext {
  capsuleId: string
  blobId: string
  kind: ArtifactKind
  ordinal: number
  senderUserId: string
  recipientUserId: string
}

export interface EnvelopeContext {
  capsuleId: string
  senderUserId: string
  recipientUserId: string
  recipientKeyBundleId: string
}

export function artifactAad(input: ArtifactContext): Uint8Array {
  requireOrdinal(input.kind, input.ordinal)
  const body = concat([
    protoVarint(1, 1),
    protoBytes(2, uuidToBytes(input.capsuleId)),
    protoBytes(3, uuidToBytes(input.blobId)),
    protoVarint(4, kindValue[input.kind]),
    protoVarint(5, zigzag32(input.ordinal)),
    protoBytes(6, uuidToBytes(input.senderUserId)),
    protoBytes(7, uuidToBytes(input.recipientUserId)),
  ])
  return domain(ARTIFACT_AAD_PREFIX, body)
}

export function recipientEnvelopeInfo(input: EnvelopeContext): Uint8Array {
  const body = concat([
    protoVarint(1, 1),
    protoBytes(2, uuidToBytes(input.capsuleId)),
    protoBytes(3, uuidToBytes(input.senderUserId)),
    protoBytes(4, uuidToBytes(input.recipientUserId)),
    protoBytes(5, uuidToBytes(input.recipientKeyBundleId)),
  ])
  return domain(ENVELOPE_INFO_PREFIX, body)
}

export function publishInput(statement: Uint8Array): Uint8Array {
  if (statement.length === 0 || statement.length > 16384) throw new ProtocolError('OVERSIZED')
  return concat([utf8(PUBLISH_PREFIX), statement])
}

function requireOrdinal(kind: ArtifactKind, ordinal: number): void {
  if (!artifactKinds.includes(kind)) throw new ProtocolError('UNKNOWN_KIND')
  const valid = kind === 'PHOTO' ? ordinal >= 0 && ordinal <= 4 : ordinal === -1
  if (!valid) throw new ProtocolError('INVALID_ORDINAL')
}

function domain(prefix: string, body: Uint8Array): Uint8Array {
  const head = utf8(prefix)
  const out = new Uint8Array(head.length + 1 + body.length)
  out.set(head, 0)
  out[head.length] = 0
  out.set(body, head.length + 1)
  return out
}
