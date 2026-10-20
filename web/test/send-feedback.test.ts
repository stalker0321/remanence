import { describe, expect, it } from 'vitest'
import { sendFeedback } from '../src/create/sendFeedback'
import type { SendOutcome } from '../src/create/sendAttempt'
import { catalog, languages } from '../src/i18n/catalog'

describe('send outcome presentation (no lifecycle effects)', () => {
  it.each(['preflight', 'seal'] as const)('scopes %s failure to local preparation', (stage) => {
    expect(sendFeedback({ kind: 'refused', stage, code: 'PRIVATE' })).toEqual({
      notice: 'sendNotSent', help: 'sendLocalHelp',
    })
  })
  it('does not describe a received draft error as local or rolled back', () => {
    expect(sendFeedback({ kind: 'refused', stage: 'draft', code: 'PRIVATE' })).toEqual({
      notice: 'sendDraftFailed', help: 'sendUncertainHelp',
    })
  })
  it.each(['draft', 'upload', 'finalize', 'cancelled'])('keeps unknown %s conservative', (stage) => {
    expect(sendFeedback({ kind: 'unknown', stage, code: 'PRIVATE' })).toEqual({
      notice: 'sendNotConfirmed', help: 'sendUncertainHelp',
    })
  })
  it('leaves absent/stale results silent and successful results unchanged', () => {
    expect(sendFeedback(null)).toBeNull()
    expect(sendFeedback({ kind: 'stale' })).toBeNull()
    expect(sendFeedback({ kind: 'sent', readyAt: 'PRIVATE', locator: null })).toEqual({
      notice: 'sendSent', help: null,
    })
  })
  it('maps only fixed message keys, without leaking arbitrary error data or mutating outcomes', () => {
    const outcomes: SendOutcome[] = [
      { kind: 'refused', stage: 'seal', code: '<PRIVATE_TOKEN>' },
      { kind: 'refused', stage: 'draft', code: '<PRIVATE_SERVER_BODY>' },
      { kind: 'unknown', stage: '<PRIVATE_PATH>', code: '<PRIVATE_TOKEN>' },
    ]
    for (const outcome of outcomes) {
      const before = structuredClone(outcome)
      const result = sendFeedback(Object.freeze(outcome))!
      expect(outcome).toEqual(before)
      for (const lang of languages) {
        expect(catalog[lang][result.notice]).not.toContain('PRIVATE')
        expect(catalog[lang][result.help!]).not.toContain('PRIVATE')
        expect(catalog[lang].sendExit.length).toBeGreaterThan(0)
      }
    }
  })
})
