import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { explainFailure, explainGoogle, explainRestore } from '../auth/notice'
import { browserGoogleIdentity } from '../auth/googleBrowser'
import { continueWithGoogle, type GoogleIdentityPort } from '../auth/google'
import { apiOrigin, fetchTransport, type ApiTransport } from '../auth/http'
import { MemoryKeys } from '../auth/memoryKeys'
import { browserPasskey, type PasskeyPort } from '../auth/passkey'
import { restoreSignedInAccount } from '../auth/restore'
import { languages, type Language, type MessageKey } from '../i18n/catalog'
import { phaseKey, translate } from '../i18n/translate'
import { writeLanguage, type PreferencePort } from '../preferences/language'
import { href, parseRoute, type RouteName } from '../router/route'
import { browserCamera, type CameraPort, type CameraSession } from '../scan/camera'
import { ScanError } from '../scan/errors'
import { SessionStore } from '../session/session'

export function App({
  store,
  preferences,
  keys,
  transport = fetchTransport(apiOrigin()),
  google = browserGoogleIdentity(document),
  passkeys = browserPasskey(),
  camera = browserCamera(document),
}: {
  store: SessionStore
  preferences: PreferencePort
  keys: MemoryKeys
  transport?: ApiTransport
  google?: GoogleIdentityPort
  passkeys?: PasskeyPort
  camera?: CameraPort
}) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [route, setRoute] = useState<RouteName>(() => parseRoute(window.location.hash))
  const [notice, setNotice] = useState<MessageKey>('errorRegion')
  const t = (key: MessageKey) => translate(view.language, key)

  useEffect(() => {
    const onHash = () => setRoute(parseRoute(window.location.hash))
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  function chooseLanguage(language: Language) {
    writeLanguage(preferences, language)
    store.setLanguage(language)
    document.documentElement.lang = language
  }

  return (
    <main className="shell">
      <div className="bar">
        <p className="mark">remanence</p>
        <nav aria-label={t('language')}>
          {languages.map((language) => (
            <button
              key={language}
              type="button"
              className="quiet"
              aria-pressed={view.language === language}
              onClick={() => chooseLanguage(language)}
            >
              {language}
            </button>
          ))}
        </nav>
      </div>
      {route === 'home' ? <Home t={t} /> : null}
      {route === 'scan' ? <Scan t={t} camera={camera} onNotice={setNotice} /> : null}
      {route === 'create' ? <Waiting t={t} title="homeMakeTitle" body="makeWaiting" /> : null}
      {route === 'account' ? (
        <Account
          store={store}
          keys={keys}
          transport={transport}
          google={google}
          passkeys={passkeys}
          t={t}
          onNotice={setNotice}
        />
      ) : null}
      <p className="status" role="status">{t(phaseKey(view.phase))}</p>
      <p className="status" role="alert">{t(notice)}</p>
    </main>
  )
}

function Home({ t }: { t: (key: MessageKey) => string }) {
  return (
    <>
      <section className="card">
        <h1>{t('homeOpenTitle')}</h1>
        <p>{t('homeOpenBody')}</p>
        <div className="actions">
          <a className="button primary" href={href('scan')}>{t('scan')}</a>
        </div>
      </section>
      <section className="card">
        <h2>{t('homeMakeTitle')}</h2>
        <p>{t('homeMakeBody')}</p>
        <div className="actions">
          <a className="button" href={href('create')}>{t('make')}</a>
        </div>
      </section>
      <div className="actions">
        <a href={href('account')}>{t('account')}</a>
      </div>
    </>
  )
}

function Scan({
  t,
  camera,
  onNotice,
}: {
  t: (key: MessageKey) => string
  camera: CameraPort
  onNotice: (notice: MessageKey) => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const session = useRef<CameraSession | null>(null)
  const heldBytes = useRef<Uint8Array | null>(null)
  const busy = useRef(false)
  const [live, setLive] = useState(false)
  const [held, setHeld] = useState(false)

  useEffect(() => () => {
    session.current?.stop()
    session.current = null
    heldBytes.current = null
  }, [])

  async function open() {
    if (busy.current) return
    busy.current = true
    try {
      const next = await camera.open()
      session.current?.stop()
      session.current = next
      heldBytes.current = null
      next.preview.className = 'preview'
      host.current?.replaceChildren(next.preview)
      setLive(true)
      setHeld(false)
      onNotice('errorRegion')
    } catch (error) {
      onNotice(error instanceof ScanError && error.code === 'CAMERA_DENIED' ? 'cameraDenied' : 'cameraUnavailable')
    } finally {
      busy.current = false
    }
  }

  async function take() {
    if (busy.current) return
    busy.current = true
    try {
      const picture = await session.current?.capture()
      if (!picture) return
      heldBytes.current = picture.bytes
      setHeld(true)
      onNotice('cameraHeld')
    } catch {
      onNotice('cameraUnavailable')
    } finally {
      busy.current = false
    }
  }

  function close() {
    session.current?.stop()
    session.current = null
    heldBytes.current = null
    host.current?.replaceChildren()
    setLive(false)
    setHeld(false)
  }

  return (
    <section className="card">
      <h1>{t('scanTitle')}</h1>
      <p>{t('scanWaiting')}</p>
      <div ref={host} />
      {held ? <p>{t('cameraHeld')}</p> : null}
      <div className="actions">
        {live ? (
          <>
            <button type="button" className="primary" onClick={() => { void take() }}>{t('takePicture')}</button>
            <button type="button" onClick={close}>{t('closeCamera')}</button>
          </>
        ) : (
          <button type="button" className="primary" onClick={() => { void open() }}>{t('useCamera')}</button>
        )}
        <a href={href('home')}>{t('back')}</a>
      </div>
    </section>
  )
}

function Waiting({
  t,
  title,
  body,
}: {
  t: (key: MessageKey) => string
  title: MessageKey
  body: MessageKey
}) {
  return (
    <section className="card">
      <h1>{t(title)}</h1>
      <p>{t(body)}</p>
      <div className="actions">
        <a href={href('home')}>{t('back')}</a>
      </div>
    </section>
  )
}

function Account({
  store,
  keys,
  transport,
  google,
  passkeys,
  t,
  onNotice,
}: {
  store: SessionStore
  keys: MemoryKeys
  transport: ApiTransport
  google: GoogleIdentityPort
  passkeys: PasskeyPort
  t: (key: MessageKey) => string
  onNotice: (notice: MessageKey) => void
}) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const abort = useRef<AbortController | null>(null)
  const signedIn = view.phase !== 'signed-out'
  const unlocked = signedIn && view.phase !== 'authenticated'

  useEffect(() => () => abort.current?.abort(), [])

  async function run(action: (signal: AbortSignal) => Promise<MessageKey | null>) {
    if (busyRef.current) return
    busyRef.current = true
    abort.current?.abort()
    const controller = new AbortController()
    abort.current = controller
    setBusy(true)
    try {
      const next = await action(controller.signal)
      if (!controller.signal.aborted) onNotice(next ?? 'errorRegion')
    } catch (error) {
      if (!controller.signal.aborted) onNotice(explainFailure(error))
    } finally {
      if (abort.current === controller) {
        busyRef.current = false
        setBusy(false)
      }
    }
  }

  return (
    <section className="card">
      <h1>{t('authTitle')}</h1>
      <p>{t('authBody')}</p>
      <p>{view.handle ? view.handle : t(phaseKey(view.phase))}</p>
      <div className="actions">
        {signedIn ? null : (
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={() => {
              void run((signal) => continueWithGoogle(store, transport, google, signal).then(explainGoogle))
            }}
          >
            {t('continueGoogle')}
          </button>
        )}
        {signedIn && !unlocked ? (
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={() => {
              void run((signal) => restoreSignedInAccount(store, transport, passkeys, keys, signal).then(explainRestore))
            }}
          >
            {t('openWithPasskey')}
          </button>
        ) : null}
        {unlocked ? (
          <button type="button" disabled={busy} onClick={() => store.lock()}>{t('lock')}</button>
        ) : null}
        {signedIn ? (
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              abort.current?.abort()
              busyRef.current = false
              setBusy(false)
              store.signOut()
              onNotice('errorRegion')
            }}
          >
            {t('signOut')}
          </button>
        ) : null}
        <a href={href('home')}>{t('back')}</a>
      </div>
    </section>
  )
}
