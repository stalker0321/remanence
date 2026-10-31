import type { KeyCapabilities } from '../crypto/keyOwner'
import type { ArtifactKind } from '../protocol/context'
import { artifactAad } from '../protocol/context'
import type { IncomingCapsule } from '../sync/incoming'
import {
  CLAIMED_ENVELOPE_DOMAIN, addressCommitment, artifactAadV2, domainInput, envelopeContext, keyCommitment,
  parseEnvelopePlaintext,
} from './wire'

function same(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

/**
 * Opens a claimed first gift. Same checks as Android's admission: the envelope
 * context is rebuilt from signed values, and the key and number must match the
 * commitments the sender signed. Returns the owned plaintext and the keyset view.
 */
export async function openClaimedEnvelope(caps: KeyCapabilities, capsule: IncomingCapsule): Promise<{ owned: Uint8Array; keyset: Uint8Array }> {
  const pending = capsule.pending
  if (!pending) throw new Error('NOT_PENDING')
  const context = envelopeContext({
    capsuleId: capsule.capsuleId,
    deliveryTargetId: pending.deliveryTargetId,
    senderUserId: capsule.senderUserId,
    recipientUserId: capsule.recipientUserId,
    recipientKeyBundleId: capsule.recipientKeyBundleId,
    statementSha256: capsule.statementSha256,
    bindingSha256: pending.bindingSha256,
  })
  const owned = await caps.openEnvelope(domainInput(CLAIMED_ENVELOPE_DOMAIN, context), capsule.envelopeCiphertext)
  try {
    const parsed = parseEnvelopePlaintext(owned)
    if (!same(parsed.context, context)) throw new Error('ENVELOPE_REJECTED')
    if (!same(await keyCommitment(capsule.capsuleId, pending.deliveryTargetId, parsed.keyset), pending.keyCommitment)) throw new Error('ENVELOPE_REJECTED')
    if (!same(await addressCommitment(parsed.opening), pending.addressCommitment)) throw new Error('ENVELOPE_REJECTED')
    return { owned, keyset: parsed.keyset }
  } catch (error) {
    owned.fill(0)
    throw error
  }
}

/** The artifact AAD for either kind of incoming postcard. */
export function capsuleArtifactAad(capsule: IncomingCapsule, blobId: string, kind: ArtifactKind, ordinal: number): Uint8Array {
  if (capsule.pending) {
    return artifactAadV2({
      capsuleId: capsule.capsuleId, blobId, kind, ordinal,
      senderUserId: capsule.senderUserId, deliveryTargetId: capsule.pending.deliveryTargetId,
    })
  }
  return artifactAad({
    capsuleId: capsule.capsuleId, blobId, kind, ordinal,
    senderUserId: capsule.senderUserId, recipientUserId: capsule.recipientUserId,
  })
}
