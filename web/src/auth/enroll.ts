import { unlockAccount, type RecoveryReply } from '../crypto/keyOwner'
import { prepareRecoveryRecord } from '../crypto/recoveryRecord'
import type { SessionStore } from '../session/session'
import { AuthError } from './errors'
import type { ApiTransport } from './http'
import type { MemoryKeys } from './memoryKeys'
import type { PasskeyPort } from './passkey'
import type { RegistrationDraft } from './register'

export type EnrollOutcome = { kind: 'enrolled'; code: Uint8Array } | { kind: 'restored' }

/**
 * Existing recovery ceremony for the identity already held from registration.
 * A missing identity does not generate a replacement. A stored record is read
 * back and does not get a new package or a new code.
 */
export async function enrollHeldIdentity(
  session: SessionStore,
  transport: ApiTransport,
  passkeys: PasskeyPort,
  keys: MemoryKeys,
  draft: RegistrationDraft,
  signal?: AbortSignal,
): Promise<EnrollOutcome> {
  const view = session.getSnapshot()
  if (view.phase !== 'authenticated' || view.accountId === null || view.bundleId === null) {
    throw new AuthError('ILLEGAL_PHASE')
  }
  if (draft.bundleId() !== view.bundleId) throw new AuthError('IDENTITY_ABSENT')
  const generation = view.generation
  const accountId = view.accountId
  const bundleId = view.bundleId
  const epoch = draft.epoch
  const token = () => {
    if (signal?.aborted) throw new AuthError('CANCELLED')
    session.bind(generation)()
    const secrets = session.secretsFor(generation)
    if (!secrets || session.getSnapshot().accountId !== accountId || session.getSnapshot().bundleId !== bundleId
      || draft.epoch !== epoch || draft.bundleId() !== bundleId) throw new AuthError('STALE')
    return secrets.accessToken
  }
  if (!passkeys.canAuthenticate()) throw new AuthError('PASSKEY_UNAVAILABLE')
  const status = await transport.request({ path: '/v1/recovery/status', method: 'GET', token: token(), signal })
  token()
  const state = readStatus(status.status === 200 ? status.body : null)
  if (!state) throw new AuthError('RECOVERY_REJECTED')
  let credentialId = state.kind === 'absent' ? '' : state.credentialId
  if (state.kind === 'absent') {
    const begin = await transport.request({
      path: '/v1/recovery/register/options',
      method: 'POST',
      body: {},
      token: token(),
      signal,
    })
    const ceremony = readCeremony(begin.status === 200 ? begin.body : null)
    if (!ceremony) throw new AuthError('RECOVERY_REJECTED')
    token()
    const created = await passkeys.create(ceremony.options, signal)
    const verified = await transport.request({
      path: '/v1/recovery/register/verify',
      method: 'POST',
      body: { flow: ceremony.flow, response: created.publicResponse },
      token: token(),
      signal,
    })
    const proof = record(verified.status === 200 ? verified.body : null)
    token()
    if (proof?.registered !== true || typeof proof.credential_id !== 'string' || proof.credential_id !== created.id) {
      throw new AuthError('RECOVERY_REJECTED')
    }
    credentialId = proof.credential_id
  }
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
    if (assertion.id !== credentialId || assertion.publicResponse.id !== credentialId) throw new AuthError('WRONG_CREDENTIAL')
    if (Object.keys(assertion.publicResponse.clientExtensionResults).length !== 0) throw new AuthError('RECOVERY_REJECTED')
    const verified = await transport.request({
      path: '/v1/recovery/setup/verify',
      method: 'POST',
      body: { flow: ceremony.flow, response: assertion.publicResponse },
      token: token(),
      signal,
    })
    const proof = record(verified.status === 200 ? verified.body : null)
    token()
    if (proof?.authentication_verified !== true || proof.credential_id !== credentialId || typeof proof.flow !== 'string') {
      throw new AuthError('RECOVERY_REJECTED')
    }
    if (state.kind === 'stored') {
      await readback(session, transport, keys, token, generation, accountId, bundleId, credentialId, secret, signal)
      draft.wipe()
      return { kind: 'restored' }
    }
    const identity = await draft.identity()
    token()
    const seeds = identity.copySeeds()
    const bundle = identity.publicBundle()
    let code: Uint8Array | null = null
    try {
      const prepared = await prepareRecoveryRecord({
        accountId,
        bundleId,
        credentialId,
        prf: secret,
        encryptionSeed: seeds.encryption,
        signingSeed: seeds.signing,
        encryptionPublicKeyset: bundle.encryption_public_keyset,
        signingPublicKeyset: bundle.signing_public_keyset,
      })
      code = prepared.code
      const saved = await transport.request({
        path: '/v1/recovery/record',
        method: 'POST',
        body: { flow: proof.flow, record: prepared.record },
        token: token(),
        signal,
      })
      const stored = record(saved.status === 200 ? saved.body : null)
      token()
      if (stored?.stored !== true || stored.generation !== 1) throw new AuthError('RECOVERY_REJECTED')
      await readback(session, transport, keys, token, generation, accountId, bundleId, credentialId, secret, signal)
      draft.wipe()
      const shown = code
      code = null
      return { kind: 'enrolled', code: shown }
    } finally {
      seeds.encryption.fill(0)
      seeds.signing.fill(0)
      code?.fill(0)
    }
  } finally {
    secret?.fill(0)
  }
}

async function readback(
  session: SessionStore,
  transport: ApiTransport,
  keys: MemoryKeys,
  token: () => string,
  generation: number,
  accountId: string,
  bundleId: string,
  credentialId: string,
  secret: Uint8Array,
  signal: AbortSignal | undefined,
): Promise<void> {
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
  const caps = await unlockAccount(reply, secret)
  try {
    token()
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
}

function readStatus(body: unknown): { kind: 'stored'; credentialId: string } | { kind: 'pending'; credentialId: string } | { kind: 'absent' } | null {
  const root = record(body)
  const credentialId = root?.credential_id
  if (root?.state === 'NOT_STARTED' && (credentialId === null || credentialId === undefined)) return { kind: 'absent' }
  if (typeof credentialId !== 'string' || credentialId.length < 1) return null
  if (root?.state === 'STORED') return { kind: 'stored', credentialId }
  if (root?.state === 'PENDING') return { kind: 'pending', credentialId }
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
  const fields = ['version', 'account_id', 'bundle_id', 'credential_id', 'salt', 'package_sealed', 'prf_ark', 'code_ark', 'signature'] as const
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
