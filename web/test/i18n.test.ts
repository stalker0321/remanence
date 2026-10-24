import { describe, expect, it } from 'vitest'
import { catalog, languages, messageKeys } from '../src/i18n/catalog'
import { readLanguage, writeLanguage, type PreferencePort } from '../src/preferences/language'

function memoryPort(): PreferencePort {
  const values = new Map<string, string>()
  return {
    get: (key) => values.get(key) ?? null,
    set: (key, value) => { values.set(key, value) },
    remove: (key) => { values.delete(key) },
  }
}

describe('languages', () => {
  it('has the same keys in English, Russian, and Ukrainian', () => {
    for (const language of languages) {
      for (const key of messageKeys) {
        expect(catalog[language][key].length).toBeGreaterThan(0)
      }
    }
  })

  it('stores only the language preference', () => {
    const port = memoryPort()
    expect(readLanguage(port)).toBe('en')
    expect(readLanguage(port, ['uk-UA', 'en'])).toBe('uk')
    expect(readLanguage(port, ['de-DE', 'ru'])).toBe('ru')
    expect(readLanguage(port, ['de-DE'])).toBe('en')
    writeLanguage(port, 'ru')
    expect(readLanguage(port)).toBe('ru')
    port.set('remanence.language', 'nope')
    expect(readLanguage(port)).toBe('en')
    expect(JSON.stringify(port.get('remanence.language'))).not.toContain('token')
  })
})
