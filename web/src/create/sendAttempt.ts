/**
 * T326 — in-memory send-attempt coordinator for ONE existing-recipient
 * encrypted Send on the existing Create screen.
 *
 * Consumes the accepted primitives unchanged: `sealCapsule`,
 * `publishCapsule`, `fetchTransport` (ApiTransport & ByteTransport &
 * UploadTransport), `MemoryKeys`, the composer `CreatorFreeze` and the
 * T320 front-fingerprint holder's public `current()` accessor. One
 * admitted attempt per Create visit; no durable retry/outbox.
 *
 * Explicit caller policy (lead brief): capsuleId = one random UUID per
 * mounted Create visit; idempotencyKey = one random UUID per admitted
 * attempt; createdAtEpochSeconds = floor(now/1000) at request
 * construction (the only wall-clock value); epoch = explicit nonnegative
 * bigint Create-visit counter (module-local, advances on entry to a new
 * visit; NOT Date.now, NOT SessionStore generation, no default inside
 * buildFrozenExpression); placeLabel = null; candidateId = "ber1-" +
 * first 32 lowercase hex of SHA-256 over the accepted
 * `encodeExpression(buildFrozenExpression(freeze, epoch))` bytes for that
 * exact frozen selection + supplied epoch.
 */
import { bytesToHex } from '../protocol/bytes'
import type { ApiTransport, ByteTransport, UploadTransport } from '../auth/http'
import { AuthError } from '../auth/errors'
import type { MemoryKeys } from '../auth/memoryKeys'
import type { SessionStore } from '../session/session'
import type { RecipientSnapshot } from './recipient'
import type { DraftPhoto } from './photos'
import type { CreatorFreeze } from './creatorFreeze'
import type { FrontStill } from './front'
import type { FrontFingerprintValue } from './frontFingerprint'
import { sealCapsule, SealCapsuleError } from './sealCapsule'
import { publishCapsule, PublishCapsuleError } from '../sync/publishCapsule'
import { buildFrozenExpression } from '../collage/frozenExpression'

/** Create-visit epoch: module-local, resets only on app restart (native-like). */
let visitEpochCounter = 0n

/** Smallest entry allocation: one new value per mounted Create visit. */
export function nextCreateVisitEpoch(): bigint {
  visitEpochCounter += 1n
  return visitEpochCounter
}

/** The synchronously captured, coherent one-visit Send input. */
export interface SendVisit {
  epoch: bigint
  capsuleId: string
  account: string | null
  bundleId: string | null
  generation: number
  keysReady: boolean
  recipient: RecipientSnapshot
  photos: readonly DraftPhoto[]
  noteText: string
  musicSelected: boolean
  freeze: CreatorFreeze
  still: FrontStill
  fingerprint: FrontFingerprintValue
  mounted: boolean
}

export interface SendPorts {
  readonly session: SessionStore
  readonly keys: MemoryKeys
  transport: ApiTransport & ByteTransport & UploadTransport
  /** Test seams only; production passes the real primitives. */
  seal?: typeof sealCapsule
  publish?: typeof publishCapsule
}

export type SendOutcome =
  | { kind: 'sent'; readyAt: string; locator: { code: string; hint: string } | null }
  /** Nothing left the device: local preflight/seal failure, or the server
   * explicitly rejected the draft before any capsule existed. */
  | { kind: 'refused'; stage: 'preflight' | 'seal' | 'draft'; code: string }
  /** Publishing started and its final result cannot prove absence of server
   * effects (upload/finalize trouble, lost finalize, mid-flight cancel). */
  | { kind: 'unknown'; stage: string; code: string }
  /** The captured visit went stale before any publish request. */
  | { kind: 'stale' }

export type SendPreflight =
  | { ok: true }
  | { ok: false; reason: 'capability' | 'session' | 'recipient' | 'photos' | 'note' | 'music' | 'freeze' | 'still' | 'fingerprint' | 'mounted' }

function photoSignature(photos: readonly DraftPhoto[]): string {
  return photos.map((photo) => `${photo.id}:${photo.contentHash}:${photo.width}x${photo.height}`).join('|')
}

/** Re-validate the captured visit at every await boundary. Value-based, so a
 * draft edit / recipient change / retake / lock lands even if a React effect
 * has not run yet. */
function visitCurrent(visit: SendVisit, ports: SendPortsFull): boolean {
  const view = ports.session.getSnapshot()
  if (!visit.mounted) return false
  if (!ports.mountedCurrent()) return false
  if (view.accountId !== visit.account || view.bundleId !== visit.bundleId) return false
  if (view.generation !== visit.generation) return false
  if (view.phase !== 'keys-unlocked' || !visit.keysReady) return false
  const recipient = ports.recipientCurrent()
  if (recipient === null) return false
  if (recipient.userId !== visit.recipient.userId ||
      recipient.keyBundleId !== visit.recipient.keyBundleId ||
      recipient.directoryVersion !== visit.recipient.directoryVersion) return false
  if (photoSignature(ports.photosCurrent()) !== photoSignature(visit.photos)) return false
  if (ports.noteTextCurrent() !== visit.noteText) return false
  if (ports.musicSelectedCurrent() !== visit.musicSelected) return false
  if (ports.freezeCurrent() !== visit.freeze) return false
  if (ports.stillCurrent() !== visit.still) return false
  if (ports.fingerprintCurrent() !== visit.fingerprint) return false
  return true
}

/** Public read-only fence for owners that must guard UI writes after awaits. */
export function sendVisitStillCurrent(visit: SendVisit, ports: SendPortsFull): boolean {
  return visitCurrent(visit, ports)
}

/** Current-value accessors the coordinator re-reads at every fence. */
export interface SendCurrentAccessors {
  recipientCurrent(): RecipientSnapshot | null
  photosCurrent(): readonly DraftPhoto[]
  noteTextCurrent(): string
  musicSelectedCurrent(): boolean
  freezeCurrent(): CreatorFreeze | null
  stillCurrent(): FrontStill | null
  fingerprintCurrent(): FrontFingerprintValue | null
  mountedCurrent(): boolean
}

export interface SendPortsFull extends SendPorts, SendCurrentAccessors {}

/** Synchronous local prerequisites. A failed preflight consumes NO attempt. */
export function sendPreflight(visit: SendVisit, ports: SendPortsFull): SendPreflight {
  if (!visit.mounted) return { ok: false, reason: 'mounted' }
  if (!ports.mountedCurrent()) return { ok: false, reason: 'mounted' }
  const view = ports.session.getSnapshot()
  if (view.phase !== 'keys-unlocked' || view.accountId === null || !visit.keysReady) {
    return { ok: false, reason: 'session' }
  }
  if (view.accountId !== visit.account || view.bundleId !== visit.bundleId || view.generation !== visit.generation) {
    return { ok: false, reason: 'session' }
  }
  if (visit.recipient.userId === '' || visit.recipient.keyBundleId === '') {
    return { ok: false, reason: 'recipient' }
  }
  if (visit.photos.length < 3 || visit.photos.length > 5) return { ok: false, reason: 'photos' }
  if (visit.noteText.trim() === '') return { ok: false, reason: 'note' }
  if (visit.musicSelected) return { ok: false, reason: 'music' }
  if (visit.freeze.grammarId !== 'g5') return { ok: false, reason: 'freeze' }
  if (visit.still.bytes.length === 0) return { ok: false, reason: 'still' }
  if (visit.fingerprint.wire.length === 0) return { ok: false, reason: 'fingerprint' }
  return { ok: true }
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
}

/**
 * `ber1-` + first 32 lowercase hex chars of SHA-256 over the accepted
 * canonical expression bytes for this exact freeze + epoch (native
 * `GeneratorBer1Provider.candidateIdFor` rule). The builder already returns
 * the canonical owned bytes; those and the digest are wiped on every path.
 */
export async function deriveCandidateId(freeze: CreatorFreeze, epoch: bigint): Promise<string> {
  const built = await buildFrozenExpression(freeze, epoch)
  if (!built.ok) throw new Error('expression-build-failed')
  let bytes: Uint8Array | null = built.bytes
  let digest: Uint8Array | null = null
  try {
    digest = await sha256(bytes)
    return `ber1-${bytesToHex(digest).slice(0, 32)}`
  } finally {
    bytes?.fill(0)
    bytes = null
    digest?.fill(0)
  }
}

/**
 * One admitted send attempt. Callers must invoke `send` at most once per
 * Create visit through the owning controller below.
 */
export async function runSendAttempt(
  visit: SendVisit,
  ports: SendPortsFull,
  signal: AbortSignal,
): Promise<SendOutcome> {
  const seal = ports.seal ?? sealCapsule
  const publish = ports.publish ?? publishCapsule

  // ---- boundary 0: synchronous visit re-check ----
  if (!visitCurrent(visit, ports)) return { kind: 'stale' }
  const pre = sendPreflight(visit, ports)
  if (!pre.ok) return { kind: 'refused', stage: 'preflight', code: pre.reason }

  const createdAtEpochSeconds = BigInt(Math.floor(Date.now() / 1000))
  const idempotencyKey = crypto.randomUUID()

  // ---- candidateId from the exact frozen selection + epoch ----
  let candidateId: string
  try {
    candidateId = await deriveCandidateId(visit.freeze, visit.epoch)
  } catch {
    return { kind: 'refused', stage: 'preflight', code: 'expression' }
  }
  if (!visitCurrent(visit, ports)) return { kind: 'stale' }
  if (signal.aborted) return { kind: 'stale' }

  // ---- real seal (local) ----
  let sealed
  try {
    sealed = await seal({
      session: ports.session,
      keys: ports.keys,
      freeze: visit.freeze,
      recipient: visit.recipient,
      photos: visit.photos,
      capsuleId: visit.capsuleId,
      candidateId,
      createdAtEpochSeconds,
      epoch: visit.epoch,
      frontFingerprint: visit.fingerprint.wire,
      placeLabel: null,
    })
  } catch (error) {
    if (error instanceof SealCapsuleError) {
      return { kind: 'refused', stage: 'seal', code: error.code }
    }
    return { kind: 'refused', stage: 'seal', code: 'unknown' }
  }
  if (!visitCurrent(visit, ports)) return { kind: 'stale' }
  if (signal.aborted) return { kind: 'stale' }

  // ---- exactly one real publication ----
  // Bounded per-attempt transport guard around the UNCHANGED accepted
  // publisher: an in-visit edit/invalidation must prevent any NEW request
  // (further uploads / finalize) even if a request already reached the wire
  // or the transport ignores AbortSignal per call. The already-issued draft
  // may complete; nothing after it starts once the visit is no longer current.
  const guardedTransport = {
    request: async (input: Parameters<ApiTransport['request']>[0]) => {
      if (signal.aborted || !visitCurrent(visit, ports)) throw new AuthError('CANCELLED')
      return ports.transport.request(input)
    },
    requestBytes: async (input: Parameters<ByteTransport['requestBytes']>[0]) => {
      if (signal.aborted || !visitCurrent(visit, ports)) throw new AuthError('CANCELLED')
      return ports.transport.requestBytes(input)
    },
    uploadBytes: async (input: Parameters<UploadTransport['uploadBytes']>[0]) => {
      if (signal.aborted || !visitCurrent(visit, ports)) throw new AuthError('CANCELLED')
      return ports.transport.uploadBytes(input)
    },
  }
  try {
    const published = await publish({
      capsule: sealed,
      session: ports.session,
      idempotencyKey,
      transport: guardedTransport,
      signal,
    })
    if (!visitCurrent(visit, ports)) return { kind: 'stale' }
    return { kind: 'sent', readyAt: published.readyAt, locator: published.locator }
  } catch (error) {
    if (!visitCurrent(visit, ports)) return { kind: 'stale' }
    if (error instanceof PublishCapsuleError) {
      // Pre-request preflight refusals prove nothing left the device.
      if (error.stage === 'preflight') {
        return { kind: 'refused', stage: 'preflight', code: error.code ?? error.category }
      }
      // The server explicitly rejected draft creation before any capsule
      // existed: a received problem response at the draft stage.
      if (error.stage === 'draft' && error.status !== undefined &&
          error.category !== 'transport' && error.category !== 'cancelled') {
        return { kind: 'refused', stage: 'draft', code: error.code ?? error.category }
      }
      // Upload/finalize trouble, lost finalize, mid-flight cancel, or a
      // transport failure: the result cannot prove absence of server effects.
      return { kind: 'unknown', stage: error.stage, code: error.code ?? error.category }
    }
    return { kind: 'unknown', stage: 'publish', code: 'unknown' }
  }
}

/** Per-visit attempt gate: one admitted send per Create visit. */
export type SendPhase = 'idle' | 'working' | 'terminal'

export class SendAttemptController {
  #phase: SendPhase = 'idle'
  #abort: AbortController | null = null
  #visit: SendVisit | null = null

  constructor(
    private readonly portsProvider: () => SendPortsFull,
    private readonly isMounted: () => boolean,
    private readonly onPhase: (phase: SendPhase) => void,
  ) {}

  get phase(): SendPhase {
    return this.#phase
  }

  /** Double-click / terminal re-entry guard. The visit snapshot is taken
   * synchronously at THIS click, never earlier. Returns false when refused. */
  begin(visit: Omit<SendVisit, 'mounted'>): boolean {
    if (this.#phase !== 'idle') return false
    this.#visit = { ...visit, mounted: this.isMounted() }
    this.#phase = 'working'
    this.onPhase('working')
    this.#abort = new AbortController()
    return true
  }

  abort(): void {
    this.#abort?.abort()
  }

  /** Invalidate on external visit death while working (best-effort abort). */
  invalidate(): void {
    if (this.#phase === 'working') this.abort()
  }

  async run(): Promise<SendOutcome> {
    const signal = this.#abort?.signal ?? new AbortController().signal
    const visit: SendVisit = { ...(this.#visit ?? { mounted: false } as SendVisit), mounted: this.isMounted() }
    const outcome = await runSendAttempt(visit, this.portsProvider(), signal)
    this.#phase = 'terminal'
    this.onPhase('terminal')
    return outcome
  }
}
