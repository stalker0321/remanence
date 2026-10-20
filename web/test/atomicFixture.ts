/** Synthetic serialized contract model, NOT real server/provider proof.
 * Production key generation/sealing/readback crypto are never substituted. */
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiRequest, ApiResult, ApiTransport, ByteTransport } from '../src/auth/http'
import type { AtomicPort, AtomicRequest, FinalIntent, Reservation } from '../src/auth/atomicWire'
import type { GoogleIdentityPort } from '../src/auth/google'
import type { PasskeyAssertion, PasskeyPort } from '../src/auth/passkey'
import type { RecoveryReply } from '../src/crypto/keyOwner'
import { AuthError } from '../src/auth/errors'

export const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
export const RESERVATION = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
export const FLOW = '11111111-1111-4111-8111-111111111111'
export const CREDENTIAL = b64(new Uint8Array(16).fill(9))
const deadline = '2031-01-01T00:00:00Z'
export class AtomicFixture {
  reservation: Reservation = { id: RESERVATION, account_id: ACCOUNT, epoch: 1, state: 'RESERVED', expires_at: deadline, credential_ids: [], outcome: null, restarted_into: null }
  saved: FinalIntent | null = null
  calls: string[] = []
  intents: FinalIntent[] = []
  secrets: Uint8Array[] = []
  creates = 0; assertions = 0; provisions = 0
  grants = 0
  noPrf = false; cancelProvider = false
  finalMode: 'ok' | 'lost-before' | 'lost-after' | 'handle' | 'expired-grant' | 'malformed' = 'ok'
  readMode: 'ok' | 'missing' | 'wrong-account' | 'wrong-bundle' | 'wrong-credential' | 'wrong-anchor' | 'corrupt' | 'offline' = 'ok'
  pause: (point: string) => Promise<void> = async () => undefined
  after: (point: string, result: ApiResult) => ApiResult = (_point, result) => result
  setup: AtomicPort = { request: (input) => this.request(input) }
  api: ApiTransport & ByteTransport = { request: (input) => this.read(input), requestBytes: async () => { throw new Error('fixture has no blob API') } }
  google: GoogleIdentityPort = { requestIdToken: async () => { this.calls.push('google/provider'); await this.pause('google/provider'); return 'synthetic-Google-proof' } }
  passkeys: PasskeyPort = { canAuthenticate: () => true,
    create: async () => { this.creates++; this.calls.push('passkey/create'); const result = this.assertion(null); await this.pause('passkey/create'); return result },
    get: async () => {
      this.assertions++; this.calls.push('passkey/get')
      if (this.cancelProvider) throw new AuthError('PASSKEY_CANCELLED')
      const prf = this.noPrf ? null : new Uint8Array(32).fill(7)
      if (prf) this.secrets.push(prf)
      const result = this.assertion(prf); await this.pause('passkey/get'); return result
    },
  }
  private assertion(prf: Uint8Array | null): PasskeyAssertion {
    return { id: CREDENTIAL, prf, publicResponse: { id: CREDENTIAL, rawId: CREDENTIAL, type: 'public-key', response: { clientDataJSON: 'e30' }, clientExtensionResults: {} } }
  }
  login() {
    if (!this.saved) throw new Error('No account in fixture')
    return { user: { user_id: this.saved.record.account_id, handle: this.saved.handle }, active_key_bundle: this.saved.key_bundle,
      session_id: FLOW, access_token: 'pm_at_synthetic', refresh_token: 'pm_rt_synthetic', access_expires_at: deadline, refresh_expires_at: deadline }
  }
  reply(): RecoveryReply {
    if (!this.saved) throw new Error('No record in fixture')
    return structuredClone({ record: this.saved.record, encryption_public_keyset: this.saved.key_bundle.encryption_public_keyset,
      signing_public_keyset: this.saved.key_bundle.signing_public_keyset })
  }
  private setupReply() {
    return { status: 'SETUP_REQUIRED', reservation: this.reservation, setup_token: `rgs_${'A'.repeat(43)}`, setup_session_id: FLOW, setup_expires_at: deadline }
  }
  async request(input: AtomicRequest): Promise<ApiResult> {
    const action = input.action
    this.calls.push(action)
    await this.pause(`${action}:before`)
    let result: ApiResult = { status: 200, body: {} }
    const body = 'body' in input ? input.body as Record<string, unknown> : {}
    if (action === 'google/options') result.body = { flow: FLOW, nonce: 'A'.repeat(43), client_id: 'synthetic' }
    else if (action === 'google/verify') {
      this.reservation.epoch++
      result.body = this.saved ? { status: 'SIGNED_IN', login: this.login() } : this.setupReply()
    } else if (action === 'status') result.body = { reservation: this.reservation }
    else if (action === 'leave') result.body = { left: true }
    else if (body.epoch !== this.reservation.epoch) result = { status: 409, body: { reason: 'STALE_SETUP' } }
    else if (action === 'credential/options' || action === 'setup/options') result.body = { flow: FLOW, options: {}, expires_at: deadline }
    else if (action === 'credential/verify') {
      this.reservation.credential_ids = [CREDENTIAL]
      result.body = { credential_id: CREDENTIAL, registered: true, recoverable: false }
    } else if (action === 'setup/verify') {
      this.grants++
      result.body = { credential_id: CREDENTIAL, authentication_verified: true, final_authorization: `rgf_${b64(new Uint8Array(32).fill(this.grants))}`, expires_at: deadline }
    } else if (action === 'finalize') {
      const intent = structuredClone(input.body) as FinalIntent
      this.intents.push(intent)
      if (this.finalMode === 'lost-before') throw new AuthError('TRANSPORT_FAILED')
      if (this.finalMode === 'handle') result = { status: 409, body: { reason: 'HANDLE_TAKEN' } }
      else if (this.finalMode === 'expired-grant') result = { status: 400, body: { reason: 'SETUP_REJECTED' } }
      else {
        if (!this.saved) {
          this.saved = intent; this.provisions++
          this.reservation.state = 'ACTIVE'
          this.reservation.outcome = { operation_id: intent.operation_id, account_id: intent.record.account_id,
            bundle_id: intent.key_bundle.key_bundle_id, credential_id: intent.record.credential_id, handle: intent.handle, generation: 1 }
          result = { status: 201, body: { status: 'ACTIVE', outcome: this.reservation.outcome, login: this.login() } }
        } else if (JSON.stringify(this.saved) === JSON.stringify(intent)) result.body = { status: 'ACTIVE', outcome: this.reservation.outcome, reauthenticate: true }
        else result = { status: 409, body: { reason: 'ALREADY_ACTIVE' } }
        if (this.finalMode === 'lost-after') throw new AuthError('TRANSPORT_FAILED')
        if (this.finalMode === 'malformed') result.body = { status: 'ACTIVE' }
      }
    } else if (action === 'cancel') {
      if (this.saved) result = { status: 409, body: { reason: 'ALREADY_ACTIVE' } }
      else { this.reservation.state = 'CANCELLED'; result.body = { reservation: this.reservation } }
    } else if (action === 'restart') {
      if (!['CANCELLED', 'EXPIRED'].includes(this.reservation.state)) throw new Error('invalid fixture restart')
      this.reservation = { ...this.reservation, id: crypto.randomUUID(), account_id: crypto.randomUUID(), state: 'RESERVED', credential_ids: [] }
      result.body = this.setupReply()
    } else throw new Error(`Unexpected fixture action ${action}`)
    result = structuredClone(result)
    await this.pause(`${action}:after`)
    return this.after(action, result)
  }
  async read(input: ApiRequest): Promise<ApiResult> {
    this.calls.push(`${input.method} ${input.path}`)
    const endpoint = input.path.split('/').at(-1)!
    let result: ApiResult
    if (endpoint === 'status') result = { status: 200, body: { state: this.readMode === 'missing' ? 'NOT_STARTED' : 'STORED', credential_id: CREDENTIAL } }
    else if (endpoint === 'options') result = { status: 200, body: { flow: FLOW, options: {} } }
    else if (endpoint === 'verify') result = { status: 200, body: { credential_id: CREDENTIAL, authentication_verified: true } }
    else if (endpoint === 'record') {
      if (this.readMode === 'offline') throw new AuthError('TRANSPORT_FAILED')
      const reply = this.reply()
      if (this.readMode === 'wrong-account') reply.record.account_id = RESERVATION
      if (this.readMode === 'wrong-bundle') reply.record.bundle_id = RESERVATION
      if (this.readMode === 'wrong-credential') reply.record.credential_id = 'AQ'
      if (this.readMode === 'wrong-anchor') reply.signing_public_keyset = reply.encryption_public_keyset
      if (this.readMode === 'corrupt') reply.record.package_sealed = `AA${reply.record.package_sealed.slice(2)}`
      result = { status: 200, body: reply }
    } else throw new Error('unexpected fixture ordinary API call')
    await this.pause(`${endpoint}:after`)
    return this.after(endpoint, result)
  }
}
