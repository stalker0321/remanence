import { useEffect, useRef, useState } from 'react'
import type { MessageKey } from '../i18n/catalog'
import { exportComposition } from './exportComposition'
import { paintScene, type PaintImage } from './paint'
import type { HeldSheet } from './memory'
import type { Scene } from './prepare'

export interface StagePorts {
  loadImage(url: string): Promise<PaintImage>
  loadFaces(scene: Scene): Promise<void>
  encode(canvas: HTMLCanvasElement): Promise<Blob | null>
}

type PaintState = 'loading' | 'ready' | 'failed'

/**
 * The admitted sheet. The bitmap is the 360×640 composition.
 * Export saves that bitmap, and the photo viewer pages the original frames.
 */
export function CollageStage({
  held,
  t,
  onClose,
  ports,
}: {
  held: HeldSheet
  t: (key: MessageKey) => string
  onClose: () => void
  ports?: Partial<StagePorts>
}) {
  const loadImagePort = ports?.loadImage ?? loadImage
  const loadFacesPort = ports?.loadFaces ?? loadFaces
  const encodePort = ports?.encode ?? encodePng
  const canvas = useRef<HTMLCanvasElement>(null)
  const closeViewer = useRef<HTMLButtonElement>(null)
  const epoch = useRef(0)
  const exportUrls = useRef(new Set<string>())
  const [photo, setPhoto] = useState<number | null>(null)
  const [paintState, setPaintState] = useState<PaintState>('loading')
  const [exporting, setExporting] = useState(false)
  const [exportFailed, setExportFailed] = useState(false)
  const ids = held.scene.photos.map((item) => item.contentId)
  const current = photo === null ? null : ids[photo] ?? null
  const currentUrl = current ? held.photos.get(current) ?? null : null
  const status = paintState === 'loading'
    ? t('sheetPreparing')
    : paintState === 'failed'
      ? t('sheetUnavailable')
      : exportFailed
        ? t('sheetSaveFailed')
        : ''

  useEffect(() => {
    const target = canvas.current
    const context = target?.getContext('2d')
    const token = ++epoch.current
    setPaintState('loading')
    setExportFailed(false)
    setExporting(false)
    if (!target || !context) {
      setPaintState('failed')
      return
    }
    clearCanvas(target, context)
    target.dataset.painted = 'pending'
    void paintHeld(context, held, loadImagePort, loadFacesPort, () => token === epoch.current)
      .then((painted) => {
        if (token !== epoch.current) return
        if (!painted) {
          clearCanvas(target, context)
          target.dataset.painted = 'refused'
          setPaintState('failed')
          return
        }
        target.dataset.painted = 'ready'
        setPaintState('ready')
      })
      .catch(() => {
        if (token !== epoch.current) return
        clearCanvas(target, context)
        target.dataset.painted = 'refused'
        setPaintState('failed')
      })
    return () => {
      epoch.current += 1
      clearCanvas(target, context)
    }
  }, [held, loadImagePort, loadFacesPort])

  useEffect(() => {
    const urls = exportUrls.current
    return () => {
      for (const url of urls) URL.revokeObjectURL(url)
      urls.clear()
    }
  }, [])

  useEffect(() => {
    if (photo === null) return
    closeViewer.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        setPhoto(null)
      } else if (event.key === 'ArrowRight') {
        setPhoto((index) => index === null ? index : Math.min(ids.length - 1, index + 1))
      } else if (event.key === 'ArrowLeft') {
        setPhoto((index) => index === null ? index : Math.max(0, index - 1))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [photo, ids.length])

  async function exportSheet() {
    if (paintState !== 'ready' || exporting) return
    const target = canvas.current
    if (!target) return
    const token = epoch.current
    setExporting(true)
    setExportFailed(false)
    const outcome = await exportComposition({
      isCurrent: () => token === epoch.current && canvas.current === target,
      encode: () => encodePort(target),
      createUrl: (blob) => {
        const url = URL.createObjectURL(blob)
        exportUrls.current.add(url)
        return url
      },
      download: (url) => {
        const link = document.createElement('a')
        link.href = url
        link.download = 'remanence.png'
        link.click()
      },
      revokeUrl: (url) => {
        exportUrls.current.delete(url)
        URL.revokeObjectURL(url)
      },
    })
    if (token !== epoch.current) return
    setExporting(false)
    if (outcome === 'failed' || outcome === 'empty') setExportFailed(true)
  }

  return (
    <div className="stage">
      <div className="sheet">
        <canvas ref={canvas} width={held.scene.canvasWidth} height={held.scene.canvasHeight} />
        {held.scene.photos.map((item, index) => (
          <button
            key={item.contentId}
            type="button"
            className="hit"
            style={{
              left: `${(item.destination.x / held.scene.canvasWidth) * 100}%`,
              top: `${(item.destination.y / held.scene.canvasHeight) * 100}%`,
              width: `${(item.destination.width / held.scene.canvasWidth) * 100}%`,
              height: `${(item.destination.height / held.scene.canvasHeight) * 100}%`,
            }}
            onClick={() => setPhoto(index)}
          >
            {t('photoViewer')}
          </button>
        ))}
      </div>
      <p className="status" role="status" aria-live="polite">{status}</p>
      <div className="actions">
        <button type="button" disabled={paintState !== 'ready' || exporting} onClick={exportSheet}>
          {t('exportComposition')}
        </button>
        <button type="button" onClick={onClose}>{t('closeSheet')}</button>
      </div>
      {currentUrl ? (
        <div className="viewer" role="dialog" aria-modal="true" aria-label={t('photoViewer')}>
          <img src={currentUrl} alt="" />
          <div className="actions">
            <button type="button" disabled={photo === 0} onClick={() => setPhoto((index) => index === null ? index : index - 1)}>{t('previousPhoto')}</button>
            <button type="button" disabled={photo === ids.length - 1} onClick={() => setPhoto((index) => index === null ? index : index + 1)}>{t('nextPhoto')}</button>
            <button ref={closeViewer} type="button" className="primary" onClick={() => setPhoto(null)}>{t('back')}</button>
          </div>
        </div>
      ) : null}
    </div>
  )
}

async function paintHeld(
  context: CanvasRenderingContext2D,
  held: HeldSheet,
  load: StagePorts['loadImage'],
  faces: StagePorts['loadFaces'],
  isCurrent: () => boolean,
): Promise<boolean> {
  if (!isCurrent()) return false
  const images = new Map<string, PaintImage>()
  for (const photo of held.scene.photos) {
    const url = held.photos.get(photo.contentId)
    if (!url) return false
    const image = await load(url)
    if (!isCurrent()) return false
    images.set(photo.contentId, image)
  }
  let artwork: PaintImage | null = null
  if (held.artwork) {
    artwork = await load(held.artwork)
    if (!isCurrent()) return false
  }
  await faces(held.scene)
  if (!isCurrent()) return false
  return paintScene(context, held.scene, images, artwork)
}

function loadImage(url: string): Promise<PaintImage> {
  const image = new Image()
  image.src = url
  return image.decode().then(() => ({ source: image, width: image.naturalWidth, height: image.naturalHeight }))
}

async function loadFaces(scene: Scene): Promise<void> {
  const faces = new Set<string>()
  if (scene.note) faces.add(`${scene.note.weight} ${scene.note.size}px ${scene.note.family}`)
  if (scene.music) {
    faces.add(`${scene.music.weight} ${scene.music.size}px ${scene.music.family}`)
    if (scene.music.vertical) faces.add('400 11px StudySans')
  }
  for (const mark of scene.provenance) faces.add(`${mark.weight} ${mark.size}px ${mark.family}`)
  await Promise.all([...faces].map((face) => document.fonts.load(face)))
}

function encodePng(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return new Promise((resolve, reject) => {
    try {
      canvas.toBlob((blob) => resolve(blob), 'image/png')
    } catch (error) {
      reject(error)
    }
  })
}

function clearCanvas(canvas: HTMLCanvasElement, context: CanvasRenderingContext2D): void {
  context.setTransform(1, 0, 0, 1, 0, 0)
  context.clearRect(0, 0, canvas.width, canvas.height)
}
