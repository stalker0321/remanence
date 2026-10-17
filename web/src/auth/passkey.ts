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
}

export function browserPasskey(): PasskeyPort {
  return {
    canAuthenticate() {
      return typeof PublicKeyCredential === 'function' && typeof navigator.credentials?.get === 'function'
    },
    async get(options, signal) {
      if (signal?.aborted) throw new AuthError('CANCELLED')
      let credential: Credential | null
      try {
        credential = await navigator.credentials.get({
          publicKey: credentialOptions(options as Record<string, unknown>) as unknown as PublicKeyCredentialRequestOptions,
          signal,
        })
      } catch (error) {
        if (signal?.aborted) throw new AuthError('CANCELLED')
        if (named(error, 'NotAllowedError') || named(error, 'AbortError')) throw new AuthError('PASSKEY_CANCELLED')
        throw new AuthError('PASSKEY_UNAVAILABLE')
      }
      if (!credential || credential.type !== 'public-key') throw new AuthError('PASSKEY_UNAVAILABLE')
      const published = publicCredential(credential as PublicKeyCredential)
      return {
        id: published.id,
        publicResponse: published as PublicAssertion,
        prf: copyPrf(extensionPrf(credential)),
      }
    },
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
