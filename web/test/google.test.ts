import { describe, expect, it } from 'vitest'
import { continueWithGoogle, type GoogleIdentityPort } from '../src/auth/google'
import { AuthError } from '../src/auth/errors'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { SessionStore } from '../src/session/session'

const FLOW = '11111111-1111-4111-8111-111111111111'
const NONCE = 'A'.repeat(43)
const CLIENT = 'web-test.apps.googleusercontent.com'
const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff'
const ACCESS = 'access-token-value'
const REFRESH = 'refresh-token-value'

function login(accountId = ACCOUNT) {
  return {
    user: { user_id: accountId, email: 'person@example.com', handle: 'ada' },
    active_key_bundle: { key_bundle_id: BUNDLE, suite: 'suite', protocol_version: 1, status: 'ACTIVE' },
    session_id: FLOW,
    access_token: ACCESS,
    refresh_token: REFRESH,
    access_expires_at: '2026-10-17T00:00:00Z',
    refresh_expires_at: '2026-11-17T00:00:00Z',
  }
}

function scripted(handle: (input: ApiRequest, calls: ApiRequest[]) => ApiResult): { transport: ApiTransport; calls: ApiRequest[] } {
  const calls: ApiRequest[] = []
  return {
    calls,
    transport: {
      request(input) {
        calls.push(input)
        return Promise.resolve(handle(input, calls))
      },
    },
  }
}

function identity(token = 'header.payload.signature'): GoogleIdentityPort & { calls: { clientId: string; nonce: string }[] } {
  const calls: { clientId: string; nonce: string }[] = []
  return {
    calls,
    async requestIdToken(clientId, nonce) {
      calls.push({ clientId, nonce })
      return token
    },
  }
}

describe('google verify routing', () => {
  it('signs in the account from the server login and does not unlock keys', async () => {
    const session = new SessionStore()
    const google = identity()
    const { transport, calls } = scripted((input) => {
      if (input.path === '/v1/auth/google/options') {
        return { status: 200, body: { flow: FLOW, nonce: NONCE, client_id: CLIENT } }
      }
      return { status: 200, body: { status: 'SIGNED_IN', login: login() } }
    })
    await expect(continueWithGoogle(session, transport, google)).resolves.toEqual({ kind: 'signed-in' })
    expect(google.calls).toEqual([{ clientId: CLIENT, nonce: NONCE }])
    expect(calls.map((call) => call.path)).toEqual(['/v1/auth/google/options', '/v1/auth/google/verify'])
    expect(calls[1]?.body).toEqual({ flow: FLOW, nonce: NONCE, id_token: 'header.payload.signature' })
    const view = session.getSnapshot()
    expect(view.phase).toBe('authenticated')
    expect(view.accountId).toBe(ACCOUNT)
    expect(view.handle).toBe('ada')
    expect(JSON.stringify(view)).not.toContain(ACCESS)
    expect(session.secretsFor(view.generation)?.accessToken).toBe(ACCESS)
  })

  it('does not create an identity when the server asks for a handle or a link', async () => {
    for (const response of [
      { status: 200, body: { status: 'NEEDS_HANDLE', flow: FLOW, registration_token: 'registration-secret' } },
      { status: 409, body: { reason: 'EXISTING_ACCOUNT_REQUIRES_LINK' } },
      { status: 426, body: { reason: 'CLIENT_UPGRADE_REQUIRED' } },
    ]) {
      const session = new SessionStore()
      const { transport, calls } = scripted((input) => {
        if (input.path.endsWith('/options')) return { status: 200, body: { flow: FLOW, nonce: NONCE, client_id: CLIENT } }
        return response
      })
      const outcome = await continueWithGoogle(session, transport, identity())
      const kind = response.status === 409
        ? 'existing-account-requires-link'
        : response.status === 426
          ? 'client-upgrade-required'
          : 'needs-handle'
      expect(outcome.kind).toBe(kind)
      expect(session.getSnapshot().phase).toBe('signed-out')
      expect(calls.some((call) => call.path.includes('register'))).toBe(false)
      expect(JSON.stringify(outcome)).not.toContain('registration-secret')
      expect(JSON.stringify(session.getSnapshot())).not.toContain('registration-secret')
    }
  })

  it('asks Google once and does not verify when the identity prompt fails', async () => {
    const session = new SessionStore()
    const { transport, calls } = scripted(() => ({ status: 200, body: { flow: FLOW, nonce: NONCE, client_id: CLIENT } }))
    const google: GoogleIdentityPort = {
      requestIdToken: () => Promise.reject(new AuthError('GOOGLE_CANCELLED')),
    }
    await expect(continueWithGoogle(session, transport, google)).rejects.toMatchObject({ code: 'GOOGLE_CANCELLED' })
    expect(calls.map((call) => call.path)).toEqual(['/v1/auth/google/options'])
    expect(session.getSnapshot().phase).toBe('signed-out')
  })
})
