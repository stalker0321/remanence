import { describe, expect, it } from 'vitest'
import { AuthError } from '../src/auth/errors'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { MemoryKeys } from '../src/auth/memoryKeys'
import type { PasskeyAssertion, PasskeyPort } from '../src/auth/passkey'
import { browserPasskey } from '../src/auth/passkey'
import { restoreSignedInAccount } from '../src/auth/restore'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import { utf8 } from '../src/protocol/bytes'
import { recipientEnvelopeInfo } from '../src/protocol/context'
import { SessionStore } from '../src/session/session'
import { sealTo, syntheticRecovery } from './recoveryFixture'

const ACCESS = 'signed-in-access'
const REFRESH = 'signed-in-refresh'

function scripted(handle: (input: ApiRequest) => ApiResult): { transport: ApiTransport; calls: ApiRequest[] } {
  const calls: ApiRequest[] = []
  return {
    calls,
    transport: {
      request(input) {
        calls.push(input)
        return Promise.resolve(handle(input))
      },
    },
  }
}

function passkey(assertion: PasskeyAssertion | Error): PasskeyPort & { gets: unknown[] } {
  const gets: unknown[] = []
  return {
    gets,
    canAuthenticate: () => true,
    get(options) {
      gets.push(options)
      if (assertion instanceof Error) return Promise.reject(assertion)
      return Promise.resolve(assertion)
    },
    create() {
      return Promise.reject(new AuthError('PASSKEY_UNAVAILABLE'))
    },
  }
}

function assertion(id: string, prf: Uint8Array | null): PasskeyAssertion {
  return {
    id,
    prf,
    publicResponse: {
      id,
      rawId: id,
      type: 'public-key',
      response: { clientDataJSON: 'e30', authenticatorData: 'YQ', signature: 'YQ', userHandle: null },
      clientExtensionResults: {},
    },
  }
}

function signIn(session: SessionStore, accountId: string, bundleId: string) {
  session.signIn({
    accountId,
    bundleId,
    handle: 'ada',
    accessToken: ACCESS,
    refreshToken: REFRESH,
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
}

describe('signed-in passkey restore', () => {
  it('opens the same account from the stored record and keeps the prf off the request', async () => {
    const fixture = await syntheticRecovery()
    const accountId = fixture.reply.record.account_id
    const bundleId = fixture.reply.record.bundle_id
    const credentialId = fixture.reply.record.credential_id
    const session = new SessionStore()
    const keys = new MemoryKeys()
    keys.attach(session)
    signIn(session, accountId, bundleId)
    const options = { challenge: 'YQ', rpId: 'localhost', allowCredentials: [{ id: credentialId, type: 'public-key' }] }
    const { transport, calls } = scripted((input) => {
      if (input.path === '/v1/recovery/status') return { status: 200, body: { state: 'STORED', credential_id: credentialId } }
      if (input.path === '/v1/recovery/setup/options') return { status: 200, body: { flow: '22222222-2222-4222-8222-222222222222', options } }
      if (input.path === '/v1/recovery/setup/verify') return { status: 200, body: { authentication_verified: true, credential_id: credentialId } }
      if (input.path === '/v1/recovery/record') return { status: 200, body: fixture.reply }
      throw new Error(`unexpected ${input.path}`)
    })
    const manager = passkey(assertion(credentialId, fixture.prf.slice()))
    await expect(restoreSignedInAccount(session, transport, manager, keys)).resolves.toEqual({ kind: 'unlocked' })
    expect(calls.map((call) => call.path)).toEqual([
      '/v1/recovery/status',
      '/v1/recovery/setup/options',
      '/v1/recovery/setup/verify',
      '/v1/recovery/record',
    ])
    expect(calls.every((call) => call.token === ACCESS)).toBe(true)
    expect(calls.some((call) => call.path.includes('login') || call.path.includes('register') || call.path.includes('google'))).toBe(false)
    const verify = calls.find((call) => call.path.endsWith('/verify'))
    expect(JSON.stringify(verify?.body)).not.toContain('3333')
    expect(manager.gets).toEqual([options])
    expect(session.getSnapshot().phase).toBe('keys-unlocked')
    expect(session.getSnapshot().accountId).toBe(accountId)
    const caps = keys.capabilities(session.getSnapshot())
    const info = recipientEnvelopeInfo({
      capsuleId: '00010203-0405-0607-0809-0a0b0c0d0e0f',
      senderUserId: '10111213-1415-1617-1819-1a1b1c1d1e1f',
      recipientUserId: accountId,
      recipientKeyBundleId: bundleId,
    })
    const sealed = await sealTo(fixture.publicKey, fixture.encryptionKeyId, info, utf8('same-memory'))
    expect(caps).not.toBeNull()
    expect(new TextDecoder().decode(await caps?.openEnvelope(info, sealed))).toBe('same-memory')
    session.signOut()
    expect(keys.capabilities(session.getSnapshot())).toBeNull()
    await expect(caps?.openEnvelope(info, sealed)).rejects.toMatchObject({ code: 'LOCKED' })
  })

  it('keeps the signed-in account when the passkey, record, or capability does not match', async () => {
    const fixture = await syntheticRecovery()
    const credentialId = fixture.reply.record.credential_id
    const cases: { name: string; status: ApiResult; assertion: PasskeyAssertion | Error; record?: unknown; code: string; verify: boolean }[] = [
      {
        name: 'not started',
        status: { status: 200, body: { state: 'NOT_STARTED', credential_id: null } },
        assertion: assertion(credentialId, fixture.prf.slice()),
        code: 'RECOVERY_NOT_STARTED',
        verify: false,
      },
      {
        name: 'cancelled',
        status: { status: 200, body: { state: 'STORED', credential_id: credentialId } },
        assertion: new AuthError('PASSKEY_CANCELLED'),
        code: 'PASSKEY_CANCELLED',
        verify: false,
      },
      {
        name: 'wrong record',
        status: { status: 200, body: { state: 'STORED', credential_id: credentialId } },
        assertion: assertion(credentialId, fixture.prf.slice()),
        record: {
          ...fixture.reply,
          record: { ...fixture.reply.record, account_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' },
        },
        code: 'WRONG_ACCOUNT',
        verify: true,
      },
      {
        name: 'prf missing',
        status: { status: 200, body: { state: 'STORED', credential_id: credentialId } },
        assertion: assertion(credentialId, null),
        code: 'PRF_UNAVAILABLE',
        verify: false,
      },
      {
        name: 'wrong passkey',
        status: { status: 200, body: { state: 'STORED', credential_id: credentialId } },
        assertion: assertion('other-credential', fixture.prf.slice()),
        code: 'WRONG_CREDENTIAL',
        verify: false,
      },
    ]
    for (const item of cases) {
      const session = new SessionStore()
      const keys = new MemoryKeys()
      signIn(session, fixture.reply.record.account_id, fixture.reply.record.bundle_id)
      const { transport, calls } = scripted((input) => {
        if (input.path.endsWith('/status')) return item.status
        if (input.path.endsWith('/options')) return { status: 200, body: { flow: '22222222-2222-4222-8222-222222222222', options: { challenge: 'YQ' } } }
        if (input.path.endsWith('/record')) return { status: 200, body: item.record ?? fixture.reply }
        return { status: 200, body: { authentication_verified: true, credential_id: credentialId } }
      })
      await expect(restoreSignedInAccount(session, transport, passkey(item.assertion), keys)).rejects.toMatchObject({ code: item.code })
      expect(session.getSnapshot().phase, item.name).toBe('authenticated')
      expect(session.getSnapshot().accountId, item.name).toBe(fixture.reply.record.account_id)
      expect(keys.capabilities(session.getSnapshot()), item.name).toBeNull()
      expect(calls.some((call) => call.path.includes('verify')), item.name).toBe(item.verify)
      expect(calls.some((call) => call.path.includes('register')), item.name).toBe(false)
    }
  })

  it('reports a saved passkey without creating another or unlocking', async () => {
    const session = new SessionStore()
    const keys = new MemoryKeys()
    signIn(session, 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff')
    const manager = passkey(assertion('pending-id', new Uint8Array(32)))
    const { transport, calls } = scripted(() => ({ status: 200, body: { state: 'PENDING', credential_id: 'pending-id' } }))
    await expect(restoreSignedInAccount(session, transport, manager, keys)).resolves.toEqual({ kind: 'pending-verification' })
    expect(calls.map((call) => call.path)).toEqual(['/v1/recovery/status'])
    expect(manager.gets).toEqual([])
    expect(session.getSnapshot().phase).toBe('authenticated')
  })

  it('drops keys when the session changes before they are installed', async () => {
    const fixture = await syntheticRecovery()
    const credentialId = fixture.reply.record.credential_id
    const session = new SessionStore()
    const keys = new MemoryKeys()
    keys.attach(session)
    signIn(session, fixture.reply.record.account_id, fixture.reply.record.bundle_id)
    const { transport } = scripted((input) => {
      if (input.path.endsWith('/status')) return { status: 200, body: { state: 'STORED', credential_id: credentialId } }
      if (input.path.endsWith('/options')) return { status: 200, body: { flow: '22222222-2222-4222-8222-222222222222', options: {} } }
      if (input.path.endsWith('/verify')) return { status: 200, body: { authentication_verified: true, credential_id: credentialId } }
      return { status: 200, body: fixture.reply }
    })
    let locked = false
    const opener = (): Promise<KeyCapabilities> => {
      session.signOut()
      return Promise.resolve({
        accountId: fixture.reply.record.account_id,
        bundleId: fixture.reply.record.bundle_id,
        openEnvelope: () => Promise.resolve(new Uint8Array()),
        decryptArtifact: () => Promise.resolve(new Uint8Array()),
        sign: () => Promise.resolve(new Uint8Array()),
        lock: () => { locked = true },
      })
    }
    await expect(restoreSignedInAccount(session, transport, passkey(assertion(credentialId, fixture.prf.slice())), keys, undefined, opener)).rejects.toMatchObject({ code: 'STALE' })
    expect(locked).toBe(true)
    expect(session.getSnapshot().phase).toBe('signed-out')
    expect(keys.capabilities(session.getSnapshot())).toBeNull()
  })

  it('treats a node test runtime as unable to authenticate', () => {
    expect(browserPasskey().canAuthenticate()).toBe(false)
    const session = new SessionStore()
    signIn(session, 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff')
    return expect(restoreSignedInAccount(session, scripted(() => ({ status: 500, body: null })).transport, browserPasskey(), new MemoryKeys())).rejects.toBeInstanceOf(AuthError)
  })
})
