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

/** The stored choice, else the first supported browser language, else English. */
export function readLanguage(port: PreferencePort, preferred: readonly string[] = []): Language {
  const stored = port.get(LANGUAGE_KEY)
  if (isLanguage(stored)) return stored
  for (const tag of preferred) {
    const base = tag.toLowerCase().split('-')[0] ?? ''
    if (isLanguage(base)) return base
  }
  return 'en'
}

export function writeLanguage(port: PreferencePort, language: Language): void {
  port.set(LANGUAGE_KEY, language)
}
