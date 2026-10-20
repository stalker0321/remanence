import { describe, expect, it } from 'vitest'
import { SessionStore } from '../src/session/session'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { PresentationMemory } from '../src/collage/memory'
import { receivePostcard } from '../src/scan/receive'
import { RegistrationDraft, registerNewAccount } from '../src/auth/register'
import { jsPressCapture, jsPressIds, jsPressMaterial } from './sealedFixture'
import { fitText } from './sheets'

describe('T388 current-base unsafe boundaries', () => {
  it('cannot present prepared plaintext without an authenticated first-open transport', async () => {
    const material = await jsPressMaterial()
    const session = new SessionStore()
    session.signIn({ accountId: jsPressIds.account, bundleId: jsPressIds.bundle, handle: 'ada',
      accessToken: 'synthetic', refreshToken: 'synthetic', accessExpiresAt: '', refreshExpiresAt: '' })
    session.unlock()
    const keys = new MemoryKeys()
    keys.attach(session)
    keys.hold(session.getSnapshot(), material.capabilities())
    const memory = new PresentationMemory({ create: () => 'memory:forbidden', revoke: () => {} })
    memory.attach(session)
    await expect(receivePostcard(session, keys, memory,
      { match: async () => ({ kind: 'one', capsuleId: jsPressIds.capsule }) }, jsPressCapture,
      { items: [material.capsule], hasMore: false, nextCursor: null },
      { read: async (_item, id) => material.ciphertext(id).slice() }, fitText,
      new AbortController().signal)).rejects.toMatchObject({ code: 'ADMISSION_UNAVAILABLE' })
    expect(memory.getSnapshot()).toBeNull()
    expect(session.getSnapshot().phase).toBe('keys-unlocked')
  })

  it('does not irreversibly register a public bundle before reload-safe continuity exists', async () => {
    const session = new SessionStore()
    const draft = new RegistrationDraft()
    draft.replace('11111111-2222-4333-8444-555555555555', 'T'.repeat(43))
    let commits = 0
    await expect(registerNewAccount(session, { request: async (request) => {
      commits += 1
      const body = request.body as { key_bundle: { key_bundle_id: string } }
      return { status: 200, body: { status: 'SIGNED_IN', login: {
        user: { user_id: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', handle: 'ada' },
        active_key_bundle: { key_bundle_id: body.key_bundle.key_bundle_id },
        access_token: 'synthetic', refresh_token: 'synthetic', access_expires_at: '', refresh_expires_at: '',
      } } }
    } }, draft, 'ada')).rejects.toMatchObject({ code: 'REGISTRATION_PAUSED' })
    expect(commits).toBe(0)
    expect(session.getSnapshot().phase).toBe('signed-out')
    expect(draft.bundleId()).toBeNull()
    // A fresh document has no held identity: warnings cannot bridge this gap.
    expect(new RegistrationDraft().bundleId()).toBeNull()
  })
})
