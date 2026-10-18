/**
 * T304 publisher browser gate (Chromium-only).
 *
 * Runs the REAL `sealCapsule` -> `publishCapsule` -> production `fetchTransport`
 * in a real browser against an isolated same-origin loopback Node fixture that
 * implements the EXACT current three-step wire (draft POST -> per-blob PUT ->
 * finalize POST) with real receipts. It records the actual request sequence and
 * raw bytes and asserts them against the real sealer output. TEST-ONLY golden
 * keysets + synthetic header-JPEG photos + a fake token; no real backend, no real
 * account, no signature/admission claim, no image decode.
 */
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { fetchTransport } from '../../src/auth/http'
import { publishCapsule } from '../../src/sync/publishCapsule'
import { utf8, bytesToHex } from '../../src/protocol/bytes'
import { nameUuidFromBytes } from '../../src/protocol/uuid'
import { buildSealedCapsuleFixture, CAPSULE_UUID, DRAFT_KEY } from '../sealedCapsuleFixture'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

function fromHex(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i += 1) out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  return out
}

function fromBase64url(value: string): Uint8Array {
  const standard = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = standard + '='.repeat((4 - (standard.length % 4)) % 4)
  const binary = atob(padded)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i)
  return out
}

function headerValues(rawHeaders: string[], name: string): string[] {
  const out: string[] = []
  for (let i = 0; i < rawHeaders.length; i += 2) if ((rawHeaders[i] ?? '').toLowerCase() === name) out.push(rawHeaders[i + 1] ?? '')
  return out
}
const header = (rawHeaders: string[], name: string): string | null => headerValues(rawHeaders, name)[0] ?? null

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

interface Captured { method: string; url: string; rawHeaders: string[]; bodyHex: string }

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'publish-capsule-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const expected: Record<string, unknown> = {}
  let captured: { draft: Captured | null; uploads: Captured[]; finalize: Captured | null } = { draft: null, uploads: [], finalize: null }
  try {
    const { capsule, session } = await buildSealedCapsuleFixture()
    const transport = fetchTransport(location.origin)
    const published = await publishCapsule({ capsule, session, idempotencyKey: DRAFT_KEY, transport })

    assert('publisher returns the bound capsule id in READY', published.capsuleId === CAPSULE_UUID && published.readyAt.length > 0, JSON.stringify(published))
    assert('negotiated locator pair surfaced (not dropped)', published.locator !== null && published.locator.code === 'AB12Z' && published.locator.hint === 'a•••@•••', JSON.stringify(published.locator))

    const response = await fetch('/__capture', { cache: 'no-store' })
    captured = (await response.json()) as typeof captured
    assert('fixture recorded draft + finalize', captured.draft !== null && captured.finalize !== null)

    const draft = captured.draft
    const finalize = captured.finalize
    if (draft && finalize) {
      assert('draft POST exact method/path', draft.method === 'POST' && draft.url === '/v1/capsules', `${draft.method} ${draft.url}`)
      assert('draft bearer + idempotency + json', header(draft.rawHeaders, 'authorization') === 'Bearer sender-access' && header(draft.rawHeaders, 'idempotency-key') === DRAFT_KEY && (header(draft.rawHeaders, 'content-type') ?? '').startsWith('application/json'))
      const draftBody = JSON.parse(new TextDecoder().decode(fromHex(draft.bodyHex))) as { capsule_id: string; protocol_version: number; blobs: Array<Record<string, unknown>> }
      assert('draft body ids/version exact', draftBody.capsule_id === CAPSULE_UUID && draftBody.protocol_version === 1)
      assert('draft blobs exact order/kinds/ordinals/base64', JSON.stringify(draftBody.blobs) === JSON.stringify(capsule.blobs.map((blob) => ({ blob_id: blob.blobId, kind: blob.kind, ordinal: blob.kind === 'PHOTO' ? blob.ordinal : null, ciphertext_size: blob.ciphertextSize, ciphertext_sha256: b64(blob.ciphertextSha256) }))), JSON.stringify(draftBody.blobs))
    }

    // Partial STORED (blob index 0) is skipped: exactly the remaining blobs upload.
    const expectedUploads = capsule.blobs.slice(1)
    assert('server-STORED blob skipped; remaining blobs uploaded in order', captured.uploads.map((call) => call.url) .join(',') === expectedUploads.map((blob) => `/v1/capsules/${CAPSULE_UUID}/blobs/${blob.blobId}`).join(','), JSON.stringify(captured.uploads.map((call) => call.url)))
    for (let index = 0; index < expectedUploads.length; index++) {
      const blob = expectedUploads[index]!
      const call = captured.uploads[index]
      if (!call) { assert(`upload ${index} recorded`, false); continue }
      const body = fromHex(call.bodyHex)
      assert(`upload ${index} PUT raw octet-stream + bearer`, call.method === 'PUT' && header(call.rawHeaders, 'content-type') === 'application/octet-stream' && header(call.rawHeaders, 'authorization') === 'Bearer sender-access')
      assert(`upload ${index} derived idempotency key`, header(call.rawHeaders, 'idempotency-key') === nameUuidFromBytes(utf8(`postmark/blob-upload/v1/${CAPSULE_UUID}/${blob.blobId}`)))
      assert(`upload ${index} digest header canonical base64url`, header(call.rawHeaders, 'x-remanence-ciphertext-sha256') === b64(blob.ciphertextSha256))
      assert(`upload ${index} raw bytes equal the real sealer ciphertext`, sameBytes(body, blob.ciphertext), `${body.length} bytes`)
      assert(`upload ${index} exactly one automatic Content-Length`, headerValues(call.rawHeaders, 'content-length').length === 1 && headerValues(call.rawHeaders, 'content-length')[0] === String(blob.ciphertext.length), JSON.stringify(headerValues(call.rawHeaders, 'content-length')))
      assert(`upload ${index} no Transfer-Encoding`, headerValues(call.rawHeaders, 'transfer-encoding').length === 0)
    }

    if (finalize) {
      const finalizeBody = JSON.parse(new TextDecoder().decode(fromHex(finalize.bodyHex))) as {
        signed_publish_statement: { statement: string; signature: string; sender_key_bundle_id: string }
        recipient_envelope: { recipient_key_bundle_id: string; ciphertext: string; ciphertext_size: number; ciphertext_sha256: string }
      }
      assert('finalize POST exact path + bearer', finalize.method === 'POST' && finalize.url === `/v1/capsules/${CAPSULE_UUID}/finalize` && header(finalize.rawHeaders, 'authorization') === 'Bearer sender-access')
      assert('finalize statement/signature/sender bundle exact', finalizeBody.signed_publish_statement.statement === b64(capsule.statement) && finalizeBody.signed_publish_statement.signature === b64(capsule.signature) && finalizeBody.signed_publish_statement.sender_key_bundle_id === capsule.senderKeyBundleId)
      const envelope = fromBase64url(finalizeBody.recipient_envelope.ciphertext)
      const envelopeSha = new Uint8Array(await crypto.subtle.digest('SHA-256', envelope as unknown as BufferSource))
      assert('finalize envelope exact + size/hash', sameBytes(envelope, capsule.envelopeCiphertext) && finalizeBody.recipient_envelope.ciphertext_size === capsule.envelopeCiphertext.length && finalizeBody.recipient_envelope.ciphertext_sha256 === b64(envelopeSha))
      assert('finalize has no extra statement_sha256 field', !('statement_sha256' in finalizeBody.signed_publish_statement))
    }

    Object.assign(expected, {
      capsuleId: CAPSULE_UUID,
      draftKey: DRAFT_KEY,
      uploadPaths: expectedUploads.map((blob) => `/v1/capsules/${CAPSULE_UUID}/blobs/${blob.blobId}`),
      uploadBodyHexes: expectedUploads.map((blob) => bytesToHex(blob.ciphertext)),
      statementB64: b64(capsule.statement),
      locator: { code: 'AB12Z', hint: 'a•••@•••' },
    })
  } catch (error) {
    assert('publisher browser harness', false, String(error))
  }
  ;(window as unknown as { __publishCapsule?: unknown }).__publishCapsule = { assertions, failures, captured, expected }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main().catch((error) => {
  assert('publisher browser main', false, String(error))
  ;(window as unknown as { __publishCapsule?: unknown }).__publishCapsule = { assertions, failures, captured: { draft: null, uploads: [], finalize: null }, expected: {} }
  const status = document.querySelector('#publish-capsule-status')
  if (status) status.textContent = 'failed'
})
