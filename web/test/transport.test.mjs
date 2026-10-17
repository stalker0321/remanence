import { createServer } from 'node:http'
import { describe, expect, it } from 'vitest'
import { AuthError } from '../src/auth/errors.ts'
import { fetchTransport, PROFILE_HEADER } from '../src/auth/http.ts'

function listen() {
  const requests = []
  const server = createServer((req, res) => {
    const decoder = new TextDecoder()
    let body = ''
    req.on('data', (chunk) => {
      body += decoder.decode(chunk, { stream: true })
    })
    req.on('end', () => {
      body += decoder.decode()
      requests.push({ method: req.method, url: req.url, headers: req.headers, body })
      if (req.url === '/v1/auth/google/redirect') {
        res.writeHead(302, { location: 'https://example.invalid/away' })
        res.end()
        return
      }
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
      res.end(JSON.stringify({ ok: true }))
    })
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      resolve({
        origin: `http://127.0.0.1:${address.port}`,
        close: () => new Promise((done) => server.close(done)),
        seen: () => {
          const last = requests.at(-1)
          if (!last) throw new Error('no request')
          return last
        },
      })
    })
  })
}

describe('api transport', () => {
  it('sends the profile header to loopback and omits cookies', async () => {
    const server = await listen()
    try {
      const transport = fetchTransport(server.origin)
      const result = await transport.request({ path: '/v1/auth/google/options', method: 'POST', body: {} })
      expect(result).toEqual({ status: 200, body: { ok: true } })
      const seen = server.seen()
      expect(seen.headers[PROFILE_HEADER]).toBe('2')
      expect(seen.headers.cookie).toBeUndefined()
      expect(seen.headers.authorization).toBeUndefined()
      expect(seen.body).toBe('{}')
      await transport.request({ path: '/v1/recovery/status', method: 'GET', token: 'access-token-value' })
      const authed = server.seen()
      expect(authed.method).toBe('GET')
      expect(authed.headers.authorization).toBe('Bearer access-token-value')
      expect(authed.body).toBe('')
    } finally {
      await server.close()
    }
  })

  it('refuses a credentialed origin and a redirect', async () => {
    expect(() => fetchTransport('http://user:secret@127.0.0.1:9')).toThrow(AuthError)
    const server = await listen()
    try {
      const transport = fetchTransport(server.origin)
      await expect(transport.request({ path: '/v1/auth/google/redirect', method: 'POST', body: {} })).rejects.toMatchObject({
        code: 'TRANSPORT_FAILED',
      })
    } finally {
      await server.close()
    }
  })
})
