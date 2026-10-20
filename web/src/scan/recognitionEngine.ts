/**
 * T346 — connected receiving `RecognitionEngine` (technical matching only).
 *
 * Factory over the accepted pieces:
 *   real identity-free query producer → strict format-3 parse →
 *   accepted `matchSide` for EVERY supplied reference (scores + weak/strong) →
 *   accepted FRONT ranking/acceptance/coordinator policy →
 *   existing `MatchResult` (`one` | `many` | `none`).
 *
 * The index carries SENDER reference fingerprints, not a learned recipient
 * baseline, so the union uses the honest SENDER_FALLBACK universe with an empty
 * RECIPIENT_PREFERRED universe. No shortcut first/highest match: every
 * candidate is evaluated before selection. Both catalog and index pages must be
 * complete (`hasMore === false`, `nextCursor === null`) and their capsule IDs
 * must match exactly once; incomplete pages fail closed and never claim global
 * uniqueness. Anchor ownership/fence/signal are captured before the first await
 * and rechecked around every async/native stage and before output.
 *
 * PURE technical adapter: no App/UI activation, no grant/crypto/session
 * change, no quality gate, no pagination, no persistence/logging of features.
 */

import type { RecognitionEngine, MatchResult } from './grant'
import type { StillCapture } from './camera'
import type { IncomingCatalog } from '../sync/incoming'
import type { RecognitionIndex } from './recognitionIndex'
import { RecognitionIndexError } from './recognitionIndex'
import { produceQueryFingerprint, type QueryFingerprint } from './queryFingerprint'
import { FrontStillError } from './frontStillProducer'
import {
  parseSiftFingerprint,
  serializeSiftFingerprint,
  SiftFingerprintError,
  type SiftFingerprint,
} from './siftFingerprint'
import { matchSide, SideMatchError, type SideMatchResult } from './sideMatcher'
import {
  coordinateFrontMatches,
  evaluateCompositeAcceptance,
  rankFrontCandidates,
  FrontPolicyError,
  type ScoredCandidateRow,
  type UniverseScanResult,
} from './frontPolicy'

export type RecognitionEngineErrorCode =
  | 'unavailable'
  | 'invalid'
  | 'incomplete'
  | 'cancelled'
  | 'stale'
  | 'contract'
  | 'internal'
  | 'dimensions'
  | 'pixels'
  | 'decode'
  | 'crop'
  | 'warp'
  | 'features'

/** Redacted bounded engine refusal; never carries feature data. */
export class RecognitionEngineError extends Error {
  constructor(readonly code: RecognitionEngineErrorCode) {
    super(code)
    this.name = 'RecognitionEngineError'
  }
}

function refuse(code: RecognitionEngineErrorCode): RecognitionEngineError {
  return new RecognitionEngineError(code)
}

export interface RecognitionEngineDeps {
  /** Test seams only; production defaults are the accepted implementations. */
  readonly produceQuery?: typeof produceQueryFingerprint
  readonly matchSide?: typeof matchSide
  /** Optional caller cancellation, captured at engine creation. */
  readonly signal?: AbortSignal
  /** Optional additional owner/current-visit fence, captured once. */
  readonly fence?: () => boolean
}

interface IndexLike {
  readonly hasMore: boolean
  readonly nextCursor: string | null
  entries(): readonly { readonly capsuleId: string; readonly fingerprint: SiftFingerprint }[]
}

function mapError(error: unknown): RecognitionEngineError {
  if (error instanceof RecognitionEngineError) return error
  if (error instanceof FrontStillError) {
    switch (error.code) {
      case 'stale':
        return refuse('stale')
      case 'cancelled':
        return refuse('cancelled')
      case 'unavailable':
        return refuse('unavailable')
      case 'internal':
        return refuse('internal')
      case 'dimensions':
      case 'pixels':
        return refuse('invalid')
      case 'decode':
      case 'crop':
      case 'warp':
      case 'features':
        return refuse(error.code)
      default:
        return refuse('internal')
    }
  }
  if (error instanceof SideMatchError) {
    switch (error.code) {
      case 'stale':
        return refuse('stale')
      case 'cancelled':
        return refuse('cancelled')
      case 'unavailable':
        return refuse('unavailable')
      case 'contract':
        return refuse('contract')
      default:
        return refuse('internal')
    }
  }
  if (error instanceof RecognitionIndexError) {
    if (error.code === 'stale') return refuse('stale')
    if (error.code === 'unavailable') return refuse('unavailable')
    return refuse('invalid')
  }
  if (error instanceof FrontPolicyError) return refuse('invalid')
  if (error instanceof SiftFingerprintError) return refuse('invalid')
  return refuse('internal')
}

function assertComplete(catalog: IncomingCatalog, index: IndexLike): void {
  if (typeof catalog.hasMore !== 'boolean' || typeof index.hasMore !== 'boolean') throw refuse('invalid')
  const catalogCursor = catalog.nextCursor
  const indexCursor = index.nextCursor
  if (catalogCursor !== null && typeof catalogCursor !== 'string') throw refuse('invalid')
  if (indexCursor !== null && typeof indexCursor !== 'string') throw refuse('invalid')
  if (catalog.hasMore !== false || catalogCursor !== null || index.hasMore !== false || indexCursor !== null) {
    throw refuse('incomplete')
  }
}

/** Deep-copy a reference fingerprint through the accepted codec (owns rows). */
function copyReference(fingerprint: SiftFingerprint): SiftFingerprint {
  let wire: Uint8Array | null = null
  try {
    wire = serializeSiftFingerprint(fingerprint)
    return parseSiftFingerprint(wire)
  } finally {
    if (wire !== null) wire.fill(0)
  }
}

function wipeFingerprint(fingerprint: SiftFingerprint | null): void {
  if (fingerprint === null) return
  for (const row of fingerprint.quantizedSiftDescriptors) row.fill(0)
  fingerprint.quantizedSiftDescriptors.length = 0
  fingerprint.keypoints.length = 0
}

/**
 * Production connected engine. `match` is the existing ReceivingEngine
 * contract; typed refusals are `RecognitionEngineError` (no fake NO_MATCH).
 */
export function createRecognitionEngine(deps: RecognitionEngineDeps = {}): RecognitionEngine {
  const produceQuery = deps.produceQuery ?? produceQueryFingerprint
  const runSide = deps.matchSide ?? matchSide
  const signal = deps.signal
  const extraFence = deps.fence

  return {
    async match(capture: StillCapture, catalog: IncomingCatalog, context?: { index: RecognitionIndex }): Promise<MatchResult> {
      const index = context?.index as IndexLike | undefined
      if (index === undefined || index === null || typeof index.entries !== 'function') {
        throw refuse('unavailable')
      }

      // --- synchronous snapshots/validation BEFORE the first await ---
      if (capture === null || typeof capture !== 'object' || !(capture.bytes instanceof Uint8Array)) {
        throw refuse('invalid')
      }
      if (catalog === null || typeof catalog !== 'object' || !Array.isArray(catalog.items)) {
        throw refuse('invalid')
      }
      assertComplete(catalog, index)

      const catalogIds = catalog.items.map((item) => {
        if (item === null || typeof item !== 'object' || typeof item.capsuleId !== 'string' || item.capsuleId.length === 0) {
          throw refuse('invalid')
        }
        return item.capsuleId
      })
      const catalogIdSet = new Set(catalogIds)
      if (catalogIdSet.size !== catalogIds.length) throw refuse('invalid')

      let rawEntries: readonly { readonly capsuleId: string; readonly fingerprint: SiftFingerprint }[]
      try {
        rawEntries = index.entries()
      } catch (error) {
        throw mapError(error)
      }
      if (!Array.isArray(rawEntries)) throw refuse('invalid')
      const entryIds = new Set<string>()
      for (const entry of rawEntries) {
        if (entry === null || typeof entry !== 'object' || typeof entry.capsuleId !== 'string' || entry.capsuleId.length === 0) {
          throw refuse('invalid')
        }
        if (entryIds.has(entry.capsuleId)) throw refuse('invalid')
        entryIds.add(entry.capsuleId)
      }
      if (entryIds.size !== catalogIdSet.size || [...entryIds].some((id) => !catalogIdSet.has(id))) {
        throw refuse('invalid')
      }

      // Snapshot every reference fingerprint synchronously (caller/index is
      // never mutated; later mutations cannot relabel the result).
      const references: SiftFingerprint[] = []
      try {
        for (const entry of rawEntries) {
          try {
            references.push(copyReference(entry.fingerprint))
          } catch (error) {
            throw mapError(error)
          }
        }
      } catch (error) {
        for (const reference of references) wipeFingerprint(reference)
        throw error
      }

      const fence = (): boolean => {
        if (extraFence !== undefined) {
          try {
            if (!extraFence()) return false
          } catch {
            return false
          }
        }
        try {
          index.entries()
          return true
        } catch {
          return false
        }
      }
      const assertLive = (): void => {
        if (signal?.aborted === true) throw refuse('cancelled')
        if (!fence()) throw refuse('stale')
      }

      let queryDomain: SiftFingerprint | null = null
      try {
        assertLive()
        let produced: QueryFingerprint
        try {
          produced = await produceQuery(capture, { fence, signal })
        } catch (error) {
          throw mapError(error)
        }
        assertLive()
        try {
          queryDomain = parseSiftFingerprint(produced.wire)
        } catch (error) {
          throw mapError(error)
        } finally {
          produced.wire.fill(0)
        }

        const query = queryDomain!
        const scored: ScoredCandidateRow[] = []
        for (let indexRef = 0; indexRef < references.length; indexRef++) {
          assertLive()
          const reference = references[indexRef]!
          let side: SideMatchResult
          try {
            side = await runSide(query, reference, { fence, signal })
          } catch (error) {
            throw mapError(error)
          }
          assertLive()
          scored.push({
            candidateId: rawEntries[indexRef]!.capsuleId,
            compositeScore: side.report.sideScore,
            frontScore: side.report.sideScore,
            frontWeakPassed: side.report.weakGatePassed,
            frontStrongPassed: side.report.strongGatePassed,
          })
        }

        const strongById = new Map(scored.map((row) => [row.candidateId, row.frontStrongPassed]))
        const fronts = scored.map((row) => ({
          candidateId: row.candidateId,
          sideScore: row.frontScore,
          weakGatePassed: row.frontWeakPassed,
        }))
        const ranking = rankFrontCandidates(fronts)
        const composites = ranking.retained.map((row) => ({
          candidateId: row.candidateId,
          compositeScore: row.sideScore,
          frontScore: row.sideScore,
          frontWeakPassed: row.weakGatePassed,
          frontStrongPassed: strongById.get(row.candidateId) ?? false,
        }))
        const acceptance = composites.length === 0 ? null : evaluateCompositeAcceptance(composites, ranking.duplicateFrontGroup)
        const senderUniverse: UniverseScanResult = {
          origin: 'SENDER_FALLBACK',
          frontRanking: ranking,
          acceptance,
        }
        const recipientUniverse: UniverseScanResult = {
          origin: 'RECIPIENT_PREFERRED',
          frontRanking: rankFrontCandidates([]),
          acceptance: null,
        }
        const decision = coordinateFrontMatches(recipientUniverse, senderUniverse)
        assertLive()

        switch (decision.kind) {
          case 'auto-accepted':
          case 'sender-fallback-accepted': {
            const capsuleId = decision.candidateId
            if (!catalogIdSet.has(capsuleId)) throw refuse('invalid')
            return { kind: 'one', capsuleId }
          }
          case 'ambiguous':
            return { kind: 'many' }
          case 'no-match-everywhere':
            return { kind: 'none' }
        }
        throw refuse('internal')
      } catch (error) {
        throw mapError(error)
      } finally {
        wipeFingerprint(queryDomain)
        for (const reference of references) wipeFingerprint(reference)
      }
    },
  }
}
