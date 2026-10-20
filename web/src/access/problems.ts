import { isCanonicalUuid } from '../protocol/uuid'

export type SendAccessFailure = 'limit' | 'unavailable'
const exact = (row: Record<string, unknown>, keys: string[]) => Object.keys(row).length === keys.length && keys.every((key) => Object.hasOwn(row, key))
const text = (value: unknown, max: number): value is string => typeof value === 'string' && value.length > 0
  && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value)

/** Recognize only received, coherent T372 v1/pending problem envelopes.
 * Does not render server strings, authorize a retry, or infer a balance. */
export function sendAccessProblem(status: number, body: unknown): SendAccessFailure | null {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return null
  const row = body as Record<string, unknown>
  const kind = status === 409 && row.code === 'SEND_LIMIT_REACHED' ? 'limit'
    : status === 503 && row.code === 'SEND_ACCESS_UNAVAILABLE' ? 'unavailable' : null
  if (kind === null || row.status !== status) return null
  if (exact(row, ['type', 'title', 'status', 'code'])) {
    return row.type === 'about:blank' && row.title === row.code ? kind : null
  }
  if (!exact(row, ['type', 'title', 'status', 'code', 'detail', 'request_id', 'retryable'])
    || !text(row.type, 256) || !text(row.title, 256) || !text(row.detail, 1024)
    || typeof row.request_id !== 'string' || !isCanonicalUuid(row.request_id)
    || row.retryable !== (kind === 'unavailable')) return null
  return kind
}
