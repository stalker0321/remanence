/** T374-send-access-client-v1 — proposed owner-read contract, CLIENT_ONLY.
 * This is metadata, never a publication capability or locally spendable balance. */
import type { ApiTransport } from '../auth/http'
import type { SessionStore } from '../session/session'

export interface FreeBucket { limit: 2; used: number; remaining: number }
export interface SendAccess {
  policy_version: 1
  mode: 'record' | 'enforce'
  lifetime: boolean
  free: { self: FreeBucket; other: FreeBucket }
  can_publish: { self: boolean; other: boolean }
}
export type AccessResult = { kind: 'hidden' } | { kind: 'available'; access: SendAccess }
export class SendAccessError extends Error {
  constructor(readonly kind: 'unavailable' | 'stale') { super('Sending access unavailable') }
}
const invalid = (): never => { throw new SendAccessError('unavailable') }
function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const row = value as Record<string, unknown>
  if (Object.keys(row).length !== keys.length || !keys.every((key) => Object.hasOwn(row, key))) return invalid()
  return row
}
function boolean(value: unknown): boolean {
  return typeof value === 'boolean' ? value : invalid()
}
function bucket(value: unknown): FreeBucket {
  const row = object(value, ['limit', 'used', 'remaining'])
  if (row.limit !== 2 || typeof row.used !== 'number' || typeof row.remaining !== 'number'
    || !Number.isInteger(row.used) || !Number.isInteger(row.remaining)
    || row.used < 0 || row.used > 2 || row.remaining < 0 || row.remaining > 2 || row.used + row.remaining !== 2) return invalid()
  return { limit: 2, used: row.used, remaining: row.remaining }
}
export function readSendAccess(value: unknown): SendAccess {
  const row = object(value, ['policy_version', 'mode', 'lifetime', 'free', 'can_publish'])
  if (row.policy_version !== 1 || (row.mode !== 'record' && row.mode !== 'enforce')) return invalid()
  const free = object(row.free, ['self', 'other'])
  const can = object(row.can_publish, ['self', 'other'])
  const access: SendAccess = {
    policy_version: 1, mode: row.mode, lifetime: boolean(row.lifetime),
    free: { self: bucket(free.self), other: bucket(free.other) },
    can_publish: { self: boolean(can.self), other: boolean(can.other) },
  }
  for (const key of ['self', 'other'] as const) {
    if (access.can_publish[key] !== (access.mode === 'record' || access.lifetime || access.free[key].remaining > 0)) return invalid()
  }
  return access
}
export async function fetchSendAccess(store: SessionStore, transport: ApiTransport, signal: AbortSignal): Promise<AccessResult> {
  const owner = store.getSnapshot()
  const current = () => {
    const view = store.getSnapshot()
    if (signal.aborted || owner.accountId === null || view.accountId !== owner.accountId
      || view.generation !== owner.generation || view.phase === 'signed-out') throw new SendAccessError('stale')
    const secrets = store.secretsFor(owner.generation)
    if (!secrets) throw new SendAccessError('stale')
    return secrets.accessToken
  }
  const token = current()
  try {
    const result = await transport.request({ method: 'GET', path: '/v1/me/send-access', token, signal })
    current()
    if (result.status === 404) return { kind: 'hidden' }
    if (result.status !== 200) return invalid()
    return { kind: 'available', access: readSendAccess(result.body) }
  } catch {
    current()
    return invalid()
  }
}
