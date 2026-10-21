import { openIdentity, unb64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { decryptArtifact as decryptArtifactBytes } from './artifact'
import { openHpke } from './hpke'
import { DhkemX25519HkdfSha256 } from '@hpke/core'
import type { ArtifactKind } from '../protocol/context'
import { ProtocolError } from '../protocol/errors'
import { nameUuidFromBytes } from '../protocol/uuid'
import { signWithKey } from './publish'

export interface RecoveryReply {
  record: {
    version: string
    generation: number
    account_id: string
    bundle_id: string
    credential_id: string
    salt: string
    package_sealed: string
    prf_ark: string
    code_ark: string
    signature: string
  }
  encryption_public_keyset: string
  signing_public_keyset: string
  login?: {
    user: { user_id: string }
    active_key_bundle: { key_bundle_id: string }
  }
}

export interface KeyCapabilities {
  readonly accountId: string
  readonly bundleId: string
  openEnvelope(info: Uint8Array, ciphertext: Uint8Array): Promise<Uint8Array>
  decryptArtifact(keyset: Uint8Array, aad: Uint8Array, ciphertext: Uint8Array, kind: ArtifactKind): Promise<Uint8Array>
  sign(statement: Uint8Array): Promise<Uint8Array>
  lock(): void
}

const kem = new DhkemX25519HkdfSha256()

/**
 * In-memory identity. Callers receive operations, not key bytes.
 * Reload requires a new unlock. lock() drops the closure keys.
 */
export async function unlockAccount(
  reply: RecoveryReply,
  secret: Uint8Array,
  useCode = false,
): Promise<KeyCapabilities> {
  const opened = await openIdentity(reply, secret, useCode).catch((error: unknown) => {
    throw asProtocol(error)
  })
  const bound = nameUuidFromBytes(unb64(reply.encryption_public_keyset)) === opened.bundleId
  if (!bound) {
    opened.signingSeed.fill(0)
    opened.encryptionSeed.fill(0)
    throw new ProtocolError('BUNDLE_MISMATCH')
  }
  const raw = new Uint8Array(opened.encryptionSeed)
  opened.encryptionSeed.fill(0)
  opened.signingSeed.fill(0)
  let recipientKey: CryptoKey
  try { recipientKey = await kem.importKey('raw', raw.buffer, false) }
  finally { raw.fill(0) }
  let alive = true
  const signing = opened.signing
  const signingKeyId = opened.signingKeyId
  const encryptionKeyId = opened.encryptionKeyId
  const finish = <T extends Uint8Array>(value: T): T => {
    if (!alive) {
      value.fill(0)
      throw new ProtocolError('LOCKED')
    }
    return value
  }
  return {
    accountId: opened.accountId,
    bundleId: opened.bundleId,
    async openEnvelope(info, ciphertext) {
      if (!alive) throw new ProtocolError('LOCKED')
      return finish(await openHpke(recipientKey, encryptionKeyId, info, ciphertext))
    },
    async decryptArtifact(keyset, aad, ciphertext, kind) {
      if (!alive) throw new ProtocolError('LOCKED')
      return finish(await decryptArtifactBytes(keyset, aad, ciphertext, kind))
    },
    async sign(statement) {
      if (!alive) throw new ProtocolError('LOCKED')
      return finish(await signWithKey(signing, signingKeyId, statement))
    },
    lock() {
      alive = false
    },
  }
}

function asProtocol(error: unknown): ProtocolError {
  if (error instanceof ProtocolError) return error
  if (error instanceof Error && /^[A-Z0-9_]+$/.test(error.message)) return new ProtocolError(error.message)
  return new ProtocolError('IDENTITY_REJECTED')
}
