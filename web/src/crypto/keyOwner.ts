import { openIdentity, unb64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { decryptArtifact as decryptArtifactBytes } from './artifact'
import { openHpke } from './hpke'
import { DhkemX25519HkdfSha256 } from '@hpke/core'
import type { ArtifactKind } from '../protocol/context'
import { ProtocolError } from '../protocol/errors'
import { nameUuidFromBytes } from '../protocol/uuid'
import { signWithKey } from './publish'
import { arrayBuffer, concat, utf8, writeUint32 } from '../protocol/bytes'

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
  /** ADR-020: only ever signs under the claim-intent domain. */
  signClaimIntent?(intent: Uint8Array): Promise<Uint8Array>
  /** The same non-extractable keys, for keeping this device signed in. */
  deviceKeys?(): DeviceKeys
  lock(): void
}

/**
 * What stays on this device between visits. Both keys are non-extractable
 * WebCrypto keys: the browser can use them, no script can read their bytes.
 */
export interface DeviceKeys {
  accountId: string
  bundleId: string
  recipientKey: CryptoKey
  signing: CryptoKey
  signingKeyId: number
  encryptionKeyId: number
}

const kem = new DhkemX25519HkdfSha256()

/**
 * Opens the identity from the recovery record. Callers receive operations,
 * not key bytes; the keys themselves are non-extractable.
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
  return deviceCapabilities({
    accountId: opened.accountId, bundleId: opened.bundleId, recipientKey,
    signing: opened.signing, signingKeyId: opened.signingKeyId, encryptionKeyId: opened.encryptionKeyId,
  })
}

/** Operations over held keys; callers never receive key bytes. lock() ends them. */
export function deviceCapabilities(held: DeviceKeys): KeyCapabilities {
  let alive = true
  const { recipientKey, signing, signingKeyId, encryptionKeyId } = held
  const finish = <T extends Uint8Array>(value: T): T => {
    if (!alive) {
      value.fill(0)
      throw new ProtocolError('LOCKED')
    }
    return value
  }
  return {
    accountId: held.accountId,
    bundleId: held.bundleId,
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
    async signClaimIntent(intent) {
      if (!alive) throw new ProtocolError('LOCKED')
      const input = new Uint8Array(await crypto.subtle.sign('Ed25519', signing, arrayBuffer(concat([utf8('postmark/claim-intent/v2'), Uint8Array.of(0), intent]))))
      if (input.length !== 64) throw new ProtocolError('BAD_SIGNATURE')
      return finish(concat([new Uint8Array([1]), writeUint32(signingKeyId), input]))
    },
    deviceKeys() {
      if (!alive) throw new ProtocolError('LOCKED')
      return { ...held }
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
