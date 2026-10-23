import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { clampTransform, type CropTransform } from './crop'
import {
  creatorScene,
  plannerFromTextMeasurer,
  previewCandidates,
  type CreatorCandidateView,
} from './creatorPreview'
import { freezeSelection, freezeValid, type CreatorFreeze, type CreatorSlotFreeze, type FreezeCurrent } from '../create/creatorFreeze'
import { captureFrozenRenderedPlan, type FrozenRenderedPlan } from './frozenRenderedPlan'
import { layoutCreatorNote, paintCreatorNote, type CreatorNoteFace, type CreatorNoteLayout } from './creatorNote'
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
  /** Optional journey label; choosing still captures the exact rendered plan. */
  chooseLabel?: string
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
  adjustTitle: string
  adjustHint: string
  transform: (z: number, x: number, y: number) => string
}

const copy: Record<CreatorLang, Copy> = {
  en: {
    prev: 'Previous layout',
    next: 'Next layout',
    count: (index, total) => `Layout ${index + 1} of ${total}`,
    branch: (branch) => `Branch ${branch}`,
    slot: (index) => `Photo ${index + 1}`,
    zoomIn: 'Zoom in',
    zoomOut: 'Zoom out',
    panUp: 'Move up',
    panDown: 'Move down',
    panLeft: 'Move left',
    panRight: 'Move right',
    reset: 'Reset crop',
    freeze: 'Choose this layout',
    preparing: 'Preparing preview…',
    frozen: () => 'Layout selected. Editing it will clear the selection.',
    musicUnsupported: 'Music cannot be sent with this composition yet. Remove the song to choose a layout.',
    noRecipient: 'Confirm a recipient to choose a layout.',
    noSender: 'Confirm a sender to compose.',
    noteOverflow: 'The note does not fit this layout. Shorten it or choose another.',
    noteMissing: 'Add a note for this layout, or choose one without text.',
    notReady: 'Wait for the preview before choosing this layout.',
    locked: 'Locked — preview cleared.',
    empty: 'Add 3–5 photos to compose.',
    preview: 'Postcard composition preview',
    adjustTitle: 'a little adjustment', adjustHint: 'Choose a photo, then adjust its crop. Your original stays unchanged.',
    transform: (z, x, y) => `zoom ${z.toFixed(2)} pan ${x.toFixed(1)} ${y.toFixed(1)}`,
  },
  ru: {
    prev: 'Предыдущий вариант',
    next: 'Следующий вариант',
    count: (index, total) => `Вариант ${index + 1} из ${total}`,
    branch: (branch) => `Ветка ${branch}`,
    slot: (index) => `Фото ${index + 1}`,
    zoomIn: 'Приблизить',
    zoomOut: 'Отдалить',
    panUp: 'Вверх',
    panDown: 'Вниз',
    panLeft: 'Влево',
    panRight: 'Вправо',
    reset: 'Сбросить кадрирование',
    freeze: 'Выбрать эту композицию',
    preparing: 'Готовим предпросмотр…',
    frozen: () => 'Композиция выбрана. После изменения её нужно выбрать снова.',
    musicUnsupported: 'Музыку пока нельзя отправить с этой композицией. Уберите песню, чтобы выбрать вариант.',
    noRecipient: 'Подтвердите получателя, чтобы выбрать композицию.',
    noSender: 'Подтвердите отправителя для композиции.',
    noteOverflow: 'Текст не помещается в эту композицию. Сократите его или выберите другую.',
    noteMissing: 'Добавьте заметку для этой композиции или выберите вариант без текста.',
    notReady: 'Дождитесь предпросмотра, прежде чем выбирать композицию.',
    locked: 'Заблокировано — предпросмотр очищен.',
    empty: 'Добавьте 3–5 фото для композиции.',
    preview: 'Предпросмотр варианта',
    adjustTitle: 'немного поправить', adjustHint: 'Выберите фото и поправьте кадрирование. Оригинал не изменится.',
    transform: (z, x, y) => `зум ${z.toFixed(2)} сдвиг ${x.toFixed(1)} ${y.toFixed(1)}`,
  },
  uk: {
    prev: 'Попередній варіант',
    next: 'Наступний варіант',
    count: (index, total) => `Варіант ${index + 1} з ${total}`,
    branch: (branch) => `Гілка ${branch}`,
    slot: (index) => `Фото ${index + 1}`,
    zoomIn: 'Наблизити',
    zoomOut: 'Віддалити',
    panUp: 'Вгору',
    panDown: 'Вниз',
    panLeft: 'Вліво',
    panRight: 'Вправо',
    reset: 'Скинути кадрування',
    freeze: 'Вибрати цю композицію',
    preparing: 'Готуємо попередній перегляд…',
    frozen: () => 'Композицію вибрано. Після зміни її потрібно вибрати знову.',
    musicUnsupported: 'Музику поки не можна надіслати з цією композицією. Приберіть пісню, щоб вибрати варіант.',
    noRecipient: 'Підтвердіть одержувача, щоб вибрати композицію.',
    noSender: 'Підтвердіть відправника для композиції.',
    noteOverflow: 'Текст не вміщується в цю композицію. Скоротіть його або виберіть іншу.',
    noteMissing: 'Додайте нотатку для цієї композиції або виберіть варіант без тексту.',
    notReady: 'Дочекайтеся попереднього перегляду, перш ніж вибирати композицію.',
    locked: 'Заблоковано — попередній перегляд очищено.',
    empty: 'Додайте 3–5 фото для композиції.',
    preview: 'Попередній перегляд варіанта',
    adjustTitle: 'трохи поправити', adjustHint: 'Виберіть фото й поправте кадрування. Оригінал не зміниться.',
    transform: (z, x, y) => `зум ${z.toFixed(2)} зсув ${x.toFixed(1)} ${y.toFixed(1)}`,
  },
}

const IDENTITY_CROP: CropTransform = { z: 1, xPercent: 0, yPercent: 0 }

/**
 * T247 R2: a stable local id per measurer object. The rendered binding key is
 * otherwise blind to the measurer: two measurers can produce the SAME plan
 * region/state/branch/slots yet different wrapped note rows (same line count),
 * which would leave a held freeze valid while the canvas shows new rows.
 * Binding the key to the measurer's identity (local UI revision only — never a
 * wire/epoch/protocol value) makes replacing the measurer void the held render.
 * Production memoizes one measurer, so this id is stable across renders.
 */
const measurerIds = new WeakMap<TextMeasurer, number>()
let measurerIdSeq = 0
function measurerIdOf(measurer: TextMeasurer): number {
  const existing = measurerIds.get(measurer)
  if (existing !== undefined) return existing
  const next = ++measurerIdSeq
  measurerIds.set(measurer, next)
  return next
}

export function CreatorComposer(props: CreatorComposerProps): React.JSX.Element {
  const t = copy[props.lang]
  const styleId = props.styleId ?? 'press'
  const snap = useSyncExternalStore(props.session.subscribe, props.session.getSnapshot)
  const [planIndex, setPlanIndex] = useState(0)
  const [activeSlot, setActiveSlot] = useState(0)
  const [transforms, setTransforms] = useState<Record<string, CropTransform>>({})
  const [fontsReady, setFontsReady] = useState(false)
  const [fontsError, setFontsError] = useState<string | null>(null)
  const [frozen, setFrozen] = useState<CreatorFreeze | null>(null)
  const [freezeError, setFreezeError] = useState<string | null>(null)
  const [paintError, setPaintError] = useState<string | null>(null)
  const [noteFits, setNoteFits] = useState(true)
  const [readySig, setReadySig] = useState<string | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const fontsEpochRef = useRef(0)
  const frozenRef = useRef<CreatorFreeze | null>(null)
  /**
   * T247: the exact plan captured for the last successful paint, paired with
   * the signature it was painted for. Freeze consumes THIS — it never rebuilds
   * a scene from changed inputs at click time.
   */
  const renderedRef = useRef<{ key: string; plan: FrozenRenderedPlan } | null>(null)
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

  const inputKey = useMemo(() => {
    const photosKey = JSON.stringify(props.photos.map((photo) => [photo.contentId, photo.width, photo.height, photo.contentHash]))
    const transformsKey = JSON.stringify(transforms)
    return JSON.stringify([photosKey, props.note, transformsKey, styleId, senderHandle, props.musicSelected])
  }, [props.photos, props.note, transforms, styleId, senderHandle, props.musicSelected])

  // Fonts gate: the ONLY async step. Required bundled faces load first;
  // its own epoch, never shared with paint (paint is synchronous after
  // this gate). A held gate keeps the composer in an honest preparing
  // state — never a stale paint.
  useEffect(() => {
    if (tornDown) {
      setFontsReady(false)
      return
    }
    const epoch = ++fontsEpochRef.current
    setFontsReady(false)
    setFontsError(null)
    let cancelled = false
    void (async () => {
      try {
        const style = styleById(styleId)
        if (!style) throw new Error(`unknown style ${styleId}`)
        await Promise.all([
          document.fonts.load('400 27px StudySerif'),
          document.fonts.load(`${style.note.weight} ${style.note.size}px ${style.note.font}`),
          document.fonts.load('400 64px StudySerif'),
          document.fonts.load('italic 400 20px StudySerif'),
        ])
        if (cancelled || epoch !== fontsEpochRef.current) return
        setFontsReady(true)
      } catch (error) {
        if (cancelled || epoch !== fontsEpochRef.current) return
        setFontsError(error instanceof Error ? error.message : String(error))
      }
    })()
    return () => {
      cancelled = true
      fontsEpochRef.current += 1
    }
  }, [styleId, tornDown])

  // Synchronous planning bound to the exact current inputs: after the
  // fonts gate, every render derives views deterministically from the
  // live props. No stale geometry can survive an input change, and no
  // paint epoch can cancel a replan.
  const planned: { views: CreatorCandidateView[] } | { error: string } | null = useMemo(() => {
    if (tornDown || !fontsReady) return null
    try {
      const noteFace = styleById(styleId)?.note
      const planner: Grammar5Measure = plannerFromTextMeasurer(props.measurer, 640, 640, noteFace)
      const completed = previewCandidates(
        {
          id: 'creator',
          sender: senderHandle ?? 'creator',
          photos: props.photos.map((photo) => ({
            contentId: photo.contentId,
            width: photo.width,
            height: photo.height,
            contentHash: photo.contentHash,
          })),
          note: props.note && props.note.trim() !== '' ? props.note : null,
        },
        new Map(Object.entries(transforms)),
        planner,
      )
      return { views: completed }
    } catch (error) {
      return { error: error instanceof Error ? error.message : String(error) }
    }
  }, [tornDown, fontsReady, inputKey, props.measurer, props.photos, props.note, transforms, senderHandle])

  const views = planned !== null && 'views' in planned ? planned.views : null
  const planError = fontsError ?? (planned !== null && 'error' in planned ? planned.error : null)
  const view = views !== null ? views[Math.min(planIndex, views.length - 1)] ?? null : null

  // T247 binding key: the full rendered geometry + choice/context that was
  // painted. The whole `view` is included, so a measurer/font change (which
  // changes the note region), a crop edit (effective/sourcePx/crop), a
  // destination/provenance change, or any owner/recipient/session/music/note
  // change all produce a new key and void a previously painted render.
  const renderedKey = useMemo(() => {
    if (view === null || senderHandle === null) return null
    const style = styleById(styleId)
    return JSON.stringify({
      measurer: measurerIdOf(props.measurer),
      view,
      styleId,
      styleVersion: style?.version ?? '',
      senderHandle,
      note: props.note && props.note.trim() !== '' ? props.note : null,
      ownerId: props.ownerId,
      generation: snap.generation,
      recipient:
        props.recipient === null
          ? null
          : [props.recipient.userId, props.recipient.keyBundleId, props.recipient.directoryVersion],
      musicSelected: props.musicSelected,
    })
  }, [props.measurer, view, styleId, senderHandle, props.note, props.ownerId, snap.generation, props.recipient, props.musicSelected])

  const currentFreeze: FreezeCurrent | null = useMemo(() => {
    if (view === null) return null
    return {
      branchId: view.branch,
      styleId,
      styleVersion: styleById(styleId)?.version ?? '',
      senderHandle,
      musicSelected: props.musicSelected,
      renderedKey,
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
  }, [view, styleId, senderHandle, props.musicSelected, renderedKey, props.note, props.photos, props.ownerId, props.recipient, snap.generation])

  const signature = renderedKey

  // Explicit parent-visible invalidation: the holder's snapshot becomes
  // unusable on any listed input/context change (checked with the same
  // gate the freeze tests use). A null/refused plan also drops a held
  // snapshot — missing geometry never preserves an old freeze.
  useEffect(() => {
    if (frozen !== null && (currentFreeze === null || freezeValid(frozen, currentFreeze).ok === false)) {
      frozenRef.current = null
      setFrozen(null)
      notifyFrozen(null)
    }
  }, [frozen, currentFreeze])

  // Sensitive teardown on lock/owner change and on unmount: clear the
  // canvas, drop the frozen snapshot (parent notified). Paint itself is
  // synchronous after the fonts gate, so no paint epoch is needed; the
  // fonts gate below owns the only async step with its own epoch.
  useEffect(() => {
    if (!tornDown) return
    renderedRef.current = null
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

  // Preview paint: real scene → real paint, fully synchronous after
  // the fonts gate. A painted `ready` for the current inputs arms the
  // ready gate that Freeze requires; refused/failed paint clears it.
  useEffect(() => {
    if (tornDown || view === null) {
      if (tornDown) {
        const canvas = canvasRef.current
        const context = canvas?.getContext('2d')
        if (canvas && context) {
          context.setTransform(1, 0, 0, 1, 0, 0)
          context.clearRect(0, 0, canvas.width, canvas.height)
          canvas.dataset['painted'] = 'cleared'
        }
      }
      return
    }
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    if (!canvas || !context) return
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
      // One shared wrapped layout drives BOTH the region (via the same
      // wrap in `plannerFromTextMeasurer`) and the painted rows. The base
      // scene is drawn WITHOUT its single-line note and the wrapper draws
      // the real rows on top, so a measured 3/7-line note is fully visible.
      let noteLayout: CreatorNoteLayout | null = null
      let noteFace: CreatorNoteFace | null = null
      if (scene.note) {
        const face: CreatorNoteFace = {
          font: scene.note.family,
          weight: scene.note.weight,
          italic: scene.note.italic,
          size: scene.note.size,
          line: scene.note.line,
        }
        const measureLineWidth = (lineText: string): number =>
          props.measurer.measure({
            text: lineText,
            family: face.font,
            size: face.size,
            weight: face.weight,
            italic: face.italic,
            line: face.line,
            maxWidth: Number.POSITIVE_INFINITY,
            maxHeight: Number.POSITIVE_INFINITY,
            softWrap: false,
          }).width
        const layout = layoutCreatorNote(scene.note.text, face, scene.note.region, measureLineWidth)
        noteLayout = layout
        noteFace = face
        setNoteFits(!layout.overflow)
        if (layout.overflow) {
          renderedRef.current = null
          setReadySig(null)
          context.setTransform(1, 0, 0, 1, 0, 0)
          context.clearRect(0, 0, canvas.width, canvas.height)
          canvas.dataset['painted'] = 'refused-note'
          return
        }
      } else {
        setNoteFits(true)
      }
      const painted = paintScene(context, { ...scene, note: null }, images, null)
      if (painted && scene.note !== null && noteLayout !== null && noteFace !== null) {
        paintCreatorNote(context, noteLayout, noteFace, scene.note.region, scene.note.color)
      }
      setPaintError(null)
      canvas.dataset['painted'] = painted ? 'ready' : 'refused'
      // T247: only a completed paint of the current inputs captures a plan.
      // Freeze later consumes exactly this, never a re-built scene.
      if (painted && signature !== null) {
        try {
          const plan = captureFrozenRenderedPlan({
            scene,
            noteLayout,
            branchId: view.branch,
            styleId,
            styleVersion: style.version,
          })
          renderedRef.current = { key: signature, plan }
          setReadySig(signature)
        } catch (captureError) {
          renderedRef.current = null
          setPaintError(captureError instanceof Error ? captureError.message : String(captureError))
          canvas.dataset['painted'] = 'refused'
          setReadySig(null)
        }
      } else {
        renderedRef.current = null
        setReadySig(null)
      }
    } catch (error) {
      setPaintError(error instanceof Error ? error.message : String(error))
      canvas.dataset['painted'] = 'refused'
      renderedRef.current = null
      setReadySig(null)
    }
  }, [view, props.photos, props.note, props.measurer, styleId, tornDown, senderHandle, signature])

  // Unmount: release the parent snapshot. Paint is synchronous, so no
  // async fence is needed here; the fonts gate owns its own epoch.
  useEffect(() => {
    const notify = notifyFrozen
    return () => {
      renderedRef.current = null
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

  // Plain callback (NOT a hook): it must stay callable after the
  // conditional returns below. Functional update so rapid taps
  // accumulate instead of clobbering each other on a stale closure.
  const setStoredTransform = (next: (current: CropTransform) => CropTransform): void => {
    if (activePhotoId === null) return
    setTransforms((prev) => {
      const cur = prev[activePhotoId] ?? IDENTITY_CROP
      const wanted = next(cur)
      // Frozen control domain first: z∈[1,1.25], pan into (z−1)*50,
      // z=1 ⇒ exactly 0.
      return { ...prev, [activePhotoId]: clampTransform(wanted.z, wanted.xPercent, wanted.yPercent) ?? IDENTITY_CROP }
    })
  }

  const freezeBlocked: { disabled: boolean; reason: string | null } = (() => {
    if (senderHandle === null) return { disabled: true, reason: t.noSender }
    if (props.musicSelected) return { disabled: true, reason: t.musicUnsupported }
    if (props.recipient === null) return { disabled: true, reason: t.noRecipient }
    if (view.note !== null && (props.note === null || props.note.trim() === '')) return { disabled: true, reason: t.noteMissing }
    if (!noteFits) return { disabled: true, reason: t.noteOverflow }
    if (paintError !== null) return { disabled: true, reason: t.notReady }
    if (readySig === null || readySig !== signature) return { disabled: true, reason: t.notReady }
    return { disabled: false, reason: null }
  })()

  const handleFreeze = (): void => {
    if (freezeBlocked.disabled || view === null || senderHandle === null) return
    // T247: freeze captures the EXACT plan painted for the current binding
    // (never a re-built scene). Missing/stale captured render is an honest
    // refusal, never a silently absent note or geometry.
    const prepared = renderedRef.current
    if (renderedKey === null || prepared === null || prepared.key !== renderedKey) {
      setFreezeError(t.notReady)
      dropIfStale()
      return
    }
    // Re-check live owner/session context at click time: never freeze
    // from a stale render, and never emit a non-null callback torn down.
    const live = props.session.getSnapshot()
    const liveTornDown =
      live.phase === 'signed-out' || live.phase === 'authenticated' || (live.accountId !== null && live.accountId !== props.ownerId)
    // Never stamp a newer generation onto a DTO built from an older render:
    // require the live generation to match the prepared signature's. A
    // same-owner lock->unlock before React flushes bumps the generation, so
    // this refuses instead of emitting a non-null snapshot for a stale view.
    if (liveTornDown || live.generation !== snap.generation) {
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
      senderHandle,
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
      renderedKey: prepared.key,
      renderedPlan: prepared.plan,
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
      <div className="creator-preview">
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
      <p className="creator-branch visually-hidden" aria-hidden="true" data-testid="creator-branch">
        {t.branch(view.branch)}
      </p>
      <canvas ref={canvasRef} data-testid="creator-canvas" role="img" aria-label={t.preview} width={360} height={640} />
      </div>
      <div className="creator-controls">
      <details className="creator-adjust">
      <summary>{t.adjustTitle}</summary><p>{t.adjustHint}</p>
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
        <button type="button" data-testid="creator-zoom-out" aria-label={t.zoomOut} onClick={() => setStoredTransform((current) => ({ ...current, z: current.z - 0.05 }))}>
          −
        </button>
        <button type="button" data-testid="creator-zoom-in" aria-label={t.zoomIn} onClick={() => setStoredTransform((current) => ({ ...current, z: current.z + 0.05 }))}>
          +
        </button>
        <button type="button" data-testid="creator-pan-left" aria-label={t.panLeft} onClick={() => setStoredTransform((current) => ({ ...current, xPercent: current.xPercent - 1 }))}>
          ←
        </button>
        <button type="button" data-testid="creator-pan-right" aria-label={t.panRight} onClick={() => setStoredTransform((current) => ({ ...current, xPercent: current.xPercent + 1 }))}>
          →
        </button>
        <button type="button" data-testid="creator-pan-up" aria-label={t.panUp} onClick={() => setStoredTransform((current) => ({ ...current, yPercent: current.yPercent - 1 }))}>
          ↑
        </button>
        <button type="button" data-testid="creator-pan-down" aria-label={t.panDown} onClick={() => setStoredTransform((current) => ({ ...current, yPercent: current.yPercent + 1 }))}>
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
      </details>
      {freezeBlocked.reason !== null ? <p data-testid="creator-freeze-blocked">{freezeBlocked.reason}</p> : null}
      <button type="button" data-testid="creator-freeze" disabled={freezeBlocked.disabled} onClick={handleFreeze}>
        {props.chooseLabel ?? t.freeze}
      </button>
      {frozen !== null ? (
        <p data-testid="creator-frozen">{t.frozen(frozen.branchId)}</p>
      ) : freezeError !== null ? (
        <p data-testid="creator-freeze-error">{freezeError}</p>
      ) : null}
      {paintError !== null ? <p data-testid="creator-paint-error">{paintError}</p> : null}
      </div>
    </div>
  )
}
