import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { MessageKey } from '../i18n/catalog'
import { href, parseRoute, type RouteName } from '../router/route'
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

/** Mounted only through the existing explicit deployment admission. */
export function AtomicAccount(props: { store: SessionStore; keys: MemoryKeys; transport: ApiTransport; setup: AtomicPort;
  google: GoogleIdentityPort; passkeys: PasskeyPort; t: (key: MessageKey) => string; returnTo?: RouteName | null }) {
  const { store, keys, transport, setup, google, passkeys, t, returnTo } = props
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
  const choosing = state.phase === 'handle' || state.phase === 'handle-conflict'
  const creating = state.phase === 'passkey' || state.phase === 'creating'
  const verifying = state.phase === 'verify' || state.phase === 'assertion-required' || state.phase === 'saving'
  const pendingCredential = state.phase === 'credential-pending'
  const start = state.phase === 'idle' || state.phase === 'google' || state.phase === 'reauthenticate' || state.phase === 'restore-required'

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
  const phaseMessage: MessageKey | null = state.phase === 'creating' ? 'atomicCreating' : state.busy && ['saving', 'unknown', 'restore-required'].includes(state.phase) ? 'atomicSaving'
    : state.phase === 'unknown' ? 'atomicUnknown' : state.phase === 'terminal' ? 'atomicTerminal'
    : state.phase === 'reauthenticate' ? 'atomicReauthenticate' : state.phase === 'restore-required' ? 'atomicRestore'
    : state.phase === 'assertion-required' ? 'atomicAssertion' : state.phase === 'saving' ? 'atomicSaving' : null
  const errorMessage: MessageKey | null = state.error === null || state.phase === 'unknown' ? null : ({ HANDLE_TAKEN: 'handleTaken', HANDLE_INVALID: 'handleInvalid',
    PRF_UNAVAILABLE: 'prfUnavailable', PASSKEY_CANCELLED: 'passkeyCancelled', PASSKEY_UNAVAILABLE: 'passkeyUnavailable',
    GOOGLE_CANCELLED: 'googleCancelled', EXISTING_ACCOUNT_REQUIRES_LINK: 'linkRequired', CLIENT_UPGRADE_REQUIRED: 'upgradeRequired',
    REGISTRATION_ADMISSION_CLOSED: 'atomicUnavailable', WRONG_CREDENTIAL: 'wrongPasskey', WRONG_ACCOUNT: 'wrongPasskey',
  } as Record<string, MessageKey>)[state.error] ?? 'atomicFailure'
  const unsupported = state.error === 'PRF_UNAVAILABLE'
  const unreadableCreation = state.error === 'PASSKEY_CREATION_UNRESOLVED'
  const stopped = unsupported || unreadableCreation
  const title: MessageKey = unreadableCreation ? 'atomicUnreadableTitle' : unsupported ? 'atomicUnsupportedTitle' : unlocked ? 'accountReadyTitle' : signedIn ? 'accountLockedTitle' : choosing ? 'accountHandleTitle'
    : creating ? 'atomicCreateTitle' : verifying || pendingCredential ? 'atomicCreatedTitle' : state.phase === 'unknown' ? 'atomicCheck' : 'atomicSignInTitle'
  const body: MessageKey = unreadableCreation ? 'atomicUnreadableBody' : unsupported ? 'atomicUnsupportedBody' : signedIn ? unlocked ? 'accountVisitHint' : 'authBody' : choosing ? 'atomicHandleBody'
    : creating ? 'atomicCreateBody' : pendingCredential ? 'atomicPendingBody' : verifying ? 'atomicVerifyBody' : 'atomicSignInBody'
  return <section className="card atomic-account" data-atomic-phase={state.phase}>
    {!signedIn ? <StepTrail labels={['stepAccount', 'stepHandle', 'atomicStepCreate', 'atomicStepVerify']} current={state.phase === 'idle' || state.phase === 'google' ? 0 : choosing ? 1 : creating || pendingCredential ? 2 : 3} t={t} /> : null}
    <div className="account-layout">
    <div className="account-intro">
    <span className="account-emblem"><Icon name="key" /></span>
    <h1 ref={heading} tabIndex={-1}>{t(title)}</h1>
    {view.handle ? <p className="account-handle">@{view.handle}</p> : null}
    {state.phase !== 'unknown' && state.phase !== 'terminal' ? <p>{t(body)}</p> : null}
    </div>
    <div className="account-controls" aria-busy={busy}>
    {unlocked ? <p className="account-ready" role="status"><Icon name="check" />{t('atomicReady')}</p> : phaseMessage ? <p className="notice" role="status">{t(phaseMessage)}</p> : null}
    {errorMessage && !unreadableCreation ? <p className="notice notice--warning" role="alert" data-capability-stop={unsupported || undefined}>{t(unsupported ? 'atomicUnsupportedHint' : errorMessage)}</p> : null}
    {restoreNotice ? <p className="notice" role="alert">{t(restoreNotice)}</p> : null}
    {!signedIn && choosing ? <form onSubmit={(event) => { event.preventDefault(); if (state.phase === 'handle-conflict') void controller.save(handle); else controller.chooseHandle(handle) }}>
      <label htmlFor="atomic-handle">{t('handleLabel')}</label>
      <input ref={input} id="atomic-handle" name="handle" value={handle} autoCapitalize="none" autoComplete="off" spellCheck={false} aria-invalid={state.error === 'HANDLE_TAKEN' || state.error === 'HANDLE_INVALID' || undefined}
        maxLength={31} disabled={busy} aria-describedby="handle-help" onChange={(event) => setHandle(event.target.value)} />
      <p id="handle-help" className="field-hint">{t('atomicHandleHint')}</p>
      <div className="actions"><button type="submit" className="primary" aria-disabled={busy}>{t(state.phase === 'handle-conflict' ? 'atomicFinishHandle' : 'atomicContinue')}<Icon name="arrow" /></button></div>
    </form> : null}
    {!signedIn && !choosing && !stopped && handle && (creating || verifying || pendingCredential) ? <p className="setup-handle"><span>{t('atomicHandleSummary')}</span><strong>@{handle.replace(/^@/, '').toLowerCase()}</strong></p> : null}
    <div className="actions">
      {stopped ? <a className="button primary" href={href('home')} onClick={leave}>{t('atomicLeave')}</a> : null}
      {!signedIn && start ? <button className="primary google-button" aria-disabled={busy} onClick={() => { void controller.begin() }}>{t('continueGoogle')}</button> : null}
      {!signedIn && creating ? <button className="primary" aria-disabled={busy} onClick={() => { void controller.createPasskey() }}><Icon name="key" />{t('atomicCreate')}</button> : null}
      {!signedIn && pendingCredential && !unreadableCreation ? <button className="primary" aria-disabled={busy} onClick={() => { void controller.createPasskey() }}>{t('atomicLink')}</button> : null}
      {!signedIn && verifying ? <button className={unsupported ? undefined : 'primary'} aria-disabled={busy} onClick={() => { void controller.save(handle) }}><Icon name="key" />{t(unsupported ? 'atomicVerifyAgain' : 'atomicVerify')}</button> : null}
      {state.phase === 'unknown' && !busy ? <>
        <button className="primary" aria-disabled={busy} onClick={() => { void controller.resolve() }}>{t('atomicCheck')}</button>
        <button aria-disabled={busy} onClick={() => { void controller.retry() }}>{t('atomicRetry')}</button>
      </> : null}
      {!signedIn && state.phase === 'terminal' ? <button aria-disabled={busy} onClick={() => { void controller.restart() }}>{t('atomicRestart')}</button> : null}
      {signedIn && !unlocked ? <button className="primary" aria-disabled={busy} onClick={() => { void open() }}>{t('openWithPasskey')}</button> : null}
    </div>
    {unsupported ? <p className="field-hint">{t('atomicResumeSavedHint')}</p> : null}
    {!signedIn && verifying && !busy && !unsupported ? <p className="field-hint">{t('atomicVerifyHint')}</p> : null}
    {busy && !phaseMessage ? <p role="status">{t('accountWorking')}</p> : null}
    {signedIn && (unlocked || returnTo === 'history') ? <div className="account-next">
      {returnTo ? <div className="actions"><a className="button primary" href={href(returnTo)}>{t(returnTo === 'create' ? 'returnMake' : returnTo === 'scan' ? 'returnOpen' : 'returnHistory')}<Icon name="arrow" /></a></div>
        : <><h2>{t('accountNext')}</h2><div className="actions"><a className="button primary" href={href('scan')}>{t('scan')}<Icon name="arrow" /></a><a className="button" href={href('create')}>{t('make')}</a></div></>}
    </div> : null}
    </div>
    <aside className="key-explanation"><h2>{t('accountKeysTitle')}</h2><p>{t('atomicPrivacy')}</p></aside>
    </div>
    <footer className="account-footer">
      {signedIn ? <div className="actions">{unlocked ? <button onClick={() => store.lock()}>{t('lock')}</button> : null}<button className="quiet" onClick={() => { leave(); store.signOut() }}>{t('signOut')}</button></div> : null}
      {!signedIn && state.phase !== 'idle' ? <details><summary>{t('atomicOptions')}</summary><p className="field-hint">{t('atomicLeaveHint')}</p><div className="actions">
        <button type="button" onClick={leave}>{t('atomicLater')}</button>
        {state.reservationState === 'RESERVED' && state.phase !== 'unknown' ? <button className="quiet" disabled={busy} onClick={() => { void controller.cancelReservation() }}>{t('cancelRegistration')}</button> : null}
      </div></details> : null}
      <a href={href('home')} onClick={leave}>{t('back')}</a>
    </footer>
  </section>
}
