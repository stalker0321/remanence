import { createServer } from 'node:http'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchTransport, PROFILE_HEADER } from '../src/auth/http.ts'
import { limits } from '../src/protocol/limits.ts'

const CAPSULE = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'
const BLOB = '00000000-0000-0000-0000-0000000000e5'
const DRAFT_KEY = '11111111-2222-4333-8444-555555555555'
const BLOB_KEY = '66666666-7777-4888-8999-aaaaaaaaaaaa'
const TOKEN = 'fake-bearer-token'
const DRAFT_BODY = { capsule_id: CAPSULE, recipient_user_id: '33333333-4444-4555-8666-777777777777', protocol_version: 1 }

function base64url(bytes) {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function syntheticCiphertext() {
  return Uint8Array.from({ length: 257 }, (_value, index) => (index * 31 + 7) & 0xff)
}

async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
}

function uploadRequest(overrides = {}) {
  return {
    path: `/v1/capsules/${CAPSULE}/blobs/${BLOB}`,
    token: TOKEN,
    idempotencyKey: BLOB_KEY,
    ciphertextSha256: new Uint8Array(32),
    body: new Uint8Array([1, 2, 3, 4]),
    ...overrides,
  }
}

function headerValues(rawHeaders, name) {
  const out = []
  for (let i = 0; i < rawHeaders.length; i += 2) if ((rawHeaders[i] ?? '').toLowerCase() === name) out.push(rawHeaders[i + 1] ?? '')
  return out
}

function listen() {
  const requests = []
  const server = createServer((req, res) => {
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => {
      requests.push({ method: req.method, url: req.url, rawHeaders: req.rawHeaders, headers: req.headers, body: Buffer.concat(chunks) })
      if (req.method === 'POST' && req.url === '/v1/capsules') {
        res.writeHead(201, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ capsule_id: CAPSULE, state: 'DRAFT' }))
        return
      }
      if (req.method === 'PUT' && req.url === `/v1/capsules/${CAPSULE}/blobs/${BLOB}`) {
        res.writeHead(204)
        res.end()
        return
      }
      res.writeHead(404)
      res.end()
    })
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      resolve({ origin: `http://127.0.0.1:${address.port}`, close: () => new Promise((done) => server.close(() => done())), seen: (index) => requests[index] })
    })
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('transport upload capability', () => {
  it('draft POST + raw PUT hit the exact wire on a real loopback server, snapshotting before mutation', async () => {
    const server = await listen()
    try {
      const transport = fetchTransport(server.origin)
      const draft = await transport.request({ path: '/v1/capsules', method: 'POST', token: TOKEN, idempotencyKey: DRAFT_KEY, body: DRAFT_BODY })
      expect(draft).toEqual({ status: 201, body: { capsule_id: CAPSULE, state: 'DRAFT' } })

      const ciphertext = syntheticCiphertext()
      const original = ciphertext.slice()
      const digest = await sha256(ciphertext)
      const digestBefore = digest.slice()
      const pending = transport.uploadBytes({ path: `/v1/capsules/${CAPSULE}/blobs/${BLOB}`, token: TOKEN, idempotencyKey: BLOB_KEY, ciphertextSha256: digest, body: ciphertext })
      ciphertext.fill(0x99)
      digest.fill(0x77)
      const upload = await pending
      expect(upload).toEqual({ status: 204, body: null })

      const draftReq = server.seen(0)
      expect(draftReq.method).toBe('POST')
      expect(draftReq.url).toBe('/v1/capsules')
      expect(draftReq.headers['idempotency-key']).toBe(DRAFT_KEY)
      expect(draftReq.headers['content-type']).toBe('application/json')
      expect(draftReq.headers[PROFILE_HEADER]).toBe('2')
      expect(draftReq.headers.authorization).toBe(`Bearer ${TOKEN}`)
      expect(draftReq.body.toString('utf8')).toBe(JSON.stringify(DRAFT_BODY))

      const up = server.seen(1)
      expect(up.method).toBe('PUT')
      expect(up.url).toBe(`/v1/capsules/${CAPSULE}/blobs/${BLOB}`)
      expect(up.headers.accept).toBe('application/json')
      expect(up.headers[PROFILE_HEADER]).toBe('2')
      expect(up.headers.authorization).toBe(`Bearer ${TOKEN}`)
      expect(up.headers['content-type']).toBe('application/octet-stream')
      expect(up.headers['idempotency-key']).toBe(BLOB_KEY)
      expect(up.headers['x-remanence-ciphertext-sha256']).toBe(base64url(digestBefore))
      // Snapshot proof: the server received the original bytes, not the mutation.
      expect(up.body.equals(Buffer.from(original))).toBe(true)
      expect(up.body.equals(Buffer.alloc(original.length, 0x99))).toBe(false)
      // Browser/Node own the framing: exactly one Content-Length, never chunked, no cookies.
      expect(headerValues(up.rawHeaders, 'content-length')).toEqual([String(original.length)])
      expect(headerValues(up.rawHeaders, 'transfer-encoding')).toEqual([])
      expect(headerValues(up.rawHeaders, 'cookie')).toEqual([])
    } finally {
      await server.close()
    }
  })

  it('does not mutate the caller body/digest', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }))
    const transport = fetchTransport('http://127.0.0.1:9')
    const body = new Uint8Array([1, 2, 3, 4])
    const digest = new Uint8Array(32).fill(9)
    const bodyBefore = body.slice()
    const digestBefore = digest.slice()
    await transport.uploadBytes(uploadRequest({ body, ciphertextSha256: digest }))
    expect(body).toEqual(bodyBefore)
    expect(digest).toEqual(digestBefore)
  })

  it('refuses every preflight violation without calling fetch', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const transport = fetchTransport('http://127.0.0.1:9')
    const cases = [
      { token: '' },
      { idempotencyKey: 'not-a-uuid' },
      { idempotencyKey: BLOB_KEY.toUpperCase() },
      { ciphertextSha256: new Uint8Array(31) },
      { ciphertextSha256: new Uint8Array(33) },
      { body: new Uint8Array(0) },
      { body: new Uint8Array(limits.photoCiphertext + 1) },
      { path: `/v1/capsules/not-a-uuid/blobs/${BLOB}` },
      { path: `/v1/capsules/${CAPSULE}/blobs/${BLOB}/extra` },
      { path: `/v1/capsules/${CAPSULE}/blobs/00000000-0000-0000-0000-00000000000g` },
      { path: `/v1/capsules/${CAPSULE.toUpperCase()}/blobs/${BLOB}` },
    ]
    for (const override of cases) {
      await expect(transport.uploadBytes(uploadRequest(override))).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    }
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('accepts the exact photo ciphertext cap and refuses one byte over', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }))
    const transport = fetchTransport('http://127.0.0.1:9')
    await expect(transport.uploadBytes(uploadRequest({ body: new Uint8Array(limits.photoCiphertext) }))).resolves.toEqual({ status: 204, body: null })
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    await expect(transport.uploadBytes(uploadRequest({ body: new Uint8Array(limits.photoCiphertext + 1) }))).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('handles 204, empty, valid problem JSON, malformed and oversize responses', async () => {
    const transport = fetchTransport('http://127.0.0.1:9')
    const mock = vi.spyOn(globalThis, 'fetch')
    mock.mockResolvedValueOnce(new Response(JSON.stringify({ code: 'IDEMPOTENCY_CONFLICT' }), { status: 409, headers: { 'content-type': 'application/json' } }))
    await expect(transport.uploadBytes(uploadRequest())).resolves.toEqual({ status: 409, body: { code: 'IDEMPOTENCY_CONFLICT' } })
    mock.mockResolvedValueOnce(new Response(null, { status: 204 }))
    await expect(transport.uploadBytes(uploadRequest())).resolves.toEqual({ status: 204, body: null })
    mock.mockResolvedValueOnce(new Response('', { status: 200 }))
    await expect(transport.uploadBytes(uploadRequest())).resolves.toEqual({ status: 200, body: null })
    mock.mockResolvedValueOnce(new Response('not json', { status: 200 }))
    await expect(transport.uploadBytes(uploadRequest())).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    mock.mockResolvedValueOnce(new Response('x'.repeat(262145), { status: 200 }))
    await expect(transport.uploadBytes(uploadRequest())).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    mock.mockRejectedValueOnce(new TypeError('network down'))
    await expect(transport.uploadBytes(uploadRequest())).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
  })

  it('maps pre-abort, in-flight abort and response-body abort to CANCELLED', async () => {
    const transport = fetchTransport('http://127.0.0.1:9')
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const pre = new AbortController()
    pre.abort()
    await expect(transport.uploadBytes(uploadRequest({ signal: pre.signal }))).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(fetchSpy).not.toHaveBeenCalled()

    const inFlight = new AbortController()
    fetchSpy.mockImplementationOnce(async () => {
      inFlight.abort()
      throw new DOMException('aborted', 'AbortError')
    })
    await expect(transport.uploadBytes(uploadRequest({ signal: inFlight.signal }))).rejects.toMatchObject({ code: 'CANCELLED' })

    const bodyAbort = new AbortController()
    const stream = new ReadableStream({
      pull() {
        bodyAbort.abort()
        throw new DOMException('aborted', 'AbortError')
      },
    })
    fetchSpy.mockResolvedValueOnce(new Response(stream, { status: 200 }))
    await expect(transport.uploadBytes(uploadRequest({ signal: bodyAbort.signal }))).rejects.toMatchObject({ code: 'CANCELLED' })
  })

  it('returns CANCELLED at the resolved-success boundary when the signal is already aborted', async () => {
    const transport = fetchTransport('http://127.0.0.1:9')
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    // (a) fetch RESOLVES 204 while the signal is aborted during the request:
    // the resolved-success boundary must not return success.
    const resolved204 = new AbortController()
    fetchSpy.mockImplementationOnce(async () => {
      resolved204.abort()
      return new Response(null, { status: 204 })
    })
    await expect(transport.uploadBytes(uploadRequest({ signal: resolved204.signal }))).rejects.toMatchObject({ code: 'CANCELLED' })

    // (b1) the body read RESOLVES empty while the signal is aborted.
    const resolvedEmpty = new AbortController()
    fetchSpy.mockImplementationOnce(async () => {
      resolvedEmpty.abort()
      return new Response('', { status: 200 })
    })
    await expect(transport.uploadBytes(uploadRequest({ signal: resolvedEmpty.signal }))).rejects.toMatchObject({ code: 'CANCELLED' })

    // (b2) the body read RESOLVES valid JSON while the signal is aborted.
    const resolvedJson = new AbortController()
    fetchSpy.mockImplementationOnce(async () => {
      resolvedJson.abort()
      return new Response(JSON.stringify({ code: 'STATUS' }), { status: 409, headers: { 'content-type': 'application/json' } })
    })
    await expect(transport.uploadBytes(uploadRequest({ signal: resolvedJson.signal }))).rejects.toMatchObject({ code: 'CANCELLED' })
  })

  it('emits exactly one Idempotency-Key only on the draft POST and refuses elsewhere before fetch', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ ok: true }), { status: 201, headers: { 'content-type': 'application/json' } }))
    const transport = fetchTransport('http://127.0.0.1:9')
    await expect(transport.request({ path: '/v1/capsules', method: 'POST', token: TOKEN, idempotencyKey: DRAFT_KEY, body: { a: 1 } })).resolves.toEqual({ status: 201, body: { ok: true } })
    const headers = ((fetchSpy.mock.calls[0][1] ?? {}).headers ?? {})
    expect(Object.keys(headers).filter((key) => key.toLowerCase() === 'idempotency-key')).toEqual(['idempotency-key'])
    expect(headers['idempotency-key']).toBe(DRAFT_KEY)
    fetchSpy.mockClear()

    // Absent option leaves existing behaviour (no Idempotency-Key header).
    await transport.request({ path: '/v1/capsules', method: 'POST', body: {} })
    const plainHeaders = ((fetchSpy.mock.calls[0][1] ?? {}).headers ?? {})
    expect('idempotency-key' in plainHeaders).toBe(false)
    fetchSpy.mockClear()

    await expect(transport.request({ path: '/v1/capsules', method: 'GET', idempotencyKey: DRAFT_KEY })).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    await expect(transport.request({ path: '/v1/other', method: 'POST', idempotencyKey: DRAFT_KEY })).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    await expect(transport.request({ path: '/v1/capsules', method: 'POST', idempotencyKey: 'not-a-uuid' })).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
