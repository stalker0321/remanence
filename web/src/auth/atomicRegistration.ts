import { generateLocalIdentity, type PublicKeyBundle } from '../crypto/identity'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { uuidToBytes } from '../protocol/uuid'
import { unlockAccount, type RecoveryReply } from '../crypto/keyOwner'
import { prepareRecoveryRecord } from '../crypto/recoveryRecord'
import type { PublicSession, SessionStore, SignInInput } from '../session/session'
import type { GoogleIdentityPort } from './google'
import { normalizeHandle } from './handle'
import type { ApiTransport } from './http'
import type { MemoryKeys } from './memoryKeys'
import type { PasskeyAssertion, PasskeyPort, PublicAssertion } from './passkey'
import { AtomicError, binary, login, object, outcome, recoveryReply, reservation, setupReply, string, success, uuid,
  type AtomicPort, type AtomicRequest, type FinalIntent, type Reservation, type SetupAction, type SetupBodies, type SetupReply } from './atomicWire'

export type AtomicPhase = 'idle' | 'google' | 'handle' | 'saving' | 'unknown' | 'handle-conflict' | 'assertion-required' | 'reauthenticate' | 'terminal' | 'restore-required' | 'ready' | 'existing'
export interface AtomicView {
  phase: AtomicPhase; busy: boolean; error: string | null
  reservationState: Reservation['state'] | null; credentialCount: number
}
interface Candidate { bundle: PublicKeyBundle; record: RecoveryReply['record']; prf: Uint8Array }
interface Owner {
  controller: AbortController; expected: PublicSession; setup: SetupReply | null
  buffers: Set<Uint8Array>; candidate: Candidate | null; intent: FinalIntent | null
  grant: string | null; credential: string | null; login: SignInInput | null
  pending: Promise<void> | null
}

/** One document, one owner, one immutable candidate. No storage; GET RESERVED
 * never grants permission to discard an uncertain intent and generate again.
 * Only fresh Google ownership (serialized server-side) fences a lost writer. */
export class AtomicRegistration {
  #owner: Owner | null = null
  #view: AtomicView = { phase: 'idle', busy: false, error: null, reservationState: null, credentialCount: 0 }
  #listeners = new Set<() => void>()
  #unsubscribe: (() => void) | null = null
  constructor(private readonly ports: {
    session: SessionStore; keys: MemoryKeys; setup: AtomicPort; api: ApiTransport
    google: GoogleIdentityPort; passkeys: PasskeyPort
  }) {}
  #observe(): void {
    if (this.#unsubscribe) return
    this.#unsubscribe = this.ports.session.subscribe(() => {
      const owner = this.#owner
      if (owner && !this.#sameSession(owner)) this.#release(owner)
    })
  }
  subscribe = (listener: () => void): (() => void) => { this.#listeners.add(listener); return () => { this.#listeners.delete(listener) } }
  getSnapshot = (): AtomicView => this.#view
  dispose(): void { this.leave(); this.#unsubscribe?.(); this.#unsubscribe = null }

  begin(): Promise<void> {
    if (this.#owner?.pending) return this.#owner.pending
    if (this.ports.session.getSnapshot().phase !== 'signed-out') return Promise.resolve()
    this.#observe() // No external effects in a React render/StrictMode initializer.
    if (this.#owner) this.#release(this.#owner)
    const owner: Owner = { controller: new AbortController(), expected: this.ports.session.getSnapshot(), setup: null,
      buffers: new Set(), candidate: null, intent: null, grant: null, credential: null, login: null, pending: null }
    this.#owner = owner
    return this.#run(owner, async () => {
      this.#show('google')
      const options = success(await this.ports.setup.request({ action: 'google/options', body: {}, signal: owner.controller.signal }))
      this.#check(owner)
      const flow = uuid(options.flow); const nonce = binary(options.nonce, 43); const client = string(options.client_id, 200)
      if (nonce.length !== 43 || /\s/.test(client)) throw new AtomicError('SETUP_REJECTED')
      const token = await this.ports.google.requestIdToken(client, nonce, owner.controller.signal)
      this.#check(owner)
      const verified = success(await this.ports.setup.request({ action: 'google/verify', body: { flow, nonce, id_token: string(token), onboarding_version: 1 }, signal: owner.controller.signal }))
      this.#check(owner)
      if (verified.status === 'SIGNED_IN') {
        const signed = login(verified.login)
        this.#signIn(owner, signed)
        this.#show('existing')
        return
      }
      owner.setup = setupReply(verified)
      owner.credential = owner.setup.reservation.credential_ids[0] ?? null
      this.#state(owner)
    })
  }

  save(rawHandle: string): Promise<void> {
    const owner = this.#owner
    if (!owner || !['handle', 'handle-conflict', 'assertion-required'].includes(this.#view.phase)) return Promise.resolve()
    return this.#run(owner, async () => {
      const handle = normalizeHandle(rawHandle)
      this.#requireReserved(owner)
      this.#show('saving')
      if (!owner.grant) await this.#authorize(owner)
      this.#check(owner)
      if (!owner.candidate) throw new AtomicError('SETUP_REJECTED')
      owner.intent = Object.freeze({ epoch: owner.setup!.reservation.epoch, operation_id: crypto.randomUUID(),
        final_authorization: owner.grant!, handle, key_bundle: owner.candidate.bundle, record: owner.candidate.record })
      await this.#finalize(owner)
    })
  }

  /** Safe exact replay, including when GET says RESERVED. No new intent here. */
  retry(): Promise<void> {
    const owner = this.#owner
    if (!owner?.intent || this.#view.phase !== 'unknown') return Promise.resolve()
    return this.#run(owner, () => this.#finalize(owner))
  }

  resolve(): Promise<void> {
    const owner = this.#owner
    if (!owner?.setup) return Promise.resolve()
    return this.#run(owner, async () => {
      const root = success(await this.ports.setup.request({ action: 'status', reservation: owner.setup!.reservation.id,
        token: owner.setup!.setup_token, signal: owner.controller.signal }))
      this.#check(owner)
      const next = reservation(root.reservation)
      this.#sameReservation(owner, next)
      owner.setup = { ...owner.setup!, reservation: next }
      if (next.state === 'RESERVED' && owner.intent) this.#show('unknown')
      else this.#state(owner)
    })
  }

  restart(): Promise<void> {
    const owner = this.#owner
    if (!owner?.setup || this.#view.phase !== 'terminal') return Promise.resolve()
    return this.#run(owner, async () => {
      const reply = await this.#post(owner, 'restart', { epoch: owner.setup!.reservation.epoch })
      if (reply.status === 'RESTARTED') { this.#show('reauthenticate'); return }
      const next = setupReply(reply)
      if (next.reservation.id === owner.setup!.reservation.id || next.reservation.account_id === owner.setup!.reservation.account_id || next.reservation.state !== 'RESERVED') throw new AtomicError('SETUP_REJECTED')
      this.#wipe(owner)
      owner.setup = next
      owner.credential = next.reservation.credential_ids[0] ?? null
      this.#state(owner)
    })
  }

  cancelReservation(): Promise<void> {
    const owner = this.#owner
    if (!owner?.setup || owner.pending) return Promise.resolve()
    return this.#run(owner, async () => {
      const reply = await this.#post(owner, 'cancel', { epoch: owner.setup!.reservation.epoch })
      const next = reservation(reply.reservation)
      this.#sameReservation(owner, next)
      if (next.state !== 'CANCELLED') throw new AtomicError('SETUP_REJECTED')
      this.#wipe(owner)
      owner.setup = { ...owner.setup!, reservation: next }
      // A new Google proof is required before restart after cancellation.
      this.#show('reauthenticate')
    })
  }

  /** Leaving is NOT cancellation and cannot undo a committed transaction. */
  leave(): void {
    const owner = this.#owner
    if (!owner) return
    const setup = owner.setup
    this.#release(owner)
    if (setup) {
      const abort = new AbortController()
      const timer = setTimeout(() => abort.abort(), 5000)
      void this.ports.setup.request({ action: 'leave', reservation: setup.reservation.id, token: setup.setup_token,
        body: { epoch: setup.reservation.epoch }, signal: abort.signal }).catch(() => undefined).finally(() => clearTimeout(timer))
    }
  }

  async #authorize(owner: Owner): Promise<void> {
    if (!this.ports.passkeys.canAuthenticate()) throw new AtomicError('PASSKEY_UNAVAILABLE')
    const epoch = owner.setup!.reservation.epoch
    // Status reconciles a lost credential-verify response BEFORE offering create.
    const status = success(await this.ports.setup.request({ action: 'status', reservation: owner.setup!.reservation.id,
      token: owner.setup!.setup_token, signal: owner.controller.signal }))
    this.#check(owner)
    const current = reservation(status.reservation)
    this.#sameReservation(owner, current)
    owner.setup = { ...owner.setup!, reservation: current }
    this.#requireReserved(owner)
    owner.credential ??= current.credential_ids[0] ?? null
    if (!owner.credential) {
      const begin = await this.#post(owner, 'credential/options', { epoch })
      const flow = uuid(begin.flow)
      if (object(object(begin.options).user).id !== b64(uuidToBytes(owner.setup!.reservation.account_id))) throw new AtomicError('WRONG_ACCOUNT')
      const created = await this.ports.passkeys.create(begin.options, owner.controller.signal)
      try {
        this.#check(owner)
        const response = this.#publicAssertion(created)
        const verified = await this.#post(owner, 'credential/verify', { epoch, flow, response })
        if (verified.registered !== true || verified.recoverable !== false || binary(verified.credential_id, 2048) !== created.id) throw new AtomicError('SETUP_REJECTED')
        owner.credential = created.id
      } finally { created.prf?.fill(0) }
    }
    const begin = await this.#post(owner, 'setup/options', { epoch, credential_id: owner.credential! })
    const flow = uuid(begin.flow)
    const allowed = object(begin.options).allowCredentials
    if (!Array.isArray(allowed) || allowed.length !== 1 || object(allowed[0]).id !== owner.credential) throw new AtomicError('WRONG_CREDENTIAL')
    const assertion = await this.ports.passkeys.get(begin.options, owner.controller.signal)
    const secret = assertion.prf
    if (secret) owner.buffers.add(secret)
    let retained = false
    try {
      this.#check(owner)
      if (!secret || secret.length !== 32) throw new AtomicError('PRF_UNAVAILABLE')
      const response = this.#publicAssertion(assertion)
      if (assertion.id !== owner.credential) throw new AtomicError('WRONG_CREDENTIAL')
      const verified = await this.#post(owner, 'setup/verify', { epoch, flow, response })
      if (verified.authentication_verified !== true || verified.credential_id !== owner.credential || typeof verified.final_authorization !== 'string' || !/^rgf_[A-Za-z0-9_-]{43}$/.test(verified.final_authorization)) throw new AtomicError('SETUP_REJECTED')
      binary(verified.final_authorization.slice(4), 43)
      const grant = verified.final_authorization
      if (owner.candidate) {
        // Reassert the SAME credential. Prove its PRF still opens the frozen
        // candidate; do not silently reseal/generate against a different result.
        const caps = await unlockAccount({ record: owner.candidate.record,
          encryption_public_keyset: owner.candidate.bundle.encryption_public_keyset,
          signing_public_keyset: owner.candidate.bundle.signing_public_keyset }, secret)
        caps.lock()
        this.#check(owner)
        owner.candidate.prf.fill(0)
        owner.buffers.delete(owner.candidate.prf)
        owner.candidate = { ...owner.candidate, prf: secret }
        owner.grant = grant
        retained = true
        return
      }
      // Real verified assertion + usable PRF precede all candidate generation.
      const identity = await generateLocalIdentity()
      try {
        this.#check(owner)
        const seeds = identity.copySeeds()
        owner.buffers.add(seeds.encryption); owner.buffers.add(seeds.signing)
        try {
          const bundle = Object.freeze(identity.publicBundle())
          const prepared = await prepareRecoveryRecord({ accountId: owner.setup!.reservation.account_id, bundleId: identity.bundleId,
            credentialId: owner.credential!, prf: secret, encryptionSeed: seeds.encryption, signingSeed: seeds.signing,
            encryptionPublicKeyset: bundle.encryption_public_keyset, signingPublicKeyset: bundle.signing_public_keyset })
          prepared.code.fill(0) // Optional recovery code is not part of this journey.
          this.#check(owner)
          owner.candidate = { bundle, record: Object.freeze(prepared.record), prf: secret }
          owner.grant = grant
          retained = true
        } finally {
          seeds.encryption.fill(0); seeds.signing.fill(0)
          owner.buffers.delete(seeds.encryption); owner.buffers.delete(seeds.signing)
        }
      } finally { identity.wipe() }
    } finally {
      if (!retained && secret) { secret.fill(0); owner.buffers.delete(secret) }
    }
  }

  async #finalize(owner: Owner): Promise<void> {
    this.#check(owner)
    const intent = owner.intent
    if (!intent) throw new AtomicError('SETUP_REJECTED')
    this.#show('unknown') // Dispatch itself crosses the uncertain-commit boundary.
    let reply: Record<string, unknown>
    try { reply = await this.#post(owner, 'finalize', intent, [200, 201]) }
    catch (error) {
      this.#check(owner)
      const code = errorCode(error)
      if (code === 'HANDLE_TAKEN') { this.#show('handle-conflict', code); return }
      if (code === 'SETUP_REJECTED' && error instanceof AtomicError && error.status === 400) { owner.grant = null; this.#show('assertion-required', code); return }
      throw error
    }
    const accepted = outcome(reply.outcome)
    if (reply.status !== 'ACTIVE' || accepted.operation_id !== intent.operation_id || accepted.account_id !== intent.record.account_id || accepted.bundle_id !== intent.key_bundle.key_bundle_id || accepted.credential_id !== intent.record.credential_id || accepted.handle !== intent.handle) throw new AtomicError('SETUP_REJECTED')
    owner.setup = { ...owner.setup!, reservation: { ...owner.setup!.reservation, state: 'ACTIVE', outcome: accepted, credential_ids: [accepted.credential_id] } }
    if (reply.reauthenticate === true) {
      this.#wipe(owner)
      this.#show('restore-required')
      return
    }
    const signed = login(reply.login)
    if (signed.accountId !== accepted.account_id || signed.bundleId !== accepted.bundle_id || signed.handle !== accepted.handle) throw new AtomicError('WRONG_ACCOUNT')
    owner.login = signed
    this.#show('restore-required')
    try { await this.#readback(owner) }
    catch (error) {
      this.#check(owner)
      // Account is already ACTIVE. Preserve ordinary authenticated restore;
      // never go back to candidate generation after any readback failure.
      this.#signIn(owner, signed)
      this.#wipe(owner)
      this.#show('restore-required', errorCode(error))
    }
  }

  async #readback(owner: Owner): Promise<void> {
    const candidate = owner.candidate!; const signed = owner.login!
    const request = { token: signed.accessToken, signal: owner.controller.signal, method: 'GET' as const }
    const status = success(await this.ports.api.request({ ...request, path: '/v1/recovery/status' }))
    this.#check(owner)
    if (status.state !== 'STORED' || status.credential_id !== owner.credential) throw new AtomicError('WRONG_CREDENTIAL')
    const read = await this.ports.api.request({ ...request, path: '/v1/recovery/record' })
    this.#check(owner)
    const reply = recoveryReply(success(read))
    if (reply.record.account_id !== signed.accountId || reply.record.bundle_id !== signed.bundleId || reply.record.credential_id !== owner.credential
      || reply.encryption_public_keyset !== candidate.bundle.encryption_public_keyset || reply.signing_public_keyset !== candidate.bundle.signing_public_keyset) throw new AtomicError('WRONG_ACCOUNT')
    for (const field of Object.keys(candidate.record) as (keyof RecoveryReply['record'])[]) {
      if (reply.record[field] !== candidate.record[field]) throw new AtomicError('SETUP_REJECTED')
    }
    const caps = await unlockAccount(reply, candidate.prf)
    try {
      this.#check(owner)
      if (caps.accountId !== signed.accountId || caps.bundleId !== signed.bundleId) throw new AtomicError('WRONG_ACCOUNT')
      this.#signIn(owner, signed)
      owner.expected = { ...owner.expected, generation: owner.expected.generation + 1, phase: 'keys-unlocked' }
      this.ports.session.unlock()
      this.#check(owner)
      this.ports.keys.hold(this.ports.session.getSnapshot(), caps)
      this.#wipe(owner)
      this.#show('ready')
    } catch (error) { caps.lock(); throw error }
  }

  async #post<A extends SetupAction>(owner: Owner, action: A, body: SetupBodies[A], statuses?: number[]): Promise<Record<string, unknown>> {
    this.#check(owner)
    const setup = owner.setup!
    const result = await this.ports.setup.request({ action, reservation: setup.reservation.id, token: setup.setup_token, body, signal: owner.controller.signal } as AtomicRequest)
    this.#check(owner)
    return success(result, statuses)
  }
  #publicAssertion(assertion: PasskeyAssertion): PublicAssertion {
    binary(assertion.id, 2048)
    if (assertion.publicResponse.id !== assertion.id || assertion.publicResponse.rawId !== assertion.id || assertion.publicResponse.type !== 'public-key' || Object.keys(object(assertion.publicResponse.clientExtensionResults)).length !== 0) throw new AtomicError('WRONG_CREDENTIAL')
    // Copy the public DTO, never serialize provider objects or arbitrary fields.
    const source = object(assertion.publicResponse.response)
    const response: Record<string, unknown> = { clientDataJSON: binary(source.clientDataJSON, 32768) }
    for (const field of ['attestationObject', 'authenticatorData', 'signature', 'userHandle']) {
      if (source[field] !== undefined) response[field] = field === 'userHandle' && source[field] === null ? null : binary(source[field], 131072)
    }
    if (source.transports !== undefined) {
      if (!Array.isArray(source.transports) || source.transports.length > 8) throw new AtomicError('SETUP_REJECTED')
      response.transports = source.transports.map((value) => string(value, 32))
    }
    return { id: assertion.id, rawId: assertion.id, type: 'public-key', response, clientExtensionResults: {} }
  }
  #signIn(owner: Owner, signed: SignInInput): void {
    this.#check(owner)
    owner.expected = { ...owner.expected, generation: owner.expected.generation + 1, accountId: signed.accountId, bundleId: signed.bundleId, handle: signed.handle, phase: 'authenticated' }
    this.ports.session.signIn(signed)
    this.#check(owner)
  }
  #sameSession(owner: Owner): boolean {
    const view = this.ports.session.getSnapshot(); const expected = owner.expected
    return view.generation === expected.generation && view.accountId === expected.accountId && view.bundleId === expected.bundleId && view.phase === expected.phase
  }
  #check(owner: Owner): void {
    if (this.#owner !== owner || owner.controller.signal.aborted || !this.#sameSession(owner)) throw new AtomicError('STALE')
  }
  #sameReservation(owner: Owner, next: Reservation): void {
    const old = owner.setup!.reservation
    if (old.id !== next.id || old.account_id !== next.account_id || old.epoch !== next.epoch) throw new AtomicError('STALE_SETUP')
  }
  #requireReserved(owner: Owner): void {
    this.#check(owner)
    if (!owner.setup) throw new AtomicError('SETUP_REJECTED')
    if (owner.setup.reservation.state === 'ACTIVE') throw new AtomicError('ALREADY_ACTIVE')
    if (owner.setup.reservation.state !== 'RESERVED') throw new AtomicError('RESERVATION_TERMINAL')
  }
  #state(owner: Owner): void {
    const state = owner.setup!.reservation.state
    if (state === 'ACTIVE') { this.#wipe(owner); this.#show('restore-required') }
    else if (state === 'RESERVED') this.#show('handle')
    else { this.#wipe(owner); this.#show('terminal') }
  }
  #run(owner: Owner, action: () => Promise<void>): Promise<void> {
    if (owner.pending) return owner.pending
    // Defer action until pending is assigned; synchronous listeners/duplicate
    // Enter cannot start a second operation before the first await.
    const promise = Promise.resolve().then(() => { this.#check(owner); this.#patch({ busy: true, error: null }); return action() }).catch((error: unknown) => {
      if (this.#owner !== owner || owner.controller.signal.aborted) return
      const code = errorCode(error)
      if (['SETUP_AUTH_INVALID', 'SETUP_REAUTH_REQUIRED', 'STALE_SETUP'].includes(code)) { this.#wipe(owner); this.#show('reauthenticate', code) }
      else if (code === 'ALREADY_ACTIVE') { this.#wipe(owner); this.#show('restore-required', code) }
      else if (code === 'RESERVATION_TERMINAL') { this.#wipe(owner); this.#show('reauthenticate', code) }
      else this.#show(this.#view.phase === 'saving' ? 'handle' : this.#view.phase === 'google' ? 'idle' : this.#view.phase, code)
    }).finally(() => {
      if (this.#owner === owner && owner.pending === promise) { owner.pending = null; this.#patch({ busy: false }) }
    })
    owner.pending = promise
    return promise
  }
  #wipe(owner: Owner): void {
    for (const bytes of owner.buffers) bytes.fill(0)
    owner.buffers.clear(); owner.candidate = null; owner.intent = null; owner.grant = null; owner.login = null
  }
  #release(owner: Owner): void {
    owner.controller.abort(); this.#wipe(owner); owner.setup = null; owner.credential = null
    if (this.#owner === owner) { this.#owner = null; this.#patch({ phase: 'idle', busy: false, error: null, reservationState: null, credentialCount: 0 }) }
  }
  #show(phase: AtomicPhase, error: string | null = null): void {
    this.#patch({ phase, error, reservationState: this.#owner?.setup?.reservation.state ?? null,
      credentialCount: this.#owner?.credential ? 1 : this.#owner?.setup?.reservation.credential_ids.length ?? 0 })
  }
  #patch(patch: Partial<AtomicView>): void { this.#view = { ...this.#view, ...patch }; for (const listener of this.#listeners) listener() }
}

function errorCode(error: unknown): string {
  return typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string' && /^[A-Z_]{1,64}$/.test(error.code) ? error.code : 'SETUP_REJECTED'
}
