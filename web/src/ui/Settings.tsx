import type { Language, MessageKey } from '../i18n/catalog'
import type { ReactNode } from 'react'
import { href } from '../router/route'
import { LanguageMenu } from './LanguageMenu'
import { ShellStatus } from '../pwa/ShellStatus'
import './settings.css'

export interface SettingsProps {
  readonly sendAccess?: ReactNode
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
export function Settings({ t, language, onSelectLanguage, handle, signedIn, sessionState, sendAccess }: SettingsProps) {
  return (
    <>
      <header className="page-heading">
        <h1>{t('settingsTitle')}</h1>
        <p className="page-lead">{t('settingsBody')}</p>
      </header>
      <div className="settings-layout">
      <section className="card settings-group">
        <h2>{t('account')}</h2>
        {signedIn ? <dl className="settings-facts">
          <dt>{t('handleLabel')}</dt>
          <dd className="settings-handle">{handle !== null ? `@${handle}` : '—'}</dd>
          <dt>{t('settingsSession')}</dt>
          <dd>{sessionState}</dd>
        </dl> : <p>{t('settingsSignedOut')}</p>}
        {signedIn ? <p className="field-hint">{t('settingsAccountHint')}</p> : null}
        <div className="actions">
          <a href={href('account')}>{t('account')}</a>
        </div>
      </section>
      {sendAccess}
      <section className="card settings-group">
        <h2>{t('language')}</h2>
        <p>{t('languageHint')}</p>
        <LanguageMenu language={language} onSelect={onSelectLanguage} t={t} />
      </section>
      <ShellStatus t={t} details />
      </div>
    </>
  )
}
