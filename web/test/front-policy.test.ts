import { describe, expect, it } from 'vitest'
import {
  classifyScanOutcome,
  coordinateFrontMatches,
  evaluateCompositeAcceptance,
  FRONT_POLICY,
  FrontPolicyError,
  rankFrontCandidates,
  type AcceptanceReport,
  type CandidateOrigin,
  type FrontCandidateRow,
  type RankingReport,
  type ScoredCandidateRow,
  type UniverseScanResult,
} from '../src/scan/frontPolicy'

/** Vectors derive from the native Kotlin sources; NOT executed Kotlin parity. */

function front(id: string, sideScore: number, weakGatePassed = true): FrontCandidateRow {
  return { candidateId: id, sideScore, weakGatePassed }
}

function scored(
  id: string,
  score: number,
  frontWeakPassed = true,
  frontStrongPassed = true,
): ScoredCandidateRow {
  return { candidateId: id, compositeScore: score, frontScore: score, frontWeakPassed, frontStrongPassed }
}

function universe(
  origin: CandidateOrigin,
  rows: ScoredCandidateRow[],
  autoAccepted: ScoredCandidateRow | null = null,
): UniverseScanResult {
  const retained = rows.filter((row) => row.frontWeakPassed).map((row) => front(row.candidateId, row.frontScore))
  const acceptance: AcceptanceReport | null =
    rows.length === 0 ? null : { scored: rows, autoAccepted, rejectionRule: null }
  return { origin, frontRanking: { retained, duplicateFrontGroup: false, noMatchFront: retained.length === 0 }, acceptance }
}

const EMPTY_RECIPIENT = universe('RECIPIENT_PREFERRED', [])

describe('T340 FRONT ranking', () => {
  it('filters weak failures, orders DESC, and never mutates the caller', () => {
    const input = [front('weak-low', 0.3), front('failing', 0.9, false), front('strong', 0.8), front('middle', 0.55)]
    const snapshot = JSON.stringify(input)
    const ranking = rankFrontCandidates(input)
    expect(ranking.retained.map((row) => row.candidateId)).toEqual(['strong', 'middle', 'weak-low'])
    expect(ranking.duplicateFrontGroup).toBe(false)
    expect(ranking.noMatchFront).toBe(false)
    expect(JSON.stringify(input)).toBe(snapshot)
  })

  it('preserves input order on stable score ties', () => {
    const ranking = rankFrontCandidates([front('a', 0.5), front('b', 0.5), front('c', 0.6)])
    expect(ranking.retained.map((row) => row.candidateId)).toEqual(['c', 'a', 'b'])
  })

  it('caps retention at five', () => {
    const input = Array.from({ length: 9 }, (_, i) => front(`c${i}`, 0.4 + i * 0.01))
    const ranking = rankFrontCandidates(input)
    expect(ranking.retained.map((row) => row.candidateId)).toEqual(['c8', 'c7', 'c6', 'c5', 'c4'])
  })

  it('forms a duplicate group strictly below 0.08 and not at/above it', () => {
    expect(rankFrontCandidates([front('a', 1), front('b', 1 - 0.0625)]).duplicateFrontGroup).toBe(true)
    expect(rankFrontCandidates([front('a', 1), front('b', 1 - 0.125)]).duplicateFrontGroup).toBe(false)
    // Exactly the double 0.08 (0.08 - 0): strict `<` is false.
    expect(rankFrontCandidates([front('a', 0.08), front('b', 0)]).duplicateFrontGroup).toBe(false)
  })

  it('never forms a duplicate group with one survivor and reports empty as noMatch', () => {
    expect(rankFrontCandidates([front('only', 0.5), front('failed', 0.99, false)]).duplicateFrontGroup).toBe(false)
    expect(rankFrontCandidates([]).noMatchFront).toBe(true)
    expect(rankFrontCandidates([front('x', 0.9, false)]).noMatchFront).toBe(true)
  })
})

describe('T340 composite acceptance', () => {
  it('accepts a clear leader and rejects non-finite/empty input', () => {
    const report = evaluateCompositeAcceptance([scored('leader', 0.8), scored('runner', 0.5, false, false)])
    expect(report.autoAccepted?.candidateId).toBe('leader')
    expect(report.rejectionRule).toBeNull()
    expect(() => evaluateCompositeAcceptance([])).toThrow(FrontPolicyError)
    expect(() => evaluateCompositeAcceptance([scored('x', Number.NaN)])).toThrow(FrontPolicyError)
  })

  it('requires FRONT weak evidence even with a huge composite', () => {
    const report = evaluateCompositeAcceptance([scored('blind', 0.95, false, true)])
    expect(report.autoAccepted).toBeNull()
    expect(report.rejectionRule).toBe('FRONT_WEAK_REQUIRED')
  })

  it('treats 0.70 as inclusive and 0.69 as below', () => {
    expect(evaluateCompositeAcceptance([scored('passing', 0.7)]).autoAccepted?.candidateId).toBe('passing')
    expect(evaluateCompositeAcceptance([scored('failing', 0.69)]).rejectionRule).toBe('FRONT_BELOW_AUTO_MIN')
  })

  it('treats a 0.12 margin as inclusive', () => {
    const leader = scored('leader', 0.8)
    expect(evaluateCompositeAcceptance([leader, scored('runner', 0.68, false, false)]).autoAccepted?.candidateId).toBe('leader')
    expect(evaluateCompositeAcceptance([leader, scored('closer', 0.72, false, false)]).rejectionRule).toBe(
      'MARGIN_OVER_RUNNER_UP_TOO_SMALL',
    )
  })

  it('skips the margin rule without a runner-up', () => {
    expect(evaluateCompositeAcceptance([scored('lone', 0.75)]).autoAccepted?.candidateId).toBe('lone')
  })

  it('requires strong FRONT evidence', () => {
    expect(evaluateCompositeAcceptance([scored('weak-only', 0.85, true, false)]).rejectionRule).toBe(
      'NO_STRONG_FRONT_EVIDENCE',
    )
  })

  it('never auto-accepts with two plausible candidates, even score-separated', () => {
    const report = evaluateCompositeAcceptance([scored('leader', 0.85), scored('runner', 0.45)])
    expect(report.autoAccepted).toBeNull()
    expect(report.rejectionRule).toBe('MULTIPLE_PLAUSIBLE_CANDIDATES')
    expect(evaluateCompositeAcceptance([scored('leader', 0.85)]).autoAccepted?.candidateId).toBe('leader')
  })

  it('records the duplicate branch as unreachable after the earlier margin rule (FRONT-only composite==front)', () => {
    // With FRONT-only composite==frontScore, a duplicate group that reaches the
    // duplicate block has already passed the runner-up margin rule, so the
    // dominant-front condition cannot fail. No synthetic failure is forced.
    const report = evaluateCompositeAcceptance([scored('leader', 0.8), scored('runner', 0.68, false, false)], true)
    expect(report.autoAccepted?.candidateId).toBe('leader')
    expect(report.rejectionRule).toBeNull()
  })
})

describe('T340 scan outcome classification', () => {
  const ranking = (rows: FrontCandidateRow[], noMatchFront = false): RankingReport => ({
    retained: rows,
    duplicateFrontGroup: false,
    noMatchFront,
  })

  it('accepts a unique auto result', () => {
    const leader = scored('leader', 0.85)
    const result = classifyScanOutcome(ranking([front('leader', 0.85)]), {
      scored: [leader],
      autoAccepted: leader,
      rejectionRule: null,
    })
    expect(result.outcome).toBe('AUTO_ACCEPTED')
    expect(result.accepted?.candidateId).toBe('leader')
    expect(result.chooserRows).toEqual([])
  })

  it('chooses for multiple plausibles even when a supplied auto is set', () => {
    const leader = scored('leader', 0.85)
    const rows = [leader, scored('p2', 0.55), scored('p3', 0.45)]
    const result = classifyScanOutcome(ranking([front('leader', 0.85)]), {
      scored: rows,
      autoAccepted: leader,
      rejectionRule: null,
    })
    expect(result.outcome).toBe('PLAUSIBLE_CHOOSER')
    expect(result.accepted).toBeNull()
    expect(result.chooserRows.map((row) => row.candidateId)).toEqual(['leader', 'p2', 'p3'])
  })

  it('forces NO_MATCH when the ranking is empty, even with stale composites', () => {
    const result = classifyScanOutcome(ranking([], true), {
      scored: [scored('ghost', 0.9), scored('ghost2', 0.8)],
      autoAccepted: null,
      rejectionRule: null,
    })
    expect(result.outcome).toBe('NO_MATCH')
    expect(result.chooserRows).toEqual([])
  })

  it('caps the chooser at five score-sorted rows and excludes implausibles', () => {
    const rows = Array.from({ length: 7 }, (_, i) => scored(`c${i}`, 0.6 - i * 0.01))
    const result = classifyScanOutcome(ranking(rows.map((row) => front(row.candidateId, row.compositeScore))), {
      scored: rows,
      autoAccepted: null,
      rejectionRule: null,
    })
    expect(result.outcome).toBe('PLAUSIBLE_CHOOSER')
    expect(result.chooserRows).toHaveLength(5)

    const only = classifyScanOutcome(ranking([front('plausible', 0.45)]), {
      scored: [scored('plausible', 0.45), scored('floor', 0.39), scored('blind', 0.9, false)],
      autoAccepted: null,
      rejectionRule: null,
    })
    expect(only.outcome).toBe('SINGLE_CANDIDATE_RECAPTURE')
    expect(only.chooserRows).toEqual([])
  })

  it('recaptures for exactly one plausible below auto and reports NO_MATCH otherwise', () => {
    const one = classifyScanOutcome(ranking([front('single', 0.5)]), {
      scored: [scored('single', 0.5), scored('dud', 0.2, false)],
      autoAccepted: null,
      rejectionRule: null,
    })
    expect(one.outcome).toBe('SINGLE_CANDIDATE_RECAPTURE')
    const none = classifyScanOutcome(ranking([front('weak', 0.3)]), {
      scored: [scored('weak', 0.3, false)],
      autoAccepted: null,
      rejectionRule: null,
    })
    expect(none.outcome).toBe('NO_MATCH')
    expect(classifyScanOutcome(ranking([front('front', 0.6)]), null).outcome).toBe('NO_MATCH')
  })
})

describe('T340 coordinator', () => {
  it('accepts a unique recipient result when the sender is not plausible', () => {
    const decision = coordinateFrontMatches(
      universe('RECIPIENT_PREFERRED', [scored('rec-1', 0.85)], scored('rec-1', 0.85)),
      universe('SENDER_FALLBACK', [scored('snd-1', 0.2, false, false)]),
    )
    expect(decision).toEqual({ kind: 'auto-accepted', origin: 'RECIPIENT_PREFERRED', candidateId: 'rec-1' })
  })

  it('marks distinct recipient+sender plausibles ambiguous with recipient origin', () => {
    const decision = coordinateFrontMatches(
      universe('RECIPIENT_PREFERRED', [scored('rec-1', 0.85)], scored('rec-1', 0.85)),
      universe('SENDER_FALLBACK', [scored('snd-1', 0.95)], scored('snd-1', 0.95)),
    )
    expect(decision.kind).toBe('ambiguous')
    if (decision.kind !== 'ambiguous') return
    expect(decision.origin).toBe('RECIPIENT_PREFERRED')
    expect(decision.classification.outcome).toBe('PLAUSIBLE_CHOOSER')
    expect(decision.classification.chooserRows.map((row) => row.candidateId)).toEqual(['snd-1', 'rec-1'])
  })

  it('does not fall back to sender for recipient ambiguity', () => {
    const decision = coordinateFrontMatches(
      universe('RECIPIENT_PREFERRED', [scored('r1', 0.55, true, false), scored('r2', 0.45, true, false)]),
      universe('SENDER_FALLBACK', [scored('snd-1', 0.2, false, false)]),
    )
    expect(decision.kind).toBe('ambiguous')
    if (decision.kind !== 'ambiguous') return
    expect(decision.origin).toBe('RECIPIENT_PREFERRED')
    expect(decision.classification.outcome).toBe('PLAUSIBLE_CHOOSER')
    expect(decision.classification.chooserRows.map((row) => row.candidateId)).toEqual(['r1', 'r2'])
  })

  it('keeps single-recipient recapture when the sender is not plausible', () => {
    const decision = coordinateFrontMatches(
      universe('RECIPIENT_PREFERRED', [scored('only', 0.5, true, false)]),
      universe('SENDER_FALLBACK', [scored('snd', 0.2, false, false)]),
    )
    expect(decision.kind).toBe('ambiguous')
    if (decision.kind !== 'ambiguous') return
    expect(decision.origin).toBe('RECIPIENT_PREFERRED')
    expect(decision.classification.outcome).toBe('SINGLE_CANDIDATE_RECAPTURE')
    expect(decision.classification.chooserRows).toEqual([])
  })

  it('uses sender fallback only when the recipient has no plausible candidate', () => {
    const decision = coordinateFrontMatches(EMPTY_RECIPIENT, universe('SENDER_FALLBACK', [scored('snd-old', 0.85)], scored('snd-old', 0.85)))
    expect(decision).toEqual({ kind: 'sender-fallback-accepted', candidateId: 'snd-old', suggestsBaselineImprovement: true })

    const zeroPlausible = coordinateFrontMatches(
      universe('RECIPIENT_PREFERRED', [scored('aged-1', 0.3, true, false), scored('aged-2', 0.2, true, false)]),
      universe('SENDER_FALLBACK', [scored('snd', 0.9)], scored('snd', 0.9)),
    )
    expect(zeroPlausible).toEqual({ kind: 'sender-fallback-accepted', candidateId: 'snd', suggestsBaselineImprovement: true })
  })

  it('reports no-match when nothing exists and rejects invalid origins', () => {
    expect(coordinateFrontMatches(EMPTY_RECIPIENT, null)).toEqual({ kind: 'no-match-everywhere' })
    expect(coordinateFrontMatches(EMPTY_RECIPIENT, universe('SENDER_FALLBACK', []))).toEqual({ kind: 'no-match-everywhere' })
    expect(() => coordinateFrontMatches(universe('SENDER_FALLBACK', [scored('x', 0.8)]), null)).toThrow(FrontPolicyError)
    expect(() => coordinateFrontMatches(EMPTY_RECIPIENT, universe('RECIPIENT_PREFERRED', [scored('x', 0.8)]))).toThrow(
      FrontPolicyError,
    )
  })

  it('dedups the same capsule to the recipient baseline despite a higher sender score', () => {
    const decision = coordinateFrontMatches(
      universe('RECIPIENT_PREFERRED', [scored('same', 0.8)], scored('same', 0.8)),
      universe('SENDER_FALLBACK', [scored('same', 0.95)], scored('same', 0.95)),
    )
    expect(decision).toEqual({ kind: 'auto-accepted', origin: 'RECIPIENT_PREFERRED', candidateId: 'same' })
  })

  it('recomputes acceptance over the union instead of trusting supplied auto claims', () => {
    // Recipient claims auto at 0.55 (below auto min), sender adds a distinct plausible 0.85.
    const decision = coordinateFrontMatches(
      universe('RECIPIENT_PREFERRED', [scored('rec', 0.55)], scored('rec', 0.55)),
      universe('SENDER_FALLBACK', [scored('snd', 0.85)], scored('snd', 0.85)),
    )
    expect(decision.kind).toBe('ambiguous')
    if (decision.kind !== 'ambiguous') return
    expect(decision.classification.outcome).toBe('PLAUSIBLE_CHOOSER')
    expect(decision.classification.accepted).toBeNull()
  })

  it('uses the last duplicate scored entry per capsule (native associateBy)', () => {
    const boost = universe('SENDER_FALLBACK', [scored('x', 0.5), scored('x', 0.8)], scored('x', 0.8))
    const decision = coordinateFrontMatches(EMPTY_RECIPIENT, boost)
    // Last associateBy entry (.8) drives the union composite -> auto.
    expect(decision).toEqual({ kind: 'sender-fallback-accepted', candidateId: 'x', suggestsBaselineImprovement: true })
  })

  it('does not mutate caller universes', () => {
    const recipient = universe('RECIPIENT_PREFERRED', [scored('a', 0.5), scored('b', 0.45)])
    const sender = universe('SENDER_FALLBACK', [scored('c', 0.7)])
    const before = JSON.stringify([recipient, sender])
    coordinateFrontMatches(recipient, sender)
    expect(JSON.stringify([recipient, sender])).toBe(before)
  })

  it('exposes the frozen FRONT policy constants', () => {
    expect(FRONT_POLICY).toEqual({
      retainedLimit: 5,
      duplicateFrontMargin: 0.08,
      autoFrontMin: 0.7,
      autoMarginOverRunnerUp: 0.12,
      duplicateFrontMinScore: 0.65,
      chooserFrontMin: 0.4,
      minChooserRows: 2,
      maxChooserRows: 5,
    })
  })
})
