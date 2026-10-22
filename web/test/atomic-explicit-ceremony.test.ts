import { describe, expect, it } from 'vitest'
import { AtomicRegistration } from '../src/auth/atomicRegistration'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { SessionStore } from '../src/session/session'
import { AtomicFixture, CREDENTIAL } from './atomicFixture'

function fixture() {
  const f = new AtomicFixture(), session = new SessionStore(), keys = new MemoryKeys()
  keys.attach(session)
  const controller = new AtomicRegistration({ session, keys, setup: f.setup, api: f.api, google: f.google, passkeys: f.passkeys })
  return { f, session, keys, controller }
}

describe('explicit create, then verify ceremony', () => {
  it('handle opens no provider; creation returns without GET, crypto generation or READY', async () => {
    const { f, controller, session, keys } = fixture()
    try {
      await controller.begin()
      controller.chooseHandle('ada')
      expect(controller.getSnapshot().phase).toBe('passkey')
      expect(f.creates).toBe(0)
      expect(f.assertions).toBe(0)
      await controller.createPasskey()
      expect(controller.getSnapshot()).toMatchObject({ phase: 'verify', busy: false, credentialCount: 1 })
      expect(f.creates).toBe(1)
      expect(f.assertions).toBe(0)
      expect(f.intents).toHaveLength(0)
      expect(f.calls).not.toContain('setup/options')
      expect(session.getSnapshot().phase).toBe('signed-out')
      expect(keys.capabilities(session.getSnapshot())).toBeNull()
      await controller.save('ada')
      expect(controller.getSnapshot().phase).toBe('ready')
      expect(f.assertions).toBe(1)
      expect(f.calls.slice(-2)).toEqual(['GET /v1/recovery/status', 'GET /v1/recovery/record'])
    } finally { controller.dispose(); keys.drop() }
  })

  it.each(['cancelProvider', 'noPrf'] as const)('%s keeps the created key and only retries verification', async failure => {
    const { f, controller, keys } = fixture()
    try {
      await controller.begin(); controller.chooseHandle('ada'); await controller.createPasskey()
      f[failure] = true
      await controller.save('ada')
      expect(controller.getSnapshot()).toMatchObject({ phase: 'verify', credentialCount: 1 })
      await controller.createPasskey()
      expect(f.creates).toBe(1)
      expect(f.intents).toHaveLength(0)
      f[failure] = false
      await controller.save('ada')
      expect(controller.getSnapshot().phase).toBe('ready')
      expect(f.creates).toBe(1)
    } finally { controller.dispose(); keys.drop() }
  })

  it.each(['before', 'after'] as const)('lost staging %s receipt retains public proof; empty status cannot cause another CREATE', async when => {
    const { f, controller, keys } = fixture()
    const request = f.setup.request
    let fail = true
    const proofs: unknown[] = []
    f.setup.request = async input => {
      if (input.action === 'credential/verify') {
        proofs.push(input.body)
        if (fail && when === 'before') throw new Error('synthetic network interruption')
        const reply = await request(input)
        if (fail) throw new Error('synthetic lost response')
        return reply
      }
      return request(input)
    }
    try {
      await controller.begin(); controller.chooseHandle('ada'); await controller.createPasskey()
      expect(controller.getSnapshot().phase).toBe('credential-pending')
      expect(f.creates).toBe(1)
      expect(f.assertions).toBe(0)
      fail = false
      // Server sorts up to four IDs. An older one cannot steal a retained
      // creation when reconciling either side of the staging boundary.
      f.reservation.credential_ids = when === 'after' ? ['AQ', CREDENTIAL] : ['AQ']
      await controller.createPasskey()
      expect(controller.getSnapshot().phase).toBe('verify')
      expect(f.creates).toBe(1)
      expect(f.assertions).toBe(0)
      if (when === 'before') expect(proofs[1]).toEqual(proofs[0])
      else expect(proofs).toHaveLength(1)
      await controller.save('ada')
      expect(controller.getSnapshot().phase).toBe('ready')
    } finally { controller.dispose(); keys.drop() }
  })
})
