import { languages, type Language } from '../i18n/catalog'

/**
 * T332 — pure language-option facts for the settings/header language UI.
 *
 * Each supported language is offered under its own endonym (language-invariant
 * copy: "English" reads the same whatever the current locale), so no new
 * translated strings are needed for the option names themselves.
 */
export const LANGUAGE_NAMES: Record<Language, string> = {
  en: 'English',
  ru: 'Русский',
  uk: 'Українська',
}

export interface LanguageOption {
  readonly code: Language
  readonly name: string
}

/** The ordered option list: exactly the supported languages, in catalog order. */
export function languageOptions(): readonly LanguageOption[] {
  return languages.map((code) => ({ code, name: LANGUAGE_NAMES[code] }))
}

/** Wrap an index into [0, length) for arrow-key navigation; length<=0 yields 0. */
export function wrapIndex(index: number, length: number): number {
  if (!Number.isInteger(index) || length <= 0) return 0
  return ((index % length) + length) % length
}
