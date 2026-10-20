import { describe, expect, it } from 'vitest'
import { explainScan } from '../src/scan/notice'
import { RecognitionEngineError, type RecognitionEngineErrorCode } from '../src/scan/recognitionEngine'
import { catalog, type MessageKey } from '../src/i18n/catalog'

const MAPPING: Array<[RecognitionEngineErrorCode, MessageKey]> = [
  ['cancelled', 'scanCancelled'],
  ['stale', 'scanCancelled'],
  ['unavailable', 'recognitionUnavailable'],
  ['internal', 'recognitionUnavailable'],
  ['invalid', 'integrityFailed'],
  ['contract', 'integrityFailed'],
  ['decode', 'scanRetake'],
  ['crop', 'scanRetake'],
  ['warp', 'scanRetake'],
  ['features', 'scanRetake'],
  ['dimensions', 'scanRetake'],
  ['pixels', 'scanRetake'],
  ['incomplete', 'incompleteIndex'],
]

describe('T349 receiving-engine notice mapping', () => {
  it('maps every typed engine refusal to a truthful existing/localized notice', () => {
    for (const [code, key] of MAPPING) {
      expect(explainScan(new RecognitionEngineError(code)), code).toBe(key)
    }
  })

  it('does not map engine refusals to noMatch or a generic sync failure', () => {
    for (const [code] of MAPPING) {
      const key = explainScan(new RecognitionEngineError(code))
      expect(key).not.toBe('noMatch')
      expect(key).not.toBe('syncRejected')
    }
  })

  it('ambiguous wording describes both chooser and single recapture truthfully', () => {
    for (const language of ['en', 'ru', 'uk'] as const) {
      expect(catalog[language].ambiguousMatch).not.toMatch(/more than one|больше одной|більше ніж одна/i)
      expect(catalog[language].ambiguousMatch.length).toBeGreaterThan(0)
    }
  })

  it('incomplete and retake notices are present in EN/RU/UK', () => {
    for (const language of ['en', 'ru', 'uk'] as const) {
      expect(catalog[language].incompleteIndex.length).toBeGreaterThan(0)
      expect(catalog[language].scanRetake.length).toBeGreaterThan(0)
    }
  })
})
