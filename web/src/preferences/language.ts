import { languages, type Language } from '../i18n/catalog'

export const LANGUAGE_KEY = 'remanence.language'

export interface PreferencePort {
  get(key: string): string | null
  set(key: string, value: string): void
  remove(key: string): void
}

export function isLanguage(value: string | null): value is Language {
  return languages.some((language) => language === value)
}

export function readLanguage(port: PreferencePort): Language {
  const stored = port.get(LANGUAGE_KEY)
  return isLanguage(stored) ? stored : 'en'
}

export function writeLanguage(port: PreferencePort, language: Language): void {
  port.set(LANGUAGE_KEY, language)
}
