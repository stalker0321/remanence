import { useEffect, useRef, useState } from 'react'
import type { MessageKey } from '../i18n/catalog'
import { paintScene, type PaintImage } from './paint'
import type { HeldSheet } from './memory'
import type { Scene } from './prepare'

/**
 * The admitted sheet. The bitmap is the 360×640 composition.
 * Export saves that bitmap, and the photo viewer pages the original frames.
 */
export function CollageStage({
  held,
  t,
  onClose,
}: {
  held: HeldSheet
  t: (key: MessageKey) => string
  onClose: () => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const closeViewer = useRef<HTMLButtonElement>(null)
  const [photo, setPhoto] = useState<number | null>(null)
  const ids = held.scene.photos.map((item) => item.contentId)
  const current = photo === null ? null : ids[photo] ?? null
  const currentUrl = current ? held.photos.get(current) ?? null : null

  useEffect(() => {
    const target = canvas.current
    const context = target?.getContext('2d')
    if (!target || !context) return
    let cancel = false
    void paintHeld(context, held).then((painted) => {
      if (cancel) return
      target.dataset.painted = painted ? 'ready' : 'refused'
    })
    return () => {
      cancel = true
    }
  }, [held])

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
    const blob = await new Promise<Blob | null>((resolve) => canvas.current?.toBlob(resolve, 'image/png'))
    if (!blob) return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'remanence.png'
    link.click()
    URL.revokeObjectURL(url)
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
      <div className="actions">
        <button type="button" onClick={exportSheet}>{t('exportComposition')}</button>
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

async function paintHeld(context: CanvasRenderingContext2D, held: HeldSheet): Promise<boolean> {
  const images = new Map<string, PaintImage>()
  for (const photo of held.scene.photos) {
    const url = held.photos.get(photo.contentId)
    if (!url) return false
    const image = await loadImage(url)
    images.set(photo.contentId, image)
  }
  const artwork = held.artwork ? await loadImage(held.artwork) : null
  await loadFaces(held.scene)
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
