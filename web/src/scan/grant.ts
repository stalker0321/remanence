import type { StillCapture } from './camera'
import { ScanError } from './errors'
import type { IncomingCatalog } from '../sync/incoming'
import type { PublicSession, SessionStore } from '../session/session'
import type { RecognitionIndex } from './recognitionIndex'

export interface MatchResult {
  kind: 'none' | 'many' | 'one'
  capsuleId?: string
  /** `many` only: capsules the same postcard matched (one per sender). */
  capsuleIds?: readonly string[]
}

/** T328 — optional pre-grant recognition context for a genuine engine. */
export interface MatchContext {
  readonly index: RecognitionIndex
}

export interface RecognitionEngine {
  match(capture: StillCapture, catalog: IncomingCatalog, context?: MatchContext): Promise<MatchResult>
}

/** No SIFT implementation is wired. A missing engine is not a match. */
export function unavailableRecognition(): RecognitionEngine {
  return {
    match() {
      return Promise.reject(new ScanError('RECOGNITION_UNAVAILABLE'))
    },
  }
}

export class ScanGrant {
  private constructor(
    readonly accountId: string,
    readonly capsuleId: string,
  ) {}

  static issue(accountId: string, capsuleId: string): ScanGrant {
    return new ScanGrant(accountId, capsuleId)
  }

  allows(view: PublicSession, capsuleId: string): boolean {
    return view.phase !== 'signed-out'
      && view.phase !== 'authenticated'
      && view.accountId === this.accountId
      && this.capsuleId === capsuleId
  }
}

export async function considerCapture(
  view: PublicSession,
  engine: RecognitionEngine,
  capture: StillCapture,
  catalog: IncomingCatalog,
  context?: MatchContext,
  chosen?: string,
): Promise<ScanGrant> {
  if (view.phase === 'signed-out' || view.phase === 'authenticated' || view.accountId === null) {
    throw new ScanError('GRANT_MISMATCH')
  }
  const result = await engine.match(capture, catalog, context)
  if (result.kind === 'none') throw new ScanError('NO_MATCH')
  const own = (capsuleId: string) => catalog.items.some((candidate) => candidate.capsuleId === capsuleId && candidate.recipientUserId === view.accountId)
  let capsuleId = result.capsuleId
  if (result.kind === 'many') {
    // The same postcard from several senders: only the recipient can say whose
    // capsule this is. A choice counts only if this very capture matched it.
    const choices = (result.capsuleIds ?? []).filter(own)
    if (chosen === undefined || !choices.includes(chosen)) throw new ScanError('AMBIGUOUS', choices.length >= 2 ? choices : [])
    capsuleId = chosen
  }
  if (capsuleId === undefined) throw new ScanError('AMBIGUOUS')
  const item = catalog.items.find((candidate) => candidate.capsuleId === capsuleId)
  if (!item || item.recipientUserId !== view.accountId) throw new ScanError('GRANT_MISMATCH')
  return ScanGrant.issue(view.accountId, item.capsuleId)
}

export function admitScan(session: SessionStore, grant: ScanGrant): void {
  const view = session.getSnapshot()
  if (!grant.allows(view, grant.capsuleId) || view.phase !== 'keys-unlocked') throw new ScanError('GRANT_MISMATCH')
  session.matchPostcard()
}
