/**
 * T320 — current-visit holder for a produced front fingerprint wire.
 *
 * Owns the accepted producer's output for exactly one live Create visit:
 * the wire (plus provenance/recipient) is stored only while the owner,
 * session generation, recipient confirmation, held still and mount that it
 * was produced under are all still current. Any retake/close/replacement/
 * lock/unmount aborts in-flight work and wipes the held bytes. There is no
 * persistence, network, or outbox here; a future sealer reads through
 * `current()`, which revalidates the visit and hands back nothing stale.
 */

import type { FrontHold, FrontStill } from './front'
import type { RecipientDraft, RecipientSnapshot } from './recipient'
import type { SessionStore } from '../session/session'
import {
  produceFrontStillFingerprint,
  FrontStillError,
  type CropProvenance,
} from '../scan/frontStillProducer'
import type { CvContourRuntime } from '../scan/opencvContour'
import type { OwnedRgbaFrame } from '../scan/frontStillProducer'

export interface FrontFingerprintValue {
  /** Owned wire bytes; wiped when the visit ends or is replaced. */
  readonly wire: Uint8Array
  readonly profileId: string
  readonly provenance: CropProvenance
  readonly recipient: { userId: string; keyBundleId: string; directoryVersion: string }
}

export interface FrontFingerprintRunDeps {
  readonly store: SessionStore
  readonly recipient: RecipientDraft
  readonly hold: FrontHold
  readonly mounted: () => boolean
  /** Test seams only; production omits them (real runtime/DOM decode). */
  readonly runtime?: CvContourRuntime | Promise<CvContourRuntime>
  readonly decode?: (jpeg: Uint8Array, signal?: AbortSignal) => Promise<OwnedRgbaFrame>
}

function refuseStale(): FrontStillError {
  return new FrontStillError('stale')
}

/**
 * Visit-bound holder. `run` executes the accepted producer under a fence
 * captured synchronously here; `current` rechecks that fence on every read.
 */
export class FrontFingerprintHold {
  #held: FrontFingerprintValue | null = null
  #fence: (() => boolean) | null = null
  #controller: AbortController | null = null
  #attempt = 0

  /** The live visit value, or null (wiping on a newly-stale visit). */
  current(): FrontFingerprintValue | null {
    if (this.#held !== null) {
      let ok = false
      try {
        ok = this.#fence !== null && this.#fence()
      } catch {
        ok = false
      }
      if (!ok) this.clear()
    }
    return this.#held
  }

  /**
   * Produces a fingerprint for `still` under the current visit and stores it
   * only if the visit is still current afterwards. A newer run or `clear()`
   * aborts in-flight work; late results are wiped, never stored or returned.
   */
  async run(still: FrontStill, deps: FrontFingerprintRunDeps): Promise<FrontFingerprintValue> {
    const attempt = ++this.#attempt
    this.#controller?.abort()
    const controller = new AbortController()
    this.#controller = controller
    if (!still || typeof still !== 'object') throw refuseStale()
    const view = deps.store.getSnapshot()
    const accountId = view.accountId
    const bundleId = view.bundleId
    const generation = view.generation
    const phase = view.phase
    const recipientSnapshot: RecipientSnapshot | null = deps.recipient.confirmed()
    if (accountId === null || recipientSnapshot === null || deps.hold.still() !== still) throw refuseStale()
    if (
      still.userId !== recipientSnapshot.userId ||
      still.keyBundleId !== recipientSnapshot.keyBundleId ||
      still.directoryVersion !== recipientSnapshot.directoryVersion
    ) {
      throw refuseStale()
    }
    const fence = (): boolean => {
      const now = deps.store.getSnapshot()
      if (now.accountId !== accountId || now.bundleId !== bundleId || now.generation !== generation) return false
      if (now.phase !== phase || now.phase === 'signed-out' || now.phase === 'authenticated') return false
      if (deps.recipient.confirmed() !== recipientSnapshot) return false
      if (deps.hold.still() !== still) return false
      if (!deps.mounted()) return false
      return this.#attempt === attempt
    }
    if (!fence()) throw refuseStale()
    let produced
    try {
      produced = await produceFrontStillFingerprint(still, {
        fence,
        signal: controller.signal,
        runtime: deps.runtime,
        decode: deps.decode,
      })
    } finally {
      if (this.#controller === controller) this.#controller = null
    }
    if (this.#attempt !== attempt || !fence()) {
      produced.wire.fill(0)
      throw refuseStale()
    }
    this.#held = {
      wire: produced.wire,
      profileId: produced.profileId,
      provenance: produced.provenance,
      recipient: produced.recipient,
    }
    this.#fence = fence
    return this.#held
  }

  /** Aborts in-flight work and wipes the held wire. Idempotent. */
  clear(): void {
    this.#attempt += 1
    this.#controller?.abort()
    this.#controller = null
    if (this.#held !== null) {
      this.#held.wire.fill(0)
      this.#held = null
    }
    this.#fence = null
  }
}
