import { AuthError } from './errors'
import { limits } from '../protocol/limits'
import { isCanonicalUuid } from '../protocol/uuid'

export const PROFILE_HEADER = 'x-remanence-profile-version'
const PROFILE_VALUE = '2'
const MAX_BODY = 262144
/** The one draft route that accepts an `Idempotency-Key`. */
const DRAFT_PATH = '/v1/capsules'
/** Exact canonical ciphertext upload route: `/v1/capsules/{uuid}/blobs/{uuid}`. */
const UPLOAD_PATH = /^\/v1\/capsules\/([0-9a-f-]{36})\/blobs\/([0-9a-f-]{36})$/

export interface ApiResult {
  status: number
  body: unknown
}

export interface ApiRequest {
  path: string
  method: 'GET' | 'POST'
  body?: unknown
  token?: string
  signal?: AbortSignal
  query?: Record<string, string>
  /**
   * Optional caller-stable idempotency key. Allowed ONLY for the JSON draft
   * POST to `/v1/capsules`; any other method/path/key is refused before fetch.
   */
  idempotencyKey?: string
}

export interface ApiTransport {
  request(input: ApiRequest): Promise<ApiResult>
}

export interface ByteRequest {
  path: string
  token?: string
  signal?: AbortSignal
  maxBytes: number
}

export interface ByteResult {
  status: number
  body: Uint8Array
}

export interface ByteTransport {
  requestBytes(input: ByteRequest): Promise<ByteResult>
}

/**
 * Raw ciphertext upload. Not a generic request API: PUT to the EXACT canonical
 * blob route only, with the fixed octet-stream/digest/idempotency headers. The
 * browser owns the wire (it must set exactly one Content-Length and no
 * Transfer-Encoding for this non-stream byte body).
 */
export interface UploadRequest {
  path: string
  token: string
  idempotencyKey: string
  ciphertextSha256: Uint8Array
  body: Uint8Array
  signal?: AbortSignal
}

export interface UploadTransport {
  uploadBytes(input: UploadRequest): Promise<ApiResult>
}

export function apiOrigin(): string {
  const value = import.meta.env.VITE_API_ORIGIN
  return typeof value === 'string' ? value : ''
}

export function fetchTransport(origin: string): ApiTransport & ByteTransport & UploadTransport {
  const base = checkOrigin(origin)
  return {
    async uploadBytes(input) {
      // Preflight + synchronous snapshot BEFORE any await/fetch, so a later
      // caller mutation of `body`/`ciphertextSha256` cannot change the request.
      if (typeof input.token !== 'string' || input.token.length === 0) throw new AuthError('TRANSPORT_FAILED')
      if (!isCanonicalUuid(input.idempotencyKey)) throw new AuthError('TRANSPORT_FAILED')
      if (!(input.ciphertextSha256 instanceof Uint8Array) || input.ciphertextSha256.length !== 32) throw new AuthError('TRANSPORT_FAILED')
      if (!(input.body instanceof Uint8Array) || input.body.length === 0 || input.body.length > limits.photoCiphertext) {
        throw new AuthError('TRANSPORT_FAILED')
      }
      const match = UPLOAD_PATH.exec(input.path)
      if (!match || !isCanonicalUuid(match[1] as string) || !isCanonicalUuid(match[2] as string) || !safeApiPath(input.path)) {
        throw new AuthError('TRANSPORT_FAILED')
      }
      if (input.signal?.aborted) throw new AuthError('CANCELLED')
      const body = input.body.slice()
      const digest = input.ciphertextSha256.slice()
      const headers: Record<string, string> = {
        accept: 'application/json',
        [PROFILE_HEADER]: PROFILE_VALUE,
        authorization: `Bearer ${input.token}`,
        'content-type': 'application/octet-stream',
        'idempotency-key': input.idempotencyKey,
        'x-remanence-ciphertext-sha256': base64url(digest),
      }
      let response: Response
      try {
        response = await fetch(endpoint(base, input.path), {
          method: 'PUT',
          headers,
          body,
          credentials: 'omit',
          redirect: 'error',
          cache: 'no-store',
          signal: input.signal,
        })
      } catch (error) {
        if (input.signal?.aborted) throw new AuthError('CANCELLED')
        if (error instanceof AuthError) throw error
        throw new AuthError('TRANSPORT_FAILED')
      }
      // Resolved-success boundary: if the signal aborted while the request was in
      // flight but fetch still resolved, this is NOT success.
      if (input.signal?.aborted) throw new AuthError('CANCELLED')
      if (response.status === 204) return { status: 204, body: null }
      let text: string
      try {
        text = await readBounded(response)
      } catch (error) {
        if (input.signal?.aborted) throw new AuthError('CANCELLED')
        if (error instanceof AuthError) throw error
        throw new AuthError('TRANSPORT_FAILED')
      }
      // Resolved-body boundary: a body that resolved while the signal aborted is
      // still a cancellation, never a success.
      if (input.signal?.aborted) throw new AuthError('CANCELLED')
      if (text.length === 0) return { status: response.status, body: null }
      try {
        return { status: response.status, body: JSON.parse(text) as unknown }
      } catch {
        throw new AuthError('TRANSPORT_FAILED')
      }
    },
    async requestBytes(input) {
      if (!Number.isInteger(input.maxBytes) || input.maxBytes < 1) throw new AuthError('TRANSPORT_FAILED')
      const headers: Record<string, string> = { [PROFILE_HEADER]: PROFILE_VALUE }
      if (input.token) headers.authorization = `Bearer ${input.token}`
      let response: Response
      try {
        response = await fetch(endpoint(base, input.path), {
          method: 'GET',
          headers,
          credentials: 'omit',
          redirect: 'error',
          cache: 'no-store',
          signal: input.signal,
        })
      } catch (error) {
        if (input.signal?.aborted) throw new AuthError('CANCELLED')
        if (error instanceof AuthError) throw error
        throw new AuthError('TRANSPORT_FAILED')
      }
      return { status: response.status, body: await readBoundedBytes(response, input.maxBytes) }
    },
    async request(input) {
      const headers: Record<string, string> = {
        accept: 'application/json',
        [PROFILE_HEADER]: PROFILE_VALUE,
      }
      if (input.body !== undefined) headers['content-type'] = 'application/json'
      if (input.token) headers.authorization = `Bearer ${input.token}`
      if (input.idempotencyKey !== undefined) {
        // Exactly one Idempotency-Key, only on the JSON draft POST, validated
        // before any fetch. Existing callers that omit it are unchanged.
        if (input.method !== 'POST' || input.path !== DRAFT_PATH || !isCanonicalUuid(input.idempotencyKey)) {
          throw new AuthError('TRANSPORT_FAILED')
        }
        headers['idempotency-key'] = input.idempotencyKey
      }
      let response: Response
      try {
        response = await fetch(endpoint(base, input.path, input.query), {
          method: input.method,
          headers,
          body: input.body === undefined ? undefined : JSON.stringify(input.body),
          credentials: 'omit',
          redirect: 'error',
          cache: 'no-store',
          signal: input.signal,
        })
      } catch (error) {
        if (input.signal?.aborted) throw new AuthError('CANCELLED')
        if (error instanceof AuthError) throw error
        throw new AuthError('TRANSPORT_FAILED')
      }
      const text = await readBounded(response)
      if (text.length === 0) return { status: response.status, body: null }
      try {
        return { status: response.status, body: JSON.parse(text) as unknown }
      } catch {
        throw new AuthError('TRANSPORT_FAILED')
      }
    },
  }
}

function checkOrigin(origin: string): string {
  if (origin === '') return ''
  let url: URL
  try {
    url = new URL(origin)
  } catch {
    throw new AuthError('TRANSPORT_FAILED')
  }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password || url.search || url.hash) {
    throw new AuthError('TRANSPORT_FAILED')
  }
  return origin.endsWith('/') ? origin : `${origin}/`
}

function endpoint(origin: string, path: string, query?: Record<string, string>): string {
  if (!safeApiPath(path)) throw new AuthError('TRANSPORT_FAILED')
  const relative = `${path.slice(1)}${encodeQuery(query)}`
  if (origin === '') return `/${relative}`
  return new URL(relative, origin).toString()
}

/**
 * Dots are legal in a handle. A `.` or `..` segment is not a handle.
 * The music search route is the one path outside `/v1/`.
 */
function safeApiPath(path: string): boolean {
  if (path.includes('?') || path.includes('#') || path.includes('%')) return false
  if (path === '/music/v1/search') return true
  if (!path.startsWith('/v1/')) return false
  const parts = path.split('/')
  if (parts.length < 3 || parts[0] !== '' || parts[1] !== 'v1') return false
  for (const part of parts.slice(2)) {
    if (part === '' || part === '.' || part === '..') return false
    if (!/^[a-z0-9_.-]+$/.test(part)) return false
  }
  return true
}

function encodeQuery(query: Record<string, string> | undefined): string {
  if (!query) return ''
  const keys = Object.keys(query)
  if (keys.length === 0) return ''
  if (keys.length > 8) throw new AuthError('TRANSPORT_FAILED')
  const parts: string[] = []
  for (const key of keys) {
    if (!/^[a-z]{1,16}$/.test(key)) throw new AuthError('TRANSPORT_FAILED')
    const value = query[key]
    if (typeof value !== 'string' || [...value].length > 200) throw new AuthError('TRANSPORT_FAILED')
    parts.push(`${key}=${encodeURIComponent(value)}`)
  }
  return `?${parts.join('&')}`
}

/** Canonical unpadded base64url (matches the server's ciphertext-digest wire). */
function base64url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function readBoundedBytes(response: Response, maxBytes: number): Promise<Uint8Array> {
  const reader = response.body?.getReader()
  if (!reader) return new Uint8Array()
  const chunks: Uint8Array[] = []
  let total = 0
  try {
    for (;;) {
      const step = await reader.read()
      if (step.done) break
      total += step.value.byteLength
      if (total > maxBytes) {
        await reader.cancel()
        throw new AuthError('TRANSPORT_FAILED')
      }
      chunks.push(step.value.slice())
    }
  } catch (error) {
    if (error instanceof AuthError) throw error
    throw new AuthError('TRANSPORT_FAILED')
  }
  const body = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    body.set(chunk, offset)
    offset += chunk.byteLength
  }
  return body
}

async function readBounded(response: Response): Promise<string> {
  const reader = response.body?.getReader()
  if (!reader) return ''
  const decoder = new TextDecoder()
  let text = ''
  let total = 0
  try {
    for (;;) {
      const step = await reader.read()
      if (step.done) break
      total += step.value.byteLength
      if (total > MAX_BODY) {
        await reader.cancel()
        throw new AuthError('TRANSPORT_FAILED')
      }
      text += decoder.decode(step.value, { stream: true })
    }
    return text + decoder.decode()
  } catch (error) {
    if (error instanceof AuthError) throw error
    throw new AuthError('TRANSPORT_FAILED')
  }
}
