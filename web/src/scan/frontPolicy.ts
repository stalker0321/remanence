/**
 * T340 — pure FRONT ranking, composite acceptance, 0/1/N classification and
 * recipient-first/sender-fallback coordination.
 *
 * Direct port of the authoritative FRONT-only Android sources:
 * `FrontCandidateRanker.kt`, `CompositeAcceptance.kt`,
 * `ScanOutcomeClassifier.kt`, `MatchCoordinator.kt` (and the active
 * `RecognitionProfile.postcardSiftRootSiftV1()` ranking constants). Composite
 * score IS the FRONT score; there is no back weighting.
 *
 * PURE: no promises/effects/verifier/grant/engine and no matcher/extractor/
 * index/Scan wiring. Inputs are copied; caller arrays are never sorted or
 * mutated. `accepted`/`AUTO_ACCEPTED` are technical policy outcomes only, never
 * recognition admission or authorization.
 */

export const FRONT_POLICY = Object.freeze({
  retainedLimit: 5,
  duplicateFrontMargin: 0.08,
  autoFrontMin: 0.7,
  autoMarginOverRunnerUp: 0.12,
  duplicateFrontMinScore: 0.65,
  chooserFrontMin: 0.4,
  minChooserRows: 2,
  maxChooserRows: 5,
})

export interface FrontCandidateRow {
  readonly candidateId: string
  readonly sideScore: number
  readonly weakGatePassed: boolean
}

export interface ScoredCandidateRow {
  readonly candidateId: string
  readonly compositeScore: number
  readonly frontScore: number
  readonly frontWeakPassed: boolean
  readonly frontStrongPassed: boolean
}

export type RejectionRule =
  | 'FRONT_WEAK_REQUIRED'
  | 'FRONT_BELOW_AUTO_MIN'
  | 'MARGIN_OVER_RUNNER_UP_TOO_SMALL'
  | 'NO_STRONG_FRONT_EVIDENCE'
  | 'DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_FRONT'
  | 'MULTIPLE_PLAUSIBLE_CANDIDATES'

export interface AcceptanceReport {
  readonly scored: readonly ScoredCandidateRow[]
  readonly autoAccepted: ScoredCandidateRow | null
  readonly rejectionRule: RejectionRule | null
}

export interface RankingReport {
  readonly retained: readonly FrontCandidateRow[]
  readonly duplicateFrontGroup: boolean
  readonly noMatchFront: boolean
}

export type ScanOutcome = 'AUTO_ACCEPTED' | 'PLAUSIBLE_CHOOSER' | 'SINGLE_CANDIDATE_RECAPTURE' | 'NO_MATCH'

export interface ScanClassification {
  readonly outcome: ScanOutcome
  readonly accepted: ScoredCandidateRow | null
  readonly chooserRows: readonly ScoredCandidateRow[]
}

export type CandidateOrigin = 'RECIPIENT_PREFERRED' | 'SENDER_FALLBACK'

export interface UniverseScanResult {
  readonly origin: CandidateOrigin
  readonly frontRanking: RankingReport
  readonly acceptance: AcceptanceReport | null
}

export type CoordinatorDecision =
  | { readonly kind: 'auto-accepted'; readonly origin: CandidateOrigin; readonly candidateId: string }
  | {
      readonly kind: 'sender-fallback-accepted'
      readonly candidateId: string
      readonly suggestsBaselineImprovement: true
    }
  | { readonly kind: 'ambiguous'; readonly origin: CandidateOrigin; readonly classification: ScanClassification }
  | { readonly kind: 'no-match-everywhere' }

/** Redacted, bounded refusal for malformed/unsupported input. */
export class FrontPolicyError extends Error {
  constructor(readonly code: 'input' | 'unsupported') {
    super(code)
    this.name = 'FrontPolicyError'
  }
}

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function copyFront(input: FrontCandidateRow): FrontCandidateRow {
  const row = input as unknown as Record<string, unknown> | null | undefined
  if (row == null || typeof row !== 'object') throw new FrontPolicyError('input')
  const candidateId = row.candidateId
  const sideScore = row.sideScore
  const weakGatePassed = row.weakGatePassed
  if (
    typeof candidateId !== 'string' ||
    candidateId.length === 0 ||
    !finite(sideScore) ||
    typeof weakGatePassed !== 'boolean'
  ) {
    throw new FrontPolicyError('input')
  }
  return { candidateId, sideScore, weakGatePassed }
}

function copyScored(input: ScoredCandidateRow): ScoredCandidateRow {
  const row = input as unknown as Record<string, unknown> | null | undefined
  if (row == null || typeof row !== 'object') throw new FrontPolicyError('input')
  const candidateId = row.candidateId
  const compositeScore = row.compositeScore
  const frontScore = row.frontScore
  const frontWeakPassed = row.frontWeakPassed
  const frontStrongPassed = row.frontStrongPassed
  if (
    typeof candidateId !== 'string' ||
    candidateId.length === 0 ||
    !finite(compositeScore) ||
    !finite(frontScore) ||
    typeof frontWeakPassed !== 'boolean' ||
    typeof frontStrongPassed !== 'boolean'
  ) {
    throw new FrontPolicyError('input')
  }
  return { candidateId, compositeScore, frontScore, frontWeakPassed, frontStrongPassed }
}

/** Stable descending sort over a fresh copy (ties preserve input order). */
function stableDescending<T>(rows: readonly T[], score: (row: T) => number): T[] {
  return [...rows].sort((left, right) => score(right) - score(left))
}

/** Shared plausibility predicate: weak front AND composite at the chooser floor. */
function plausible(rows: readonly ScoredCandidateRow[]): ScoredCandidateRow[] {
  return rows.filter((row) => row.frontWeakPassed && row.compositeScore >= FRONT_POLICY.chooserFrontMin)
}

/** Stage 1: weak filter, stable DESC, top 5, duplicate-front group. */
export function rankFrontCandidates(candidates: readonly FrontCandidateRow[]): RankingReport {
  const eligible = stableDescending(
    candidates.map(copyFront).filter((row) => row.weakGatePassed),
    (row) => row.sideScore,
  )
  const retained = eligible.slice(0, FRONT_POLICY.retainedLimit)
  const duplicateFrontGroup =
    retained.length >= 2 &&
    retained[0]!.sideScore - retained[1]!.sideScore < FRONT_POLICY.duplicateFrontMargin
  return { retained, duplicateFrontGroup, noMatchFront: retained.length === 0 }
}

/** Stage 2: FRONT-only automatic acceptance on the LEADING scored row. */
export function evaluateCompositeAcceptance(
  candidates: readonly ScoredCandidateRow[],
  duplicateFrontGroup = false,
): AcceptanceReport {
  if (!Array.isArray(candidates) || candidates.length === 0) throw new FrontPolicyError('input')
  // FRONT-only contract: the composite IS the FRONT score. Derive it here
  // rather than trusting a caller-supplied compositeScore (native constructs
  // its ScoredComposite from frontScore), so a mismatched caller value cannot
  // promote a below-auto FRONT.
  const scored = stableDescending(
    candidates.map((row) => {
      const copied = copyScored(row)
      return { ...copied, compositeScore: copied.frontScore }
    }),
    (row) => row.compositeScore,
  )

  if (plausible(scored).length >= 2) {
    return { scored, autoAccepted: null, rejectionRule: 'MULTIPLE_PLAUSIBLE_CANDIDATES' }
  }

  const leader = scored[0]!
  const runnerUp: ScoredCandidateRow | undefined = scored.length > 1 ? scored[1] : undefined
  let rejectionRule: RejectionRule | null = null
  if (!leader.frontWeakPassed) {
    rejectionRule = 'FRONT_WEAK_REQUIRED'
  } else if (leader.compositeScore < FRONT_POLICY.autoFrontMin) {
    rejectionRule = 'FRONT_BELOW_AUTO_MIN'
  } else if (
    runnerUp !== undefined &&
    leader.compositeScore - runnerUp.compositeScore < FRONT_POLICY.autoMarginOverRunnerUp
  ) {
    rejectionRule = 'MARGIN_OVER_RUNNER_UP_TOO_SMALL'
  } else if (!leader.frontStrongPassed) {
    rejectionRule = 'NO_STRONG_FRONT_EVIDENCE'
  } else if (duplicateFrontGroup) {
    const next: ScoredCandidateRow | undefined = scored.length > 1 ? scored[1] : undefined
    const frontMarginOk =
      next === undefined || leader.frontScore - next.frontScore >= FRONT_POLICY.autoMarginOverRunnerUp
    const dominantStrongFront =
      leader.frontStrongPassed &&
      leader.frontScore >= FRONT_POLICY.duplicateFrontMinScore &&
      frontMarginOk
    if (!dominantStrongFront) rejectionRule = 'DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_FRONT'
  }

  return {
    scored,
    autoAccepted: rejectionRule === null ? leader : null,
    rejectionRule,
  }
}

/** Fail-safe 0/1/N classification over a scored universe. */
export function classifyScanOutcome(
  frontRanking: RankingReport,
  acceptance: AcceptanceReport | null,
): ScanClassification {
  if (frontRanking.noMatchFront) {
    return { outcome: 'NO_MATCH', accepted: null, chooserRows: [] }
  }
  // Validate and COPY every row used for the decision and for returned
  // structured outputs; callers must not receive aliases they can mutate.
  const scored = (acceptance?.scored ?? []).map(copyScored)
  const plausibleRows = stableDescending(plausible(scored), (row) => row.compositeScore)
  if (plausibleRows.length >= FRONT_POLICY.minChooserRows) {
    return {
      outcome: 'PLAUSIBLE_CHOOSER',
      accepted: null,
      chooserRows: plausibleRows.slice(0, FRONT_POLICY.maxChooserRows).map(copyScored),
    }
  }
  if (acceptance !== null && acceptance.autoAccepted !== null) {
    return { outcome: 'AUTO_ACCEPTED', accepted: copyScored(acceptance.autoAccepted), chooserRows: [] }
  }
  if (plausibleRows.length === 1) {
    return { outcome: 'SINGLE_CANDIDATE_RECAPTURE', accepted: null, chooserRows: [] }
  }
  return { outcome: 'NO_MATCH', accepted: null, chooserRows: [] }
}

interface UnionRow {
  readonly front: FrontCandidateRow
  readonly scored: ScoredCandidateRow | null
  readonly origin: CandidateOrigin
}

/**
 * M1-M09 union decision: absorb recipient FIRST then sender, keyed by capsule
 * id with `putIfAbsent` (recipient baseline wins even with a higher sender
 * score). Native absorb order is retained fronts THEN `acceptance.scored`;
 * `associateBy` keeps the LAST duplicate scored row. Re-rank and RECOMPUTE
 * acceptance/classification over the union; individual-universe
 * `autoAccepted` claims are not trusted.
 */
export function coordinateFrontMatches(
  recipient: UniverseScanResult,
  sender: UniverseScanResult | null,
): CoordinatorDecision {
  const recipientView = recipient as unknown as { origin?: unknown } | null | undefined
  if (recipientView == null || typeof recipientView !== 'object' || recipientView.origin !== 'RECIPIENT_PREFERRED') {
    throw new FrontPolicyError('input')
  }
  if (sender !== null) {
    const senderView = sender as unknown as { origin?: unknown } | null | undefined
    if (senderView == null || typeof senderView !== 'object' || senderView.origin !== 'SENDER_FALLBACK') {
      throw new FrontPolicyError('input')
    }
  }

  const rows = new Map<string, UnionRow>()
  const absorb = (universe: UniverseScanResult): void => {
    const scoredById = new Map<string, ScoredCandidateRow>()
    for (const scored of universe.acceptance?.scored ?? []) {
      // Native `associateBy`: the LAST duplicate candidateId wins.
      scoredById.set(scored.candidateId, copyScored(scored))
    }
    for (const front of universe.frontRanking.retained) {
      const copied = copyFront(front)
      if (!rows.has(copied.candidateId)) {
        rows.set(copied.candidateId, { front: copied, scored: scoredById.get(copied.candidateId) ?? null, origin: universe.origin })
      }
    }
    for (const scored of universe.acceptance?.scored ?? []) {
      const copied = copyScored(scored)
      if (!rows.has(copied.candidateId)) {
        rows.set(copied.candidateId, {
          front: { candidateId: copied.candidateId, sideScore: copied.frontScore, weakGatePassed: copied.frontWeakPassed },
          scored: copied,
          origin: universe.origin,
        })
      }
    }
  }
  absorb(recipient)
  if (sender !== null) absorb(sender)

  const ranking = rankFrontCandidates([...rows.values()].map((row) => row.front))
  const composites: ScoredCandidateRow[] = ranking.retained.map((retained) => {
    const existing = rows.get(retained.candidateId)
    return {
      candidateId: retained.candidateId,
      compositeScore: existing?.scored?.frontScore ?? retained.sideScore,
      frontScore: existing?.scored?.frontScore ?? retained.sideScore,
      frontWeakPassed: existing?.scored?.frontWeakPassed ?? retained.weakGatePassed,
      frontStrongPassed: existing?.scored?.frontStrongPassed ?? false,
    }
  })
  const acceptance = composites.length === 0 ? null : evaluateCompositeAcceptance(composites, ranking.duplicateFrontGroup)
  const classification = classifyScanOutcome(ranking, acceptance)

  const involvedIds: string[] =
    classification.accepted !== null
      ? [classification.accepted.candidateId]
      : classification.chooserRows.length > 0
        ? classification.chooserRows.map((row) => row.candidateId)
        : plausible(acceptance?.scored ?? []).map((row) => row.candidateId)
  const origin: CandidateOrigin = involvedIds.some((id) => rows.get(id)?.origin === 'RECIPIENT_PREFERRED')
    ? 'RECIPIENT_PREFERRED'
    : 'SENDER_FALLBACK'

  if (classification.outcome === 'AUTO_ACCEPTED') {
    const candidateId = classification.accepted!.candidateId
    return origin === 'RECIPIENT_PREFERRED'
      ? { kind: 'auto-accepted', origin, candidateId }
      : { kind: 'sender-fallback-accepted', candidateId, suggestsBaselineImprovement: true }
  }
  if (classification.outcome === 'PLAUSIBLE_CHOOSER' || classification.outcome === 'SINGLE_CANDIDATE_RECAPTURE') {
    return { kind: 'ambiguous', origin, classification }
  }
  return { kind: 'no-match-everywhere' }
}
