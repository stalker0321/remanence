/** T410 declared v1 cancel regression. Synthetic contract, real client/crypto;
 * actual API/PG lifecycle proof is separately pinned in the task packet. */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AtomicRegistration } from '../src/auth/atomicRegistration'
import { atomicTransport, success } from '../src/auth/atomicWire'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { restoreSignedInAccount } from '../src/auth/restore'
import { SessionStore } from '../src/session/session'
import * as identity from '../src/crypto/identity'
import { AtomicFixture, RESERVATION } from './atomicFixture'
import { completeAtomicSteps } from './atomicSteps'

const cleanups: (() => void)[] = []
afterEach(() => { for (const clean of cleanups.splice(0)) clean(); vi.restoreAllMocks() })
function context() {
  const f = new AtomicFixture(), session = new SessionStore(), keys = new MemoryKeys()
  const detach = keys.attach(session)
  const controller = new AtomicRegistration({ session, keys, setup: f.setup, api: f.api, google: f.google, passkeys: f.passkeys })
  cleanups.push(() => { controller.dispose(); keys.drop(); detach() })
  return { f, session, keys, controller }
}
function held(f: AtomicFixture) {
  let arrived!: () => void, release!: () => void
  const reached = new Promise<void>((done) => { arrived = done })
  const wait = new Promise<void>((done) => { release = done })
  f.pause = async (point) => { if (point === 'cancel:after') { arrived(); await wait } }
  return { reached, release }
}

describe('cancel terminal wire and lifecycle without regeneration', () => {
  it('actual bounded transport/parser preserves exact410 reason, route and no-store/abort controls', async () => {
    const send = vi.fn(async () => new Response('{"reason":"RESERVATION_TERMINAL"}', {
      status: 410, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
    }))
    const abort = new AbortController()
    const result = await atomicTransport('', send).request({ action: 'cancel', reservation: RESERVATION,
      token: `rgs_${'A'.repeat(43)}`, body: { epoch: 7 }, signal: abort.signal })
    expect(result).toEqual({ status: 410, body: { reason: 'RESERVATION_TERMINAL' } })
    expect(() => success(result)).toThrow(expect.objectContaining({ code: 'RESERVATION_TERMINAL', status: 410 }))
    expect(send).toHaveBeenCalledExactlyOnceWith(`/v2/registration/reservations/${RESERVATION}/cancel`, {
      method: 'POST', headers: { accept: 'application/json', 'x-remanence-profile-version': '2',
        authorization: `Bearer rgs_${'A'.repeat(43)}`, 'content-type': 'application/json' },
      body: '{"epoch":7}', credentials: 'omit', redirect: 'error', cache: 'no-store', signal: abort.signal,
    })
  })

  it.each(['fresh-terminal-proof', 'expires-with-live-candidate'] as const)('%s:410 wipes local candidate and needs fresh ownership before explicit restart', async (mode) => {
    const c = context(), generate = vi.spyOn(identity, 'generateLocalIdentity')
    if (mode === 'fresh-terminal-proof') c.f.reservation.state = 'EXPIRED'
    await c.controller.begin()
    if (mode === 'expires-with-live-candidate') {
      c.f.finalMode = 'handle'; await completeAtomicSteps(c.controller, 'taken')
      expect(c.controller.getSnapshot().phase).toBe('handle-conflict')
      expect(c.f.secrets.some((value) => value.some((byte) => byte !== 0))).toBe(true)
      c.f.reservation.state = 'EXPIRED'
    }
    const old = c.f.reservation.id, before = generate.mock.calls.length, creates = c.f.creates
    await c.controller.cancelReservation()
    expect(c.controller.getSnapshot()).toMatchObject({ phase: 'reauthenticate', error: 'RESERVATION_TERMINAL', busy: false })
    expect(c.session.getSnapshot().phase).toBe('signed-out'); expect(c.keys.capabilities(c.session.getSnapshot())).toBeNull()
    expect(c.f.secrets.every((value) => value.every((byte) => byte === 0))).toBe(true)
    await c.controller.save('replacement'); await c.controller.retry(); await c.controller.restart()
    expect(generate).toHaveBeenCalledTimes(before); expect(c.f.creates).toBe(creates)
    expect(c.f.provisions).toBe(0); expect(c.f.calls).not.toContain('restart'); expect(c.f.reservation.id).toBe(old)
    await c.controller.begin()
    expect(c.controller.getSnapshot().phase).toBe('terminal'); expect(c.f.reservation.id).toBe(old)
    await c.controller.restart()
    expect(c.controller.getSnapshot()).toMatchObject({ phase: 'handle', credentialCount: 0 })
    expect(c.f.reservation.id).not.toBe(old); expect(generate).toHaveBeenCalledTimes(before); expect(c.f.creates).toBe(creates)
  })

  it('valid cancel and repeated CANCELLED200 converge without generating or restarting', async () => {
    const c = context(), generate = vi.spyOn(identity, 'generateLocalIdentity')
    await c.controller.begin(); const old = c.f.reservation.id
    await c.controller.cancelReservation(); await c.controller.cancelReservation()
    expect(c.controller.getSnapshot()).toMatchObject({ phase: 'reauthenticate', reservationState: 'CANCELLED', error: null })
    expect(c.f.calls.filter((action) => action === 'cancel')).toHaveLength(2)
    expect(c.f.reservation.id).toBe(old); expect(c.f.calls).not.toContain('restart')
    expect(generate).not.toHaveBeenCalled(); expect(c.f.provisions).toBe(0)
  })

  it('ACTIVE409 after a lost finalize cannot imply cancelled account or replace the original keys', async () => {
    const c = context(), generate = vi.spyOn(identity, 'generateLocalIdentity')
    await c.controller.begin(); c.f.finalMode = 'lost-after'; await completeAtomicSteps(c.controller, 'original')
    const saved = c.f.saved; expect(c.controller.getSnapshot().phase).toBe('unknown')
    await c.controller.cancelReservation()
    expect(c.controller.getSnapshot()).toMatchObject({ phase: 'restore-required', error: 'ALREADY_ACTIVE' })
    expect(c.session.getSnapshot().phase).toBe('signed-out'); expect(c.f.saved).toBe(saved)
    expect(c.f.secrets.every((value) => value.every((byte) => byte === 0))).toBe(true)
    await c.controller.save('replacement'); await c.controller.restart(); await c.controller.begin()
    await restoreSignedInAccount(c.session, c.f.api, c.f.passkeys, c.keys)
    expect(c.keys.capabilities(c.session.getSnapshot())?.bundleId).toBe(saved!.record.bundle_id)
    expect(generate).toHaveBeenCalledTimes(1); expect(c.f.creates).toBe(1); expect(c.f.provisions).toBe(1)
  })

  it.each([200, 410])('late cancel%d after Back/leave cannot revive setup or erase a newer owner', async (status) => {
    const c = context(); await c.controller.begin()
    c.f.finalMode = 'handle'; await completeAtomicSteps(c.controller, 'taken')
    if (status === 410) c.f.reservation.state = 'EXPIRED'
    const hold = held(c.f), pending = c.controller.cancelReservation()
    await hold.reached; c.controller.leave()
    const replacement = { accountId: RESERVATION, bundleId: RESERVATION, openEnvelope: vi.fn(), decryptArtifact: vi.fn(), sign: vi.fn(), lock: vi.fn() }
    c.session.signIn({ accountId: RESERVATION, bundleId: RESERVATION, handle: 'newer', accessToken: 'synthetic', refreshToken: 'synthetic', accessExpiresAt: '', refreshExpiresAt: '' })
    c.session.unlock(); c.keys.hold(c.session.getSnapshot(), replacement)
    hold.release(); await pending
    expect(c.controller.getSnapshot().phase).toBe('idle')
    expect(c.keys.capabilities(c.session.getSnapshot())).toBe(replacement); expect(replacement.lock).not.toHaveBeenCalled()
    expect(c.f.secrets.every((value) => value.every((byte) => byte === 0))).toBe(true)
    expect(c.f.provisions).toBe(0); expect(c.f.calls).not.toContain('restart')
  })
})
