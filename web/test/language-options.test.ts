import { describe, expect, it } from 'vitest'
import { LANGUAGE_NAMES, languageOptions, wrapIndex } from '../src/ui/languageOptions'
import { catalog, languages, type Language } from '../src/i18n/catalog'

describe('T332 language options', () => {
  it('offers exactly the supported languages, in catalog order', () => {
    const options = languageOptions()
    expect(options.map((option) => option.code)).toEqual([...languages])
    expect(options.map((option) => option.code)).toEqual(['en', 'ru', 'uk'])
  })

  it('names every option by a non-empty, distinct endonym', () => {
    const options = languageOptions()
    for (const option of options) {
      expect(option.name.trim().length).toBeGreaterThan(0)
      expect(option.name).toBe(LANGUAGE_NAMES[option.code])
    }
    expect(new Set(options.map((option) => option.name)).size).toBe(options.length)
  })

  it('wraps the arrow-key index into range', () => {
    expect(wrapIndex(0, 3)).toBe(0)
    expect(wrapIndex(1, 3)).toBe(1)
    expect(wrapIndex(3, 3)).toBe(0)
    expect(wrapIndex(-1, 3)).toBe(2)
    expect(wrapIndex(-4, 3)).toBe(2)
    expect(wrapIndex(0, 0)).toBe(0)
    expect(wrapIndex(5, 0)).toBe(0)
    expect(wrapIndex(Number.NaN, 3)).toBe(0)
  })

  it('has the new settings copy in every supported language', () => {
    for (const language of languages as readonly Language[]) {
      expect(catalog[language].settingsSession.trim().length).toBeGreaterThan(0)
      expect(catalog[language].settingsSignedOut.trim().length).toBeGreaterThan(0)
    }
  })
})
