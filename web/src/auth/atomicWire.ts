import type { ApiResult } from './http'
import { isCanonicalUuid } from '../protocol/uuid'
import type { PublicKeyBundle } from '../crypto/identity'
import type { RecoveryReply } from '../crypto/keyOwner'
import type { SignInInput } from '../session/session'

/** T403 API v1, SHA256 22e08ea72729432876b72e874cba349a54ae2e3bd78ee5fd78fe8e91ea818abf. */
export interface Outcome {
  operation_id: string; account_id: string; bundle_id: string
  credential_id: string; generation: 1; handle: string
}
export interface Reservation {
  id: string; account_id: string; state: 'RESERVED' | 'ACTIVE' | 'CANCELLED' | 'EXPIRED'
  epoch: number; expires_at: string; credential_ids: string[]
  outcome: Outcome | null; restarted_into: string | null
}
export interface SetupReply {
  status: 'SETUP_REQUIRED'; reservation: Reservation
  setup_token: string; setup_session_id: string; setup_expires_at: string
}
export interface FinalIntent {
  epoch: number; operation_id: string; final_authorization: string; handle: string
  key_bundle: PublicKeyBundle; record: RecoveryReply['record']
}
export type SetupAction = 'credential/options' | 'credential/verify' | 'setup/options' | 'setup/verify' | 'finalize' | 'leave' | 'cancel' | 'restart'
export type AtomicRequest =
  | { action: 'google/options'; body: Record<string, never>; signal?: AbortSignal }
  | { action: 'google/verify'; body: { flow: string; nonce: string; id_token: string; onboarding_version: 1 }; signal?: AbortSignal }
  | { action: 'status'; reservation: string; token: string; signal?: AbortSignal }
  | { action: SetupAction; reservation: string; token: string; body: unknown; signal?: AbortSignal }
export interface AtomicPort { request(input: AtomicRequest): Promise<ApiResult> }
export class AtomicError extends Error {
  constructor(readonly code: string, readonly status?: number) { super(code); this.name = 'AtomicError' }
}

const actions = new Set<SetupAction>(['credential/options', 'credential/verify', 'setup/options', 'setup/verify', 'finalize', 'leave', 'cancel', 'restart'])
const MAX_BYTES = 262144
export function atomicTransport(origin: string, send: typeof fetch = fetch): AtomicPort {
  let base = ''
  if (origin !== '') {
    const url = new URL(origin)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)) throw new AtomicError('TRANSPORT_FAILED')
    base = url.origin
  }
  return { async request(input) {
    // Exact closed route/method/header surface. No generic API/PUT/query escape.
    let path: string
    const headers: Record<string, string> = { accept: 'application/json', 'x-remanence-profile-version': '2' }
    if (input.action === 'google/options' || input.action === 'google/verify') {
      path = `/v1/auth/${input.action}`
    } else {
      if (!isCanonicalUuid(input.reservation) || !/^rgs_[A-Za-z0-9_-]{43}$/.test(input.token)) throw new AtomicError('TRANSPORT_FAILED')
      binary(input.token.slice(4), 43)
      if (input.action !== 'status' && !actions.has(input.action)) throw new AtomicError('TRANSPORT_FAILED')
      path = `/v2/registration/reservations/${input.reservation}${input.action === 'status' ? '' : `/${input.action}`}`
      headers.authorization = `Bearer ${input.token}`
    }
    if (input.signal?.aborted) throw new AtomicError('CANCELLED')
    const body = 'body' in input ? JSON.stringify(input.body) : undefined
    if (body !== undefined) {
      if (new TextEncoder().encode(body).length > MAX_BYTES) throw new AtomicError('TRANSPORT_FAILED')
      headers['content-type'] = 'application/json'
    }
    try {
      const response = await send(`${base}${path}`, { method: input.action === 'status' ? 'GET' : 'POST', headers, body,
        credentials: 'omit', redirect: 'error', cache: 'no-store', signal: input.signal })
      if (input.signal?.aborted) { await response.body?.cancel(); throw new AtomicError('CANCELLED') }
      if (!/^application\/(?:problem\+)?json(?:;|$)/i.test(response.headers.get('content-type') ?? '')) {
        await response.body?.cancel(); throw new AtomicError('TRANSPORT_FAILED')
      }
      const reader = response.body?.getReader()
      if (!reader) throw new AtomicError('TRANSPORT_FAILED')
      const decoder = new TextDecoder('utf-8', { fatal: true })
      let text = ''; let bytes = 0
      try {
        for (;;) {
          const step = await reader.read()
          if (input.signal?.aborted) throw new AtomicError('CANCELLED')
          if (step.done) break
          bytes += step.value.length
          if (bytes > MAX_BYTES) throw new AtomicError('TRANSPORT_FAILED')
          text += decoder.decode(step.value, { stream: true })
        }
        text += decoder.decode()
        return { status: response.status, body: JSON.parse(text) as unknown }
      } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
    } catch (error) {
      if (input.signal?.aborted) throw new AtomicError('CANCELLED')
      if (error instanceof AtomicError) throw error
      throw new AtomicError('TRANSPORT_FAILED')
    }
  } }
}

export function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AtomicError('SETUP_REJECTED')
  return value as Record<string, unknown>
}
export function string(value: unknown, max = 16384): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > max) throw new AtomicError('SETUP_REJECTED')
  return value
}
export function uuid(value: unknown): string {
  const id = string(value, 36)
  if (!isCanonicalUuid(id)) throw new AtomicError('SETUP_REJECTED')
  return id
}
export function binary(value: unknown, max = 131072): string {
  const encoded = string(value, max)
  try {
    if (!/^[A-Za-z0-9_-]+$/.test(encoded) || btoa(atob(encoded.replaceAll('-', '+').replaceAll('_', '/'))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '') !== encoded) throw new Error()
  } catch { throw new AtomicError('SETUP_REJECTED') }
  return encoded
}
function date(value: unknown): string {
  const text = string(value, 64)
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/.test(text) || !Number.isFinite(Date.parse(text))) throw new AtomicError('SETUP_REJECTED')
  return text
}
export function outcome(value: unknown): Outcome {
  const o = object(value)
  if (o.generation !== 1) throw new AtomicError('SETUP_REJECTED')
  return { operation_id: uuid(o.operation_id), account_id: uuid(o.account_id), bundle_id: uuid(o.bundle_id), credential_id: binary(o.credential_id, 2048), generation: 1, handle: string(o.handle, 30) }
}
export function reservation(value: unknown): Reservation {
  const r = object(value)
  if (!['RESERVED', 'ACTIVE', 'CANCELLED', 'EXPIRED'].includes(r.state as string) || !Number.isSafeInteger(r.epoch) || (r.epoch as number) < 1 || !Array.isArray(r.credential_ids) || r.credential_ids.length > 4) throw new AtomicError('SETUP_REJECTED')
  const result: Reservation = { id: uuid(r.id), account_id: uuid(r.account_id), state: r.state as Reservation['state'], epoch: r.epoch as number, expires_at: date(r.expires_at), credential_ids: r.credential_ids.map((id) => binary(id, 2048)), outcome: r.outcome === null ? null : outcome(r.outcome), restarted_into: r.restarted_into === null ? null : uuid(r.restarted_into) }
  if (new Set(result.credential_ids).size !== result.credential_ids.length || (result.outcome && (result.state !== 'ACTIVE' || result.outcome.account_id !== result.account_id || !result.credential_ids.includes(result.outcome.credential_id)))) throw new AtomicError('SETUP_REJECTED')
  return result
}
export function setupReply(value: unknown): SetupReply {
  const r = object(value)
  if (r.status !== 'SETUP_REQUIRED' || typeof r.setup_token !== 'string' || !/^rgs_[A-Za-z0-9_-]{43}$/.test(r.setup_token)) throw new AtomicError('SETUP_REJECTED')
  binary(r.setup_token.slice(4), 43)
  return { status: 'SETUP_REQUIRED', reservation: reservation(r.reservation), setup_token: r.setup_token, setup_session_id: uuid(r.setup_session_id), setup_expires_at: date(r.setup_expires_at) }
}
export function login(value: unknown): SignInInput {
  const l = object(value); const u = object(l.user); const b = object(l.active_key_bundle)
  return { accountId: uuid(u.user_id), bundleId: uuid(b.key_bundle_id), handle: u.handle === null ? null : string(u.handle, 32), accessToken: string(l.access_token, 4096), refreshToken: string(l.refresh_token, 4096), accessExpiresAt: date(l.access_expires_at), refreshExpiresAt: date(l.refresh_expires_at) }
}
export function recoveryReply(value: unknown): RecoveryReply {
  const root = object(value); const r = object(root.record)
  if (r.version !== 'REMANENCE-RECOVERY-V1' || r.generation !== 1) throw new AtomicError('SETUP_REJECTED')
  return { record: { version: r.version, generation: 1, account_id: uuid(r.account_id), bundle_id: uuid(r.bundle_id), credential_id: binary(r.credential_id, 2048), salt: binary(r.salt, 128), package_sealed: binary(r.package_sealed), prf_ark: binary(r.prf_ark, 512), code_ark: binary(r.code_ark, 512), signature: binary(r.signature, 256) }, encryption_public_keyset: binary(root.encryption_public_keyset, 16384), signing_public_keyset: binary(root.signing_public_keyset, 16384) }
}
export function success(result: ApiResult, statuses = [200]): Record<string, unknown> {
  const root = object(result.body)
  if (!statuses.includes(result.status)) throw new AtomicError(typeof root.reason === 'string' && /^[A-Z_]{1,64}$/.test(root.reason) ? root.reason : 'SETUP_REJECTED', result.status)
  return root
}
