/**
 * T271 recipient HPKE seal browser smoke (Chromium-only gate).
 *
 * Runs the REAL `sealRecipientEnvelope` with real `window.crypto` / the pinned
 * `@hpke/core` library, then opens the frame with the EXISTING
 * `openRecipientEnvelope`, using the committed `recipient-envelope-v1.json`
 * fixture keypair/info/plaintext. No React App, server, provider, network or
 * envelope-plaintext builder; no secret bytes are logged or screenshotted.
 */
import vector from '../../../protocol/fixtures/recipient-envelope-v1.json'
import { openRecipientEnvelope } from '../../src/crypto/hpke'
import { sealRecipientEnvelope } from '../../src/crypto/hpkeSeal'
import { hexToBytes, readUint32 } from '../../src/protocol/bytes'
import { ProtocolError } from '../../src/protocol/errors'
import { limits } from '../../src/protocol/limits'
import { keysetFromJson } from '../keysetJson'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

const PUBLIC_KEYSET = keysetFromJson(vector.fixed_public_keyset_json)
const PRIVATE_KEYSET = keysetFromJson(vector.fixed_private_keyset_json)
const INFO = hexToBytes(vector.expected_context_info_hex)
const PLAINTEXT = hexToBytes(vector.envelope_plaintext_hex)
const KEY_ID = vector.signing_key_id

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'hpke-seal-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  try {
    const info = INFO.slice()
    const plaintext = PLAINTEXT.slice()
    const keyset = PUBLIC_KEYSET.slice()
    const infoBefore = info.slice()
    const plaintextBefore = plaintext.slice()
    const keysetBefore = keyset.slice()

    const frame = await sealRecipientEnvelope(keyset, info, plaintext)
    assert('real WebCrypto HPKE seal round-trips through the existing open', sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, info, frame), plaintext))
    assert('frame length is plaintext + 53', frame.length === plaintext.length + 53, String(frame.length))
    assert('frame has the 0x01 TINK prefix byte', frame[0] === 1, String(frame[0]))
    assert('frame binds the recipient key id (BE)', readUint32(frame, 1) === KEY_ID, String(readUint32(frame, 1)))
    assert('frame carries a 32-byte encapsulated key', frame.slice(5, 37).length === 32)
    const second = await sealRecipientEnvelope(keyset, info, plaintext)
    assert('every seal uses a fresh ephemeral key', !sameBytes(frame, second))
    assert('second frame also opens to the same plaintext', sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, info, second), plaintext))
    assert('caller public keyset is not mutated', sameBytes(keyset, keysetBefore))
    assert('caller info is not mutated', sameBytes(info, infoBefore))
    assert('caller plaintext is not mutated', sameBytes(plaintext, plaintextBefore))

    const maxPlaintext = limits.recipientEnvelopeCiphertext - 53
    const at = new Uint8Array(maxPlaintext).fill(7)
    const maxFrame = await sealRecipientEnvelope(PUBLIC_KEYSET, INFO, at)
    assert('exact envelope plaintext cap seals to the ciphertext cap', maxFrame.length === limits.recipientEnvelopeCiphertext)
    assert('exact-cap frame opens', sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, INFO, maxFrame), at))
    let oversizeRefused = false
    try {
      await sealRecipientEnvelope(PUBLIC_KEYSET, INFO, new Uint8Array(maxPlaintext + 1))
    } catch (error) {
      oversizeRefused = error instanceof ProtocolError && error.code === 'OVERSIZED'
    }
    assert('cap + 1 byte refuses OVERSIZED', oversizeRefused)

    let tamperRefused = false
    const tampered = frame.slice()
    tampered[tampered.length - 1] ^= 1
    try {
      await openRecipientEnvelope(PRIVATE_KEYSET, INFO, tampered)
    } catch (error) {
      tamperRefused = error instanceof ProtocolError
    }
    assert('tampered tag is refused', tamperRefused)

    let wrongInfoRefused = false
    const wrongInfo = INFO.slice()
    wrongInfo[wrongInfo.length - 1] ^= 1
    try {
      await openRecipientEnvelope(PRIVATE_KEYSET, wrongInfo, frame)
    } catch (error) {
      wrongInfoRefused = error instanceof ProtocolError
    }
    assert('wrong info is refused', wrongInfoRefused)
  } catch (error) {
    assert('hpke seal browser harness', false, String(error))
  }
  ;(window as unknown as { __hpkeSeal?: unknown }).__hpkeSeal = { assertions, failures }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main().catch((error) => {
  assert('hpke seal main', false, String(error))
  ;(window as unknown as { __hpkeSeal?: unknown }).__hpkeSeal = { assertions, failures }
  const status = document.querySelector('#hpke-seal-status')
  if (status) status.textContent = 'failed'
})
