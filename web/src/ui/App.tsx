import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { explainFailure, explainGoogle, explainRestore } from '../auth/notice'
import { browserGoogleIdentity } from '../auth/googleBrowser'
import { continueWithGoogle, type GoogleIdentityPort } from '../auth/google'
import { RegistrationDraft, registerNewAccount } from '../auth/register'
import { apiOrigin, fetchTransport, type ApiTransport, type ByteTransport } from '../auth/http'
import { MemoryKeys } from '../auth/memoryKeys'
import { browserPasskey, type PasskeyPort } from '../auth/passkey'
import { restoreSignedInAccount } from '../auth/restore'
import { languages, type Language, type MessageKey } from '../i18n/catalog'
import { phaseKey, translate } from '../i18n/translate'
import { writeLanguage, type PreferencePort } from '../preferences/language'
import { href, parseRoute, type RouteName } from '../router/route'
import { CollageStage } from '../collage/stage'
import type { PresentationMemory } from '../collage/memory'
import { browserCamera, type CameraPort, type CameraSession, type StillCapture } from '../scan/camera'
import { ScanError } from '../scan/errors'
import { unavailableRecognition, type RecognitionEngine } from '../scan/grant'
import { explainScan } from '../scan/notice'
import { receivePostcard } from '../scan/receive'
import { downloadCiphertext, fetchIncoming } from '../sync/incoming'
import { canvasTextMeasurer } from '../collage/measure'
import { SessionStore } from '../session/session'

export function App({
  store,
  preferences,
  keys,
  memory,
  transport = fetchTransport(apiOrigin()),
  engine = unavailableRecognition(),
  google = browserGoogleIdentity(document),
  passkeys = browserPasskey(),
  camera = browserCamera(document),
}: {
  store: SessionStore
  preferences: PreferencePort
  keys: MemoryKeys
  memory: PresentationMemory
  transport?: ApiTransport & ByteTransport
  engine?: RecognitionEngine
  google?: GoogleIdentityPort
  passkeys?: PasskeyPort
  camera?: CameraPort
}) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const held = useSyncExternalStore(memory.subscribe, memory.getSnapshot)
  const draft = useRef(new RegistrationDraft())
  const [route, setRoute] = useState<RouteName>(() => parseRoute(window.location.hash))
  const [notice, setNotice] = useState<MessageKey>('errorRegion')
  const t = (key: MessageKey) => translate(view.language, key)

  useEffect(() => {
    document.documentElement.dataset.remanenceBoot = 'ready'
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
      {route === 'scan' ? (
        <Scan
          t={t}
          camera={camera}
          onNotice={setNotice}
          store={store}
          keys={keys}
          memory={memory}
          transport={transport}
          engine={engine}
        />
      ) : null}
      {route === 'create' ? <Waiting t={t} title="homeMakeTitle" body="makeWaiting" /> : null}
      {view.phase === 'presentation-admitted' && held ? (
        <CollageStage held={held} t={t} onClose={() => store.lock()} />
      ) : null}
      {route === 'account' ? (
        <Account
          store={store}
          keys={keys}
          transport={transport}
          google={google}
          passkeys={passkeys}
          draft={draft.current}
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
  store,
  keys,
  memory,
  transport,
  engine,
}: {
  t: (key: MessageKey) => string
  camera: CameraPort
  onNotice: (notice: MessageKey) => void
  store: SessionStore
  keys: MemoryKeys
  memory: PresentationMemory
  transport: ApiTransport & ByteTransport
  engine: RecognitionEngine
}) {
  const host = useRef<HTMLDivElement>(null)
  const session = useRef<CameraSession | null>(null)
  const heldShot = useRef<StillCapture | null>(null)
  const busy = useRef(false)
  const openAbort = useRef<AbortController | null>(null)
  const [live, setLive] = useState(false)
  const [held, setHeld] = useState(false)

  useEffect(() => () => {
    openAbort.current?.abort()
    session.current?.stop()
    session.current = null
    heldShot.current = null
    host.current?.replaceChildren()
  }, [])

  async function open() {
    if (busy.current) return
    busy.current = true
    openAbort.current?.abort()
    const controller = new AbortController()
    openAbort.current = controller
    try {
      const next = await camera.open(controller.signal)
      if (controller.signal.aborted) {
        next.stop()
        return
      }
      session.current?.stop()
      session.current = next
      heldShot.current = null
      next.preview.className = 'preview'
      host.current?.replaceChildren(next.preview)
      setLive(true)
      setHeld(false)
      onNotice('errorRegion')
    } catch (error) {
      if (controller.signal.aborted) return
      onNotice(error instanceof ScanError && error.code === 'CAMERA_DENIED' ? 'cameraDenied' : 'cameraUnavailable')
    } finally {
      if (openAbort.current === controller) busy.current = false
    }
  }

  async function take() {
    if (busy.current) return
    busy.current = true
    try {
      const picture = await session.current?.capture()
      if (!picture) return
      heldShot.current = picture
      setHeld(true)
      onNotice('cameraHeld')
    } catch {
      onNotice('cameraUnavailable')
    } finally {
      busy.current = false
    }
  }

  async function match() {
    if (busy.current) return
    const picture = heldShot.current
    if (!picture) return
    if (store.getSnapshot().phase !== 'keys-unlocked') {
      onNotice('keysLocked')
      return
    }
    busy.current = true
    const controller = new AbortController()
    openAbort.current?.abort()
    openAbort.current = controller
    try {
      const incoming = await fetchIncoming(store, transport, controller.signal)
      if (controller.signal.aborted) return
      if (incoming.items.length === 0) {
        onNotice('emptyIndex')
        return
      }
      await receivePostcard(
        store,
        keys,
        memory,
        engine,
        picture,
        incoming,
        { read: (item, blobId, signal) => downloadCiphertext(store, transport, item, blobId, signal) },
        canvasTextMeasurer(document),
        controller.signal,
      )
      if (!controller.signal.aborted) onNotice('errorRegion')
    } catch (error) {
      if (!controller.signal.aborted) onNotice(explainScan(error))
    } finally {
      if (openAbort.current === controller) busy.current = false
    }
  }

  function close() {
    openAbort.current?.abort()
    session.current?.stop()
    session.current = null
    heldShot.current = null
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
            {held ? <button type="button" onClick={() => { void match() }}>{t('matchPicture')}</button> : null}
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
  draft,
  t,
  onNotice,
}: {
  store: SessionStore
  keys: MemoryKeys
  transport: ApiTransport
  google: GoogleIdentityPort
  passkeys: PasskeyPort
  draft: RegistrationDraft
  t: (key: MessageKey) => string
  onNotice: (notice: MessageKey) => void
}) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [busy, setBusy] = useState(false)
  const [choosing, setChoosing] = useState(() => draft.hasTicket())
  const [handle, setHandle] = useState('')
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
              void run(async (signal) => {
                const outcome = await continueWithGoogle(store, transport, google, signal, draft)
                setChoosing(draft.hasTicket())
                return explainGoogle(outcome)
              })
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
              draft.wipe()
              store.signOut()
              setChoosing(false)
              onNotice('errorRegion')
            }}
          >
            {t('signOut')}
          </button>
        ) : null}
        <a href={href('home')}>{t('back')}</a>
      </div>
      {choosing && !signedIn ? (
        <form
          className="handle-form"
          onSubmit={(event) => {
            event.preventDefault()
            void run(async (signal) => {
              await registerNewAccount(store, transport, draft, handle, signal)
              setChoosing(false)
              return null
            })
          }}
        >
          <p>{t('chooseHandle')}</p>
          <p>{t('registrationUnsaved')}</p>
          <label>
            {t('handleLabel')}
            <input
              value={handle}
              autoComplete="off"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              maxLength={32}
              onChange={(event) => setHandle(event.target.value)}
            />
          </label>
          <div className="actions">
            <button type="submit" className="primary" disabled={busy}>{t('createAccount')}</button>
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                abort.current?.abort()
                draft.wipe()
                setChoosing(false)
                setHandle('')
                onNotice('errorRegion')
              }}
            >
              {t('cancelRegistration')}
            </button>
          </div>
        </form>
      ) : null}
    </section>
  )
}
