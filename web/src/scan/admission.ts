import type { ApiTransport } from '../auth/http'
import { isCanonicalUuid } from '../protocol/uuid'
import { ScanError } from './errors'

/** ADR-014 authorization only, after local preparation. Never queues or infers
 * offline success. A lost reply may have committed: an explicit retry replays
 * the existing server claim; it is not a new local/durable opening grant. */
export async function admitFirstOpen(
  transport: ApiTransport | undefined,
  capsuleId: string,
  currentToken: () => string,
  signal: AbortSignal,
): Promise<void> {
  const token = currentToken()
  if (!transport || !isCanonicalUuid(capsuleId)) throw new ScanError('ADMISSION_UNAVAILABLE')
  let response
  try {
    response = await transport.request({ method: 'POST', path: `/v1/capsules/${capsuleId}/first-open`, token, signal })
  } catch {
    currentToken()
    throw new ScanError('ADMISSION_UNAVAILABLE')
  }
  currentToken()
  const body = response.body
  if (response.status === 200 && valid(body, capsuleId)) return
  if (response.status === 401) throw new ScanError('ADMISSION_AUTH_REQUIRED')
  if (body && typeof body === 'object' && !Array.isArray(body)) {
    const problem = body as Record<string, unknown>
    if (problem.status === response.status &&
      ((response.status === 404 && problem.code === 'CAPSULE_NOT_FOUND') ||
       (response.status === 409 && problem.code === 'CAPSULE_STATE_INVALID'))) {
      throw new ScanError('ADMISSION_DENIED')
    }
  }
  throw new ScanError('ADMISSION_UNAVAILABLE')
}

function valid(value: unknown, capsuleId: string): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const row = value as Record<string, unknown>
  return Object.keys(row).sort().join(',') === 'capsule_id,first_opened_at,is_replay,state'
    && row.capsule_id === capsuleId && row.state === 'OPENED' && typeof row.is_replay === 'boolean'
    && typeof row.first_opened_at === 'string' && row.first_opened_at.length <= 40
    && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|\+00:00)$/.test(row.first_opened_at)
    && Number.isFinite(Date.parse(row.first_opened_at))
}
