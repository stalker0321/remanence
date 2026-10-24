import en from './locales/en.json'
import ru from './locales/ru.json'
import uk from './locales/uk.json'

/**
 * Interface copy lives in `locales/<code>.json`; English is the source of
 * truth for keys. To add a language: copy `en.json`, translate it (see
 * `docs/copy-style.md` and `locales/context.json`), import it here and add the
 * code to `languages`. Missing strings fall back to English.
 */
export const languages = ['en', 'ru', 'uk'] as const

export type Language = (typeof languages)[number]

export type MessageKey = keyof typeof en

export const messageKeys = Object.keys(en) as MessageKey[]

type Locale = Partial<Record<MessageKey, string>>

const locales: Record<Language, Locale> = { en, ru, uk }

export const catalog = Object.fromEntries(
  languages.map((language) => [language, { ...en, ...locales[language] }]),
) as Record<Language, Record<MessageKey, string>>
