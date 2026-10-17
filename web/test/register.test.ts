import { describe, expect, it } from 'vitest'
import { continueWithGoogle, type GoogleIdentityPort } from '../src/auth/google'
import { AuthError } from '../src/auth/errors'
import { normalizeHandle } from '../src/auth/handle'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { RegistrationDraft, registerNewAccount } from '../src/auth/register'
import { parseEd25519PublicKeyset, parseHpkePublicKeyset } from '../src/crypto/tink'
import { nameUuidFromBytes } from '../src/protocol/uuid'
import { unb64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { SessionStore } from '../src/session/session'

const FLOW = '11111111-1111-4111-8111-111111111111'
const NONCE = 'A'.repeat(43)
const TOKEN = 'B'.repeat(43)
const CLIENT = 'web-test.apps.googleusercontent.com'
const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'

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

function identity(): GoogleIdentityPort {
  return { requestIdToken: async () => 'header.payload.signature' }
}

function login(bundleId: string, handle = 'ada') {
  return {
    status: 'SIGNED_IN',
    login: {
      user: { user_id: ACCOUNT, email: 'person@example.com', handle },
      active_key_bundle: { key_bundle_id: bundleId, suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocol_version: 1, status: 'ACTIVE' },
      session_id: FLOW,
      access_token: 'access-token-value',
      refresh_token: 'refresh-token-value',
      access_expires_at: '2026-10-17T00:00:00Z',
      refresh_expires_at: '2026-11-17T00:00:00Z',
    },
  }
}

describe('new account registration', () => {
  it('keeps one identity across a taken handle and does not register an existing account', async () => {
    const session = new SessionStore()
    const draft = new RegistrationDraft()
    const { transport, calls } = scripted((input) => {
      if (input.path.endsWith('/options')) return { status: 200, body: { flow: FLOW, nonce: NONCE, client_id: CLIENT } }
      if (input.path.endsWith('/verify')) {
        return { status: 200, body: { status: 'NEEDS_HANDLE', flow: FLOW, registration_token: TOKEN } }
      }
      const body = input.body as { handle: string; key_bundle: { key_bundle_id: string } }
      if (body.handle === 'taken') return { status: 409, body: { reason: 'REGISTRATION_CONFLICT' } }
      return { status: 200, body: login(body.key_bundle.key_bundle_id, body.handle) }
    })
    const outcome = await continueWithGoogle(session, transport, identity(), undefined, draft)
    expect(outcome).toEqual({ kind: 'needs-handle' })
    expect(JSON.stringify(outcome)).not.toContain(TOKEN)
    expect(draft.hasTicket()).toBe(true)
    expect(session.getSnapshot().phase).toBe('signed-out')

    await expect(registerNewAccount(session, transport, draft, 'Taken', undefined)).rejects.toMatchObject({ code: 'HANDLE_TAKEN' })
    const first = calls.filter((call) => call.path.endsWith('/register'))
    expect(first).toHaveLength(1)
    const firstBody = first[0]?.body as { key_bundle: { encryption_public_keyset: string; key_bundle_id: string } }
    const bundleId = draft.bundleId()
    expect(bundleId).toBe(firstBody.key_bundle.key_bundle_id)
    expect(nameUuidFromBytes(unb64(firstBody.key_bundle.encryption_public_keyset))).toBe(bundleId)
    parseHpkePublicKeyset(unb64(firstBody.key_bundle.encryption_public_keyset))
    parseEd25519PublicKeyset(unb64((first[0]?.body as { key_bundle: { signing_public_keyset: string } }).key_bundle.signing_public_keyset))

    await registerNewAccount(session, transport, draft, '@Ada', undefined)
    const posts = calls.filter((call) => call.path.endsWith('/register'))
    expect(posts).toHaveLength(2)
    const second = posts[1]?.body as { handle: string; registration_token: string; key_bundle: { encryption_public_keyset: string } }
    expect(second.handle).toBe('ada')
    expect(second.registration_token).toBe(TOKEN)
    expect(second.key_bundle.encryption_public_keyset).toBe(firstBody.key_bundle.encryption_public_keyset)
    expect(session.getSnapshot().phase).toBe('authenticated')
    expect(session.getSnapshot().handle).toBe('ada')
    expect(session.getSnapshot().bundleId).toBe(bundleId)
    expect(draft.hasTicket()).toBe(false)
    expect(draft.bundleId()).toBe(bundleId)
    await expect(registerNewAccount(session, transport, draft, 'ada')).rejects.toMatchObject({ code: 'ILLEGAL_PHASE' })
    expect(calls.filter((call) => call.path.endsWith('/register'))).toHaveLength(2)
  })

  it('drops the ticket when the email already belongs to an account', async () => {
    const session = new SessionStore()
    const draft = new RegistrationDraft()
    const { transport, calls } = scripted((input) => {
      if (input.path.endsWith('/options')) return { status: 200, body: { flow: FLOW, nonce: NONCE, client_id: CLIENT } }
      return { status: 409, body: { reason: 'EXISTING_ACCOUNT_REQUIRES_LINK' } }
    })
    draft.replace(FLOW, TOKEN)
    await draft.identity()
    const held = draft.bundleId()
    expect(held).not.toBeNull()
    const outcome = await continueWithGoogle(session, transport, identity(), undefined, draft)
    expect(outcome.kind).toBe('existing-account-requires-link')
    expect(session.getSnapshot().phase).toBe('signed-out')
    expect(draft.hasTicket()).toBe(false)
    expect(draft.bundleId()).toBeNull()
    expect(calls.some((call) => call.path.includes('register'))).toBe(false)
  })

  it('rejects a handle the server would reject before any request', async () => {
    expect(normalizeHandle('@Ada')).toBe('ada')
    expect(() => normalizeHandle('no')).toThrow(AuthError)
    const session = new SessionStore()
    const draft = new RegistrationDraft()
    draft.replace(FLOW, TOKEN)
    const { transport, calls } = scripted(() => ({ status: 500, body: null }))
    await expect(registerNewAccount(session, transport, draft, 'no spaces')).rejects.toMatchObject({ code: 'HANDLE_INVALID' })
    expect(calls).toHaveLength(0)
    expect(draft.hasTicket()).toBe(true)
    expect(draft.bundleId()).toBeNull()
  })
})
