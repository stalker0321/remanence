import { describe, expect, it } from 'vitest'
import { catalog, languages, messageKeys } from '../src/i18n/catalog'
import en from '../src/i18n/locales/en.json'
import ru from '../src/i18n/locales/ru.json'
import uk from '../src/i18n/locales/uk.json'
import context from '../src/i18n/locales/context.json'
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
  it('keeps the house style: lowercase start, no closing period (docs/copy-style.md)', () => {
    for (const locale of [en, ru, uk]) for (const [key, text] of Object.entries(locale)) {
      expect(text.trim(), key).toBe(text)
      expect(/^(Google|JPEG)/.test(text) || text[0] === text[0]!.toLowerCase(), `${key}: ${text}`).toBe(true)
      expect(/[^.]\.$/.test(text), `${key}: ${text}`).toBe(false)
    }
  })

  it('translates exactly the English keys', () => {
    for (const locale of [ru, uk, context]) expect(Object.keys(locale).sort()).toEqual(Object.keys(en).sort())
  })

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
