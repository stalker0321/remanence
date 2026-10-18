import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { explainFailure, explainGoogle, explainRestore } from '../auth/notice'
import { browserGoogleIdentity } from '../auth/googleBrowser'
import { continueWithGoogle, type GoogleIdentityPort } from '../auth/google'
import { RegistrationDraft, registerNewAccount } from '../auth/register'
import { enrollHeldIdentity } from '../auth/enroll'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
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
import { FrontHold } from '../create/front'
import { browserPhotoCodec } from '../create/browserPhoto'
import { PhotoIntake, PhotoNormalizer } from '../create/normalize'
import { MUSIC_QUERY_MAX_CODE_POINTS, MUSIC_SEARCH_DEBOUNCE_MS, musicCoverKey, musicLine, MusicDraft, searchMusic } from '../create/music'
import { NOTE_MAX_UTF8_BYTES, type NoteDraft } from '../create/note'
import { PHOTO_COUNT_MAX, type PhotoDraft } from '../create/photos'
import { arrayBuffer } from '../protocol/bytes'
import { RecipientDraft, lookupRecipient, type RecipientSnapshot } from '../create/recipient'
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
  recipient,
  note,
  photos,
  music,
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
  recipient: RecipientDraft
  note: NoteDraft
  photos: PhotoDraft
  music: MusicDraft
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
      {route === 'create' ? (
        <Create t={t} store={store} transport={transport} recipient={recipient} camera={camera} note={note} photos={photos} music={music} onNotice={setNotice} />
      ) : null}
      {view.phase === 'presentation-admitted' && held ? (
        <CollageStage held={held} t={t} onClose={() => store.lock()} />
      ) : null}
      {route === 'settings' ? <Settings t={t} /> : null}
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
        <a href={href('settings')}>{t('settingsTitle')}</a>
      </div>
    </>
  )
}

function Settings({ t }: { t: (key: MessageKey) => string }) {
  return (
    <section className="card">
      <h1>{t('settingsTitle')}</h1>
      <p>{t('settingsBody')}</p>
      <div className="actions">
        <a href={href('account')}>{t('account')}</a>
        <a href={href('home')}>{t('back')}</a>
      </div>
    </section>
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

function Create({
  t,
  store,
  transport,
  recipient,
  camera,
  note,
  photos,
  music,
  onNotice,
}: {
  t: (key: MessageKey) => string
  store: SessionStore
  transport: ApiTransport
  recipient: RecipientDraft
  camera: CameraPort
  note: NoteDraft
  photos: PhotoDraft
  music: MusicDraft
  onNotice: (notice: MessageKey) => void
}) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [handle, setHandle] = useState('')
  const [busy, setBusy] = useState(false)
  const [stamp, setStamp] = useState(0)
  const [live, setLive] = useState(false)
  const [pictured, setPictured] = useState(false)
  const abort = useRef<AbortController | null>(null)
  const musicAbort = useRef<AbortController | null>(null)
  const musicTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [musicQuery, setMusicQuery] = useState('')
  const front = useRef(new FrontHold())
  const host = useRef<HTMLDivElement>(null)
  const urls = useRef(new Map<string, string>())
  const mounted = useRef(true)
  const intake = useRef<PhotoIntake | null>(null)
  const open = view.phase !== 'signed-out' && view.phase !== 'authenticated' && view.accountId !== null
  const pending = open ? recipient.pending() : null
  const confirmed = open ? recipient.confirmed() : null
  const selectedMusic = confirmed ? music.selected() : null
  const bound = confirmed ? `${confirmed.userId}:${confirmed.keyBundleId}:${confirmed.directoryVersion}` : ''
  void stamp

  function forgetPhotos() {
    for (const url of urls.current.values()) URL.revokeObjectURL(url)
    urls.current.clear()
  }

  function rememberPhotos() {
    const live = new Set(photos.photos().map((photo) => photo.id))
    for (const [id, url] of urls.current) {
      if (live.has(id)) continue
      URL.revokeObjectURL(url)
      urls.current.delete(id)
    }
    for (const photo of photos.photos()) {
      if (urls.current.has(photo.id)) continue
      urls.current.set(photo.id, URL.createObjectURL(new Blob([arrayBuffer(photo.bytes)], { type: 'image/jpeg' })))
    }
  }

  // Owned Create mount only: the intake is bound to this draft for the visit;
  // a StrictMode remount disposes the old intake and builds a fresh one.
  useEffect(() => {
    mounted.current = true
    const next = new PhotoIntake(new PhotoNormalizer(browserPhotoCodec(document)), photos)
    intake.current = next
    return () => {
      mounted.current = false
      next.dispose()
      if (intake.current === next) intake.current = null
      abort.current?.abort()
      musicAbort.current?.abort()
      if (musicTimer.current !== null) clearTimeout(musicTimer.current)
      front.current.close()
      forgetPhotos()
    }
  }, [])

  useEffect(() => {
    if (bound) return
    front.current.close()
    host.current?.replaceChildren()
    setLive(false)
    setPictured(false)
  }, [bound])

  useEffect(() => {
    if (!open) {
      forgetPhotos()
      return
    }
    const before = urls.current.size
    rememberPhotos()
    if (urls.current.size !== before) setStamp((value) => value + 1)
  }, [open])

  useEffect(() => {
    if (open) return
    if (musicTimer.current !== null) {
      clearTimeout(musicTimer.current)
      musicTimer.current = null
    }
    musicAbort.current?.abort()
    setMusicQuery('')
  }, [open])

  function stopMusicSearch() {
    if (musicTimer.current !== null) {
      clearTimeout(musicTimer.current)
      musicTimer.current = null
    }
    musicAbort.current?.abort()
    musicAbort.current = null
  }

  function closeCamera() {
    abort.current?.abort()
    front.current.close()
    host.current?.replaceChildren()
    setLive(false)
    setPictured(false)
  }

  function changeRecipient() {
    closeCamera()
    stopMusicSearch()
    recipient.restart()
    setStamp((value) => value + 1)
    onNotice('errorRegion')
  }

  async function openCamera() {
    if (busy || !confirmed) return
    abort.current?.abort()
    const controller = new AbortController()
    abort.current = controller
    setBusy(true)
    try {
      const session = await front.current.open(camera, controller.signal)
      if (controller.signal.aborted) return
      session.preview.className = 'preview'
      host.current?.replaceChildren(session.preview)
      setLive(true)
      setPictured(false)
      onNotice('errorRegion')
    } catch (error) {
      if (controller.signal.aborted || (error instanceof ScanError && error.code === 'CANCELLED')) return
      onNotice(error instanceof ScanError && error.code === 'CAMERA_DENIED' ? 'cameraDenied' : 'cameraUnavailable')
    } finally {
      if (abort.current === controller) setBusy(false)
    }
  }

  async function takeFront() {
    const snapshot = recipient.confirmed()
    if (!snapshot || busy) return
    setBusy(true)
    try {
      await front.current.take(snapshot)
      if (recipient.confirmed() !== snapshot) {
        front.current.close()
        return
      }
      setPictured(true)
      onNotice('frontHeld')
    } catch (error) {
      if (error instanceof ScanError && error.code === 'CANCELLED') return
      onNotice('cameraUnavailable')
    } finally {
      setBusy(false)
    }
  }

  async function addPhotos(list: FileList | null) {
    if (!list || !confirmed) return
    const intakePort = intake.current
    if (!intakePort) return
    onNotice('photoLoading')
    const outcome = await intakePort.addFiles(list, () => ({
      owner: store.getSnapshot().accountId,
      generation: store.getSnapshot().generation,
      confirmed: recipient.confirmed(),
      mounted: mounted.current,
    }))
    rememberPhotos()
    setStamp((value) => value + 1)
    onNotice(outcome.completed ? (outcome.refused ? 'photoRejected' : 'errorRegion') : 'errorRegion')
  }

  function onMusicInput(value: string) {
    setMusicQuery(value)
    stopMusicSearch()
    const trimmed = value.trim()
    if (trimmed.length === 0 || [...trimmed].length > MUSIC_QUERY_MAX_CODE_POINTS) {
      music.nextTicket()
      setStamp((next) => next + 1)
      onNotice(trimmed.length === 0 ? 'errorRegion' : 'musicRejected')
      return
    }
    const ticket = music.nextTicket()
    setStamp((next) => next + 1)
    musicTimer.current = setTimeout(() => {
      musicTimer.current = null
      void runMusic(ticket, trimmed)
    }, MUSIC_SEARCH_DEBOUNCE_MS)
  }

  async function runMusic(ticket: number, query: string) {
    const controller = new AbortController()
    musicAbort.current = controller
    try {
      const outcome = await searchMusic(store, transport, query, controller.signal)
      if (controller.signal.aborted) return
      if (outcome.kind === 'empty') {
        music.offer(ticket, [])
        return
      }
      if (!music.offer(ticket, outcome.hits)) return
      onNotice(outcome.hits.length === 0 ? 'musicNone' : 'errorRegion')
    } catch (error) {
      if (!controller.signal.aborted) onNotice(explainFailure(error))
    } finally {
      setStamp((next) => next + 1)
    }
  }

  async function lookup(mode: 'named' | 'self') {
    if (busy || !open || view.accountId === null) return
    abort.current?.abort()
    const controller = new AbortController()
    abort.current = controller
    const ticket = recipient.nextTicket(view.accountId)
    setBusy(true)
    setStamp((value) => value + 1)
    try {
      const outcome = await lookupRecipient(store, transport, handle, controller.signal, mode)
      if (controller.signal.aborted) return
      if (outcome.kind === 'not-found') {
        onNotice('recipientNotFound')
        return
      }
      if (!recipient.offer(ticket, view.accountId, outcome.snapshot)) return
      onNotice('errorRegion')
    } catch (error) {
      if (!controller.signal.aborted) onNotice(explainFailure(error))
    } finally {
      if (abort.current === controller) setBusy(false)
      setStamp((value) => value + 1)
    }
  }

  if (!open) {
    return (
      <section className="card">
        <h1>{t('homeMakeTitle')}</h1>
        <p>{t('createLocked')}</p>
        <div className="actions">
          <a className="button" href={href('account')}>{t('account')}</a>
          <a href={href('home')}>{t('back')}</a>
        </div>
      </section>
    )
  }

  return (
    <section className="card">
      <h1>{t('homeMakeTitle')}</h1>
      {confirmed ? <RecipientLine t={t} snapshot={confirmed} held /> : null}
      {pending && !confirmed ? <RecipientLine t={t} snapshot={pending} held={false} /> : null}
      {!confirmed ? (
        <form
          className="handle-form"
          onSubmit={(event) => {
            event.preventDefault()
            void lookup('named')
          }}
        >
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
            <button type="submit" className="primary" disabled={busy || handle.trim().length === 0}>{t('lookupRecipient')}</button>
            <button type="button" disabled={busy || view.handle === null} onClick={() => { void lookup('self') }}>{t('forMyself')}</button>
          </div>
        </form>
      ) : null}
      {pending && !confirmed ? (
        <div className="actions">
          <button
            type="button"
            className="primary"
            onClick={() => {
              recipient.confirm()
              setStamp((value) => value + 1)
              onNotice('errorRegion')
            }}
          >
            {t('recipientConfirm')}
          </button>
          <button
            type="button"
            onClick={() => {
              abort.current?.abort()
              recipient.restart()
              setStamp((value) => value + 1)
              onNotice('errorRegion')
            }}
          >
            {t('recipientChange')}
          </button>
        </div>
      ) : null}
      {confirmed ? (
        <>
          <div ref={host} />
          {pictured ? <p>{t('frontHeld')}</p> : null}
          <label className="handle-form">
            {t('noteLabel')}
            <textarea
              value={note.text()}
              rows={4}
              onChange={(event) => {
                note.edit(event.target.value)
                setStamp((value) => value + 1)
              }}
            />
          </label>
          <p>{note.bytes()} / {NOTE_MAX_UTF8_BYTES}</p>
          {note.fits() ? null : <p>{t('noteTooLong')}</p>}
          <div className="photo-draft">
            <label>
              {t('addPhotos')}
              <input
                type="file"
                accept="image/jpeg,.jpg,.jpeg"
                multiple
                onChange={(event) => {
                  const list = event.target.files
                  event.target.value = ''
                  void addPhotos(list)
                }}
              />
            </label>
            <ul>
              {photos.photos().map((photo, index) => (
                <li key={photo.id}>
                  <img src={urls.current.get(photo.id)} alt="" />
                  <div className="actions">
                    <button type="button" disabled={index === 0} onClick={() => { photos.move(photo.id, -1); setStamp((value) => value + 1) }}>{t('photoEarlier')}</button>
                    <button type="button" disabled={index === photos.photos().length - 1} onClick={() => { photos.move(photo.id, 1); setStamp((value) => value + 1) }}>{t('photoLater')}</button>
                    <button type="button" onClick={() => { photos.remove(photo.id); rememberPhotos(); setStamp((value) => value + 1) }}>{t('removePhoto')}</button>
                  </div>
                </li>
              ))}
            </ul>
            <p>{photos.photos().length} / {PHOTO_COUNT_MAX}</p>
            <p>{t('photoCount')}</p>
          </div>
          <div className="music-draft">
            <label className="handle-form">
              {t('musicLabel')}
              <input
                value={musicQuery}
                autoComplete="off"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                onChange={(event) => onMusicInput(event.target.value)}
              />
            </label>
            {selectedMusic ? (
              <div>
                <p>{musicLine(selectedMusic)}</p>
                <p>{t(musicCoverKey(selectedMusic))}</p>
                <button type="button" onClick={() => { music.remove(); setStamp((value) => value + 1) }}>{t('musicRemove')}</button>
              </div>
            ) : null}
            <ul>
              {music.hits().map((hit) => (
                <li key={hit.id}>
                  <button
                    type="button"
                    onClick={() => {
                      music.select(hit.id)
                      setStamp((value) => value + 1)
                      onNotice('errorRegion')
                    }}
                  >
                    {musicLine(hit)}
                  </button>
                  <p>{t(musicCoverKey(hit))}</p>
                </li>
              ))}
            </ul>
            <p>{t('musicNotSent')}</p>
          </div>
          <div className="actions">
            {live ? (
              <>
                <button type="button" className="primary" disabled={busy} onClick={() => { void takeFront() }}>{t('takePicture')}</button>
                <button type="button" disabled={busy} onClick={closeCamera}>{t('closeCamera')}</button>
              </>
            ) : (
              <button type="button" className="primary" disabled={busy} onClick={() => { void openCamera() }}>{t('useCamera')}</button>
            )}
            <button type="button" disabled={busy} onClick={changeRecipient}>{t('recipientChange')}</button>
            <a href={href('home')}>{t('back')}</a>
          </div>
        </>
      ) : (
        <div className="actions">
          <a href={href('home')}>{t('back')}</a>
        </div>
      )}
    </section>
  )
}

function RecipientLine({
  t,
  snapshot,
  held,
}: {
  t: (key: MessageKey) => string
  snapshot: RecipientSnapshot
  held: boolean
}) {
  return (
    <p>
      @{snapshot.handle}
      {snapshot.self ? ` · ${t('forMyself')}` : ''}
      {held ? ` ${t('recipientHeld')}` : ''}
    </p>
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
  const [recoveryCode, setRecoveryCode] = useState('')
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
        {signedIn && !unlocked && draft.bundleId() === view.bundleId ? (
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={() => {
              void run(async (signal) => {
                const outcome = await enrollHeldIdentity(store, transport, passkeys, keys, draft, signal)
                if (outcome.kind === 'enrolled') {
                  const shown = b64(outcome.code)
                  outcome.code.fill(0)
                  setRecoveryCode(shown)
                }
                return null
              })
            }}
          >
            {t('savePasskey')}
          </button>
        ) : null}
        {signedIn && !unlocked && draft.bundleId() !== view.bundleId ? (
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
              setRecoveryCode('')
              onNotice('errorRegion')
            }}
          >
            {t('signOut')}
          </button>
        ) : null}
        <a href={href('home')}>{t('back')}</a>
      </div>
      {recoveryCode ? <p className="recovery-code">{t('recoveryCodeHint')} {recoveryCode}</p> : null}
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
