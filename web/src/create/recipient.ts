import { AuthError } from '../auth/errors'
import { normalizeHandle } from '../auth/handle'
import type { ApiTransport } from '../auth/http'
import type { SessionStore } from '../session/session'

const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const KEYSET = /^[A-Za-z0-9_-]{1,16384}$/
const DIRECTORY_VERSION = /^[0-9a-f]{16,64}$/

export interface RecipientSnapshot {
  userId: string
  handle: string
  keyBundleId: string
  suite: typeof SUITE
  protocolVersion: 1
  encryptionPublicKeyset: string
  signingPublicKeyset: string
  directoryVersion: string
  self: boolean
}

export type RecipientLookup = { kind: 'found'; snapshot: RecipientSnapshot } | { kind: 'not-found' }

/**
 * Directory lookup for the person a remanence will be addressed to.
 * The result is not confirmed, and nothing is sent.
 */
export async function lookupRecipient(
  session: SessionStore,
  transport: ApiTransport,
  rawHandle: string,
  signal?: AbortSignal,
  mode: 'named' | 'self' = 'named',
): Promise<RecipientLookup> {
  const view = session.getSnapshot()
  if (view.phase === 'signed-out' || view.phase === 'authenticated' || view.accountId === null) {
    throw new AuthError('ILLEGAL_PHASE')
  }
  const generation = view.generation
  const accountId = view.accountId
  const readToken = () => {
    if (signal?.aborted) throw new AuthError('CANCELLED')
    session.bind(generation)()
    const secrets = session.secretsFor(generation)
    if (!secrets || session.getSnapshot().accountId !== accountId) throw new AuthError('STALE')
    return secrets.accessToken
  }
  if (mode === 'self' && (view.handle === null || view.handle.length === 0)) throw new AuthError('SELF_REJECTED')
  const handle = normalizeHandle(mode === 'self' ? view.handle ?? '' : rawHandle)
  if (mode === 'self' && handle !== view.handle) throw new AuthError('SELF_REJECTED')
  const result = await transport.request({
    path: `/v1/directory/handles/${handle}`,
    method: 'GET',
    token: readToken(),
    signal,
  })
  readToken()
  if (result.status === 404) return { kind: 'not-found' }
  if (result.status === 422 && record(result.body)?.code === 'HANDLE_INVALID') throw new AuthError('HANDLE_INVALID')
  if (result.status !== 200) throw new AuthError('DIRECTORY_REJECTED')
  const snapshot = readSnapshot(result.body, handle)
  if (!snapshot) throw new AuthError('DIRECTORY_REJECTED')
  if (mode === 'self' && snapshot.userId !== accountId) throw new AuthError('SELF_REJECTED')
  return { kind: 'found', snapshot: { ...snapshot, self: mode === 'self' } }
}

/** Memory draft. A lookup is not an address until confirm. Leaving the unlocked visit drops it. */
export class RecipientDraft {
  #ticket = 0
  #owner: string | null = null
  #pending: RecipientSnapshot | null = null
  #confirmed: RecipientSnapshot | null = null

  nextTicket(owner: string): number {
    this.#ticket += 1
    this.#owner = owner
    this.#pending = null
    return this.#ticket
  }

  offer(ticket: number, owner: string, snapshot: RecipientSnapshot): boolean {
    if (ticket !== this.#ticket || owner !== this.#owner || this.#confirmed !== null) return false
    this.#pending = snapshot
    return true
  }

  pending(): RecipientSnapshot | null {
    return this.#pending
  }

  confirmed(): RecipientSnapshot | null {
    return this.#confirmed
  }

  confirm(): void {
    if (this.#confirmed) return
    if (!this.#pending) throw new AuthError('ILLEGAL_PHASE')
    this.#confirmed = this.#pending
    this.#pending = null
  }

  restart(): void {
    this.#ticket += 1
    this.#pending = null
    this.#confirmed = null
  }

  wipe(): void {
    this.#ticket += 1
    this.#owner = null
    this.#pending = null
    this.#confirmed = null
  }

  attach(session: SessionStore): () => void {
    return session.subscribe(() => {
      const view = session.getSnapshot()
      const locked = view.phase === 'signed-out' || view.phase === 'authenticated'
      const moved = this.#owner !== null && view.accountId !== this.#owner
      if ((locked || moved) && (this.#owner !== null || this.#pending !== null || this.#confirmed !== null)) this.wipe()
    })
  }
}

function readSnapshot(body: unknown, handle: string): Omit<RecipientSnapshot, 'self'> | null {
  const root = exact(body, ['user', 'key_bundle', 'directory_version'])
  const user = exact(root?.user, ['user_id', 'handle'])
  const bundle = exact(root?.key_bundle, [
    'key_bundle_id',
    'user_id',
    'suite',
    'protocol_version',
    'encryption_public_keyset',
    'signing_public_keyset',
    'status',
    'created_at',
  ])
  if (!root || !user || !bundle) return null
  if (typeof root.directory_version !== 'string' || !DIRECTORY_VERSION.test(root.directory_version)) return null
  if (typeof user.user_id !== 'string' || !UUID.test(user.user_id)) return null
  if (user.handle !== handle) return null
  if (bundle.user_id !== user.user_id) return null
  if (typeof bundle.key_bundle_id !== 'string' || !UUID.test(bundle.key_bundle_id)) return null
  if (bundle.suite !== SUITE || bundle.protocol_version !== 1 || bundle.status !== 'ACTIVE') return null
  if (typeof bundle.encryption_public_keyset !== 'string' || !KEYSET.test(bundle.encryption_public_keyset)) return null
  if (typeof bundle.signing_public_keyset !== 'string' || !KEYSET.test(bundle.signing_public_keyset)) return null
  if (typeof bundle.created_at !== 'string' || bundle.created_at.length === 0 || bundle.created_at.length > 40) return null
  return {
    userId: user.user_id,
    handle,
    keyBundleId: bundle.key_bundle_id,
    suite: SUITE,
    protocolVersion: 1,
    encryptionPublicKeyset: bundle.encryption_public_keyset,
    signingPublicKeyset: bundle.signing_public_keyset,
    directoryVersion: root.directory_version,
  }
}

function record(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

function exact(value: unknown, keys: readonly string[]): Record<string, unknown> | null {
  const body = record(value)
  if (!body || Object.keys(body).length !== keys.length) return null
  for (const key of keys) if (!Object.hasOwn(body, key)) return null
  return body
}
