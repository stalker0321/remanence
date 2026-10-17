import { credentialOptions, publicCredential } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { AuthError } from './errors'

export interface PublicAssertion {
  id: string
  rawId: string
  type: 'public-key'
  response: Record<string, unknown>
  clientExtensionResults: Record<string, never>
}

export interface PasskeyAssertion {
  id: string
  publicResponse: PublicAssertion
  prf: Uint8Array | null
}

export interface PasskeyPort {
  /** Browser WebAuthn get is present. Backup flags are not a capability. */
  canAuthenticate(): boolean
  get(options: unknown, signal?: AbortSignal): Promise<PasskeyAssertion>
  create(options: unknown, signal?: AbortSignal): Promise<PasskeyAssertion>
}

export function browserPasskey(): PasskeyPort {
  return {
    canAuthenticate() {
      return typeof PublicKeyCredential === 'function' && typeof navigator.credentials?.get === 'function'
    },
    async get(options, signal) {
      return credential(options, signal, 'get')
    },
    async create(options, signal) {
      if (typeof navigator.credentials?.create !== 'function') throw new AuthError('PASSKEY_UNAVAILABLE')
      return credential(options, signal, 'create')
    },
  }
}

async function credential(options: unknown, signal: AbortSignal | undefined, kind: 'get' | 'create'): Promise<PasskeyAssertion> {
  if (signal?.aborted) throw new AuthError('CANCELLED')
  let created: Credential | null
  try {
    created = kind === 'get'
      ? await navigator.credentials.get({
        publicKey: credentialOptions(options as Record<string, unknown>) as unknown as PublicKeyCredentialRequestOptions,
        signal,
      })
      : await navigator.credentials.create({
        publicKey: credentialOptions(options as Record<string, unknown>) as unknown as PublicKeyCredentialCreationOptions,
        signal,
      })
  } catch (error) {
    if (signal?.aborted) throw new AuthError('CANCELLED')
    if (named(error, 'NotAllowedError') || named(error, 'AbortError')) throw new AuthError('PASSKEY_CANCELLED')
    throw new AuthError('PASSKEY_UNAVAILABLE')
  }
  if (!created || created.type !== 'public-key') throw new AuthError('PASSKEY_UNAVAILABLE')
  const published = publicCredential(created as PublicKeyCredential)
  return {
    id: published.id,
    publicResponse: published as PublicAssertion,
    prf: copyPrf(extensionPrf(created)),
  }
}

function extensionPrf(credential: Credential): unknown {
  const reader = credential as Credential & {
    getClientExtensionResults?: () => { prf?: { results?: { first?: unknown } } }
  }
  return reader.getClientExtensionResults?.().prf?.results?.first
}

function copyPrf(value: unknown): Uint8Array | null {
  if (value instanceof ArrayBuffer) {
    const copy = new Uint8Array(value.slice(0))
    if (copy.length === 32) return copy
    copy.fill(0)
    return null
  }
  if (ArrayBuffer.isView(value)) {
    const copy = new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength))
    if (copy.length === 32) return copy
    copy.fill(0)
    return null
  }
  return null
}

function named(error: unknown, name: string): boolean {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === name
}
