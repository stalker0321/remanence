import { describe, expect, it } from 'vitest'
import { AuthError } from '../src/auth/errors'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { RecipientDraft, lookupRecipient } from '../src/create/recipient'
import { SessionStore } from '../src/session/session'

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const OTHER = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const DIRECTORY = '9f1c0d2e7a6b4c5d9f1c0d2e7a6b4c5d'

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

function unlock(session: SessionStore, handle = 'ada'): void {
  session.signIn({
    accountId: ACCOUNT,
    bundleId: BUNDLE,
    handle,
    accessToken: 'access-token-value',
    refreshToken: 'refresh-token-value',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
}

function directory(handle: string, userId = OTHER) {
  return {
    user: { user_id: userId, handle },
    key_bundle: {
      key_bundle_id: BUNDLE,
      user_id: userId,
      suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519',
      protocol_version: 1,
      encryption_public_keyset: 'CIabc123',
      signing_public_keyset: 'CJxyz789',
      status: 'ACTIVE',
      created_at: '2026-08-23T03:00:00Z',
    },
    directory_version: DIRECTORY,
  }
}

describe('recipient lookup', () => {
  it('confirms one directory snapshot and ignores a late reply', async () => {
    const session = new SessionStore()
    unlock(session)
    const { transport, calls } = scripted((input) => {
      expect(input.token).toBe('access-token-value')
      if (input.path.endsWith('/missing')) return { status: 404, body: { code: 'HANDLE_NOT_FOUND', email: 'secret@example.com' } }
      return { status: 200, body: directory('ada') }
    })
    const missing = await lookupRecipient(session, transport, '@Missing', undefined)
    expect(missing).toEqual({ kind: 'not-found' })
    expect(JSON.stringify(missing)).not.toContain('secret@example.com')
    expect(calls[0]?.path).toBe('/v1/directory/handles/missing')

    const found = await lookupRecipient(session, transport, '@Ada', undefined)
    expect(found.kind).toBe('found')
    if (found.kind !== 'found') return
    expect(found.snapshot.handle).toBe('ada')
    expect(found.snapshot.userId).toBe(OTHER)
    expect(found.snapshot.self).toBe(false)
    expect(found.snapshot.encryptionPublicKeyset).toBe('CIabc123')
    expect(JSON.stringify(found.snapshot)).not.toContain('created_at')

    const draft = new RecipientDraft()
    const ticket = draft.nextTicket(ACCOUNT)
    expect(draft.offer(ticket, ACCOUNT, found.snapshot)).toBe(true)
    draft.confirm()
    expect(draft.confirmed()?.keyBundleId).toBe(BUNDLE)
    expect(draft.pending()).toBeNull()
    draft.confirm()
    expect(draft.offer(draft.nextTicket(ACCOUNT), ACCOUNT, { ...found.snapshot, handle: 'nope' })).toBe(false)
    expect(draft.confirmed()?.handle).toBe('ada')
    const late = draft.nextTicket(ACCOUNT)
    draft.restart()
    expect(draft.offer(late, ACCOUNT, found.snapshot)).toBe(false)
    expect(draft.confirmed()).toBeNull()
  })

  it('rejects a leaked field, a mismatched bundle, and a locked session', async () => {
    const session = new SessionStore()
    unlock(session)
    const leaked = { ...directory('ada'), email: 'secret@example.com' }
    const mismatched = directory('ada')
    mismatched.key_bundle.user_id = ACCOUNT
    const { transport, calls } = scripted((input) => {
      if (input.path.endsWith('/ada')) return { status: 200, body: leaked }
      if (input.path.endsWith('/bad')) return { status: 422, body: { code: 'HANDLE_INVALID', detail: 'no' } }
      return { status: 200, body: mismatched }
    })
    await expect(lookupRecipient(session, transport, 'ada', undefined)).rejects.toMatchObject({ code: 'DIRECTORY_REJECTED' })
    await expect(lookupRecipient(session, transport, 'bad', undefined)).rejects.toMatchObject({ code: 'HANDLE_INVALID' })
    await expect(lookupRecipient(session, transport, 'a..b', undefined)).rejects.toMatchObject({ code: 'DIRECTORY_REJECTED' })
    expect(calls.map((call) => call.path)).toEqual([
      '/v1/directory/handles/ada',
      '/v1/directory/handles/bad',
      '/v1/directory/handles/a..b',
    ])
    session.lock()
    await expect(lookupRecipient(session, transport, 'ada', undefined)).rejects.toBeInstanceOf(AuthError)
    expect(calls).toHaveLength(3)
  })

  it('addresses the signed-in account only when the directory user matches', async () => {
    const session = new SessionStore()
    unlock(session, 'ada')
    const { transport, calls } = scripted((input) => {
      if (input.path.endsWith('/ada')) return { status: 200, body: directory('ada', ACCOUNT) }
      return { status: 200, body: directory('ada', OTHER) }
    })
    const self = await lookupRecipient(session, transport, 'ignored', undefined, 'self')
    expect(self).toMatchObject({ kind: 'found', snapshot: { userId: ACCOUNT, handle: 'ada', self: true } })
    session.signOut()
    unlock(session, 'ada')
    const swapped = scripted(() => ({ status: 200, body: directory('ada', OTHER) }))
    await expect(lookupRecipient(session, swapped.transport, 'ada', undefined, 'self')).rejects.toMatchObject({ code: 'SELF_REJECTED' })
    expect(swapped.calls).toHaveLength(1)
    const draft = new RecipientDraft()
    draft.attach(session)
    const ticket = draft.nextTicket(ACCOUNT)
    const named = await lookupRecipient(session, transport, 'ada', undefined)
    if (named.kind !== 'found') throw new Error('expected a snapshot')
    expect(draft.offer(ticket, ACCOUNT, named.snapshot)).toBe(true)
    session.signOut()
    expect(draft.confirmed()).toBeNull()
    expect(draft.pending()).toBeNull()
    expect(calls[0]?.path).toBe('/v1/directory/handles/ada')
  })

  it('drops the snapshot when the session ends during the request', async () => {
    const session = new SessionStore()
    unlock(session)
    const { transport, calls } = scripted(() => {
      session.signOut()
      return { status: 200, body: directory('ada') }
    })
    await expect(lookupRecipient(session, transport, 'ada', undefined)).rejects.toMatchObject({ code: 'STALE' })
    expect(calls).toHaveLength(1)
    expect(calls[0]?.token).toBe('access-token-value')
  })
})
