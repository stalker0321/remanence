import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
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
import { type Language, type MessageKey } from '../i18n/catalog'
import { phaseKey, translate } from '../i18n/translate'
import { writeLanguage, type PreferencePort } from '../preferences/language'
import { href, parseRoute, type RouteName } from '../router/route'
import { PresentationHistory } from '../router/presentationHistory'
import { LanguageMenu } from './LanguageMenu'
import { Settings } from './Settings'
import { ShellStatus } from '../pwa/ShellStatus'
import { CollageStage } from '../collage/stage'
import type { PresentationMemory } from '../collage/memory'
import { browserCamera, type CameraPort, type CameraSession, type StillCapture } from '../scan/camera'
import { ScanError } from '../scan/errors'
import { type RecognitionEngine } from '../scan/grant'
import { explainScan } from '../scan/notice'
import { receivePostcard } from '../scan/receive'
import { createRecognitionEngine } from '../scan/recognitionEngine'
import { downloadCiphertext } from '../sync/incoming'
import { fetchCompleteIncoming, wipeIncomingCatalog } from '../sync/incomingAll'
import { buildRecognitionIndex, type RecognitionIndex } from '../scan/recognitionIndex'
import { canvasTextMeasurer } from '../collage/measure'
import { CreatorComposer, type CreatorComposerPhoto } from '../collage/CreatorComposer'
import type { CreatorFreeze } from '../create/creatorFreeze'
import { CreatorImageBridge, browserImageDecode, type CreatorImageDecode } from '../create/creatorImages'
import { FrontHold, type FrontStill } from '../create/front'
import { FrontFingerprintHold } from '../create/frontFingerprint'
import { sendFeedback } from '../create/sendFeedback'
import {
  SendAttemptController,
  nextCreateVisitEpoch,
  sendVisitStillCurrent,
  type SendOutcome,
  type SendPortsFull,
  type SendPhase,
  type SendVisit,
} from '../create/sendAttempt'
import { FrontStillError } from '../scan/frontStillProducer'
import { browserPhotoCodec } from '../create/browserPhoto'
import { PhotoIntake, PhotoNormalizer, type PhotoCodec, type IntakeFence, type IntakeOutcome } from '../create/normalize'
import { PhotoIntakeActivity } from '../create/intakeActivity'
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
  engine,
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
  const navigation = useMemo(() => new PresentationHistory(), [])
  const layers = useSyncExternalStore(navigation.subscribe, navigation.getSnapshot)
  const presenting = view.phase === 'presentation-admitted' && held !== null
  const t = (key: MessageKey) => translate(view.language, key)

  useEffect(() => {
    const sync = () => {
      const owner = memory.getSnapshot()
      navigation.present(store.getSnapshot().phase === 'presentation-admitted' ? owner : null, () => {
        // An old history callback cannot lock a replacement session or sheet.
        if (owner !== null && memory.getSnapshot() === owner && store.getSnapshot().phase === 'presentation-admitted') store.lock()
      })
    }
    window.addEventListener('popstate', navigation.navigated)
    window.addEventListener('hashchange', navigation.navigated)
    navigation.initialize()
    const stopSession = store.subscribe(sync)
    const stopMemory = memory.subscribe(sync)
    sync()
    return () => {
      stopSession()
      stopMemory()
      window.removeEventListener('popstate', navigation.navigated)
      window.removeEventListener('hashchange', navigation.navigated)
    }
  }, [store, memory, navigation])

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
    <>
    <main className="shell" inert={presenting} tabIndex={-1}>
      <div className="bar">
        <p className="mark">remanence</p>
        <LanguageMenu language={view.language} onSelect={chooseLanguage} t={t} />
      </div>
      <ShellStatus t={t} details={false} />
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
        <Create t={t} store={store} transport={transport} recipient={recipient} camera={camera} note={note} photos={photos} music={music} onNotice={setNotice} keys={keys} />
      ) : null}
      {route === 'settings' ? (
        <Settings
          t={t}
          language={view.language}
          onSelectLanguage={chooseLanguage}
          handle={view.handle}
          signedIn={view.phase !== 'signed-out'}
          sessionState={t(phaseKey(view.phase))}
        />
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
    {presenting && held ? (
      <CollageStage held={held} t={t} onClose={() => navigation.closeSheet()} navigation={{
        index: layers?.owner === held ? layers.index : null,
        open: (index) => navigation.openPhoto(index),
        select: (index) => navigation.selectPhoto(index),
        close: () => navigation.closePhoto(),
      }} />
    ) : null}
    </>
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

export function Scan({
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
  engine?: RecognitionEngine
}) {
  const host = useRef<HTMLDivElement>(null)
  const session = useRef<CameraSession | null>(null)
  const heldShot = useRef<StillCapture | null>(null)
  const busy = useRef(false)
  const openAbort = useRef<AbortController | null>(null)
  const operation = useRef(0)
  const [live, setLive] = useState(false)
  const [held, setHeld] = useState(false)

  function release() {
    operation.current += 1
    busy.current = false
    openAbort.current?.abort()
    openAbort.current = null
    session.current?.stop()
    session.current = null
    heldShot.current?.bytes.fill(0)
    heldShot.current = null
    host.current?.replaceChildren()
  }

  useEffect(() => {
    let previous = store.getSnapshot()
    const unsubscribe = store.subscribe(() => {
      const next = store.getSnapshot()
      if (next.accountId !== previous.accountId || next.bundleId !== previous.bundleId ||
          (next.generation !== previous.generation && (next.phase === 'authenticated' || next.phase === 'signed-out'))) {
        close()
      }
      previous = next
    })
    return () => { unsubscribe(); release() }
  }, [store])

  async function open() {
    if (busy.current) return
    busy.current = true
    const attempt = ++operation.current
    openAbort.current?.abort()
    const controller = new AbortController()
    openAbort.current = controller
    try {
      const next = await camera.open(controller.signal)
      if (controller.signal.aborted || operation.current !== attempt) {
        next.stop()
        return
      }
      session.current?.stop()
      session.current = next
      heldShot.current?.bytes.fill(0)
      heldShot.current = null
      next.preview.className = 'preview'
      host.current?.replaceChildren(next.preview)
      setLive(true)
      setHeld(false)
      onNotice('errorRegion')
    } catch (error) {
      if (controller.signal.aborted || operation.current !== attempt) return
      onNotice(error instanceof ScanError && error.code === 'CAMERA_DENIED' ? 'cameraDenied' : 'cameraUnavailable')
    } finally {
      if (operation.current === attempt) busy.current = false
    }
  }

  async function take() {
    if (busy.current) return
    const current = session.current
    if (!current) return
    busy.current = true
    const attempt = ++operation.current
    try {
      const picture = await current.capture()
      if (operation.current !== attempt || session.current !== current) {
        picture.bytes.fill(0)
        return
      }
      heldShot.current?.bytes.fill(0)
      heldShot.current = picture
      setHeld(true)
      onNotice('cameraHeld')
    } catch {
      if (operation.current === attempt) onNotice('cameraUnavailable')
    } finally {
      if (operation.current === attempt) busy.current = false
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
    const attempt = ++operation.current
    const controller = new AbortController()
    openAbort.current?.abort()
    openAbort.current = controller
    const engineForAttempt = engine ?? createRecognitionEngine({ signal: controller.signal })
    try {
      // T353: exhaust the ordered server pages, then present ONE complete
      // catalog to the unchanged index/engine/grant flow. Terminal non-null
      // server cursors are normalized only after exhaustion.
      const incoming = await fetchCompleteIncoming(store, transport, controller.signal)
      if (controller.signal.aborted) return
      if (incoming.items.length === 0) {
        onNotice('emptyIndex')
        return
      }
      // T328: one transient pre-grant recognition index per match attempt,
      // built after the verified incoming page and always disposed in finally
      // (match consumption or refusal alike). Keys-unlocked only; no admission.
      let index: RecognitionIndex | null = null
      try {
        index = await buildRecognitionIndex({
          session: store,
          keys,
          transport,
          catalog: incoming,
          signal: controller.signal,
        })
        if (controller.signal.aborted) return
        await receivePostcard(
          store,
          keys,
          memory,
          engineForAttempt,
          picture,
          incoming,
          { read: (item, blobId, signal) => downloadCiphertext(store, transport, item, blobId, signal) },
          canvasTextMeasurer(document),
          controller.signal,
          index,
        )
      } finally {
        index?.dispose()
        // This attempt owns the collected metadata buffers.
        wipeIncomingCatalog(incoming.items)
      }
      if (!controller.signal.aborted) onNotice('errorRegion')
    } catch (error) {
      if (!controller.signal.aborted) onNotice(explainScan(error))
    } finally {
      if (operation.current === attempt) busy.current = false
    }
  }

  function close() {
    release()
    setLive(false)
    setHeld(false)
    onNotice('errorRegion')
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

/** Exported for connected browser checks; production renders it via App. */
export function Create({
  t,
  store,
  transport,
  recipient,
  camera,
  note,
  photos,
  music,
  onNotice,
  keys,
  photoCodec = browserPhotoCodec(document),
  imageDecode = browserImageDecode(),
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
  /**
   * Accepted MemoryKeys for the real Send path. API-only test callers may
   * omit it: Send then reports an explicit unavailable capability instead
   * of faking upload success.
   */
  keys?: MemoryKeys
  /**
   * Injectable codec seam for connected tests (default: real browser
   * decoder). Production always uses the default; tests inject a gated
   * fake around the real codec or a full fake. No test bypass route.
   */
  photoCodec?: PhotoCodec
  /**
   * Injectable normalized-photo decode seam (default: real browser
   * `createImageBitmap`). Tests gate timing only; production uses the real
   * decoder. No plaintext or provider bypass.
   */
  imageDecode?: CreatorImageDecode
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
  const frontFp = useRef(new FrontFingerprintHold())
  const fpAttempt = useRef(0)
  const [fpState, setFpState] = useState<'idle' | 'working' | 'ready' | 'failed'>('idle')
  const host = useRef<HTMLDivElement>(null)
  const urls = useRef(new Map<string, string>())
  const mounted = useRef(true)
  const intake = useRef<PhotoIntake | null>(null)
  const intakeActivity = useRef(new PhotoIntakeActivity())
  const imageBridge = useRef<CreatorImageBridge | null>(null)
  const frozenRef = useRef<CreatorFreeze | null>(null)
  const [creatorImages, setCreatorImages] = useState<CreatorComposerPhoto[] | null>(null)
  const [creatorImageState, setCreatorImageState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const measurer = useMemo(() => canvasTextMeasurer(document), [])

  const open = view.phase !== 'signed-out' && view.phase !== 'authenticated' && view.accountId !== null
  const pending = open ? recipient.pending() : null
  const confirmed = open ? recipient.confirmed() : null
  const selectedMusic = confirmed ? music.selected() : null
  const bound = confirmed ? `${confirmed.userId}:${confirmed.keyBundleId}:${confirmed.directoryVersion}` : ''
  void stamp
  // ---- T326 in-memory Send (one admitted attempt per mounted Create visit) ----
  // Epoch + capsuleId are allocated lazily ONCE per mount (a new Create visit
  // gets new values; re-renders/language changes keep them).
  const visitEpochRef = useRef<bigint | undefined>(undefined)
  if (visitEpochRef.current === undefined) visitEpochRef.current = nextCreateVisitEpoch()
  const capsuleIdRef = useRef<string | undefined>(undefined)
  if (capsuleIdRef.current === undefined) capsuleIdRef.current = crypto.randomUUID()
  const [sendPhase, setSendPhase] = useState<SendPhase>('idle')
  const [sendOutcome, setSendOutcome] = useState<SendOutcome | null>(null)
  const feedback = sendFeedback(sendOutcome)
  const sendControllerRef = useRef<SendAttemptController | null>(null)
  const sendPortsRef = useRef<SendPortsFull | null>(null)
  const sendUploadCapable = typeof (transport as { uploadBytes?: unknown }).uploadBytes === 'function'
  sendPortsRef.current = {
    session: store,
    // Type is the runtime fetchTransport object (Api+Byte+Upload); API-only
    // test callers without uploadBytes never pass the capability gate.
    keys: keys as MemoryKeys,
    transport: transport as SendPortsFull['transport'],
    recipientCurrent: () => recipient.confirmed(),
    photosCurrent: () => photos.photos(),
    noteTextCurrent: () => note.accepted() ?? '',
    musicSelectedCurrent: () => music.selected() !== null,
    freezeCurrent: () => frozenRef.current,
    stillCurrent: () => front.current.still(),
    fingerprintCurrent: () => frontFp.current.current(),
    mountedCurrent: () => mounted.current,
  }
  if (sendControllerRef.current === null && keys !== undefined) {
    sendControllerRef.current = new SendAttemptController(
      () => sendPortsRef.current as SendPortsFull,
      () => mounted.current,
      (phase) => setSendPhase(phase),
    )
  }
  function intakeFence(): IntakeFence {
    const current = store.getSnapshot()
    return { owner: current.accountId, generation: current.generation,
      confirmed: recipient.confirmed(), mounted: mounted.current }
  }
  function pendingPhotoIntakes(): number {
    return intakeActivity.current.pending(intake.current, intakeFence())
  }
  const photoIntakePending = pendingPhotoIntakes() > 0
  // Render-time availability (no attempt consumed on an unready click).
  const sendReady = (() => {
    if (photoIntakePending) return false
    if (!sendUploadCapable || keys === undefined || sendPhase !== 'idle') return false
    const snap = store.getSnapshot()
    if (snap.phase !== 'keys-unlocked' || snap.accountId === null || !confirmed) return false
    if (frozenRef.current === null) return false
    if (front.current.still() === null) return false
    if (frontFp.current.current() === null) return false
    if (selectedMusic !== null) return false
    if (photos.photos().length < 3 || photos.photos().length > PHOTO_COUNT_MAX) return false
    if (note.accepted() === null) return false
    return true
  })()
  /** One synchronous owner: any in-visit draft/attempt invalidation aborts
   * the in-flight Send and stops every subsequent publication request. */
  function invalidateSend(): void {
    sendControllerRef.current?.invalidate()
  }

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
  // The codec comes from props (default: real browser decoder) so connected
  // checks can gate timing without touching production wiring.
  useEffect(() => {
    mounted.current = true
    const next = new PhotoIntake(new PhotoNormalizer(photoCodec), photos)
    intake.current = next
    const bridge = new CreatorImageBridge(imageDecode)
    imageBridge.current = bridge
    return () => {
      mounted.current = false
      next.dispose()
      intakeActivity.current.retire(next)
      if (intake.current === next) intake.current = null
      bridge.dispose()
      if (imageBridge.current === bridge) imageBridge.current = null
      frozenRef.current = null
      abort.current?.abort()
      abort.current = null
      recipient.cancelPending()
      musicAbort.current?.abort()
      if (musicTimer.current !== null) clearTimeout(musicTimer.current)
      fpAttempt.current += 1
      frontFp.current.clear()
      front.current.close()
      sendControllerRef.current?.abort()
      forgetPhotos()
    }
  }, [])

  useEffect(() => {
    let generation = store.getSnapshot().generation
    return store.subscribe(() => {
      const next = store.getSnapshot().generation
      if (next === generation) return
      generation = next
      if (abort.current === null) return
      abort.current.abort()
      abort.current = null
      recipient.cancelPending()
      setBusy(false)
      setStamp((value) => value + 1)
    })
  }, [store, recipient])

  useEffect(() => {
    if (bound) return
    fpAttempt.current += 1
    frontFp.current.clear()
    setFpState('idle')
    // Visit death also invalidates an in-flight Send (fences + best-effort abort).
    sendControllerRef.current?.invalidate()
    // Release a stuck busy so lock-during-work followed by unlock/reconfirm
    // can take again; the bumped attempt keeps a stale finally from clearing
    // a newer operation's busy state.
    setBusy(false)
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

  const accepted = confirmed ? photos.accepted() : null
  const acceptedKey = accepted ? accepted.map((photo) => `${photo.id}:${photo.contentHash}`).join(',') : ''
  const noteFits = note.fits()
  // Stable recipient tuple: a fresh object each render would re-sign the
  // composer's freeze signature and repaint in a loop.
  const composerRecipient = useMemo(
    () =>
      confirmed === null
        ? null
        : {
            userId: confirmed.userId,
            keyBundleId: confirmed.keyBundleId,
            directoryVersion: confirmed.directoryVersion,
            handle: confirmed.handle,
          },
    [confirmed],
  )

  // Normalized photos -> owner/generation/confirmed/mount-fenced decoded
  // images for the creator composer. Any stale completion is dropped and
  // its owned bitmaps closed; a new image set drops a held selection.
  useEffect(() => {
    const bridge = imageBridge.current
    frozenRef.current = null
    if (!open || confirmed === null || accepted === null || !noteFits || bridge === null) {
      // Sensitive teardown: lock / account-context loss / recipient loss /
      // over-limit note must close the published decoded plaintext now, not
      // only at component unmount. The bridge is reusable; the next accepted
      // load re-decodes from the current draft.
      bridge?.dispose()
      setCreatorImages(null)
      setCreatorImageState('idle')
      return
    }
    let cancelled = false
    // Keep the mounted composer stable across reorder/restore loads: only
    // the very first load shows the preparing state (cached bitmaps resolve
    // without an unmount flash).
    if (creatorImages === null) setCreatorImageState('loading')
    void bridge
      .load(accepted, acceptedKey, () => ({
        owner: store.getSnapshot().accountId,
        generation: store.getSnapshot().generation,
        confirmed: recipient.confirmed(),
        mounted: mounted.current,
      }))
      .then((result) => {
        if (cancelled) return
        if (result.ok) {
          setCreatorImages(result.photos)
          setCreatorImageState('ready')
        } else {
          setCreatorImages(null)
          setCreatorImageState(result.stale ? 'idle' : 'error')
        }
      })
    return () => {
      cancelled = true
    }
  }, [acceptedKey, open, confirmed, noteFits])

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
    invalidateSend()
    abort.current?.abort()
    fpAttempt.current += 1
    frontFp.current.clear()
    setFpState('idle')
    // The invalidation owner releases busy so the same mounted Create can
    // take again after close/retake; no newer attempt can be running here
    // (takes are busy-serialized), so this cannot clear a newer busy state.
    setBusy(false)
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

  /** ONE admitted Send: capture the coherent visit synchronously at click. */
  function handleSend(): void {
    // Re-read synchronously: an earlier render/callback may still say ready
    // after a picker has queued work but before React commits its busy state.
    if (pendingPhotoIntakes() > 0) return
    const controller = sendControllerRef.current
    if (!controller || !sendReady || sendPhase !== 'idle') return
    const freeze = frozenRef.current
    const still = front.current.still()
    const fingerprint = frontFp.current.current()
    const rec = recipient.confirmed()
    if (!freeze || !still || !fingerprint || !rec) return // unready: no attempt
    const snap = store.getSnapshot()
    const visit: Omit<SendVisit, 'mounted'> = {
      epoch: visitEpochRef.current as bigint,
      capsuleId: capsuleIdRef.current as string,
      account: snap.accountId,
      bundleId: snap.bundleId,
      generation: snap.generation,
      keysReady: snap.phase === 'keys-unlocked',
      recipient: rec,
      // Snapshot identity values; never alias the live PhotoDraft array.
      photos: photos.photos().slice(),
      noteText: note.accepted() ?? '',
      musicSelected: selectedMusic !== null,
      freeze,
      still,
      fingerprint,
    }
    if (!controller.begin(visit)) return
    // Retire the old preparation notice immediately. This caveat remains true
    // even if the visit is abandoned and the guarded completion stays silent.
    onNotice('sendWorkingHelp')
    const coherentVisit = (): boolean =>
      sendVisitStillCurrent({ ...visit, mounted: mounted.current }, sendPortsRef.current as SendPortsFull)
    void (async () => {
      const outcome = await controller.run()
      // Guard ALL post-await UI writes (state and notice) on the FULL current
      // visit, not only on a notice-time tuple check.
      if (!coherentVisit()) return
      setSendOutcome(outcome)
      const resultFeedback = sendFeedback(outcome)
      if (resultFeedback) onNotice(resultFeedback.notice)
    })()
  }

  async function takeFront() {
    const snapshot = recipient.confirmed()
    if (!snapshot || busy) return
    invalidateSend()
    // Capture the full current visit BEFORE the camera await: the sender
    // tuple, session generation/phase, and the confirmed recipient identity.
    const visit = store.getSnapshot()
    // This attempt owns every state/notice mutation below; a newer attempt,
    // close, recipient change or unmount invalidates it. Stale completions
    // must not clear another attempt's busy/notice/ready state.
    const my = ++fpAttempt.current
    // A retake aborts old processing and wipes the prior wire BEFORE the take.
    frontFp.current.clear()
    setBusy(true)
    // Full current-visit recheck. The producer and holder continuations are
    // distinct Promises, so their fences cannot cover this continuation's
    // gap: recheck everything after EVERY await before ANY side effect.
    const visitOk = (still: FrontStill | null): boolean => {
      const now = store.getSnapshot()
      return now.accountId === visit.accountId
        && now.bundleId === visit.bundleId
        && now.generation === visit.generation
        && now.phase === visit.phase
        && now.phase !== 'signed-out'
        && now.phase !== 'authenticated'
        && recipient.confirmed() === snapshot
        && (still === null || front.current.still() === still)
        && mounted.current
        && fpAttempt.current === my
    }
    try {
      const still = await front.current.take(snapshot)
      if (!visitOk(still)) return
      setPictured(true)
      onNotice('frontHeld')
      setFpState('working')
      let fp
      try {
        fp = await frontFp.current.run(still, {
          store,
          recipient,
          hold: front.current,
          mounted: () => mounted.current,
        })
      } catch (error) {
        // Leaving-visit stale/cancel produces no misleading error or success.
        if (!visitOk(still)) return
        if (error instanceof FrontStillError && (error.code === 'stale' || error.code === 'cancelled')) return
        setFpState('failed')
        onNotice('frontScanFailed')
        return
      }
      // Authoritative final boundary: the held fingerprint must still be the
      // value this attempt produced — a session/recipient invalidation may
      // have landed after the holder resolved but before this continuation.
      if (!visitOk(still) || frontFp.current.current() !== fp) return
      setFpState('ready')
      onNotice('frontReady')
    } catch (error) {
      // A camera failure is also an awaited outcome: recheck the full
      // current visit before any error notice, so a stale invalidation
      // cannot surface a misleading cameraUnavailable.
      if (!visitOk(null)) return
      if (error instanceof ScanError && error.code === 'CANCELLED') return
      onNotice('cameraUnavailable')
    } finally {
      if (mounted.current && fpAttempt.current === my) setBusy(false)
    }
  }

  async function addPhotos(list: File[] | FileList | null) {
    if (!list || list.length === 0 || !confirmed) return
    const intakePort = intake.current
    const fence = intakeFence()
    if (!intakePort || !fence.mounted || fence.owner === null || fence.confirmed !== confirmed) return
    const work = intakeActivity.current.begin(intakePort, fence)
    const photoKey = () => photos.photos().map((photo) => `${photo.id}:${photo.contentHash}`).join(',')
    const before = photoKey()
    // Acquire before any callback/await; includes picks queued in PhotoIntake.
    invalidateSend()
    setStamp((value) => value + 1)
    onNotice('photoLoading')
    let outcome: IntakeOutcome | undefined
    let failed = false
    try {
      outcome = await intakePort.addFiles(list, intakeFence)
    } catch {
      failed = true
    } finally {
      const current = intakeActivity.current.owns(work, intake.current, intakeFence())
      // Drop a changed selection BEFORE releasing this busy lease, not in a
      // later React effect. A wholly failed/cancelled unchanged pick preserves
      // an otherwise valid freeze; it never restores an invalidated freeze.
      if (current && ((outcome?.added ?? 0) > 0 || photoKey() !== before)) frozenRef.current = null
      intakeActivity.current.finish(work)
      // An old owner/recipient/mount cannot clear a replacement's busy,
      // object URLs or notice, even after a late read rejection.
      if (current) {
        rememberPhotos()
        setStamp((value) => value + 1)
        onNotice(failed || outcome?.refused ? 'photoRejected' : pendingPhotoIntakes() > 0 ? 'photoLoading' : 'errorRegion')
      }
    }
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

  function editRecipient(value: string): void {
    abort.current?.abort()
    abort.current = null
    recipient.cancelPending()
    setBusy(false)
    setHandle(value)
    setStamp((next) => next + 1)
    onNotice('errorRegion')
  }

  async function lookup(mode: 'named' | 'self') {
    if (busy || !open || view.accountId === null) return
    abort.current?.abort()
    const controller = new AbortController()
    abort.current = controller
    const ticket = recipient.nextTicket(view.accountId)
    const generation = store.getSnapshot().generation
    const current = () => mounted.current && abort.current === controller
      && !controller.signal.aborted && store.getSnapshot().generation === generation
    setBusy(true)
    setStamp((value) => value + 1)
    try {
      const outcome = await lookupRecipient(store, transport, handle, controller.signal, mode)
      if (!current()) return
      if (outcome.kind === 'not-found') {
        onNotice('recipientNotFound')
        return
      }
      if (!recipient.offer(ticket, view.accountId, outcome.snapshot)) return
      onNotice('errorRegion')
    } catch (error) {
      if (current()) onNotice(explainFailure(error))
    } finally {
      if (current()) {
        abort.current = null
        setBusy(false)
        setStamp((value) => value + 1)
      }
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
      {confirmed ? <RecipientLine t={t} snapshot={confirmed} held={sendPhase === 'idle' && !pictured} /> : null}
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
              onChange={(event) => editRecipient(event.target.value)}
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
          {pictured && fpState === 'idle' && sendPhase === 'idle' ? <p>{t('frontHeld')}</p> : null}
          {pictured && fpState === 'working' && sendPhase === 'idle' ? <p>{t('frontWorking')}</p> : null}
          {pictured && fpState === 'ready' && sendPhase === 'idle' ? <p>{t('frontReady')}</p> : null}
          {pictured && fpState === 'failed' ? <p role="alert">{t('frontScanFailed')}</p> : null}
          <label className="handle-form">
            {t('noteLabel')}
            <textarea
              value={note.text()}
              rows={4}
              disabled={sendPhase === 'working'}
              onChange={(event) => {
                invalidateSend()
                note.edit(event.target.value)
                setStamp((value) => value + 1)
              }}
            />
          </label>
          <p>{note.bytes()} / {NOTE_MAX_UTF8_BYTES}</p>
          {note.fits() ? null : <p>{t('noteTooLong')}</p>}
          <div className="photo-draft" aria-busy={photoIntakePending}>
            <label>
              {t('addPhotos')}
              <input
                type="file"
                accept="image/jpeg,.jpg,.jpeg"
                multiple
                disabled={sendPhase === 'working'}
                onChange={(event) => {
                  // Snapshot the picked files FIRST: input.files is a live
                  // FileList — resetting the input below would empty the
                  // same object before addPhotos reads it.
                  const list = event.target.files ? Array.from(event.target.files) : null
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
                    <button type="button" disabled={index === 0} onClick={() => { invalidateSend(); photos.move(photo.id, -1); setStamp((value) => value + 1) }}>{t('photoEarlier')}</button>
                    <button type="button" disabled={index === photos.photos().length - 1} onClick={() => { invalidateSend(); photos.move(photo.id, 1); setStamp((value) => value + 1) }}>{t('photoLater')}</button>
                    <button type="button" onClick={() => { invalidateSend(); photos.remove(photo.id); rememberPhotos(); setStamp((value) => value + 1) }}>{t('removePhoto')}</button>
                  </div>
                </li>
              ))}
            </ul>
            <p>{photos.photos().length} / {PHOTO_COUNT_MAX}</p>
            {sendPhase === 'idle' ? <p>{t('photoCount')}</p> : null}
          </div>
          {accepted !== null && noteFits ? (
            creatorImageState === 'loading' ? (
              <p>{t('photoLoading')}</p>
            ) : creatorImageState === 'error' ? (
              <p role="alert">{t('createComposeFailed')}</p>
            ) : creatorImages !== null ? (
              <div className="creator-connected">
                <CreatorComposer
                  photos={creatorImages}
                  note={note.accepted()}
                  musicSelected={selectedMusic !== null}
                  recipient={composerRecipient}
                  ownerId={view.accountId ?? ''}
                  session={store}
                  lang={view.language}
                  measurer={measurer}
                  onFrozenChange={(frozen) => {
                    frozenRef.current = frozen
                    // Freeze readiness is owned by the child composer; make it
                    // reactive in the parent so camera-before-freeze arms Send
                    // (and any freeze drop invalidates readiness/attempt) in
                    // the same visit without extra parent state updates.
                    invalidateSend()
                    setStamp((value) => value + 1)
                  }}
                />
              </div>
            ) : null
          ) : null}
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
                <button type="button" onClick={() => { invalidateSend(); music.remove(); setStamp((value) => value + 1) }}>{t('musicRemove')}</button>
              </div>
            ) : null}
            <ul>
              {music.hits().map((hit) => (
                <li key={hit.id}>
                  <button
                    type="button"
                    onClick={() => {
                      invalidateSend()
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
              {sendPhase === 'idle' ? <p>{t('musicNotSent')}</p> : null}
            </div>
            <div className="send-block">
              <button
                type="button"
                data-testid="send_button"
                disabled={!sendReady}
                onClick={handleSend}
              >
                {t('sendAction')}
              </button>
              {sendPhase === 'working' ? (
                <>
                  <p data-testid="send_state">{t('sendWorking')}</p>
                  <p data-testid="send_help">{t('sendWorkingHelp')}</p>
                </>
              ) : feedback ? (
                <>
                  <p data-testid="send_state" role={feedback.help ? 'alert' : undefined}>{t(feedback.notice)}</p>
                  {feedback.help ? (
                    <>
                      <p data-testid="send_help">{t(feedback.help)}</p>
                      <a className="button" data-testid="send_exit" href={href('home')}>{t('sendExit')}</a>
                    </>
                  ) : null}
                </>
              ) : null}
            </div>
          <div className="actions">
            {live ? (
              <>
                <button type="button" className="primary" disabled={busy || sendPhase === 'working'} onClick={() => { void takeFront() }}>{t('takePicture')}</button>
                <button type="button" disabled={busy} onClick={closeCamera}>{t('closeCamera')}</button>
              </>
            ) : (
              <button type="button" className="primary" disabled={busy} onClick={() => { void openCamera() }}>{t('useCamera')}</button>
            )}
            <button type="button" disabled={busy || sendPhase === 'working'} onClick={changeRecipient}>{t('recipientChange')}</button>
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
