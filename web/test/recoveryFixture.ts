import { Aes256Gcm, CipherSuite, DhkemX25519HkdfSha256, HkdfSha256 } from '@hpke/core'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { arrayBuffer, concat, utf8, writeUint32 } from '../src/protocol/bytes'
import { protoBytes, protoVarint } from '../src/protocol/proto'
import { nameUuidFromBytes } from '../src/protocol/uuid'
import type { RecoveryReply } from '../src/crypto/keyOwner'

const VERSION = 'REMANENCE-RECOVERY-V1'
const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Aes256Gcm(),
})

export interface SyntheticRecovery {
  reply: RecoveryReply
  prf: Uint8Array
  code: Uint8Array
  encryptionKeyId: number
  publicKey: CryptoKey
  /** JS producer. Not an Android AccountRecoveryCrypto fixture. */
  producer: 'js-webcrypto'
}

export async function syntheticRecovery(bindBundle = true): Promise<SyntheticRecovery> {
  const encryption = await okp('X25519')
  const signing = await okp('Ed25519')
  const encryptionKeyId = 1001
  const signingKeyId = 1002
  const encryptionPublic = hpkePublic(encryption.raw)
  const encryptionPrivate = hpkePrivate(encryption.raw, encryption.seed)
  const signingPublic = edPublic(signing.raw)
  const signingPrivate = edPrivate(signing.seed, signingPublic)
  const encryptionPublicKeyset = keyset(encryptionPublic, 'type.googleapis.com/google.crypto.tink.HpkePublicKey', 3, encryptionKeyId)
  const signingPublicKeyset = keyset(signingPublic, 'type.googleapis.com/google.crypto.tink.Ed25519PublicKey', 3, signingKeyId)
  const accountId = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
  const bundleId = bindBundle
    ? nameUuidFromBytes(encryptionPublicKeyset)
    : '11111111-2222-4333-8444-555555555555'
  const credential = b64(new Uint8Array(16).fill(0x11))
  const saltBytes = new Uint8Array(32).fill(0x22)
  const salt = b64(saltBytes)
  const prf = new Uint8Array(32).fill(0x33)
  const code = new Uint8Array(32).fill(0x44)
  const ark = new Uint8Array(32).fill(0x55)
  const ctx = [VERSION, accountId, bundleId, 1, credential, salt].join('\n')
  const encKeyset = keyset(encryptionPrivate, 'type.googleapis.com/google.crypto.tink.HpkePrivateKey', 2, encryptionKeyId)
  const signKeyset = keyset(signingPrivate, 'type.googleapis.com/google.crypto.tink.Ed25519PrivateKey', 2, signingKeyId)
  const packagePlain = concat([utf8('RPK1'), writeUint32(encKeyset.length), encKeyset, writeUint32(signKeyset.length), signKeyset])
  const pack = await seal(ark, `${ctx}\npackage`, packagePlain)
  const prfKey = await hkdf(prf, saltBytes, `${ctx}\nprf-ark`)
  const codeKey = await hkdf(code, saltBytes, `${ctx}\ncode-ark`)
  const prfArk = await seal(prfKey, `${ctx}\nprf-ark`, ark)
  const codeArk = await seal(codeKey, `${ctx}\ncode-ark`, ark)
  prfKey.fill(0)
  codeKey.fill(0)
  const manifest = concat([
    utf8(`${ctx}\nmanifest`),
    await sha(pack),
    await sha(prfArk),
    await sha(codeArk),
  ])
  const rawSig = new Uint8Array(await crypto.subtle.sign('Ed25519', signing.key, arrayBuffer(manifest)))
  const signature = concat([new Uint8Array([1]), writeUint32(signingKeyId), rawSig])
  const kem = new DhkemX25519HkdfSha256()
  const publicKey = await kem.importKey('raw', arrayBuffer(encryption.raw), true)
  return {
    producer: 'js-webcrypto',
    prf,
    code,
    encryptionKeyId,
    publicKey,
    reply: {
      record: {
        version: VERSION,
        generation: 1,
        account_id: accountId,
        bundle_id: bundleId,
        credential_id: credential,
        salt,
        package_sealed: b64(pack),
        prf_ark: b64(prfArk),
        code_ark: b64(codeArk),
        signature: b64(signature),
      },
      encryption_public_keyset: b64(encryptionPublicKeyset),
      signing_public_keyset: b64(signingPublicKeyset),
      login: {
        user: { user_id: accountId },
        active_key_bundle: { key_bundle_id: bundleId },
      },
    },
  }
}

export async function sealTo(publicKey: CryptoKey, keyId: number, info: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array> {
  const sealed = await suite.seal({ recipientPublicKey: publicKey, info }, plaintext)
  return concat([new Uint8Array([1]), writeUint32(keyId), new Uint8Array(sealed.enc), new Uint8Array(sealed.ct)])
}

async function okp(algorithm: 'X25519' | 'Ed25519'): Promise<{ seed: Uint8Array; raw: Uint8Array; key: CryptoKey; publicKey: CryptoKey }> {
  const usages: KeyUsage[] = algorithm === 'Ed25519' ? ['sign', 'verify'] : ['deriveBits']
  const pair = await crypto.subtle.generateKey(algorithm, true, usages) as CryptoKeyPair
  const priv = await crypto.subtle.exportKey('jwk', pair.privateKey)
  const pub = await crypto.subtle.exportKey('jwk', pair.publicKey)
  if (!priv.d || !pub.x) throw new Error('key export failed')
  return { seed: b64url(priv.d), raw: b64url(pub.x), key: pair.privateKey, publicKey: pair.publicKey }
}

function hpkePublic(raw: Uint8Array): Uint8Array {
  const params = concat([protoVarint(1, 1), protoVarint(2, 1), protoVarint(3, 2)])
  return concat([protoBytes(2, params), protoBytes(3, raw)])
}

function hpkePrivate(raw: Uint8Array, seed: Uint8Array): Uint8Array {
  return concat([protoBytes(2, hpkePublic(raw)), protoBytes(3, seed)])
}

function edPublic(raw: Uint8Array): Uint8Array {
  return protoBytes(2, raw)
}

function edPrivate(seed: Uint8Array, pub: Uint8Array): Uint8Array {
  return concat([protoBytes(2, seed), protoBytes(3, pub)])
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

async function seal(key: Uint8Array, aad: string, plaintext: Uint8Array): Promise<Uint8Array> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const cryptoKey = await crypto.subtle.importKey('raw', arrayBuffer(key), 'AES-GCM', false, ['encrypt'])
  const body = new Uint8Array(await crypto.subtle.encrypt({
    name: 'AES-GCM',
    iv: arrayBuffer(iv),
    additionalData: arrayBuffer(utf8(aad)),
  }, cryptoKey, arrayBuffer(plaintext)))
  return concat([iv, body])
}

async function hkdf(secret: Uint8Array, salt: Uint8Array, info: string): Promise<Uint8Array> {
  const base = await crypto.subtle.importKey('raw', arrayBuffer(secret), 'HKDF', false, ['deriveBits'])
  return new Uint8Array(await crypto.subtle.deriveBits({
    name: 'HKDF',
    hash: 'SHA-256',
    salt: arrayBuffer(salt),
    info: arrayBuffer(utf8(info)),
  }, base, 256))
}

async function sha(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes)))
}

function b64url(value: string): Uint8Array {
  const padded = value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - (value.length % 4)) % 4)
  const binary = atob(padded)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}
