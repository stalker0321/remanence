import { configuredAtomicTestAdapters } from '../atomicTest/adapters'
import { atomicTransport } from '../auth/atomicWire'
import { AuthError } from '../auth/errors'
import { browserGoogleIdentity } from '../auth/googleBrowser'
import { apiOrigin, fetchTransport } from '../auth/http'
import { browserPasskey, type PasskeyAssertion } from '../auth/passkey'
import type { DeploymentConfig } from './config'

/** One immutable target for this document; reload is explicit, never mid-ceremony. */
export function deploymentPorts(config: DeploymentConfig | null, allowed: () => boolean, report: () => void, doc: Document) {
  const target = config && 'apiOrigin' in config ? config : null
  const api = fetchTransport(target?.apiOrigin ?? apiOrigin())
  const setup = atomicTransport(target?.apiOrigin ?? '')
  const raw = { google: browserGoogleIdentity(doc), passkeys: browserPasskey() }
  // These existing wrappers only validate backend clientID/RP; real browser and
  // server validation remain authoritative. DEV and TEST use identical adapters.
  const adapters = target ? configuredAtomicTestAdapters({ ...target, environment: 'TEST' }, raw.google, raw.passkeys, report) : raw
  const guard = () => { if (!config || !allowed()) { report(); throw new AuthError('TRANSPORT_FAILED') } }
  const checked = async (operation: () => Promise<PasskeyAssertion>) => {
    guard(); const result = await operation()
    try { guard(); return result } catch (error) { result.prf?.fill(0); throw error }
  }
  return {
    transport: {
      request: (input: Parameters<typeof api.request>[0]) => { guard(); return api.request(input) },
      requestBytes: (input: Parameters<typeof api.requestBytes>[0]) => { guard(); return api.requestBytes(input) },
      uploadBytes: (input: Parameters<typeof api.uploadBytes>[0]) => { guard(); return api.uploadBytes(input) },
    },
    atomicRegistration: config?.atomicRegistration ? { request: (input: Parameters<typeof setup.request>[0]) => { guard(); return setup.request(input) } } : undefined,
    google: { async requestIdToken(clientId: string, nonce: string, signal?: AbortSignal) {
      guard(); const token = await adapters.google.requestIdToken(clientId, nonce, signal); guard(); return token
    } },
    passkeys: {
      canAuthenticate: () => Boolean(config) && allowed() && adapters.passkeys.canAuthenticate(),
      create: (options: unknown, signal?: AbortSignal) => checked(() => adapters.passkeys.create(options, signal)),
      get: (options: unknown, signal?: AbortSignal) => checked(() => adapters.passkeys.get(options, signal)),
    },
  }
}
