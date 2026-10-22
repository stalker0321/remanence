import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { MessageKey } from '../i18n/catalog'
import { href, parseRoute } from '../router/route'
import type { SessionStore } from '../session/session'
import { AtomicRegistration } from './atomicRegistration'
import type { AtomicPort } from './atomicWire'
import type { GoogleIdentityPort } from './google'
import type { ApiTransport } from './http'
import type { MemoryKeys } from './memoryKeys'
import { explainFailure, explainRestore } from './notice'
import type { PasskeyPort } from './passkey'
import { restoreSignedInAccount } from './restore'
import { Icon, StepTrail } from '../ui/Primitives'
import './atomicAccount.css'

/** Explicit isolated TEST composition only. App has no env/storage/public flag
 * enabling this component. Ordinary account login/restore stays unchanged. */
export function AtomicAccount(props: { store: SessionStore; keys: MemoryKeys; transport: ApiTransport; setup: AtomicPort;
  google: GoogleIdentityPort; passkeys: PasskeyPort; t: (key: MessageKey) => string }) {
  const { store, keys, transport, setup, google, passkeys, t } = props
  const [controller] = useState(() => new AtomicRegistration({ session: store, keys, api: transport, setup, google, passkeys }))
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [handle, setHandle] = useState('')
  const [restoring, setRestoring] = useState(false)
  const [restoreNotice, setRestoreNotice] = useState<MessageKey | null>(null)
  const restore = useRef<AbortController | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const signedIn = view.phase !== 'signed-out'
  const unlocked = signedIn && view.phase !== 'authenticated'
  const busy = state.busy || restoring
  const choosing = ['handle', 'handle-conflict', 'assertion-required', 'saving'].includes(state.phase)

  useEffect(() => {
    const leave = () => {
      if (parseRoute(window.location.hash) !== 'account') {
        restore.current?.abort(); controller.leave()
      }
    }
    // Capture phase: fence late callbacks before React unmount (T398 pattern).
    window.addEventListener('popstate', leave, true)
    window.addEventListener('hashchange', leave, true)
    const stop = store.subscribe(() => {
      if (store.getSnapshot().phase === 'signed-out') restore.current?.abort()
    })
    return () => {
      window.removeEventListener('popstate', leave, true)
      window.removeEventListener('hashchange', leave, true)
      restore.current?.abort(); stop(); controller.dispose()
    }
  }, [controller, store])
  useEffect(() => {
    if (state.busy) return
    if (state.phase === 'handle' || state.phase === 'handle-conflict') input.current?.focus()
    else heading.current?.focus()
  }, [state.phase, state.busy])

  function leave() { restore.current?.abort(); controller.leave(); setHandle(''); setRestoreNotice(null) }
  async function open() {
    if (restore.current || busy) return
    const abort = new AbortController(); restore.current = abort; setRestoring(true); setRestoreNotice(null)
    try {
      const result = await restoreSignedInAccount(store, transport, passkeys, keys, abort.signal)
      if (!abort.signal.aborted) setRestoreNotice(explainRestore(result))
    } catch (error) { if (!abort.signal.aborted) setRestoreNotice(explainFailure(error)) }
    finally { if (restore.current === abort) { restore.current = null; setRestoring(false) } }
  }
  const phaseMessage: MessageKey | null = state.busy && ['saving', 'unknown', 'restore-required'].includes(state.phase) ? 'atomicSaving'
    : state.phase === 'unknown' ? 'atomicUnknown' : state.phase === 'terminal' ? 'atomicTerminal'
    : state.phase === 'reauthenticate' ? 'atomicReauthenticate' : state.phase === 'restore-required' ? 'atomicRestore'
    : state.phase === 'assertion-required' ? 'atomicAssertion' : state.phase === 'saving' ? 'atomicSaving' : null
  const errorMessage: MessageKey | null = state.error === null || state.phase === 'unknown' ? null : ({ HANDLE_TAKEN: 'handleTaken', HANDLE_INVALID: 'handleInvalid',
    PRF_UNAVAILABLE: 'prfUnavailable', PASSKEY_CANCELLED: 'passkeyCancelled', PASSKEY_UNAVAILABLE: 'passkeyUnavailable',
    GOOGLE_CANCELLED: 'googleCancelled', EXISTING_ACCOUNT_REQUIRES_LINK: 'linkRequired', CLIENT_UPGRADE_REQUIRED: 'upgradeRequired',
    REGISTRATION_ADMISSION_CLOSED: 'atomicUnavailable', WRONG_CREDENTIAL: 'wrongPasskey', WRONG_ACCOUNT: 'wrongPasskey',
  } as Record<string, MessageKey>)[state.error] ?? 'atomicFailure'
  return <section className="card atomic-account" data-atomic-phase={state.phase}>
    {!signedIn ? <StepTrail labels={['stepAccount', 'stepHandle', 'stepKeys']} current={state.phase === 'idle' || state.phase === 'google' ? 0 : state.phase === 'handle' || state.phase === 'handle-conflict' ? 1 : 2} t={t} /> : null}
    <div className="account-layout">
    <div className="account-intro">
    <span className="account-emblem"><Icon name="key" /></span>
    <h1 ref={heading} tabIndex={-1}>{t(unlocked ? 'accountReadyTitle' : signedIn ? 'accountLockedTitle' : choosing ? 'accountHandleTitle' : 'authTitle')}</h1>
    {view.handle ? <p className="account-handle">@{view.handle}</p> : null}
    {!signedIn && (choosing || state.phase === 'idle' || state.phase === 'google') ? <p>{t('atomicIntro')}</p> : signedIn && !unlocked ? <p>{t('authBody')}</p> : null}
    </div>
    <div className="account-controls" aria-busy={busy}>
    {unlocked ? <p className="account-ready" role="status"><Icon name="check" />{t('atomicReady')}</p> : phaseMessage ? <p className="notice" role="status">{t(phaseMessage)}</p> : null}
    {errorMessage ? <p className="notice notice--warning" role="alert">{t(errorMessage)}</p> : null}
    {restoreNotice ? <p className="notice" role="alert">{t(restoreNotice)}</p> : null}
    {!signedIn && choosing ? <form onSubmit={(event) => { event.preventDefault(); void controller.save(handle) }}>
      <p>{t('chooseHandle')}</p>
      <label htmlFor="atomic-handle">{t('handleLabel')}</label>
      <input ref={input} id="atomic-handle" name="handle" value={handle} autoCapitalize="none" autoComplete="off" spellCheck={false} aria-invalid={state.error === 'HANDLE_TAKEN' || state.error === 'HANDLE_INVALID' || undefined}
        maxLength={31} disabled={busy} onChange={(event) => setHandle(event.target.value)} />
      <div className="actions"><button type="submit" className="primary" aria-disabled={busy}>{t('atomicSave')}</button></div>
    </form> : null}
    <div className="actions">
      {!signedIn && !choosing && state.phase !== 'terminal' ? <button className="primary" aria-disabled={busy} onClick={() => { void controller.begin() }}>{t('continueGoogle')}</button> : null}
      {state.phase === 'unknown' && !busy ? <>
        <button aria-disabled={busy} onClick={() => { void controller.resolve() }}>{t('atomicCheck')}</button>
        <button aria-disabled={busy} onClick={() => { void controller.retry() }}>{t('atomicRetry')}</button>
      </> : null}
      {!signedIn && state.phase === 'terminal' ? <button aria-disabled={busy} onClick={() => { void controller.restart() }}>{t('atomicRestart')}</button> : null}
      {signedIn && !unlocked ? <button className="primary" aria-disabled={busy} onClick={() => { void open() }}>{t('openWithPasskey')}</button> : null}
      {unlocked ? <button onClick={() => store.lock()}>{t('lock')}</button> : null}
      {signedIn ? <button onClick={() => { leave(); store.signOut() }}>{t('signOut')}</button> : null}
      {!signedIn && state.reservationState === 'RESERVED' && state.phase !== 'unknown' ? <button disabled={busy} onClick={() => { void controller.cancelReservation() }}>{t('cancelRegistration')}</button> : null}
      <a href={href('home')} onClick={leave}>{t('back')}</a>
    </div>
    {busy ? <p role="status">{t('accountWorking')}</p> : null}
    {!signedIn && state.phase !== 'idle' ? <>
      <p>{t('atomicLeaveHint')}</p><button type="button" onClick={leave}>{t('atomicLeave')}</button>
    </> : null}
    {unlocked ? <div className="account-next"><h2>{t('accountNext')}</h2><div className="actions"><a className="button primary" href={href('scan')}>{t('scan')}<Icon name="arrow" /></a><a className="button" href={href('create')}>{t('make')}</a></div></div> : null}
    </div>
    <aside className="key-explanation"><h2>{t('accountKeysTitle')}</h2><p>{t(unlocked ? 'accountVisitHint' : 'accountKeysBody')}</p></aside>
    </div>
  </section>
}
