import { afterEach, describe, expect, it, vi } from 'vitest'
import { AtomicRegistration } from '../src/auth/atomicRegistration'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { SessionStore } from '../src/session/session'
import { restoreSignedInAccount } from '../src/auth/restore'
import * as identity from '../src/crypto/identity'
import * as ownerCrypto from '../src/crypto/keyOwner'
import { AtomicFixture, RESERVATION } from './atomicFixture'
import { DhkemX25519HkdfSha256 } from '@hpke/core'
import { unb64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { parseHpkePublicKeyset } from '../src/crypto/tink'
import { arrayBuffer, utf8 } from '../src/protocol/bytes'
import { sealTo } from './recoveryFixture'

const cleanups: (() => void)[] = []
afterEach(() => { for (const cleanup of cleanups.splice(0)) cleanup(); vi.restoreAllMocks() })
function context(f = new AtomicFixture()) {
  const session = new SessionStore(); const keys = new MemoryKeys()
  const detach = keys.attach(session)
  const controller = new AtomicRegistration({ session, keys, setup: f.setup, api: f.api, google: f.google, passkeys: f.passkeys })
  cleanups.push(() => { controller.dispose(); keys.drop(); detach() })
  return { f, session, keys, controller }
}
function gate(f: AtomicFixture, point: string) {
  let release!: () => void; let reached!: () => void
  const arrived = new Promise<void>((resolve) => { reached = resolve })
  const held = new Promise<void>((resolve) => { release = resolve })
  f.pause = async (current) => { if (current === point) { reached(); await held } }
  return { arrived, release }
}
async function saved(c = context()) { await c.controller.begin(); await c.controller.save('@Ada'); return c }

describe('atomic A document ownership / real ADR011 crypto', () => {
  it('generates only after a verified assertion, reads actual stored bytes, drops generation and PRF before READY', async () => {
    const c = context(); const generate = vi.spyOn(identity, 'generateLocalIdentity')
    await c.controller.begin()
    expect(generate).not.toHaveBeenCalled(); expect(c.session.getSnapshot().phase).toBe('signed-out')
    const hold = gate(c.f, 'setup/verify:after'); const run = c.controller.save('ada')
    await hold.arrived; expect(generate).not.toHaveBeenCalled(); hold.release(); await run
    expect(generate).toHaveBeenCalledTimes(1)
    expect((await generate.mock.results[0]!.value).wiped).toBe(true)
    expect(c.controller.getSnapshot().phase).toBe('ready')
    expect(c.session.getSnapshot().phase).toBe('keys-unlocked')
    expect(c.f.calls.slice(-2)).toEqual(['GET /v1/recovery/status', 'GET /v1/recovery/record'])
    expect(c.f.secrets.every((b) => b.every((v) => v === 0))).toBe(true)
    expect(c.keys.capabilities(c.session.getSnapshot())?.bundleId).toBe(c.f.saved!.key_bundle.key_bundle_id)
    expect(JSON.stringify(c.controller.getSnapshot())).not.toMatch(/rgs_|rgf_|pm_at_|package_sealed/)
  })

  it('duplicate Enter joins one Google operation and one immutable finalize', async () => {
    const c = context(); const begin = c.controller.begin()
    expect(c.controller.begin()).toBe(begin); await begin
    const run = c.controller.save('ada'); expect(c.controller.save('other')).toBe(run); await run
    expect(c.f.creates).toBe(1); expect(c.f.intents).toHaveLength(1); expect(c.f.provisions).toBe(1)
  })

  it.each(['noPrf', 'cancelProvider'] as const)('%s never generates or commits, retries the same verified credential', async (kind) => {
    const c = context(); c.f[kind] = true; const generate = vi.spyOn(identity, 'generateLocalIdentity')
    await saved(c)
    expect(generate).not.toHaveBeenCalled(); expect(c.f.intents).toHaveLength(0); expect(c.f.creates).toBe(1)
    c.f[kind] = false; await c.controller.save('ada')
    expect(c.controller.getSnapshot().phase).toBe('ready'); expect(c.f.creates).toBe(1)
  })

  it('handle correction retains the exact record/bundle/grant and uses only a new operation ID', async () => {
    const c = context(); c.f.finalMode = 'handle'; await saved(c)
    const first = c.f.intents[0]!
    expect(c.controller.getSnapshot().phase).toBe('handle-conflict')
    c.f.finalMode = 'ok'; await c.controller.save('@NewName')
    expect(c.f.intents[1]).toEqual({ ...first, operation_id: c.f.intents[1]!.operation_id, handle: 'newname' })
    expect(c.f.intents[1]!.operation_id).not.toBe(first.operation_id)
    expect(c.f.assertions).toBe(1); expect(c.f.creates).toBe(1)
  })

  it('expired final grant reasserts SAME credential/candidate, no automatic new passkey/keys', async () => {
    const c = context(); c.f.finalMode = 'expired-grant'; await saved(c)
    const first = c.f.intents[0]!
    expect(c.controller.getSnapshot().phase).toBe('assertion-required')
    c.f.finalMode = 'ok'; await c.controller.save('ada')
    expect(c.f.intents[1]!.record).toEqual(first.record)
    expect(c.f.intents[1]!.key_bundle).toEqual(first.key_bundle)
    expect(c.f.creates).toBe(1); expect(c.f.assertions).toBe(2)
  })

  it('lost before commit + bare RESERVED does not authorize regeneration; retry bytes are identical', async () => {
    const c = context(); c.f.finalMode = 'lost-before'; await saved(c)
    expect(c.controller.getSnapshot().phase).toBe('unknown')
    await c.controller.resolve(); expect(c.controller.getSnapshot().phase).toBe('unknown')
    await c.controller.save('replacement'); expect(c.f.intents).toHaveLength(1)
    c.f.finalMode = 'ok'; await c.controller.retry()
    expect(c.f.intents[1]).toEqual(c.f.intents[0]); expect(c.f.provisions).toBe(1)
    expect(c.controller.getSnapshot().phase).toBe('ready')
  })

  it.each(['lost-after', 'malformed'] as const)('%s remains UNKNOWN; accepted replay requires fresh login and original-key restore', async (mode) => {
    const c = context(); c.f.finalMode = mode; await saved(c)
    expect(c.controller.getSnapshot().phase).toBe('unknown')
    c.f.finalMode = 'ok'; await c.controller.retry()
    expect(c.controller.getSnapshot().phase).toBe('restore-required'); expect(c.session.getSnapshot().phase).toBe('signed-out')
    await c.controller.begin(); expect(c.controller.getSnapshot().phase).toBe('existing')
    await restoreSignedInAccount(c.session, c.f.api, c.f.passkeys, c.keys)
    expect(c.f.provisions).toBe(1); expect(c.f.creates).toBe(1)
    expect(c.keys.capabilities(c.session.getSnapshot())?.bundleId).toBe(c.f.saved!.key_bundle.key_bundle_id)
  })

  it.each(['missing', 'wrong-account', 'wrong-bundle', 'wrong-credential', 'wrong-anchor', 'corrupt', 'offline'] as const)('stored %s never READY: ACTIVE account remains restore-required, no replacement', async (mode) => {
    const c = context(); c.f.readMode = mode; await saved(c)
    expect(c.controller.getSnapshot().phase).toBe('restore-required')
    expect(c.session.getSnapshot().phase).toBe('authenticated')
    expect(c.keys.capabilities(c.session.getSnapshot())).toBeNull()
    expect(c.f.provisions).toBe(1); expect(c.f.secrets.every((b) => b.every((v) => v === 0))).toBe(true)
    await c.controller.save('replacement'); expect(c.f.intents).toHaveLength(1)
    c.f.readMode = 'ok'; await restoreSignedInAccount(c.session, c.f.api, c.f.passkeys, c.keys)
    expect(c.session.getSnapshot().phase).toBe('keys-unlocked')
  })

  it.each(['google/provider', 'google/verify:after', 'credential/verify:after', 'passkey/get', 'setup/verify:after', 'finalize:after', 'record:after'])('Back/leave at %s fences late completion and clears only owned buffers', async (point) => {
    const c = context(); const hold = gate(c.f, point)
    const run = point.startsWith('google/') ? c.controller.begin() : (await c.controller.begin(), c.controller.save('ada'))
    await hold.arrived; c.controller.leave(); hold.release(); await run
    expect(c.controller.getSnapshot().phase).toBe('idle'); expect(c.session.getSnapshot().phase).toBe('signed-out')
    expect(c.keys.capabilities(c.session.getSnapshot())).toBeNull()
    expect(c.f.secrets.every((b) => b.every((v) => v === 0))).toBe(true)
    if (point === 'finalize:after' || point === 'record:after') expect(c.f.provisions).toBe(1) // leave cannot uncommit
  })

  it('late crypto completion locks its own capability and cannot replace newer session/key owner', async () => {
    const c = context(); let locks = 0
    const original = ownerCrypto.unlockAccount
    const newer = { accountId: RESERVATION, bundleId: RESERVATION, openEnvelope: vi.fn(), decryptArtifact: vi.fn(), sign: vi.fn(), lock: vi.fn() }
    vi.spyOn(ownerCrypto, 'unlockAccount').mockImplementation(async (...args) => {
      const caps = await original(...args); const lock = caps.lock; caps.lock = () => { locks++; lock() }
      c.session.signIn({ accountId: RESERVATION, bundleId: RESERVATION, handle: 'newer', accessToken: 'other', refreshToken: 'other', accessExpiresAt: '', refreshExpiresAt: '' })
      c.session.unlock(); c.keys.hold(c.session.getSnapshot(), newer)
      return caps
    })
    await saved(c)
    expect(locks).toBe(1); expect(c.keys.capabilities(c.session.getSnapshot())).toBe(newer); expect(newer.lock).not.toHaveBeenCalled()
  })

  it('lost credential-verify ACK reconciles status before any further create', async () => {
    const c = context(); let fail = true
    c.f.after = (action, result) => { if (action === 'credential/verify' && fail) throw new AuthErrorForTest(); return result }
    await saved(c); fail = false; await c.controller.save('ada')
    expect(c.f.creates).toBe(1); expect(c.controller.getSnapshot().phase).toBe('ready')
  })

  it('fresh proof takeover before old commit rejects old epoch without erasing newer candidate', async () => {
    const f = new AtomicFixture(); const a = context(f); const b = context(f)
    await a.controller.begin(); const hold = gate(f, 'finalize:before'); const old = a.controller.save('ada')
    await hold.arrived; f.pause = async () => undefined; await b.controller.begin()
    hold.release(); await old
    expect(a.controller.getSnapshot().phase).toBe('reauthenticate'); expect(f.provisions).toBe(0)
    await b.controller.save('ada'); expect(f.provisions).toBe(1); expect(f.creates).toBe(1)
  })

  it('fresh document resolves ACTIVE and decrypts ciphertext addressed to ORIGINAL keys', async () => {
    const old = await saved()
    const parsed = parseHpkePublicKeyset(unb64(old.f.saved!.key_bundle.encryption_public_keyset))
    const pub = await new DhkemX25519HkdfSha256().importKey('raw', arrayBuffer(parsed.raw), true)
    const info = utf8('synthetic prior ciphertext'); const encrypted = await sealTo(pub, parsed.keyId, info, utf8('original-only'))
    old.controller.dispose(); old.session.signOut()
    const fresh = context(old.f); const generate = vi.spyOn(identity, 'generateLocalIdentity')
    await fresh.controller.begin(); await restoreSignedInAccount(fresh.session, old.f.api, old.f.passkeys, fresh.keys)
    const plain = await fresh.keys.capabilities(fresh.session.getSnapshot())!.openEnvelope(info, encrypted)
    try { expect(new TextDecoder().decode(plain)).toBe('original-only') } finally { plain.fill(0) }
    expect(generate).not.toHaveBeenCalled(); expect(old.f.creates).toBe(1); expect(old.f.provisions).toBe(1)
  })

  it('terminal reservation requires explicit restart; cancel/leave never restart automatically', async () => {
    const c = context(); c.f.reservation.state = 'EXPIRED'; await c.controller.begin()
    expect(c.controller.getSnapshot().phase).toBe('terminal'); expect(c.f.calls).not.toContain('restart')
    await c.controller.restart(); expect(c.controller.getSnapshot().phase).toBe('handle')
    expect(c.f.reservation.id).not.toBe(RESERVATION)
    await c.controller.cancelReservation(); expect(c.controller.getSnapshot().phase).toBe('reauthenticate')
  })
})
class AuthErrorForTest extends Error { readonly code = 'TRANSPORT_FAILED' }
