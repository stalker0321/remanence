import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { clampTransform, type CropTransform } from './crop'
import {
  creatorScene,
  plannerFromTextMeasurer,
  previewCandidates,
  type CreatorCandidateView,
} from './creatorPreview'
import { freezeSelection, freezeValid, type CreatorFreeze, type CreatorSlotFreeze, type FreezeCurrent } from '../create/creatorFreeze'
import { paintScene, type PaintImage } from './paint'
import type { TextMeasurer } from './prepare'
import { styleById } from './styles'
import type { SessionStore } from '../session/session'
import type { Grammar5Measure } from './planGrammar5'
import './creatorComposer.css'

export type CreatorLang = 'en' | 'ru' | 'uk'

export interface CreatorComposerPhoto {
  contentId: string
  width: number
  height: number
  contentHash: string
  image: CanvasImageSource
}

export interface CreatorComposerRecipient {
  userId: string
  keyBundleId: string
  directoryVersion: string
  handle: string
}

export interface CreatorComposerProps {
  photos: CreatorComposerPhoto[]
  note: string | null
  musicSelected: boolean
  styleId?: string
  recipient: CreatorComposerRecipient | null
  /** Explicit sender handle (same owner). Falls back to the live session handle. */
  senderHandle?: string
  ownerId: string
  session: SessionStore
  lang: CreatorLang
  measurer: TextMeasurer
  /** Parent-visible freeze lifecycle: snapshot on freeze, null on every invalidation/teardown/unmount. */
  onFrozenChange?: (frozen: CreatorFreeze | null) => void
}

interface Copy {
  prev: string
  next: string
  count: (index: number, total: number) => string
  branch: (branch: string) => string
  slot: (index: number) => string
  zoomIn: string
  zoomOut: string
  panUp: string
  panDown: string
  panLeft: string
  panRight: string
  reset: string
  freeze: string
  preparing: string
  frozen: (branch: string) => string
  musicUnsupported: string
  noRecipient: string
  noSender: string
  noteOverflow: string
  noteMissing: string
  notReady: string
  locked: string
  empty: string
  preview: string
  transform: (z: number, x: number, y: number) => string
}

const copy: Record<CreatorLang, Copy> = {
  en: {
    prev: 'Previous candidate',
    next: 'Next candidate',
    count: (index, total) => `Candidate ${index + 1} of ${total}`,
    branch: (branch) => `Branch ${branch}`,
    slot: (index) => `Slot ${index + 1}`,
    zoomIn: 'Zoom in',
    zoomOut: 'Zoom out',
    panUp: 'Move up',
    panDown: 'Move down',
    panLeft: 'Move left',
    panRight: 'Move right',
    reset: 'Reset crop',
    freeze: 'Freeze selection',
    preparing: 'Preparing preview…',
    frozen: (branch) => `Frozen ${branch} — edit to invalidate`,
    musicUnsupported: 'Music is not yet supported for this composition — Freeze is disabled.',
    noRecipient: 'Confirm a recipient to freeze.',
    noSender: 'Confirm a sender to compose.',
    noteOverflow: 'Note does not fit its region — Freeze is disabled.',
    noteMissing: 'A sealed note region requires note text — Freeze is disabled.',
    notReady: 'Preview is not ready yet — Freeze is disabled.',
    locked: 'Locked — preview cleared.',
    empty: 'Add 3–5 photos to compose.',
    preview: 'Candidate preview',
    transform: (z, x, y) => `zoom ${z.toFixed(2)} pan ${x.toFixed(1)} ${y.toFixed(1)}`,
  },
  ru: {
    prev: 'Предыдущий вариант',
    next: 'Следующий вариант',
    count: (index, total) => `Вариант ${index + 1} из ${total}`,
    branch: (branch) => `Ветка ${branch}`,
    slot: (index) => `Слот ${index + 1}`,
    zoomIn: 'Приблизить',
    zoomOut: 'Отдалить',
    panUp: 'Вверх',
    panDown: 'Вниз',
    panLeft: 'Влево',
    panRight: 'Вправо',
    reset: 'Сбросить кадрирование',
    freeze: 'Зафиксировать выбор',
    preparing: 'Готовим предпросмотр…',
    frozen: (branch) => `Зафиксировано: ${branch} — изменение сбросит фиксацию`,
    musicUnsupported: 'Музыка пока не поддерживается в этой композиции — фиксация отключена.',
    noRecipient: 'Подтвердите получателя для фиксации.',
    noSender: 'Подтвердите отправителя для композиции.',
    noteOverflow: 'Текст не помещается в область — фиксация отключена.',
    noteMissing: 'Для области заметки нужен текст — фиксация отключена.',
    notReady: 'Предпросмотр ещё не готов — фиксация отключена.',
    locked: 'Заблокировано — предпросмотр очищен.',
    empty: 'Добавьте 3–5 фото для композиции.',
    preview: 'Предпросмотр варианта',
    transform: (z, x, y) => `зум ${z.toFixed(2)} сдвиг ${x.toFixed(1)} ${y.toFixed(1)}`,
  },
  uk: {
    prev: 'Попередній варіант',
    next: 'Наступний варіант',
    count: (index, total) => `Варіант ${index + 1} з ${total}`,
    branch: (branch) => `Гілка ${branch}`,
    slot: (index) => `Слот ${index + 1}`,
    zoomIn: 'Наблизити',
    zoomOut: 'Віддалити',
    panUp: 'Вгору',
    panDown: 'Вниз',
    panLeft: 'Вліво',
    panRight: 'Вправо',
    reset: 'Скинути кадрування',
    freeze: 'Зафіксувати вибір',
    preparing: 'Готуємо попередній перегляд…',
    frozen: (branch) => `Зафіксовано: ${branch} — зміна скине фіксацію`,
    musicUnsupported: 'Музика поки не підтримується в цій композиції — фіксація вимкнена.',
    noRecipient: 'Підтвердіть отримувача для фіксації.',
    noSender: 'Підтвердіть відправника для композиції.',
    noteOverflow: 'Текст не вміщується в область — фіксація вимкнена.',
    noteMissing: 'Для області нотатки потрібен текст — фіксація вимкнена.',
    notReady: 'Попередній перегляд ще не готовий — фіксація вимкнена.',
    locked: 'Заблоковано — попередній перегляд очищено.',
    empty: 'Додайте 3–5 фото для композиції.',
    preview: 'Попередній перегляд варіанта',
    transform: (z, x, y) => `зум ${z.toFixed(2)} зсув ${x.toFixed(1)} ${y.toFixed(1)}`,
  },
}

const IDENTITY_CROP: CropTransform = { z: 1, xPercent: 0, yPercent: 0 }

export function CreatorComposer(props: CreatorComposerProps): React.JSX.Element {
  const t = copy[props.lang]
  const styleId = props.styleId ?? 'press'
  const snap = useSyncExternalStore(props.session.subscribe, props.session.getSnapshot)
  const [planIndex, setPlanIndex] = useState(0)
  const [activeSlot, setActiveSlot] = useState(0)
  const [transforms, setTransforms] = useState<Record<string, CropTransform>>({})
  const [views, setViews] = useState<CreatorCandidateView[] | null>(null)
  const [planError, setPlanError] = useState<string | null>(null)
  const [frozen, setFrozen] = useState<CreatorFreeze | null>(null)
  const [freezeError, setFreezeError] = useState<string | null>(null)
  const [paintError, setPaintError] = useState<string | null>(null)
  const [noteFits, setNoteFits] = useState(true)
  const [readySig, setReadySig] = useState<string | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const epochRef = useRef(0)
  const frozenRef = useRef<CreatorFreeze | null>(null)
  const onFrozenChangeRef = useRef(props.onFrozenChange)
  onFrozenChangeRef.current = props.onFrozenChange
  const notifyFrozen = (value: CreatorFreeze | null): void => {
    onFrozenChangeRef.current?.(value)
  }

  const locked = snap.phase === 'signed-out' || snap.phase === 'authenticated'
  const ownerMismatch = snap.accountId !== null && snap.accountId !== props.ownerId
  const tornDown = locked || ownerMismatch

  const explicitSender = props.senderHandle !== undefined && props.senderHandle.trim() !== '' ? props.senderHandle.trim() : null
  const sessionSender = snap.handle !== null && snap.handle.trim() !== '' ? snap.handle.trim() : null
  const senderHandle = explicitSender ?? sessionSender

  const photosKey = useMemo(
    () => JSON.stringify(props.photos.map((photo) => [photo.contentId, photo.width, photo.height, photo.contentHash])),
    [props.photos],
  )
  const transformsKey = useMemo(() => JSON.stringify(transforms), [transforms]);

  // Plan with REAL note geometry: required bundled faces load first, then
  // the injected measurer drives the grammar-5 row decision (short vs
  // multi-line/regular) through the same T233 seam — never the stub.
  useEffect(() => {
    if (tornDown) return
    const epoch = ++epochRef.current
    setPlanError(null)
    void (async () => {
      try {
        const style = styleById(styleId)
        if (!style) throw new Error(`unknown style ${styleId}`)
        await Promise.all([
          document.fonts.load(`400 ${27}px StudySerif`),
          document.fonts.load(`${style.note.weight} ${style.note.size}px ${style.note.font}`),
          document.fonts.load('400 64px StudySerif'),
          document.fonts.load('italic 400 20px StudySerif'),
        ])
        if (cancelledOrStale(epoch)) return
        const planner: Grammar5Measure = plannerFromTextMeasurer(props.measurer, 640, 640)
        const planned = previewCandidates(
          {
            id: 'creator',
            sender: senderHandle ?? 'creator',
            photos: props.photos.map((photo) => ({
              contentId: photo.contentId,
              width: photo.width,
              height: photo.height,
              contentHash: photo.contentHash,
            })),
            note: props.note,
          },
          new Map(Object.entries(transforms)),
          planner,
        )
        if (cancelledOrStale(epoch)) return
        setViews(planned)
      } catch (error) {
        if (cancelledOrStale(epoch)) return
        setPlanError(error instanceof Error ? error.message : String(error))
        setViews(null)
      }
    })()
    function cancelledOrStale(run: number): boolean {
      return run !== epochRef.current
    }
    return () => {
      epochRef.current += 1
    }
  }, [photosKey, props.note, transformsKey, styleId, tornDown, senderHandle, props.measurer, props.photos, transforms])

  const view = views !== null ? views[Math.min(planIndex, views.length - 1)] ?? null : null

  const currentFreeze: FreezeCurrent | null = useMemo(() => {
    if (view === null) return null
    return {
      branchId: view.branch,
      styleId,
      styleVersion: styleById(styleId)?.version ?? '',
      slots: view.slots.map((slot, index) => {
        const meta = props.photos.find((photo) => photo.contentId === slot.photoId)
        return {
          contentId: slot.photoId,
          ordinal: index,
          contentHash: meta?.contentHash ?? '',
          widthPx: meta?.width ?? 0,
          heightPx: meta?.height ?? 0,
          crop: { ...slot.crop },
        }
      }),
      note: props.note && props.note.trim() !== '' ? props.note : null,
      ownerId: props.ownerId,
      sessionGeneration: snap.generation,
      recipient:
        props.recipient === null
          ? null
          : {
              userId: props.recipient.userId,
              keyBundleId: props.recipient.keyBundleId,
              directoryVersion: props.recipient.directoryVersion,
            },
    }
  }, [view, styleId, props.note, props.photos, props.ownerId, props.recipient, snap.generation])

  const signature = useMemo(() => (currentFreeze === null ? null : JSON.stringify(currentFreeze)), [currentFreeze])

  // Explicit parent-visible invalidation: the holder's snapshot becomes
  // unusable on any listed input/context change (checked with the same
  // gate the freeze tests use).
  useEffect(() => {
    if (frozen !== null && currentFreeze !== null && freezeValid(frozen, currentFreeze).ok === false) {
      frozenRef.current = null
      setFrozen(null)
      notifyFrozen(null)
    }
  }, [frozen, currentFreeze])

  // Sensitive teardown on lock/owner change and on unmount: clear the
  // canvas, drop the frozen snapshot (parent notified), fence async.
  useEffect(() => {
    if (!tornDown) return
    epochRef.current += 1
    setReadySig(null)
    setFreezeError(null)
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (canvas && context) {
      context.setTransform(1, 0, 0, 1, 0, 0)
      context.clearRect(0, 0, canvas.width, canvas.height)
      canvas.dataset['painted'] = 'cleared'
    }
    if (frozenRef.current !== null) {
      frozenRef.current = null
      setFrozen(null)
      notifyFrozen(null)
    }
  }, [tornDown])

  useEffect(() => {
    if (views !== null && planIndex >= views.length) setPlanIndex(0)
  }, [views, planIndex])

  useEffect(() => {
    if (view !== null && activeSlot >= view.slots.length) setActiveSlot(0)
  }, [view, activeSlot])

  // Preview paint: real scene → real paint. Async font loads are fenced;
  // stale completions never touch DOM or canvas. A completed paint for
  // the current inputs arms the ready gate that Freeze requires.
  useEffect(() => {
    if (tornDown || view === null) return
    const epoch = ++epochRef.current
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (!canvas || !context) return
    let cancelled = false
    void (async () => {
      try {
        const style = styleById(styleId)
        if (!style) throw new Error(`unknown style ${styleId}`)
        if (senderHandle === null) throw new Error('no sender')
        const images = new Map<string, PaintImage>()
        for (const photo of props.photos) {
          images.set(photo.contentId, { source: photo.image, width: photo.width, height: photo.height })
        }
        const scene = creatorScene({
          view,
          styleId,
          senderHandle,
          noteText: props.note && props.note.trim() !== '' ? props.note : null,
          photos: new Map(props.photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
        })
        if (cancelled || epoch !== epochRef.current) return
        if (scene.note) {
          const measurement = props.measurer.measure({
            text: scene.note.text,
            family: scene.note.family,
            size: scene.note.size,
            weight: scene.note.weight,
            italic: scene.note.italic,
            line: scene.note.line,
            maxWidth: scene.note.region.width,
            maxHeight: scene.note.region.height,
          })
          const fits =
            !measurement.hasVisualOverflow &&
            measurement.width >= 1 &&
            measurement.height >= 1 &&
            measurement.width <= scene.note.region.width &&
            measurement.height <= scene.note.region.height
          if (cancelled || epoch !== epochRef.current) return
          setNoteFits(fits)
          if (!fits) {
            setReadySig(null)
            context.setTransform(1, 0, 0, 1, 0, 0)
            context.clearRect(0, 0, canvas.width, canvas.height)
            canvas.dataset['painted'] = 'refused-note'
            return
          }
        } else {
          setNoteFits(true)
        }
        const painted = paintScene(context, scene, images, null)
        if (cancelled || epoch !== epochRef.current) return
        setPaintError(null)
        canvas.dataset['painted'] = painted ? 'ready' : 'refused'
        if (signature !== null) setReadySig(signature)
      } catch (error) {
        if (cancelled || epoch !== epochRef.current) return
        setPaintError(error instanceof Error ? error.message : String(error))
        canvas.dataset['painted'] = 'refused'
      }
    })()
    return () => {
      cancelled = true
    }
  }, [view, props.photos, props.note, props.measurer, styleId, tornDown, senderHandle, signature])

  // Unmount: fence async work and release the parent snapshot.
  useEffect(() => {
    const epoch = epochRef
    const notify = notifyFrozen
    return () => {
      epoch.current += 1
      if (frozenRef.current !== null) {
        frozenRef.current = null
        notify(null)
      }
    }
  }, [])

  if (tornDown) {
    return (
      <div className="creator-composer" data-testid="creator-composer">
        <p data-testid="creator-locked">{t.locked}</p>
      </div>
    )
  }

  if (planError !== null) {
    return (
      <div className="creator-composer" data-testid="creator-composer">
        <p data-testid="creator-empty">{t.empty}</p>
        <p data-testid="creator-paint-error">{planError}</p>
      </div>
    )
  }

  if (views === null || view === null) {
    return (
      <div className="creator-composer" data-testid="creator-composer">
        <p data-testid="creator-preparing">{t.preparing}</p>
      </div>
    )
  }

  const activePhotoId = view.slots[activeSlot]?.photoId ?? null
  const activeTransform: CropTransform = view.slots[activeSlot]?.effective ?? IDENTITY_CROP

  const setActiveTransform = (next: CropTransform): void => {
    if (activePhotoId === null) return
    // Frozen control domain first: z∈[1,1.25], pan into (z−1)*50,
    // z=1 ⇒ exactly 0. Readout, stored state, and preview agree.
    const frozen = clampTransform(next.z, next.xPercent, next.yPercent) ?? IDENTITY_CROP
    setTransforms((prev) => ({ ...prev, [activePhotoId]: frozen }))
  }

  const freezeBlocked: { disabled: boolean; reason: string | null } = (() => {
    if (senderHandle === null) return { disabled: true, reason: t.noSender }
    if (props.musicSelected) return { disabled: true, reason: t.musicUnsupported }
    if (props.recipient === null) return { disabled: true, reason: t.noRecipient }
    if (view.note !== null && (props.note === null || props.note.trim() === '')) return { disabled: true, reason: t.noteMissing }
    if (!noteFits) return { disabled: true, reason: t.noteOverflow }
    if (readySig === null || readySig !== signature) return { disabled: true, reason: t.notReady }
    return { disabled: false, reason: null }
  })()

  const handleFreeze = (): void => {
    if (freezeBlocked.disabled || view === null) return
    // Re-check live owner/session context at click time: never freeze
    // from a stale render, and never emit a non-null callback torn down.
    const live = props.session.getSnapshot()
    const liveTornDown =
      live.phase === 'signed-out' || live.phase === 'authenticated' || (live.accountId !== null && live.accountId !== props.ownerId)
    if (liveTornDown) {
      dropIfStale()
      return
    }
    const style = styleById(styleId)
    const slots: CreatorSlotFreeze[] = view.slots.map((slot, index) => {
      const meta = props.photos.find((photo) => photo.contentId === slot.photoId)
      return {
        contentId: slot.photoId,
        ordinal: index,
        contentHash: meta?.contentHash ?? '',
        widthPx: meta?.width ?? 0,
        heightPx: meta?.height ?? 0,
        crop: { ...slot.crop },
      }
    })
    const result = freezeSelection({
      branchId: view.branch,
      styleId,
      styleVersion: style?.version ?? '',
      slots,
      note: props.note && props.note.trim() !== '' ? props.note : null,
      noteRegion: view.note === null ? null : { ...view.note.region },
      noteFits,
      musicSelected: props.musicSelected,
      ownerId: props.ownerId,
      sessionGeneration: live.generation,
      recipient:
        props.recipient === null
          ? null
          : {
              userId: props.recipient.userId,
              keyBundleId: props.recipient.keyBundleId,
              directoryVersion: props.recipient.directoryVersion,
            },
    })
    if (!result.ok) {
      setFreezeError(result.reason)
      setFrozen(null)
      frozenRef.current = null
      notifyFrozen(null)
      return
    }
    setFreezeError(null)
    setFrozen(result.frozen)
    frozenRef.current = result.frozen
    notifyFrozen(result.frozen)
  }

  function dropIfStale(): void {
    if (frozenRef.current !== null) {
      frozenRef.current = null
      setFrozen(null)
      notifyFrozen(null)
    }
  }

  return (
    <div className="creator-composer" data-testid="creator-composer">
      <div className="creator-pager">
        <button type="button" data-testid="creator-prev" aria-label={t.prev} disabled={planIndex === 0} onClick={() => setPlanIndex((i) => Math.max(0, i - 1))}>
          ‹
        </button>
        <span data-testid="creator-count">{t.count(planIndex, views.length)}</span>
        <button
          type="button"
          data-testid="creator-next"
          aria-label={t.next}
          disabled={planIndex === views.length - 1}
          onClick={() => setPlanIndex((i) => Math.min(views.length - 1, i + 1))}
        >
          ›
        </button>
      </div>
      <p className="creator-branch" data-testid="creator-branch">
        {t.branch(view.branch)}
      </p>
      <canvas ref={canvasRef} data-testid="creator-canvas" role="img" aria-label={t.preview} width={360} height={640} />
      <div className="creator-slots" role="group" aria-label={t.preview}>
        {view.slots.map((slot, index) => (
          <button
            key={slot.photoId}
            type="button"
            data-testid={`creator-slot-${index}`}
            aria-pressed={index === activeSlot}
            aria-label={t.slot(index)}
            onClick={() => setActiveSlot(index)}
          >
            {index + 1}
          </button>
        ))}
      </div>
      <div className="creator-crop">
        <button type="button" data-testid="creator-zoom-out" aria-label={t.zoomOut} onClick={() => setActiveTransform({ ...activeTransform, z: activeTransform.z - 0.05 })}>
          −
        </button>
        <button type="button" data-testid="creator-zoom-in" aria-label={t.zoomIn} onClick={() => setActiveTransform({ ...activeTransform, z: activeTransform.z + 0.05 })}>
          +
        </button>
        <button type="button" data-testid="creator-pan-left" aria-label={t.panLeft} onClick={() => setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent - 1, yPercent: activeTransform.yPercent })}>
          ←
        </button>
        <button type="button" data-testid="creator-pan-right" aria-label={t.panRight} onClick={() => setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent + 1, yPercent: activeTransform.yPercent })}>
          →
        </button>
        <button type="button" data-testid="creator-pan-up" aria-label={t.panUp} onClick={() => setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent, yPercent: activeTransform.yPercent - 1 })}>
          ↑
        </button>
        <button type="button" data-testid="creator-pan-down" aria-label={t.panDown} onClick={() => setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent, yPercent: activeTransform.yPercent + 1 })}>
          ↓
        </button>
        <button
          type="button"
          data-testid="creator-reset"
          onClick={() => {
            if (activePhotoId === null) return
            setTransforms((prev) => {
              const next = { ...prev }
              delete next[activePhotoId]
              return next
            })
          }}
        >
          {t.reset}
        </button>
        <span data-testid="creator-transform-readout">{t.transform(activeTransform.z, activeTransform.xPercent, activeTransform.yPercent)}</span>
      </div>
      {freezeBlocked.reason !== null ? <p data-testid="creator-freeze-blocked">{freezeBlocked.reason}</p> : null}
      <button type="button" data-testid="creator-freeze" disabled={freezeBlocked.disabled} onClick={handleFreeze}>
        {t.freeze}
      </button>
      {frozen !== null ? (
        <p data-testid="creator-frozen">{t.frozen(frozen.branchId)}</p>
      ) : freezeError !== null ? (
        <p data-testid="creator-freeze-error">{freezeError}</p>
      ) : null}
      {paintError !== null ? <p data-testid="creator-paint-error">{paintError}</p> : null}
    </div>
  )
}
