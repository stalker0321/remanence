import { b64, openIdentity, unb64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import type { RecoveryReply } from './keyOwner'
import { arrayBuffer, concat, utf8, writeUint32 } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'
import { protoBytes, protoVarint } from '../protocol/proto'
import { parseEd25519PublicKeyset, parseHpkePublicKeyset } from './tink'

const VERSION = 'REMANENCE-RECOVERY-V1'

export interface PreparedRecovery {
  record: RecoveryReply['record']
  /** 32 raw bytes. The caller shows them once and wipes them. */
  code: Uint8Array
}

/**
 * Seals the held seeds into the ADR-011 record the recovery endpoint already stores.
 * Checks PRF open and code open locally before returning. This is a JS producer of
 * that record, not an Android AccountRecoveryCrypto export.
 */
export async function prepareRecoveryRecord(input: {
  accountId: string
  bundleId: string
  credentialId: string
  prf: Uint8Array
  encryptionSeed: Uint8Array
  signingSeed: Uint8Array
  encryptionPublicKeyset: string
  signingPublicKeyset: string
}): Promise<PreparedRecovery> {
  if (input.prf.length !== 32 || input.encryptionSeed.length !== 32 || input.signingSeed.length !== 32) {
    throw new ProtocolError('INVALID_KEY')
  }
  const encryptionPublic = unb64(input.encryptionPublicKeyset)
  const signingPublic = unb64(input.signingPublicKeyset)
  const hpke = parseHpkePublicKeyset(encryptionPublic)
  const ed = parseEd25519PublicKeyset(signingPublic)
  const saltBytes = crypto.getRandomValues(new Uint8Array(32))
  const salt = b64(saltBytes)
  const ark = crypto.getRandomValues(new Uint8Array(32))
  const code = crypto.getRandomValues(new Uint8Array(32))
  let prfKey: Uint8Array | null = null
  let codeKey: Uint8Array | null = null
  let plain: Uint8Array | null = null
  try {
    const ctx = [VERSION, input.accountId, input.bundleId, 1, input.credentialId, salt].join('\n')
    const encKeyset = keyset(hpkePrivate(hpke.raw, input.encryptionSeed), 'type.googleapis.com/google.crypto.tink.HpkePrivateKey', 2, hpke.keyId)
    const signKeyset = keyset(edPrivate(input.signingSeed, edPublic(ed.raw)), 'type.googleapis.com/google.crypto.tink.Ed25519PrivateKey', 2, ed.keyId)
    plain = concat([utf8('RPK1'), writeUint32(encKeyset.length), encKeyset, writeUint32(signKeyset.length), signKeyset])
    encKeyset.fill(0)
    signKeyset.fill(0)
    const pack = await seal(ark, `${ctx}\npackage`, plain)
    prfKey = await hkdf(input.prf, saltBytes, `${ctx}\nprf-ark`)
    codeKey = await hkdf(code, saltBytes, `${ctx}\ncode-ark`)
    const prfArk = await seal(prfKey, `${ctx}\nprf-ark`, ark)
    const codeArk = await seal(codeKey, `${ctx}\ncode-ark`, ark)
    const manifest = concat([utf8(`${ctx}\nmanifest`), await sha(pack), await sha(prfArk), await sha(codeArk)])
    const rawSig = new Uint8Array(await crypto.subtle.sign('Ed25519', await edKey(input.signingSeed), arrayBuffer(manifest)))
    const signature = concat([new Uint8Array([1]), writeUint32(ed.keyId), rawSig])
    const record: RecoveryReply['record'] = {
      version: VERSION,
      generation: 1,
      account_id: input.accountId,
      bundle_id: input.bundleId,
      credential_id: input.credentialId,
      salt,
      package_sealed: b64(pack),
      prf_ark: b64(prfArk),
      code_ark: b64(codeArk),
      signature: b64(signature),
    }
    const reply: RecoveryReply = {
      record,
      encryption_public_keyset: input.encryptionPublicKeyset,
      signing_public_keyset: input.signingPublicKeyset,
    }
    await check(reply, input.prf, false)
    await check(reply, code, true)
    return { record, code }
  } catch (error) {
    code.fill(0)
    throw error
  } finally {
    ark.fill(0)
    saltBytes.fill(0)
    plain?.fill(0)
    prfKey?.fill(0)
    codeKey?.fill(0)
  }
}

async function check(reply: RecoveryReply, secret: Uint8Array, useCode: boolean): Promise<void> {
  const opened = await openIdentity(reply, secret, useCode) as { signingSeed: Uint8Array; encryptionSeed: Uint8Array }
  opened.signingSeed.fill(0)
  opened.encryptionSeed.fill(0)
}

async function edKey(seed: Uint8Array): Promise<CryptoKey> {
  const der = concat([new Uint8Array([48, 46, 2, 1, 0, 48, 5, 6, 3, 43, 101, 112, 4, 34, 4, 32]), seed])
  try {
    return await crypto.subtle.importKey('pkcs8', arrayBuffer(der), 'Ed25519', false, ['sign'])
  } finally {
    der.fill(0)
  }
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
