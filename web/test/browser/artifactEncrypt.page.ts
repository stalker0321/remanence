/**
 * T263 artifact-encrypt browser smoke (Chromium-only gate).
 *
 * Runs the REAL `encryptArtifact` with the real `window.crypto.subtle` (no
 * mocked crypto on the positive path) using a synthetic serialized Tink
 * AES256_GCM keyset, the shared versioned AAD bytes and a synthetic plaintext,
 * then decrypts with the EXISTING `decryptArtifact`. No React App, server,
 * provider or network path; no secret bytes are logged or screenshotted.
 */
import { decryptArtifact } from '../../src/crypto/artifact'
import { encryptArtifact } from '../../src/crypto/artifactEncrypt'
import { concat, readUint32, utf8 } from '../../src/protocol/bytes'
import { artifactAad } from '../../src/protocol/context'
import { ProtocolError } from '../../src/protocol/errors'
import { limits } from '../../src/protocol/limits'
import { protoBytes, protoVarint } from '../../src/protocol/proto'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

const AES_URL = 'type.googleapis.com/google.crypto.tink.AesGcmKey'
const KEY_ID = 1_234_567
const RAW_KEY = Uint8Array.from({ length: 32 }, (_value, index) => index + 1)
const CAPSULE = '00010203-0405-0607-0809-0a0b0c0d0e0f'
const BLOB = '00000000-0000-0000-0000-0000000000e5'
const SENDER = '10111213-1415-1617-1819-1a1b1c1d1e1f'
const RECIPIENT = '20212223-2425-2627-2829-2a2b2c2d2e2f'

function aesKeyset(raw: Uint8Array, keyId = KEY_ID): Uint8Array {
  const material = concat([protoBytes(2, raw)])
  const data = concat([protoBytes(1, utf8(AES_URL)), protoBytes(2, material), protoVarint(3, 1)])
  const key = concat([protoBytes(1, data), protoVarint(2, 1), protoVarint(3, keyId), protoVarint(4, 1)])
  return concat([protoVarint(1, keyId), protoBytes(2, key)])
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'artifact-encrypt-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  try {
    const keyset = aesKeyset(RAW_KEY)
    const aad = artifactAad({
      capsuleId: CAPSULE,
      blobId: BLOB,
      kind: 'PHOTO',
      ordinal: 2,
      senderUserId: SENDER,
      recipientUserId: RECIPIENT,
    })
    const keysetBefore = keyset.slice()
    const aadBefore = aad.slice()
    const plaintext = utf8('browser artifact plaintext')
    const plaintextBefore = plaintext.slice()

    const ciphertext = await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    assert('real WebCrypto encrypt round-trips through existing decrypt', sameBytes(await decryptArtifact(keyset, aad, ciphertext, 'PHOTO'), plaintext))
    assert('frame length is plaintext + 33', ciphertext.length === plaintext.length + limits.artifactOverhead, String(ciphertext.length))
    assert('frame has the 0x01 TINK prefix byte', ciphertext[0] === 1, String(ciphertext[0]))
    assert('frame binds the primary key id (BE)', readUint32(ciphertext, 1) === KEY_ID, String(readUint32(ciphertext, 1)))
    assert('frame carries a 12-byte IV', ciphertext.slice(5, 17).length === 12)
    const second = await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    assert('every call uses a fresh IV', !sameBytes(ciphertext.slice(5, 17), second.slice(5, 17)))
    assert('caller keyset is not mutated', sameBytes(keyset, keysetBefore))
    assert('caller AAD is not mutated', sameBytes(aad, aadBefore))
    assert('caller plaintext is not mutated', sameBytes(plaintext, plaintextBefore))

    const contentAad = artifactAad({ capsuleId: CAPSULE, blobId: BLOB, kind: 'CONTENT_MANIFEST', ordinal: -1, senderUserId: SENDER, recipientUserId: RECIPIENT })
    const contentMax = new Uint8Array(limits.contentCiphertext - limits.artifactOverhead).fill(7)
    const contentCiphertext = await encryptArtifact(keyset, contentAad, contentMax, 'CONTENT_MANIFEST')
    assert('content boundary plaintext encrypts', contentCiphertext.length === contentMax.length + limits.artifactOverhead)
    assert('content boundary decrypts', sameBytes(await decryptArtifact(keyset, contentAad, contentCiphertext, 'CONTENT_MANIFEST'), contentMax))
    let oversizeRefused = false
    try {
      await encryptArtifact(keyset, contentAad, new Uint8Array(contentMax.length + 1), 'CONTENT_MANIFEST')
    } catch (error) {
      oversizeRefused = error instanceof ProtocolError && error.code === 'OVERSIZED'
    }
    assert('content boundary + 1 byte refuses OVERSIZED', oversizeRefused)

    let tamperRefused = false
    const tampered = ciphertext.slice()
    tampered[tampered.length - 1] ^= 1
    try {
      await decryptArtifact(keyset, aad, tampered, 'PHOTO')
    } catch (error) {
      tamperRefused = error instanceof ProtocolError
    }
    assert('tampered tag is refused', tamperRefused)

    let wrongAadRefused = false
    const wrongAad = aad.slice()
    wrongAad[wrongAad.length - 1] ^= 1
    try {
      await decryptArtifact(keyset, wrongAad, ciphertext, 'PHOTO')
    } catch (error) {
      wrongAadRefused = error instanceof ProtocolError
    }
    assert('wrong AAD is refused', wrongAadRefused)
  } catch (error) {
    assert('artifact encrypt browser harness', false, String(error))
  }
  ;(window as unknown as { __artifactEncrypt?: unknown }).__artifactEncrypt = { assertions, failures }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main().catch((error) => {
  assert('artifact encrypt main', false, String(error))
  ;(window as unknown as { __artifactEncrypt?: unknown }).__artifactEncrypt = { assertions, failures }
  const status = document.querySelector('#artifact-encrypt-status')
  if (status) status.textContent = 'failed'
})
