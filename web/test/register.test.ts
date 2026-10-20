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

describe('new account registration', () => {
  it('gates new registration without generating keys or sending a public bundle', async () => {
    const session = new SessionStore()
    const draft = new RegistrationDraft()
    const { transport, calls } = scripted((input) => {
      if (input.path.endsWith('/options')) return { status: 200, body: { flow: FLOW, nonce: NONCE, client_id: CLIENT } }
      return { status: 200, body: { status: 'NEEDS_HANDLE', flow: FLOW, registration_token: TOKEN } }
    })
    expect(await continueWithGoogle(session, transport, identity(), undefined, draft)).toEqual({ kind: 'needs-handle' })
    expect(draft.hasTicket()).toBe(false)
    for (const handle of ['Taken', '@Ada']) {
      await expect(registerNewAccount(session, transport, draft, handle)).rejects.toMatchObject({ code: 'REGISTRATION_PAUSED' })
    }
    expect(draft.bundleId()).toBeNull()
    expect(session.getSnapshot().phase).toBe('signed-out')
    expect(calls.some((call) => call.path.endsWith('/register'))).toBe(false)
  })

  it('keeps one held identity during concurrent calls and wipes a late generated identity after replacement', async () => {
    const draft = new RegistrationDraft()
    draft.replace(FLOW, TOKEN)
    const [left, right] = await Promise.all([draft.identity(), draft.identity()])
    expect(left).toBe(right)
    expect(nameUuidFromBytes(unb64(left.publicBundle().encryption_public_keyset))).toBe(left.bundleId)
    parseHpkePublicKeyset(unb64(left.publicBundle().encryption_public_keyset))
    parseEd25519PublicKeyset(unb64(left.publicBundle().signing_public_keyset))
    draft.wipe()
    expect(left.wiped).toBe(true)
    expect(draft.bundleId()).toBeNull()
    const pending = new RegistrationDraft()
    pending.replace(FLOW, TOKEN)
    const late = pending.identity()
    pending.wipe()
    await expect(late).rejects.toMatchObject({ code: 'STALE' })
    expect(pending.bundleId()).toBeNull()
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
