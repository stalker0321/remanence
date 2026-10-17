import { AuthError } from './errors'

export const PROFILE_HEADER = 'x-remanence-profile-version'
const PROFILE_VALUE = '2'
const MAX_BODY = 262144

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

export function apiOrigin(): string {
  const value = import.meta.env.VITE_API_ORIGIN
  return typeof value === 'string' ? value : ''
}

export function fetchTransport(origin: string): ApiTransport & ByteTransport {
  const base = checkOrigin(origin)
  return {
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
      let response: Response
      try {
        response = await fetch(endpoint(base, input.path), {
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

function endpoint(origin: string, path: string): string {
  if (!/^\/v1\/[a-z0-9/_-]+$/.test(path)) throw new AuthError('TRANSPORT_FAILED')
  if (origin === '') return path
  return new URL(path.slice(1), origin).toString()
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
