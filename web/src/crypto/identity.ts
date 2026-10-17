import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { arrayBuffer, concat, utf8 } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'
import { protoBytes, protoVarint } from '../protocol/proto'
import { nameUuidFromBytes } from '../protocol/uuid'
import { parseEd25519PublicKeyset, parseHpkePublicKeyset } from './tink'

export const KEY_BUNDLE_SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
const HPKE_PUBLIC_URL = 'type.googleapis.com/google.crypto.tink.HpkePublicKey'
const ED25519_PUBLIC_URL = 'type.googleapis.com/google.crypto.tink.Ed25519PublicKey'

export interface PublicKeyBundle {
  key_bundle_id: string
  suite: typeof KEY_BUNDLE_SUITE
  protocol_version: 1
  encryption_public_keyset: string
  signing_public_keyset: string
}

/**
 * One HPKE X25519 key and one Ed25519 key, encoded as the Tink public keysets
 * the register endpoint already accepts. The private seeds stay in this object.
 * Reload does not restore them. wipe() drops the copies this object owns.
 * WebCrypto's own key objects cannot be zeroed.
 */
export class LocalIdentity {
  readonly bundleId: string
  readonly encryptionKeyId: number
  readonly signingKeyId: number
  #encryptionSeed: Uint8Array
  #signingSeed: Uint8Array
  #encryptionPublic: Uint8Array
  #signingPublic: Uint8Array
  #wiped = false

  constructor(parts: {
    bundleId: string
    encryptionKeyId: number
    signingKeyId: number
    encryptionSeed: Uint8Array
    signingSeed: Uint8Array
    encryptionPublic: Uint8Array
    signingPublic: Uint8Array
  }) {
    this.bundleId = parts.bundleId
    this.encryptionKeyId = parts.encryptionKeyId
    this.signingKeyId = parts.signingKeyId
    this.#encryptionSeed = parts.encryptionSeed
    this.#signingSeed = parts.signingSeed
    this.#encryptionPublic = parts.encryptionPublic
    this.#signingPublic = parts.signingPublic
  }

  get wiped(): boolean {
    return this.#wiped
  }

  /** The bundle id is the name UUID of the encryption public keyset bytes. */
  bound(): boolean {
    this.#live()
    return nameUuidFromBytes(this.#encryptionPublic) === this.bundleId
  }

  publicBundle(): PublicKeyBundle {
    this.#live()
    return {
      key_bundle_id: this.bundleId,
      suite: KEY_BUNDLE_SUITE,
      protocol_version: 1,
      encryption_public_keyset: b64(this.#encryptionPublic),
      signing_public_keyset: b64(this.#signingPublic),
    }
  }

  /** Copy for a later seal. The caller owns the copy and must wipe it. */
  copySeeds(): { encryption: Uint8Array; signing: Uint8Array } {
    this.#live()
    return {
      encryption: this.#encryptionSeed.slice(),
      signing: this.#signingSeed.slice(),
    }
  }

  wipe(): void {
    this.#wiped = true
    this.#encryptionSeed.fill(0)
    this.#signingSeed.fill(0)
  }

  #live(): void {
    if (this.#wiped) throw new ProtocolError('LOCKED')
  }
}

export async function generateLocalIdentity(): Promise<LocalIdentity> {
  const encryption = await okp('X25519')
  const signing = await okp('Ed25519')
  try {
    const encryptionKeyId = freshKeyId()
    const signingKeyId = freshKeyId()
    const encryptionValue = hpkePublic(encryption.raw)
    const signingValue = edPublic(signing.raw)
    const encryptionPublic = keyset(encryptionValue, HPKE_PUBLIC_URL, 3, encryptionKeyId)
    const signingPublic = keyset(signingValue, ED25519_PUBLIC_URL, 3, signingKeyId)
    const hpke = parseHpkePublicKeyset(encryptionPublic)
    const ed = parseEd25519PublicKeyset(signingPublic)
    if (hpke.keyId !== encryptionKeyId || ed.keyId !== signingKeyId) throw new ProtocolError('INVALID_KEY')
    await correspond(signing.key, ed.raw, encryption.key, hpke.raw)
    return new LocalIdentity({
      bundleId: nameUuidFromBytes(encryptionPublic),
      encryptionKeyId,
      signingKeyId,
      encryptionSeed: encryption.seed,
      signingSeed: signing.seed,
      encryptionPublic,
      signingPublic,
    })
  } catch (error) {
    encryption.seed.fill(0)
    signing.seed.fill(0)
    throw error
  }
}

async function correspond(
  signing: CryptoKey,
  signingRaw: Uint8Array,
  encryption: CryptoKey,
  encryptionRaw: Uint8Array,
): Promise<void> {
  const challenge = crypto.getRandomValues(new Uint8Array(32))
  const verifier = await crypto.subtle.importKey('raw', arrayBuffer(signingRaw), 'Ed25519', false, ['verify'])
  const signature = await crypto.subtle.sign('Ed25519', signing, arrayBuffer(challenge))
  const signed = await crypto.subtle.verify('Ed25519', verifier, signature, arrayBuffer(challenge))
  if (!signed) throw new ProtocolError('INVALID_KEY')
  const expected = await crypto.subtle.importKey('raw', arrayBuffer(encryptionRaw), 'X25519', false, [])
  const ephemeral = await crypto.subtle.generateKey('X25519', false, ['deriveBits']) as CryptoKeyPair
  let left: Uint8Array | null = null
  let right: Uint8Array | null = null
  try {
    left = new Uint8Array(await crypto.subtle.deriveBits(
      { name: 'X25519', public: ephemeral.publicKey },
      encryption,
      256,
    ))
    right = new Uint8Array(await crypto.subtle.deriveBits(
      { name: 'X25519', public: expected },
      ephemeral.privateKey,
      256,
    ))
    if (left.length !== right.length || left.some((byte, index) => byte !== right?.[index])) {
      throw new ProtocolError('INVALID_KEY')
    }
  } finally {
    left?.fill(0)
    right?.fill(0)
  }
}

async function okp(algorithm: 'X25519' | 'Ed25519'): Promise<{ seed: Uint8Array; raw: Uint8Array; key: CryptoKey }> {
  const usages: KeyUsage[] = algorithm === 'Ed25519' ? ['sign', 'verify'] : ['deriveBits']
  const pair = await crypto.subtle.generateKey(algorithm, true, usages) as CryptoKeyPair
  const priv = await crypto.subtle.exportKey('jwk', pair.privateKey)
  const pub = await crypto.subtle.exportKey('raw', pair.publicKey)
  if (!priv.d || pub.byteLength !== 32) throw new ProtocolError('INVALID_KEY')
  const seed = b64url(priv.d)
  if (seed.length !== 32) {
    seed.fill(0)
    throw new ProtocolError('INVALID_KEY')
  }
  return { seed, raw: new Uint8Array(pub), key: pair.privateKey }
}

function freshKeyId(): number {
  const bytes = new Uint8Array(4)
  crypto.getRandomValues(bytes)
  const value = new DataView(bytes.buffer).getUint32(0)
  return value === 0 ? 1 : value
}

function hpkePublic(raw: Uint8Array): Uint8Array {
  const params = concat([protoVarint(1, 1), protoVarint(2, 1), protoVarint(3, 2)])
  return concat([protoBytes(2, params), protoBytes(3, raw)])
}

function edPublic(raw: Uint8Array): Uint8Array {
  return protoBytes(2, raw)
}

function keyset(value: Uint8Array, typeUrl: string, material: number, keyId: number): Uint8Array {
  const data = concat([
    protoBytes(1, utf8(typeUrl)),
    protoBytes(2, value),
    protoVarint(3, material),
  ])
  const key = concat([
    protoBytes(1, data),
    protoVarint(2, 1),
    protoVarint(3, keyId),
    protoVarint(4, 1),
  ])
  return concat([protoVarint(1, keyId), protoBytes(2, key)])
}

function b64url(value: string): Uint8Array {
  const padded = value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - (value.length % 4)) % 4)
  const binary = atob(padded)
  const out = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) out[index] = binary.charCodeAt(index)
  return out
}
