import { describe, expect, it } from 'vitest'
import { continueWithGoogle } from '../src/auth/google'
import { enrollHeldIdentity } from '../src/auth/enroll'
import { restoreSignedInAccount } from '../src/auth/restore'
import { RegistrationDraft } from '../src/auth/register'
import { MemoryKeys } from '../src/auth/memoryKeys'
import type { ApiResult, ApiTransport } from '../src/auth/http'
import type { PasskeyPort } from '../src/auth/passkey'
import { SessionStore } from '../src/session/session'
import { syntheticRecovery } from './recoveryFixture'

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff'
const FLOW = '11111111-1111-4111-8111-111111111111'
const CRED = 'c3ludGhldGljLWNyZWRlbnRpYWw'
const sign = (s: SessionStore, accountId = ACCOUNT, bundleId = BUNDLE) => s.signIn({ accountId, bundleId,
  handle: 'synthetic', accessToken: 'test-only', refreshToken: 'test-only', accessExpiresAt: '', refreshExpiresAt: '' })
const assertion = (id: string, prf: Uint8Array | null) => ({ id, prf,
  publicResponse: { id, rawId: id, type: 'public-key' as const, response: { clientDataJSON: 'e30' }, clientExtensionResults: {} } })
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>((ok) => { resolve = ok }); return { promise, resolve } }

describe('interrupted identity continuity within existing protocol', () => {
  for (const boundary of ['options', 'identity', 'verify'] as const) {
    it(`late Google ${boundary} cannot replace a new signed-in owner`, async () => {
      const session = new SessionStore(), draft = new RegistrationDraft()
      const gate = deferred<void>(), reached = deferred<void>()
      let providers = 0, verifies = 0
      const pause = async (step: string) => { if (step === boundary) { reached.resolve(); await gate.promise } }
      const run = continueWithGoogle(session, { async request(input) {
        if (input.path.endsWith('/options')) { await pause('options'); return { status: 200, body: { flow: FLOW, nonce: 'A'.repeat(43), client_id: 'synthetic' } } }
        verifies++; await pause('verify')
        return { status: 200, body: { status: 'SIGNED_IN', login: { user: { user_id: ACCOUNT, handle: 'old' },
          active_key_bundle: { key_bundle_id: BUNDLE }, access_token: 'old', refresh_token: 'old', access_expires_at: '', refresh_expires_at: '' } } }
      } }, { async requestIdToken() { providers++; await pause('identity'); return 'synthetic' } }, undefined, draft)
      const refused = expect(run).rejects.toMatchObject({ code: 'STALE' })
      await reached.promise
      sign(session, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
      const newer = session.getSnapshot()
      gate.resolve(); await refused
      expect(session.getSnapshot()).toBe(newer)
      expect(providers).toBe(boundary === 'options' ? 0 : 1)
      expect(verifies).toBe(boundary === 'verify' ? 1 : 0)
      expect(draft.bundleId()).toBeNull()
    })
  }
  for (const flow of ['restore', 'enroll'] as const) {
    for (const boundary of ['status', 'options', 'passkey', 'record'] as const) {
      it(`${flow}: cancellation at ${boundary} cannot start another ceremony or unlock`, async () => {
        const fixture = await syntheticRecovery(), session = new SessionStore(), keys = new MemoryKeys()
        const draft = new RegistrationDraft(); draft.replace(FLOW, 'A'.repeat(43))
        const identity = flow === 'enroll' ? await draft.identity() : null
        sign(session, fixture.reply.record.account_id, identity?.bundleId ?? fixture.reply.record.bundle_id)
        const before = session.getSnapshot(), controller = new AbortController()
        const gate = deferred<void>(), reached = deferred<void>()
        const pause = async (step: string) => { if (step === boundary) { reached.resolve(); await gate.promise } }
        let prompts = 0, verifies = 0
        const secret = fixture.prf.slice(), credential = fixture.reply.record.credential_id
        const transport: ApiTransport = { async request(input) {
          if (input.path.endsWith('/status')) { await pause('status'); return { status: 200, body: { state: 'STORED', credential_id: credential } } }
          if (input.path.endsWith('/options')) { await pause('options'); return { status: 200, body: { flow: FLOW, options: {} } } }
          if (input.path.endsWith('/verify')) { verifies++; return { status: 200, body: { authentication_verified: true, credential_id: credential, flow: FLOW } } }
          await pause('record'); return { status: 200, body: fixture.reply }
        } }
        const passkeys: PasskeyPort = { canAuthenticate: () => true,
          create: async () => { throw new Error('must not create a new passkey') },
          get: async () => { prompts++; await pause('passkey'); return assertion(credential, secret) } }
        const run = flow === 'enroll' ? enrollHeldIdentity(session, transport, passkeys, keys, draft, controller.signal)
          : restoreSignedInAccount(session, transport, passkeys, keys, controller.signal)
        const refused = expect(run).rejects.toMatchObject({ code: 'CANCELLED' })
        await reached.promise; controller.abort(); gate.resolve(); await refused
        expect(session.getSnapshot()).toBe(before)
        expect(keys.capabilities(before)).toBeNull()
        expect(prompts).toBe(boundary === 'status' || boundary === 'options' ? 0 : 1)
        expect(verifies).toBe(boundary === 'record' ? 1 : 0)
        if (prompts) expect(secret.every((n) => n === 0)).toBe(true)
        draft.wipe()
      })
    }
  }
  it('lost reply after encrypted record storage resumes by readback; reload restores the same bundle', async () => {
    const session = new SessionStore(), draft = new RegistrationDraft(), keys = new MemoryKeys()
    draft.replace(FLOW, 'A'.repeat(43))
    const identity = await draft.identity(), bundle = identity.publicBundle()
    sign(session, ACCOUNT, identity.bundleId)
    const prf = new Uint8Array(32).fill(12)
    let creates = 0, saves = 0, record: unknown = null
    const transport: ApiTransport = { async request(input): Promise<ApiResult> {
      if (input.path.endsWith('/status')) return { status: 200, body: { state: record ? 'STORED' : 'NOT_STARTED', credential_id: record ? CRED : null } }
      if (input.path.endsWith('/options')) return { status: 200, body: { flow: FLOW, options: {} } }
      if (input.path.endsWith('/register/verify')) return { status: 200, body: { registered: true, credential_id: CRED } }
      if (input.path.endsWith('/setup/verify')) return { status: 200, body: { authentication_verified: true, credential_id: CRED, flow: FLOW } }
      if (input.method === 'POST') { saves++; record = (input.body as { record: unknown }).record; throw new Error('synthetic lost response AFTER storage') }
      return { status: 200, body: { record, encryption_public_keyset: bundle.encryption_public_keyset, signing_public_keyset: bundle.signing_public_keyset } }
    } }
    const passkeys: PasskeyPort = { canAuthenticate: () => true,
      create: async () => { creates++; return assertion(CRED, null) }, get: async () => assertion(CRED, prf.slice()) }
    await expect(enrollHeldIdentity(session, transport, passkeys, keys, draft)).rejects.toThrow('AFTER storage')
    expect(session.getSnapshot().phase).toBe('authenticated')
    expect(draft.bundleId()).toBe(identity.bundleId)
    expect(await enrollHeldIdentity(session, transport, passkeys, keys, draft)).toEqual({ kind: 'restored' })
    expect([creates, saves]).toEqual([1, 1])
    expect(draft.bundleId()).toBeNull()
    const restarted = new SessionStore(), freshKeys = new MemoryKeys()
    sign(restarted, ACCOUNT, identity.bundleId)
    expect(await restoreSignedInAccount(restarted, transport, passkeys, freshKeys)).toEqual({ kind: 'unlocked' })
    expect(freshKeys.capabilities(restarted.getSnapshot())?.bundleId).toBe(identity.bundleId)
    expect([creates, saves]).toEqual([1, 1])
    keys.drop(); freshKeys.drop(); prf.fill(0)
  })
})
