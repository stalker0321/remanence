import type { Language, MessageKey } from '../i18n/catalog'
import { href } from '../router/route'
import { LanguageMenu } from './LanguageMenu'
import { ShellStatus } from '../pwa/ShellStatus'
import './settings.css'

export interface SettingsProps {
  readonly t: (key: MessageKey) => string
  readonly language: Language
  readonly onSelectLanguage: (language: Language) => void
  /** The session's permanent handle, or null when signed out. */
  readonly handle: string | null
  readonly signedIn: boolean
  /** Already-translated current session state (from `phaseKey`). */
  readonly sessionState: string
}

/**
 * T332 — real user-visible Settings screen (replaces the placeholder body).
 *
 * Two grouped sections in the existing quiet card style: Account (readonly
 * permanent handle + current session state, linking to the existing Account
 * screen for the actual account/key controls) and Language (the reusable
 * popover). It invents no reset/backup/phone/telemetry/terms controls and makes
 * an honest signed-out state. Navigation uses the existing router links.
 */
export function Settings({ t, language, onSelectLanguage, handle, signedIn, sessionState }: SettingsProps) {
  return (
    <>
      <section className="card">
        <h1>{t('settingsTitle')}</h1>
        <p>{t('settingsBody')}</p>
      </section>
      <section className="card settings-group">
        <h2>{t('account')}</h2>
        <dl className="settings-facts">
          <dt>{t('handleLabel')}</dt>
          <dd className="settings-handle">{signedIn && handle !== null ? handle : t('settingsSignedOut')}</dd>
          <dt>{t('settingsSession')}</dt>
          <dd>{sessionState}</dd>
        </dl>
        <div className="actions">
          <a href={href('account')}>{t('account')}</a>
        </div>
      </section>
      <section className="card settings-group">
        <h2>{t('language')}</h2>
        <LanguageMenu language={language} onSelect={onSelectLanguage} t={t} />
      </section>
      <ShellStatus t={t} details />
      <div className="actions">
        <a href={href('home')}>{t('back')}</a>
      </div>
    </>
  )
}
