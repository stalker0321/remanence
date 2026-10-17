import type { StillCapture } from './camera'
import { ScanError } from './errors'
import type { IncomingCatalog } from '../sync/incoming'
import type { PublicSession, SessionStore } from '../session/session'

export interface MatchResult {
  kind: 'none' | 'many' | 'one'
  capsuleId?: string
}

export interface RecognitionEngine {
  match(capture: StillCapture, catalog: IncomingCatalog): Promise<MatchResult>
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
): Promise<ScanGrant> {
  if (view.phase === 'signed-out' || view.phase === 'authenticated' || view.accountId === null) {
    throw new ScanError('GRANT_MISMATCH')
  }
  const result = await engine.match(capture, catalog)
  if (result.kind === 'none') throw new ScanError('NO_MATCH')
  if (result.kind !== 'one' || result.capsuleId === undefined) throw new ScanError('AMBIGUOUS')
  const item = catalog.items.find((candidate) => candidate.capsuleId === result.capsuleId)
  if (!item || item.recipientUserId !== view.accountId) throw new ScanError('GRANT_MISMATCH')
  return ScanGrant.issue(view.accountId, item.capsuleId)
}

export function admitScan(session: SessionStore, grant: ScanGrant): void {
  const view = session.getSnapshot()
  if (!grant.allows(view, grant.capsuleId) || view.phase !== 'keys-unlocked') throw new ScanError('GRANT_MISMATCH')
  session.matchPostcard()
}
