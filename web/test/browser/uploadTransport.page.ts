/**
 * T300 upload-transport browser fixture (Chromium-only).
 *
 * Uses the REAL production `fetchTransport` from a real browser to (1) POST a
 * JSON draft with a caller idempotency key and (2) PUT a raw ciphertext blob,
 * against an isolated loopback fixture. It then reads the fixture's recorded
 * raw headers/body and asserts the exact wire the BROWSER produced: the fixed
 * Accept/profile/Auth/Content-Type/Idempotency/hash headers, the raw byte body,
 * exactly ONE automatic Content-Length, and NO Transfer-Encoding. The caller
 * buffer is mutated right after the call to prove the transport snapshotted it.
 * Synthetic ciphertext + a fake fixture token only; no real account/backend,
 * crypto, or recognition proof.
 */
import { fetchTransport } from '../../src/auth/http'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

const CAPSULE = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'
const BLOB = '00000000-0000-0000-0000-0000000000e5'
const DRAFT_KEY = '11111111-2222-4333-8444-555555555555'
const BLOB_KEY = '66666666-7777-4888-8999-aaaaaaaaaaaa'
const TOKEN = 'fake-fixture-token'
const DRAFT_BODY = { capsule_id: CAPSULE, recipient_user_id: '33333333-4444-4555-8666-777777777777', protocol_version: 1 }

function base64url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64(value: string): Uint8Array {
  const binary = atob(value)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i)
  return out
}

function headerValues(rawHeaders: string[], name: string): string[] {
  const out: string[] = []
  for (let i = 0; i < rawHeaders.length; i += 2) if ((rawHeaders[i] ?? '').toLowerCase() === name) out.push(rawHeaders[i + 1] ?? '')
  return out
}

function headerValue(rawHeaders: string[], name: string): string | null {
  return headerValues(rawHeaders, name)[0] ?? null
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

interface Captured { method: string; url: string; rawHeaders: string[]; bodyBase64: string }

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'upload-transport-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  let captured: { draft: Captured | null; upload: Captured | null } = { draft: null, upload: null }
  const expected: Record<string, unknown> = {}
  try {
    const transport = fetchTransport(location.origin)
    const ciphertext = Uint8Array.from({ length: 257 }, (_value, index) => (index * 31 + 7) & 0xff)
    const original = ciphertext.slice()
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', ciphertext as unknown as BufferSource))
    const originalDigest = digest.slice()

    const draft = await transport.request({ path: '/v1/capsules', method: 'POST', token: TOKEN, idempotencyKey: DRAFT_KEY, body: DRAFT_BODY })
    assert('draft POST returns 201 with the capsule id', draft.status === 201 && (draft.body as { capsule_id?: string } | null)?.capsule_id === CAPSULE, JSON.stringify(draft))

    const pending = transport.uploadBytes({ path: `/v1/capsules/${CAPSULE}/blobs/${BLOB}`, token: TOKEN, idempotencyKey: BLOB_KEY, ciphertextSha256: digest, body: ciphertext })
    ciphertext.fill(0x99)
    digest.fill(0x77)
    const upload = await pending
    assert('raw PUT returns 204 with an empty body', upload.status === 204 && upload.body === null, JSON.stringify(upload))

    const response = await fetch('/__capture', { cache: 'no-store' })
    captured = (await response.json()) as typeof captured
    const draftRequest = captured.draft
    const uploadRequest = captured.upload
    assert('fixture recorded both requests', draftRequest !== null && uploadRequest !== null)
    if (!draftRequest || !uploadRequest) throw new Error('missing captured requests')

    const draftHeaders = draftRequest.rawHeaders
    assert('draft method/path exact', draftRequest.method === 'POST' && draftRequest.url === '/v1/capsules', `${draftRequest.method} ${draftRequest.url}`)
    assert('draft idempotency-key exact', headerValue(draftHeaders, 'idempotency-key') === DRAFT_KEY, String(headerValue(draftHeaders, 'idempotency-key')))
    assert('draft content-type json', (headerValue(draftHeaders, 'content-type') ?? '').startsWith('application/json'), String(headerValue(draftHeaders, 'content-type')))
    assert('draft profile 2 + bearer auth', headerValue(draftHeaders, 'x-remanence-profile-version') === '2' && headerValue(draftHeaders, 'authorization') === `Bearer ${TOKEN}`)
    assert('draft JSON body exact', new TextDecoder().decode(fromBase64(draftRequest.bodyBase64)) === JSON.stringify(DRAFT_BODY), draftRequest.bodyBase64)

    const uploadHeaders = uploadRequest.rawHeaders
    assert('upload method/path exact', uploadRequest.method === 'PUT' && uploadRequest.url === `/v1/capsules/${CAPSULE}/blobs/${BLOB}`, `${uploadRequest.method} ${uploadRequest.url}`)
    assert('upload accept json + profile 2', headerValue(uploadHeaders, 'accept') === 'application/json' && headerValue(uploadHeaders, 'x-remanence-profile-version') === '2')
    assert('upload bearer auth', headerValue(uploadHeaders, 'authorization') === `Bearer ${TOKEN}`)
    assert('upload content-type octet-stream', headerValue(uploadHeaders, 'content-type') === 'application/octet-stream', String(headerValue(uploadHeaders, 'content-type')))
    assert('upload idempotency-key exact', headerValue(uploadHeaders, 'idempotency-key') === BLOB_KEY)
    assert('upload ciphertext digest header canonical base64url', headerValue(uploadHeaders, 'x-remanence-ciphertext-sha256') === base64url(originalDigest), `${headerValue(uploadHeaders, 'x-remanence-ciphertext-sha256')} vs ${base64url(originalDigest)}`)

    const lengths = headerValues(uploadHeaders, 'content-length')
    assert('browser sent exactly one automatic Content-Length', lengths.length === 1 && lengths[0] === String(original.length), JSON.stringify(lengths))
    assert('browser sent NO Transfer-Encoding', headerValues(uploadHeaders, 'transfer-encoding').length === 0, JSON.stringify(headerValues(uploadHeaders, 'transfer-encoding')))
    assert('browser sent NO cookie (credentials omit)', headerValues(uploadHeaders, 'cookie').length === 0)

    const recorded = fromBase64(uploadRequest.bodyBase64)
    assert('fixture body bytes equal the pre-mutation ciphertext', sameBytes(recorded, original), `${recorded.length} bytes`)
    assert('fixture body is NOT the post-call mutation', !sameBytes(recorded, new Uint8Array(recorded.length).fill(0x99)))

    const recordedDigest = new Uint8Array(await crypto.subtle.digest('SHA-256', recorded as unknown as BufferSource))
    assert('fixture body SHA-256 equals the declared digest', sameBytes(recordedDigest, originalDigest))

    Object.assign(expected, {
      capsule: CAPSULE,
      blob: BLOB,
      draftKey: DRAFT_KEY,
      blobKey: BLOB_KEY,
      token: TOKEN,
      ciphertextLength: original.length,
      digestB64Url: base64url(originalDigest),
      bodyHex: Array.from(original, (byte) => byte.toString(16).padStart(2, '0')).join(''),
    })
  } catch (error) {
    assert('upload transport harness', false, String(error))
  }
  ;(window as unknown as { __uploadTransport?: unknown }).__uploadTransport = { assertions, failures, captured, expected }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main().catch((error) => {
  assert('upload transport main', false, String(error))
  ;(window as unknown as { __uploadTransport?: unknown }).__uploadTransport = { assertions, failures, captured: { draft: null, upload: null }, expected: {} }
  const status = document.querySelector('#upload-transport-status')
  if (status) status.textContent = 'failed'
})
