import { describe, expect, it } from 'vitest'
import { enrollHeldIdentity } from '../src/auth/enroll'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { MemoryKeys } from '../src/auth/memoryKeys'
import type { PasskeyAssertion, PasskeyPort } from '../src/auth/passkey'
import { RegistrationDraft } from '../src/auth/register'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { prepareRecoveryRecord } from '../src/crypto/recoveryRecord'
import { SessionStore } from '../src/session/session'

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const FLOW = '11111111-1111-4111-8111-111111111111'
const UPLOAD = '22222222-2222-4222-8222-222222222222'
const CRED = b64(new Uint8Array(16).fill(4))

function assertion(id: string, prf: Uint8Array | null): PasskeyAssertion {
  return {
    id,
    prf,
    publicResponse: {
      id,
      rawId: id,
      type: 'public-key',
      response: { clientDataJSON: 'e30' },
      clientExtensionResults: {},
    },
  }
}

async function heldDraft(): Promise<{ draft: RegistrationDraft; bundleId: string; encryption: string; signing: string }> {
  const draft = new RegistrationDraft()
  draft.replace(FLOW, 'C'.repeat(43))
  const identity = await draft.identity()
  const bundle = identity.publicBundle()
  return {
    draft,
    bundleId: identity.bundleId,
    encryption: bundle.encryption_public_keyset,
    signing: bundle.signing_public_keyset,
  }
}

function sign(session: SessionStore, bundleId: string): void {
  session.signIn({
    accountId: ACCOUNT,
    bundleId,
    handle: 'ada',
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
}

describe('passkey enrollment of the held identity', () => {
  it('creates one passkey, stores one record, and reads that record back', async () => {
    const session = new SessionStore()
    const { draft, bundleId, encryption, signing } = await heldDraft()
    sign(session, bundleId)
    const prf = new Uint8Array(32).fill(7)
    let posted: unknown = null
    let creates = 0
    const calls: string[] = []
    const transport: ApiTransport = {
      request(input: ApiRequest): Promise<ApiResult> {
        calls.push(`${input.method} ${input.path}`)
        if (input.path.endsWith('/status')) return Promise.resolve({ status: 200, body: { state: 'NOT_STARTED', credential_id: null } })
        if (input.path.endsWith('/register/options')) return Promise.resolve({ status: 200, body: { flow: FLOW, options: { challenge: 'aa' } } })
        if (input.path.endsWith('/register/verify')) return Promise.resolve({ status: 200, body: { registered: true, credential_id: CRED } })
        if (input.path.endsWith('/setup/options')) return Promise.resolve({ status: 200, body: { flow: FLOW, options: {} } })
        if (input.path.endsWith('/setup/verify')) {
          return Promise.resolve({ status: 200, body: { authentication_verified: true, credential_id: CRED, flow: UPLOAD } })
        }
        if (input.method === 'POST' && input.path.endsWith('/record')) {
          posted = (input.body as { record: unknown }).record
          return Promise.resolve({ status: 200, body: { stored: true, generation: 1 } })
        }
        return Promise.resolve({
          status: 200,
          body: { record: posted, encryption_public_keyset: encryption, signing_public_keyset: signing },
        })
      },
    }
    const passkeys: PasskeyPort = {
      canAuthenticate: () => true,
      create() {
        creates += 1
        return Promise.resolve(assertion(CRED, null))
      },
      get() {
        return Promise.resolve(assertion(CRED, prf.slice()))
      },
    }
    const outcome = await enrollHeldIdentity(session, transport, passkeys, new MemoryKeys(), draft)
    expect(outcome.kind).toBe('enrolled')
    if (outcome.kind !== 'enrolled') return
    expect(outcome.code.length).toBe(32)
    expect(creates).toBe(1)
    expect(calls.filter((call) => call.includes('/register/options'))).toHaveLength(1)
    expect(calls.filter((call) => call === 'POST /v1/recovery/record')).toHaveLength(1)
    const serialized = JSON.stringify(posted)
    expect(serialized).not.toContain(b64(prf))
    expect(serialized).not.toContain(b64(outcome.code))
    expect(session.getSnapshot().phase).toBe('keys-unlocked')
    expect(draft.bundleId()).toBeNull()
    outcome.code.fill(0)
  })

  it('reads a stored record and does not create another passkey or package', async () => {
    const session = new SessionStore()
    const { draft, bundleId, encryption, signing } = await heldDraft()
    sign(session, bundleId)
    const prf = new Uint8Array(32).fill(9)
    const seeds = (await draft.identity()).copySeeds()
    const prepared = await prepareRecoveryRecord({
      accountId: ACCOUNT,
      bundleId,
      credentialId: CRED,
      prf,
      encryptionSeed: seeds.encryption,
      signingSeed: seeds.signing,
      encryptionPublicKeyset: encryption,
      signingPublicKeyset: signing,
    })
    seeds.encryption.fill(0)
    seeds.signing.fill(0)
    const calls: string[] = []
    const transport: ApiTransport = {
      request(input) {
        calls.push(`${input.method} ${input.path}`)
        if (input.path.endsWith('/status')) return Promise.resolve({ status: 200, body: { state: 'STORED', credential_id: CRED } })
        if (input.path.endsWith('/setup/options')) return Promise.resolve({ status: 200, body: { flow: FLOW, options: {} } })
        if (input.path.endsWith('/setup/verify')) {
          return Promise.resolve({ status: 200, body: { authentication_verified: true, credential_id: CRED, flow: UPLOAD } })
        }
        return Promise.resolve({
          status: 200,
          body: { record: prepared.record, encryption_public_keyset: encryption, signing_public_keyset: signing },
        })
      },
    }
    const passkeys: PasskeyPort = {
      canAuthenticate: () => true,
      create: () => Promise.reject(new Error('create')),
      get: () => Promise.resolve(assertion(CRED, prf.slice())),
    }
    const outcome = await enrollHeldIdentity(session, transport, passkeys, new MemoryKeys(), draft)
    expect(outcome).toEqual({ kind: 'restored' })
    expect(calls.some((call) => call.includes('register') || call.endsWith('POST /v1/recovery/record'))).toBe(false)
    expect(session.getSnapshot().phase).toBe('keys-unlocked')
    expect(draft.bundleId()).toBeNull()
    prepared.code.fill(0)
  })

  it('does not generate a key when this tab has no registration identity', async () => {
    const session = new SessionStore()
    sign(session, 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff')
    const calls: ApiRequest[] = []
    const transport: ApiTransport = {
      request(input) {
        calls.push(input)
        return Promise.resolve({ status: 500, body: null })
      },
    }
    const passkeys: PasskeyPort = {
      canAuthenticate: () => true,
      create: () => Promise.reject(new Error('create')),
      get: () => Promise.reject(new Error('get')),
    }
    await expect(enrollHeldIdentity(session, transport, passkeys, new MemoryKeys(), new RegistrationDraft())).rejects.toMatchObject({
      code: 'IDENTITY_ABSENT',
    })
    expect(calls).toHaveLength(0)
    expect(session.getSnapshot().phase).toBe('authenticated')
  })
})
