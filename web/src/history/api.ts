/** T368-history-v1. Metadata only; never opening admission or a send retry. */
import type { ApiRequest, ApiTransport } from '../auth/http'
import { normalizeHandle } from '../auth/handle'
import { isCanonicalUuid } from '../protocol/uuid'
import type { SessionStore } from '../session/session'

export const historyStatuses = ['DRAFT', 'ABORTED', 'READY', 'OPENED', 'REVOKED', 'PENDING', 'CLAIMED', 'CANCELLED', 'EXPIRED', 'UNSUPPORTED'] as const
export type HistoryStatus = typeof historyStatuses[number]
export interface HistoryItem {
  capsule_id: string
  protocol_version: 1 | 2
  delivery_id: string | null
  status: HistoryStatus
  created_at: string
  published_at: string | null
  expires_at: string | null
  first_opened_at: string | null
  revoked_at: string | null
  recipient: { user_id: string | null; handle: string | null; hint: string | null; is_self: boolean | null }
  locator: { status: 'ACTIVE' | 'RETIRED_OPEN' | 'RETIRED_TERMINAL' | 'UNAVAILABLE'; code: string | null }
  cancel_action: 'REVOKE_V1' | 'CANCEL_PENDING_V2' | null
  cancel_deadline: string | null
}
export interface HistoryPage { items: HistoryItem[]; has_more: boolean; next_cursor: string | null }
export interface RecentRecipient { user_id: string; handle: string; last_sent_at: string }
export class HistoryError extends Error {
  constructor(readonly kind: 'unavailable' | 'auth' | 'invalid' | 'stale' | 'conflict' | 'unknown') {
    super('History operation unavailable')
  }
}
const invalid = (): never => { throw new HistoryError('invalid') }
function record(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const data = value as Record<string, unknown>
  if (Object.keys(data).sort().join(',') !== keys.sort().join(',')) return invalid()
  return data
}
function uuid(value: unknown): string {
  if (typeof value !== 'string' || !isCanonicalUuid(value)) return invalid()
  return value
}
function nullableText(value: unknown, max: number): string | null {
  if (value === null) return null
  if (typeof value !== 'string' || !value.length || value.length > max || /[\u0000-\u001f\u007f]/.test(value)) return invalid()
  return value
}
function time(value: unknown): string | null {
  const text = nullableText(value, 40)
  if (text !== null && (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|\+00:00)$/.test(text) || !Number.isFinite(Date.parse(text)))) return invalid()
  return text
}
function handle(value: unknown): string {
  if (typeof value !== 'string' || value.length > 32) return invalid()
  try { if (normalizeHandle(value) !== value) return invalid() } catch { return invalid() }
  return value
}
function item(value: unknown, owner: string): HistoryItem {
  const data = record(value, ['capsule_id', 'protocol_version', 'delivery_id', 'status', 'created_at', 'published_at', 'expires_at', 'first_opened_at', 'revoked_at', 'recipient', 'locator', 'cancel_action', 'cancel_deadline'])
  const version = data.protocol_version
  if (version !== 1 && version !== 2) return invalid()
  const recipient = record(data.recipient, ['user_id', 'handle', 'hint', 'is_self'])
  const locator = record(data.locator, ['status', 'code'])
  const rawStatus = nullableText(data.status, 32)
  const supportedStatus = version === 1 ? ['DRAFT', 'ABORTED', 'READY', 'OPENED', 'REVOKED'] : ['DRAFT', 'PENDING', 'CLAIMED', 'CANCELLED', 'EXPIRED']
  const knownAction = data.cancel_action === null || data.cancel_action === 'REVOKE_V1' || data.cancel_action === 'CANCEL_PENDING_V2'
  const supported = rawStatus !== null && supportedStatus.includes(rawStatus) && knownAction
  const row: HistoryItem = {
    capsule_id: uuid(data.capsule_id), protocol_version: version,
    delivery_id: data.delivery_id === null ? null : uuid(data.delivery_id),
    status: supported ? rawStatus as HistoryStatus : 'UNSUPPORTED',
    created_at: time(data.created_at) ?? invalid(), published_at: time(data.published_at),
    expires_at: time(data.expires_at), first_opened_at: time(data.first_opened_at), revoked_at: time(data.revoked_at),
    recipient: {
      user_id: recipient.user_id === null ? null : uuid(recipient.user_id),
      handle: recipient.handle === null ? null : handle(recipient.handle),
      hint: nullableText(recipient.hint, 64), is_self: recipient.is_self as boolean | null,
    },
    locator: { status: 'UNAVAILABLE', code: null },
    cancel_action: supported ? data.cancel_action as HistoryItem['cancel_action'] : null,
    cancel_deadline: time(data.cancel_deadline),
  }
  if (version === 1) {
    if (row.delivery_id !== null || row.recipient.user_id === null || row.recipient.is_self !== (row.recipient.user_id === owner)) return invalid()
  } else if (row.delivery_id === null || row.recipient.user_id !== null || row.recipient.handle !== null || row.recipient.is_self !== null
    || row.published_at !== null || row.first_opened_at !== null || row.revoked_at !== null || row.cancel_deadline !== null) return invalid()
  if (supported) {
    if (row.cancel_action === 'REVOKE_V1' && (version !== 1 || row.status !== 'READY' || row.first_opened_at !== null || row.cancel_deadline === null)) return invalid()
    if (row.cancel_action === 'CANCEL_PENDING_V2' && (version !== 2 || !['DRAFT', 'PENDING'].includes(row.status))) return invalid()
    if (locator.status === 'ACTIVE') {
      if (typeof locator.code !== 'string' || !/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{5}$/.test(locator.code)
        || !['READY', 'OPENED', 'PENDING', 'CLAIMED'].includes(row.status)) return invalid()
      row.locator = { status: 'ACTIVE', code: locator.code }
    } else if (['RETIRED_OPEN', 'RETIRED_TERMINAL', 'UNAVAILABLE'].includes(String(locator.status))) {
      if (locator.code !== null) return invalid()
      row.locator.status = locator.status as HistoryItem['locator']['status']
    }
  }
  return row
}
export function readHistory(value: unknown, owner: string, limit = 20): HistoryPage {
  const data = record(value, ['items', 'has_more', 'next_cursor'])
  if (!Array.isArray(data.items) || data.items.length > limit || typeof data.has_more !== 'boolean') return invalid()
  const cursor = nullableText(data.next_cursor, 1024)
  if ((cursor !== null && !/^[A-Za-z0-9_-]+$/.test(cursor)) || data.has_more !== (cursor !== null) || (data.has_more && data.items.length === 0)) return invalid()
  const items = data.items.map((value) => item(value, owner))
  if (new Set(items.map(historyKey)).size !== items.length) return invalid()
  return { items, has_more: data.has_more, next_cursor: cursor }
}
export const historyKey = (row: HistoryItem): string => `${row.protocol_version}:${row.capsule_id}`
export function readRecent(value: unknown, owner: string): RecentRecipient[] {
  const data = record(value, ['items'])
  if (!Array.isArray(data.items) || data.items.length > 8) return invalid()
  const items = data.items.map((value) => {
    const row = record(value, ['user_id', 'handle', 'last_sent_at'])
    return { user_id: uuid(row.user_id), handle: handle(row.handle), last_sent_at: time(row.last_sent_at) ?? invalid() }
  })
  if (items.some((row) => row.user_id === owner) || new Set(items.map((row) => row.user_id)).size !== items.length) return invalid()
  return items
}
async function ownedRequest(store: SessionStore, transport: ApiTransport, input: ApiRequest) {
  const view = store.getSnapshot()
  const check = () => {
    const live = store.getSnapshot()
    if (input.signal?.aborted || view.accountId === null || live.accountId !== view.accountId || live.generation !== view.generation || live.phase === 'signed-out') throw new HistoryError('stale')
    const secrets = store.secretsFor(view.generation)
    if (!secrets) throw new HistoryError('auth')
    return secrets.accessToken
  }
  const token = check()
  try {
    const result = await transport.request({ ...input, token })
    check()
    return { result, owner: view.accountId! }
  } catch (error) {
    check()
    throw error instanceof HistoryError ? error : new HistoryError('unavailable')
  }
}
export async function outgoing(store: SessionStore, transport: ApiTransport, cursor: string | null, signal: AbortSignal): Promise<HistoryPage> {
  const { result, owner } = await ownedRequest(store, transport, { path: '/v1/capsules/outgoing', method: 'GET', query: cursor ? { cursor } : undefined, signal })
  if (result.status !== 200) throw new HistoryError(result.status === 401 ? 'auth' : 'unavailable')
  return readHistory(result.body, owner)
}
export async function recentRecipients(store: SessionStore, transport: ApiTransport, signal: AbortSignal): Promise<RecentRecipient[]> {
  const { result, owner } = await ownedRequest(store, transport, { path: '/v1/recipients/recent', method: 'GET', signal })
  if (result.status !== 200) throw new HistoryError(result.status === 401 ? 'auth' : 'unavailable')
  return readRecent(result.body, owner)
}
export async function cancelOutgoing(store: SessionStore, transport: ApiTransport, row: HistoryItem, signal: AbortSignal): Promise<void> {
  const v1 = row.cancel_action === 'REVOKE_V1' && row.protocol_version === 1
  const v2 = row.cancel_action === 'CANCEL_PENDING_V2' && row.protocol_version === 2 && row.delivery_id !== null
  if (!v1 && !v2) return invalid()
  try {
    const { result } = await ownedRequest(store, transport, {
      path: v1 ? `/v1/capsules/${uuid(row.capsule_id)}/revoke` : `/v2/pending-deliveries/${uuid(row.delivery_id)}/cancel`,
      method: 'POST', signal,
    })
    if (result.status === 409) throw new HistoryError('conflict')
    if (result.status === 401) throw new HistoryError('auth')
    if (result.status !== 200) throw new HistoryError('unknown')
    const body = record(result.body, v1 ? ['capsule_id', 'state', 'is_replay'] : ['delivery_id', 'state'])
    if (v1 ? body.capsule_id !== row.capsule_id || body.state !== 'REVOKED' || typeof body.is_replay !== 'boolean'
      : body.delivery_id !== row.delivery_id || body.state !== 'CANCELLED') throw new HistoryError('unknown')
  } catch (error) {
    if (error instanceof HistoryError && ['stale', 'auth', 'conflict'].includes(error.kind)) throw error
    throw new HistoryError('unknown')
  }
}
