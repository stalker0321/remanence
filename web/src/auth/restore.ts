import { unlockAccount, type KeyCapabilities, type RecoveryReply } from '../crypto/keyOwner'
import type { SessionStore } from '../session/session'
import { AuthError } from './errors'
import type { ApiTransport } from './http'
import type { MemoryKeys } from './memoryKeys'
import type { PasskeyPort } from './passkey'

export type RestoreOutcome = { kind: 'unlocked' } | { kind: 'pending-verification' }

type OpenIdentity = (reply: RecoveryReply, secret: Uint8Array) => Promise<KeyCapabilities>

/**
 * Signed-in restore of an existing recovery record.
 * Uses setup options for this account's credential. Does not register a new passkey
 * and does not call the anonymous login ceremony.
 */
export async function restoreSignedInAccount(
  session: SessionStore,
  transport: ApiTransport,
  passkeys: PasskeyPort,
  keys: MemoryKeys,
  signal?: AbortSignal,
  openIdentity: OpenIdentity = unlockAccount,
): Promise<RestoreOutcome> {
  const view = session.getSnapshot()
  if (view.phase !== 'authenticated' || view.accountId === null || view.bundleId === null) {
    throw new AuthError('ILLEGAL_PHASE')
  }
  const generation = view.generation
  const accountId = view.accountId
  const bundleId = view.bundleId
  const token = () => {
    if (signal?.aborted) throw new AuthError('CANCELLED')
    session.bind(generation)()
    const secrets = session.secretsFor(generation)
    if (!secrets || session.getSnapshot().accountId !== accountId || session.getSnapshot().bundleId !== bundleId) throw new AuthError('STALE')
    return secrets.accessToken
  }
  if (!passkeys.canAuthenticate()) throw new AuthError('PASSKEY_UNAVAILABLE')
  const status = await transport.request({
    path: '/v1/recovery/status',
    method: 'GET',
    token: token(),
    signal,
  })
  const state = readStatus(status.status === 200 ? status.body : null)
  token()
  if (!state) throw new AuthError('RECOVERY_REJECTED')
  if (state.kind === 'absent') throw new AuthError('RECOVERY_NOT_STARTED')
  if (state.kind === 'pending') return { kind: 'pending-verification' }
  const credentialId = state.credentialId
  const begin = await transport.request({
    path: '/v1/recovery/setup/options',
    method: 'POST',
    body: { credential_id: credentialId },
    token: token(),
    signal,
  })
  const ceremony = readCeremony(begin.status === 200 ? begin.body : null)
  if (!ceremony) throw new AuthError('RECOVERY_REJECTED')
  token()
  const assertion = await passkeys.get(ceremony.options, signal)
  const secret = assertion.prf
  try {
    token()
    if (secret === null || secret.length !== 32) throw new AuthError('PRF_UNAVAILABLE')
    if (assertion.id !== credentialId || assertion.publicResponse.id !== credentialId) {
      throw new AuthError('WRONG_CREDENTIAL')
    }
    if (Object.keys(assertion.publicResponse.clientExtensionResults).length !== 0) {
      throw new AuthError('RECOVERY_REJECTED')
    }
    const verified = await transport.request({
      path: '/v1/recovery/setup/verify',
      method: 'POST',
      body: { flow: ceremony.flow, response: assertion.publicResponse },
      token: token(),
      signal,
    })
    const proof = record(verified.status === 200 ? verified.body : null)
    token()
    if (proof?.authentication_verified !== true || proof.credential_id !== credentialId) {
      throw new AuthError('RECOVERY_REJECTED')
    }
    const loaded = await transport.request({
      path: '/v1/recovery/record',
      method: 'GET',
      token: token(),
      signal,
    })
    const reply = readReply(loaded.status === 200 ? loaded.body : null)
    token()
    if (
      !reply
      || reply.record.account_id !== accountId
      || reply.record.bundle_id !== bundleId
      || reply.record.credential_id !== credentialId
    ) {
      throw new AuthError('WRONG_ACCOUNT')
    }
    const caps = await openIdentity(reply, secret)
    try {
      if (signal?.aborted) throw new AuthError('CANCELLED')
      session.bind(generation)()
      const current = session.getSnapshot()
      if (current.accountId !== accountId || current.bundleId !== bundleId) throw new AuthError('STALE')
      session.unlock()
      if (signal?.aborted || session.getSnapshot().generation !== generation + 1
        || session.getSnapshot().accountId !== accountId || session.getSnapshot().bundleId !== bundleId
        || session.getSnapshot().phase !== 'keys-unlocked') throw new AuthError('STALE')
      keys.hold(session.getSnapshot(), caps)
    } catch (error) {
      caps.lock()
      throw error
    }
    return { kind: 'unlocked' }
  } finally {
    secret?.fill(0)
  }
}

function readStatus(body: unknown): { kind: 'stored'; credentialId: string } | { kind: 'pending' } | { kind: 'absent' } | null {
  const root = record(body)
  const credentialId = root?.credential_id
  if (root?.state === 'NOT_STARTED' && (credentialId === null || credentialId === undefined)) return { kind: 'absent' }
  if (typeof credentialId !== 'string' || credentialId.length < 1) return null
  if (root?.state === 'STORED') return { kind: 'stored', credentialId }
  if (root?.state === 'PENDING') return { kind: 'pending' }
  return null
}

function readCeremony(body: unknown): { flow: string; options: unknown } | null {
  const root = record(body)
  if (!root || typeof root.flow !== 'string' || root.options === undefined || root.options === null) return null
  return { flow: root.flow, options: root.options }
}

function readReply(body: unknown): RecoveryReply | null {
  const root = record(body)
  const source = record(root?.record)
  if (!root || !source || source.generation !== 1) return null
  const fields = [
    'version',
    'account_id',
    'bundle_id',
    'credential_id',
    'salt',
    'package_sealed',
    'prf_ark',
    'code_ark',
    'signature',
  ] as const
  const recordBody: Record<string, string> = {}
  for (const field of fields) {
    if (typeof source[field] !== 'string') return null
    recordBody[field] = source[field]
  }
  if (typeof root.encryption_public_keyset !== 'string' || typeof root.signing_public_keyset !== 'string') return null
  return {
    record: {
      version: recordBody.version ?? '',
      generation: 1,
      account_id: recordBody.account_id ?? '',
      bundle_id: recordBody.bundle_id ?? '',
      credential_id: recordBody.credential_id ?? '',
      salt: recordBody.salt ?? '',
      package_sealed: recordBody.package_sealed ?? '',
      prf_ark: recordBody.prf_ark ?? '',
      code_ark: recordBody.code_ark ?? '',
      signature: recordBody.signature ?? '',
    },
    encryption_public_keyset: root.encryption_public_keyset,
    signing_public_keyset: root.signing_public_keyset,
  }
}

function record(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}
