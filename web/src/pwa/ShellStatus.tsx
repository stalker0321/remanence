import { useRef, useSyncExternalStore } from 'react'
import type { MessageKey } from '../i18n/catalog'
import { installShell, shellStore } from './shell'

export function ShellStatus({ t, details }: { t: (key: MessageKey) => string; details: boolean }) {
  const state = useSyncExternalStore(shellStore.subscribe, shellStore.getSnapshot)
  const description = useRef<HTMLParagraphElement>(null)
  const status: MessageKey = state.support === 'disabled' ? 'shellTestOnly'
    : state.support === 'available' ? 'shellReady'
    : state.support === 'unsupported' ? 'shellUnsupported'
      : state.support === 'unavailable' ? 'shellUnavailable' : 'shellPreparing'
  return (
    <>
      {!details ? <div className={state.offline || state.waiting ? 'status' : undefined} role="status" aria-live="polite">
        {state.offline ? <p>{t('shellOffline')}</p> : null}
        {state.waiting ? <p>{t('shellUpdate')}</p> : null}
      </div> : null}
      {details ? (
        <section className="card settings-group">
          <h2>{t('shellTitle')}</h2>
          <p>{t(status)}</p>
          {state.support !== 'disabled' ? <p ref={description} tabIndex={-1}>
            {t(state.installed ? 'shellInstalled' : 'shellInstallHelp')}
          </p> : null}
          {state.installable && !state.installed ? (
            <button type="button" onClick={async () => {
              await installShell()
              // The one-shot control disappears after use. Return keyboard
              // focus to its explanation if nothing else has taken focus.
              if (document.activeElement === document.body) description.current?.focus({ preventScroll: true })
            }}>{t('shellInstall')}</button>
          ) : null}
        </section>
      ) : null}
    </>
  )
}
