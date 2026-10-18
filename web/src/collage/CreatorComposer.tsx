import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { panDelta, type CropTransform } from './crop'
import {
  creatorScene,
  previewCandidates,
  type CreatorCandidateView,
} from './creatorPreview'
import { freezeSelection, type CreatorFreeze, type CreatorSlotFreeze } from '../create/creatorFreeze'
import { paintScene, type PaintImage } from './paint'
import type { TextMeasurer } from './prepare'
import { styleById } from './styles'
import type { SessionStore } from '../session/session'
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
  ownerId: string
  session: SessionStore
  lang: CreatorLang
  measurer: TextMeasurer
  onFreeze?: (frozen: CreatorFreeze) => void
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
  frozen: (branch: string) => string
  musicUnsupported: string
  noRecipient: string
  noteOverflow: string
  noteMissing: string
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
    frozen: (branch) => `Frozen ${branch} — edit to invalidate`,
    musicUnsupported: 'Music is not yet supported for this composition — Freeze is disabled.',
    noRecipient: 'Confirm a recipient to freeze.',
    noteOverflow: 'Note does not fit its region — Freeze is disabled.',
    noteMissing: 'A sealed note region requires note text — Freeze is disabled.',
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
    frozen: (branch) => `Зафиксировано: ${branch} — изменение сбросит фиксацию`,
    musicUnsupported: 'Музыка пока не поддерживается в этой композиции — фиксация отключена.',
    noRecipient: 'Подтвердите получателя для фиксации.',
    noteOverflow: 'Текст не помещается в область — фиксация отключена.',
    noteMissing: 'Для области заметки нужен текст — фиксация отключена.',
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
    frozen: (branch) => `Зафіксовано: ${branch} — зміна скине фіксацію`,
    musicUnsupported: 'Музика поки не підтримується в цій композиції — фіксація вимкнена.',
    noRecipient: 'Підтвердіть отримувача для фіксації.',
    noteOverflow: 'Текст не вміщується в область — фіксація вимкнена.',
    noteMissing: 'Для області нотатки потрібен текст — фіксація вимкнена.',
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
  const [frozen, setFrozen] = useState<CreatorFreeze | null>(null)
  const [freezeError, setFreezeError] = useState<string | null>(null)
  const [paintError, setPaintError] = useState<string | null>(null)
  const [noteFits, setNoteFits] = useState(true)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const epochRef = useRef(0)
  const frozenSigRef = useRef<string | null>(null)

  const locked = snap.phase === 'signed-out' || snap.phase === 'authenticated'
  const ownerMismatch = snap.accountId !== null && snap.accountId !== props.ownerId
  const tornDown = locked || ownerMismatch

  const candidates: CreatorCandidateView[] | null = useMemo(() => {
    try {
      return previewCandidates(
        {
          id: 'creator',
          sender: 'creator',
          photos: props.photos.map((photo) => ({
            contentId: photo.contentId,
            width: photo.width,
            height: photo.height,
            contentHash: photo.contentHash,
          })),
          note: props.note,
        },
        new Map(Object.entries(transforms)),
      )
    } catch {
      return null
    }
  }, [props.photos, props.note, transforms])

  const view = candidates !== null ? candidates[Math.min(planIndex, candidates.length - 1)] ?? null : null

  const signature = useMemo(() => {
    if (view === null) return null
    return JSON.stringify({
      branch: view.branch,
      style: styleId,
      slots: view.slots.map((slot) => [slot.photoId, slot.sourcePx, slot.crop]),
      note: props.note,
      music: props.musicSelected,
      recipient:
        props.recipient === null
          ? null
          : [props.recipient.userId, props.recipient.keyBundleId, props.recipient.directoryVersion],
      owner: props.ownerId,
      generation: snap.generation,
    })
  }, [view, styleId, props.note, props.musicSelected, props.recipient, props.ownerId, snap.generation])

  // Explicit invalidation: any listed input/context change drops the freeze.
  useEffect(() => {
    if (frozen !== null && signature !== frozenSigRef.current) {
      frozenSigRef.current = null
      setFrozen(null)
    }
  }, [frozen, signature])

  // Sensitive teardown on lock/owner change and on unmount: clear the
  // canvas, drop the frozen snapshot, fence every async continuation.
  useEffect(() => {
    if (!tornDown) return
    epochRef.current += 1
    frozenSigRef.current = null
    setFrozen(null)
    setFreezeError(null)
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (canvas && context) {
      context.setTransform(1, 0, 0, 1, 0, 0)
      context.clearRect(0, 0, canvas.width, canvas.height)
      canvas.dataset['painted'] = 'cleared'
    }
  }, [tornDown])

  useEffect(() => {
    if (planIndex >= (candidates?.length ?? 1)) setPlanIndex(0)
  }, [candidates, planIndex])

  useEffect(() => {
    if (activeSlot >= (view?.slots.length ?? 1)) setActiveSlot(0)
  }, [view, activeSlot])

  // Preview paint: pure adapter → creator scene → real paintScene.
  // Async font loads are fenced by the paint epoch; stale completions
  // never touch DOM or canvas.
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
        const images = new Map<string, PaintImage>()
        for (const photo of props.photos) {
          images.set(photo.contentId, { source: photo.image, width: photo.width, height: photo.height })
        }
        const scene = creatorScene({
          view,
          styleId,
          senderHandle: props.recipient?.handle ?? 'creator',
          noteText: props.note && props.note.trim() !== '' ? props.note : null,
          photos: new Map(props.photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
        })
        if (cancelled || epochRef.current !== epoch) return
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
          if (cancelled || epochRef.current !== epoch) return
          setNoteFits(fits)
          if (!fits) {
            canvas.dataset['painted'] = 'refused-note'
            return
          }
        } else {
          setNoteFits(true)
        }
        const faces = new Set<string>()
        for (const mark of scene.provenance) faces.add(`${mark.weight} ${mark.size}px ${mark.family}`)
        await Promise.all([...faces].map((face) => document.fonts.load(face)))
        if (cancelled || epochRef.current !== epoch) return
        const painted = paintScene(context, scene, images, null)
        if (cancelled || epochRef.current !== epoch) return
        setPaintError(null)
        canvas.dataset['painted'] = painted ? 'ready' : 'refused'
      } catch (error) {
        if (cancelled || epochRef.current !== epoch) return
        setPaintError(error instanceof Error ? error.message : String(error))
        canvas.dataset['painted'] = 'refused'
      }
    })()
    return () => {
      cancelled = true
    }
  }, [view, props.photos, props.note, props.measurer, props.recipient, styleId, tornDown])

  // Unmount: fence async work. Caller owns image sources; we only drop refs.
  useEffect(() => {
    const epoch = epochRef
    return () => {
      epoch.current += 1
    }
  }, [])

  if (tornDown) {
    return (
      <div className="creator-composer" data-testid="creator-composer">
        <p data-testid="creator-locked">{t.locked}</p>
      </div>
    )
  }

  if (candidates === null || view === null) {
    return (
      <div className="creator-composer" data-testid="creator-composer">
        <p data-testid="creator-empty">{t.empty}</p>
        {paintError !== null ? <p data-testid="creator-paint-error">{paintError}</p> : null}
      </div>
    )
  }

  const activePhotoId = view.slots[activeSlot]?.photoId ?? null
  const activeTransform = (activePhotoId !== null ? transforms[activePhotoId] : undefined) ?? IDENTITY_CROP

  const setActiveTransform = (next: CropTransform): void => {
    if (activePhotoId === null) return
    const z = Math.min(1.25, Math.max(1, next.z))
    const clamped = { z, xPercent: Math.min(60, Math.max(-60, next.xPercent)), yPercent: Math.min(60, Math.max(-60, next.yPercent)) }
    setTransforms((prev) => ({ ...prev, [activePhotoId]: clamped }))
  }

  const freezeState = (() => {
    if (props.musicSelected) return { disabled: true, reason: t.musicUnsupported } as const
    if (props.recipient === null) return { disabled: true, reason: t.noRecipient } as const
    if (view.note !== null && (props.note === null || props.note.trim() === '')) return { disabled: true, reason: t.noteMissing } as const
    if (!noteFits) return { disabled: true, reason: t.noteOverflow } as const
    return { disabled: false, reason: null } as const
  })()

  const handleFreeze = (): void => {
    if (freezeState.disabled || view === null) {
      setFreezeError(freezeState.reason)
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
      sessionGeneration: snap.generation,
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
      frozenSigRef.current = null
      return
    }
    setFreezeError(null)
    setFrozen(result.frozen)
    frozenSigRef.current = signature
    props.onFreeze?.(result.frozen)
  }

  return (
    <div className="creator-composer" data-testid="creator-composer">
      <div className="creator-pager">
        <button type="button" data-testid="creator-prev" aria-label={t.prev} disabled={planIndex === 0} onClick={() => setPlanIndex((i) => Math.max(0, i - 1))}>
          ‹
        </button>
        <span data-testid="creator-count">{t.count(planIndex, candidates.length)}</span>
        <button
          type="button"
          data-testid="creator-next"
          aria-label={t.next}
          disabled={planIndex === candidates.length - 1}
          onClick={() => setPlanIndex((i) => Math.min(candidates.length - 1, i + 1))}
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
        <button type="button" data-testid="creator-pan-left" aria-label={t.panLeft} onClick={() => {
          const delta = panDelta('left')
          setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent + delta.xPercent, yPercent: activeTransform.yPercent + delta.yPercent })
        }}>
          ←
        </button>
        <button type="button" data-testid="creator-pan-right" aria-label={t.panRight} onClick={() => {
          const delta = panDelta('right')
          setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent + delta.xPercent, yPercent: activeTransform.yPercent + delta.yPercent })
        }}>
          →
        </button>
        <button type="button" data-testid="creator-pan-up" aria-label={t.panUp} onClick={() => {
          const delta = panDelta('up')
          setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent + delta.xPercent, yPercent: activeTransform.yPercent + delta.yPercent })
        }}>
          ↑
        </button>
        <button type="button" data-testid="creator-pan-down" aria-label={t.panDown} onClick={() => {
          const delta = panDelta('down')
          setActiveTransform({ ...activeTransform, xPercent: activeTransform.xPercent + delta.xPercent, yPercent: activeTransform.yPercent + delta.yPercent })
        }}>
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
      {freezeState.reason !== null ? <p data-testid="creator-freeze-blocked">{freezeState.reason}</p> : null}
      <button type="button" data-testid="creator-freeze" disabled={freezeState.disabled} onClick={handleFreeze}>
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
