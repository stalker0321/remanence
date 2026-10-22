import { AuthError } from './errors'
import type { GoogleIdentityPort } from './google'

const GIS_SCRIPT = 'https://accounts.google.com/gsi/client'

interface GoogleMoment {
  isNotDisplayed(): boolean
  isSkippedMoment(): boolean
  getNotDisplayedReason(): string
  getSkippedReason(): string
}

interface GoogleIdentityServices {
  initialize(config: {
    client_id: string
    nonce: string
    auto_select: boolean
    use_fedcm_for_prompt: boolean
    callback: (response: { credential?: string }) => void
  }): void
  prompt(listener: (notification: GoogleMoment) => void): void
  cancel(): void
  disableAutoSelect(): void
}

/**
 * Official Google Identity Services script, loaded only after the person asks
 * to continue. The ID token is sent to our verify endpoint. This module does
 * not decide the account.
 */
export function browserGoogleIdentity(doc: Document): GoogleIdentityPort {
  return {
    async requestIdToken(clientId, nonce, signal) {
      if (signal?.aborted) throw new AuthError('CANCELLED')
      await loadScript(doc, signal)
      // Loading (including an already cached script) yields. Leaving during
      // that handoff must not initialize or open a new provider prompt.
      if (signal?.aborted) throw new AuthError('CANCELLED')
      const google = gis()
      if (!google) throw new AuthError('GOOGLE_CLIENT_UNAVAILABLE')
      return new Promise((resolve, reject) => {
        let settled = false
        const finish = (error: AuthError | null, token?: string) => {
          if (settled) return
          settled = true
          signal?.removeEventListener('abort', onAbort)
          if (error) reject(error)
          else resolve(token ?? '')
        }
        const onAbort = () => {
          google.cancel()
          finish(new AuthError('CANCELLED'))
        }
        signal?.addEventListener('abort', onAbort, { once: true })
        google.disableAutoSelect()
        google.initialize({
          client_id: clientId,
          nonce,
          auto_select: false,
          use_fedcm_for_prompt: true,
          callback: (response) => {
            if (typeof response.credential === 'string' && response.credential.length > 0) {
              finish(null, response.credential)
            } else {
              finish(new AuthError('GOOGLE_CLIENT_UNAVAILABLE'))
            }
          },
        })
        google.prompt((notification) => {
          if (!notification.isNotDisplayed() && !notification.isSkippedMoment()) return
          const reason = notification.isSkippedMoment()
            ? notification.getSkippedReason()
            : notification.getNotDisplayedReason()
          const dismissed = reason === 'user_cancel' || reason === 'tap_outside'
          finish(new AuthError(dismissed ? 'GOOGLE_CANCELLED' : 'GOOGLE_CLIENT_UNAVAILABLE'))
        })
      })
    },
  }
}

function gis(): GoogleIdentityServices | null {
  const candidate = (globalThis as { google?: { accounts?: { id?: GoogleIdentityServices } } }).google
  return candidate?.accounts?.id ?? null
}

function loadScript(doc: Document, signal: AbortSignal | undefined): Promise<void> {
  if (gis()) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const script = doc.createElement('script')
    script.src = GIS_SCRIPT
    script.async = true
    script.referrerPolicy = 'no-referrer'
    script.dataset.remanenceGis = '1'
    const fail = (error: AuthError) => {
      script.remove()
      reject(error)
    }
    const onAbort = () => fail(new AuthError('CANCELLED'))
    signal?.addEventListener('abort', onAbort, { once: true })
    script.onload = () => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }
    script.onerror = () => {
      signal?.removeEventListener('abort', onAbort)
      fail(new AuthError('GOOGLE_CLIENT_UNAVAILABLE'))
    }
    doc.head.appendChild(script)
  })
}
