import type { GoogleIdentityPort } from '../auth/google'
import { browserGoogleIdentity } from '../auth/googleBrowser'
import { browserPasskey, type PasskeyPort } from '../auth/passkey'
import { AtomicTestConfigurationError, type AtomicTestConfig } from './config'

/** Bind existing real adapters; these guards do not authorize provider proof.
 * Server validators and the browser's native RP/origin checks remain authoritative. */
export function configuredAtomicTestAdapters(
  config: AtomicTestConfig,
  google: GoogleIdentityPort,
  passkeys: PasskeyPort,
  report: (message: string) => void,
): { google: GoogleIdentityPort; passkeys: PasskeyPort } {
  function reject(message: string): never { report(message); throw new AtomicTestConfigurationError(message) }
  function rp(options: unknown, create: boolean): void {
    const root = typeof options === 'object' && options !== null ? options as Record<string, unknown> : null
    const supplied = create && typeof root?.rp === 'object' && root.rp !== null
      ? (root.rp as Record<string, unknown>).id : root?.rpId
    if (supplied !== config.rpId) reject('Backend WebAuthn RP ID differs from TEST rpId. No credential prompt was opened; check the existing backend configuration.')
  }
  return {
    google: {
      requestIdToken(clientId, nonce, signal) {
        // Source is POST /google/options' public ID, not a fixture or a secret.
        if (!/^[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/.test(clientId)) {
          return reject('Backend Google options did not provide a configured public Google client ID. No Google prompt was opened.')
        }
        return google.requestIdToken(clientId, nonce, signal)
      },
    },
    passkeys: {
      canAuthenticate: () => passkeys.canAuthenticate(),
      create(options, signal) { rp(options, true); return passkeys.create(options, signal) },
      get(options, signal) { rp(options, false); return passkeys.get(options, signal) },
    },
  }
}

/** Runnable entry uses only these actual browser adapters, never test fixtures. */
export function browserAtomicTestAdapters(config: AtomicTestConfig, doc: Document, report: (message: string) => void) {
  return configuredAtomicTestAdapters(config, browserGoogleIdentity(doc), browserPasskey(), report)
}
