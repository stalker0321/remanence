import { argon2id } from 'hash-wasm'
import { arrayBuffer, concat, utf8 } from '../protocol/bytes'
import { fieldBytes, fieldNumber, readMessage } from '../protocol/proto'

/**
 * ADR-020: the first gift is keyed by the card. The code from the card plus a
 * random server half unlock the capsule key, and neither side holds both.
 * Byte-for-byte equal to `protocol/fixtures/card-seal-v1.json`. The code stays
 * on this device: it must never reach a URL, a request body or a log.
 */
export const ALPHABET = '0123456789ACDEFHJKMNPQRTVWXY'
export const SECRET_LENGTH = 7
const KDF = { version: 1, timeCost: 3, memoryKib: 65536, parallelism: 1 }
const INPUT_MAP: Record<string, string> = { O: '0', I: '1', L: '1', B: '8', G: '6', S: '5', Z: '2', U: 'V' }
const KEK_DOMAIN = 'remanence/card-seal/kek/v1'
const PROOF_DOMAIN = 'remanence/card-seal/claim-proof/v1'
const AAD_DOMAIN = 'remanence/card-seal/aad/v1'

export interface ParsedCode { secret: string; hintDigits: string }

export function formatCode(secret: string, phone: string): string {
  const whole = secret + phone.slice(-2)
  return `${whole.slice(0, 3)}-${whole.slice(3, 6)}-${whole.slice(6)}`
}

/** Null for anything that is not a code. Handwritten look-alikes map on input. */
export function parseCode(typed: string): ParsedCode | null {
  const mapped = [...typed.toUpperCase().replace(/[ -]/g, '')].map((ch) => INPUT_MAP[ch] ?? ch).join('')
  if (mapped.length !== SECRET_LENGTH + 2) return null
  if ([...mapped].some((ch) => !ALPHABET.includes(ch))) return null
  const hintDigits = mapped.slice(SECRET_LENGTH)
  if (!/^[0-9]{2}$/.test(hintDigits)) return null
  return { secret: mapped.slice(0, SECRET_LENGTH), hintDigits }
}

export interface CardSeal {
  /** Canonical CardSealAadV1 bytes, exactly as the sender built them. */
  binding: Uint8Array
  statementSha256: Uint8Array
  salt: Uint8Array
  serverHalf: Uint8Array
  sealedKey: Uint8Array
  verifier: Uint8Array
}

export interface OpenedSeal { keyset: Uint8Array; opening: Uint8Array; proof: Uint8Array }

export function parseSeal(raw: Uint8Array): CardSeal {
  const seal = readMessage(raw, [1, 2, 3, 4, 5, 6])
  if (fieldNumber(seal, 1) !== 1) throw new Error('SEAL_REJECTED')
  const binding = fieldBytes(seal, 2)
  const kdf = readMessage(fieldBytes(seal, 3), [1, 2, 3, 4, 5])
  // Weaker parameters from a server are refused: they would make the code cheaper to guess.
  if (fieldNumber(kdf, 1) !== KDF.version || fieldNumber(kdf, 2) !== KDF.timeCost
    || fieldNumber(kdf, 3) !== KDF.memoryKib || fieldNumber(kdf, 4) !== KDF.parallelism) {
    throw new Error('SEAL_REJECTED')
  }
  const salt = fieldBytes(kdf, 5)
  const serverHalf = fieldBytes(seal, 4)
  const verifier = fieldBytes(seal, 6)
  const statementSha256 = fieldBytes(readMessage(binding, [1, 2, 3, 4, 5, 6, 7, 8]), 5)
  if (salt.length !== 32 || serverHalf.length !== 32 || verifier.length !== 32 || statementSha256.length !== 32) {
    throw new Error('SEAL_REJECTED')
  }
  return { binding, statementSha256, salt, serverHalf, sealedKey: fieldBytes(seal, 5), verifier }
}

async function hmac(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  const imported = await crypto.subtle.importKey('raw', arrayBuffer(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return new Uint8Array(await crypto.subtle.sign('HMAC', imported, arrayBuffer(data)))
}

async function hkdfSha256(ikm: Uint8Array, info: Uint8Array): Promise<Uint8Array> {
  const prk = await hmac(new Uint8Array(32), ikm)
  try { return await hmac(prk, concat([info, Uint8Array.of(1)])) } finally { prk.fill(0) }
}

function domain(name: string): Uint8Array { return concat([utf8(name), Uint8Array.of(0)]) }

export async function deriveKeys(secret: string, salt: Uint8Array, serverHalf: Uint8Array, statementSha256: Uint8Array): Promise<{ kek: Uint8Array; proof: Uint8Array }> {
  const cardKey = await argon2id({
    password: utf8(secret),
    salt,
    iterations: KDF.timeCost,
    memorySize: KDF.memoryKib,
    parallelism: KDF.parallelism,
    hashLength: 32,
    outputType: 'binary',
  })
  const ikm = concat([cardKey, serverHalf])
  cardKey.fill(0)
  try {
    return {
      kek: await hkdfSha256(ikm, concat([domain(KEK_DOMAIN), statementSha256])),
      proof: await hkdfSha256(ikm, concat([domain(PROOF_DOMAIN), statementSha256])),
    }
  } finally {
    ikm.fill(0)
  }
}

/** Null when the code does not open this seal. */
export async function openSeal(secret: string, seal: CardSeal): Promise<OpenedSeal | null> {
  if (seal.sealedKey.length <= 12) return null
  const { kek, proof } = await deriveKeys(secret, seal.salt, seal.serverHalf, seal.statementSha256)
  try {
    const key = await crypto.subtle.importKey('raw', arrayBuffer(kek), 'AES-GCM', false, ['decrypt'])
    let plain: Uint8Array
    try {
      plain = new Uint8Array(await crypto.subtle.decrypt({
        name: 'AES-GCM',
        iv: arrayBuffer(seal.sealedKey.subarray(0, 12)),
        additionalData: arrayBuffer(concat([domain(AAD_DOMAIN), seal.binding])),
        tagLength: 128,
      }, key, arrayBuffer(seal.sealedKey.subarray(12))))
    } catch {
      proof.fill(0)
      return null
    }
    const parsed = readMessage(plain, [1, 2, 3])
    const opened = { keyset: fieldBytes(parsed, 2).slice(), opening: fieldBytes(parsed, 3).slice(), proof }
    plain.fill(0)
    return opened
  } finally {
    kek.fill(0)
  }
}
