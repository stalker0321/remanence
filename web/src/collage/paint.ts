import { bandLayout, placeCover, roundRadii } from './geometry'
import type { PhotoShape, Rect } from './model'
import { spineCopyPlacements, type Scene, type SceneDecor } from './prepare'

export interface DrawableSource {
  width: number
  height: number
}

export type DrawOp =
  | { kind: 'paper' }
  | { kind: 'decor'; index: number }
  | { kind: 'photo'; contentId: string; shape: PhotoShape }
  | { kind: 'note' }
  | { kind: 'music-background'; transparent: boolean }
  | { kind: 'music-artwork' }
  | { kind: 'music-text' }
  | { kind: 'music-play' }
  | { kind: 'provenance'; index: number }

/**
 * Frozen draw order from CollagePainter.drawList.
 * Print-plane shadow stays on the scene and is not an operation: the current
 * Android canvas paints fill and border only.
 */
export function drawList(scene: Scene, images: ReadonlyMap<string, DrawableSource>): DrawOp[] | null {
  if (!drawable(scene, images)) return null
  const ops: DrawOp[] = [{ kind: 'paper' }]
  scene.decor.forEach((_, index) => ops.push({ kind: 'decor', index }))
  for (const photo of scene.photos) ops.push({ kind: 'photo', contentId: photo.contentId, shape: photo.shape })
  if (scene.note) ops.push({ kind: 'note' })
  if (scene.music) ops.push({ kind: 'music-background', transparent: scene.music.transparentBackground })
  if (scene.artwork) ops.push({ kind: 'music-artwork' })
  if (scene.music) {
    ops.push({ kind: 'music-text' })
    if (scene.artwork === null && scene.music.playRegion) ops.push({ kind: 'music-play' })
  }
  scene.provenance.forEach((_, index) => ops.push({ kind: 'provenance', index }))
  return ops
}

/** What the canvas actually strokes for a decor plane. Shadow is omitted. */
export function decorStroke(decor: SceneDecor): { background: number; border: number | null; borderWidth: number } {
  return { background: decor.background, border: decor.border, borderWidth: decor.borderWidth }
}

export interface PaintImage extends DrawableSource {
  source: CanvasImageSource
}

export function paintScene(
  context: CanvasRenderingContext2D,
  scene: Scene,
  images: ReadonlyMap<string, PaintImage>,
  artwork: PaintImage | null,
): boolean {
  const sources = new Map<string, DrawableSource>()
  for (const [id, image] of images) sources.set(id, image)
  const ops = drawList(scene, sources)
  if (!ops) return false
  context.setTransform(1, 0, 0, 1, 0, 0)
  context.clearRect(0, 0, scene.canvasWidth, scene.canvasHeight)
  for (const op of ops) {
    if (op.kind === 'paper') {
      fill(context, scene.paper, { x: 0, y: 0, width: scene.canvasWidth, height: scene.canvasHeight })
    } else if (op.kind === 'decor') {
      const decor = scene.decor[op.index]
      if (!decor) return false
      const stroke = decorStroke(decor)
      fill(context, stroke.background, decor.region)
      if (stroke.border !== null) strokeRect(context, stroke.border, decor.region, stroke.borderWidth)
    } else if (op.kind === 'photo') {
      const photo = scene.photos.find((item) => item.contentId === op.contentId)
      const image = images.get(op.contentId)
      if (!photo || !image) return false
      context.save()
      clipShape(context, scene.grammarId, photo.destination, op.shape)
      context.drawImage(
        image.source,
        photo.source.x, photo.source.y, photo.source.width, photo.source.height,
        photo.destination.x, photo.destination.y, photo.destination.width, photo.destination.height,
      )
      context.restore()
    } else if (op.kind === 'note' && scene.note) {
      writeText(context, scene.note.text, scene.note.region, scene.note, scene.note.color, false)
    } else if (op.kind === 'music-background' && scene.music) {
      if (op.transparent) {
        context.fillStyle = css(scene.music.color)
        context.fillRect(scene.music.region.x, scene.music.region.y, scene.music.region.width, 1)
      } else {
        fill(context, scene.music.background, scene.music.region)
      }
    } else if (op.kind === 'music-artwork' && scene.artwork) {
      paintCover(context, scene, artwork)
    } else if (op.kind === 'music-text' && scene.music) {
      if (scene.music.vertical) paintSpine(context, scene)
      else writeText(context, scene.music.text, scene.music.copyRegion, scene.music, scene.music.color, true)
    } else if (op.kind === 'music-play' && scene.music?.playRegion) {
      paintPlay(context, scene.music.playRegion, scene.music)
    } else if (op.kind === 'provenance') {
      const mark = scene.provenance[op.index]
      if (!mark) return false
      paintMark(context, mark)
    }
  }
  return true
}

function drawable(scene: Scene, images: ReadonlyMap<string, DrawableSource>): boolean {
  if (scene.photos.length === 0) return false
  const ids = scene.photos.map((photo) => photo.contentId)
  const keys = [...images.keys()]
  if (keys.length !== ids.length || !ids.every((id) => images.has(id))) return false
  return scene.photos.every((photo) => {
    const image = images.get(photo.contentId)
    if (!image) return false
    const source = photo.source
    return image.width > 0 && image.height > 0
      && source.x >= 0 && source.y >= 0 && source.width > 0 && source.height > 0
      && source.x + source.width <= image.width && source.y + source.height <= image.height
      && photo.destination.width > 0 && photo.destination.height > 0
  })
}

function paintCover(context: CanvasRenderingContext2D, scene: Scene, artwork: PaintImage | null): void {
  const region = scene.artwork?.region
  if (!region) return
  const track = scene.music
  if (!artwork) {
    fill(context, scene.artwork?.background ?? scene.sound, region)
    return
  }
  if (!track) {
    contain(context, artwork, region)
    return
  }
  const placement = placeCover(region, scene.grammarId, track.mode)
  if (!placement) return
  if (placement.fit === 'cover') cover(context, artwork, placement.cover)
  else contain(context, artwork, placement.cover)
}

function contain(context: CanvasRenderingContext2D, image: PaintImage, box: Rect): void {
  const scale = Math.min(box.width / image.width, box.height / image.height)
  const width = Math.round(image.width * scale)
  const height = Math.round(image.height * scale)
  if (width < 1 || height < 1) return
  context.drawImage(
    image.source, 0, 0, image.width, image.height,
    box.x + Math.floor((box.width - width) / 2),
    box.y + Math.floor((box.height - height) / 2),
    width, height,
  )
}

function cover(context: CanvasRenderingContext2D, image: PaintImage, box: Rect): void {
  const scale = Math.max(box.width / image.width, box.height / image.height)
  const srcW = Math.min(image.width, Math.round(box.width / scale))
  const srcH = Math.min(image.height, Math.round(box.height / scale))
  if (srcW < 1 || srcH < 1) return
  context.drawImage(
    image.source,
    Math.floor((image.width - srcW) / 2),
    Math.floor((image.height - srcH) / 2),
    srcW, srcH,
    box.x, box.y, box.width, box.height,
  )
}

function paintSpine(context: CanvasRenderingContext2D, scene: Scene): void {
  const track = scene.music
  if (!track) return
  const band = bandLayout(scene.grammarId, track.mode, track.region.height)
  const title = track.title.trim() === '' ? 0 : textWidth(context, track.title, track)
  const artistFace = { family: 'StudySans', size: 11, weight: 400, italic: false, line: 13 }
  const artist = track.artist.trim() === '' ? 0 : textWidth(context, track.artist, artistFace)
  const place = spineCopyPlacements(track.region, band, title, track.line, artistFace.line)
  if (title > 0) rotated(context, place.title.x + place.title.width, place.title.y, () => {
    context.fillStyle = css(track.color)
    context.font = fontOf(track)
    context.fillText(track.title, 0, track.size)
  })
  if (artist > 0) rotated(context, place.artist.x + place.artist.width, place.artist.y, () => {
    context.fillStyle = css(track.color)
    context.font = fontOf(artistFace)
    context.fillText(track.artist, 0, artistFace.size)
  })
}

function paintMark(context: CanvasRenderingContext2D, mark: Scene['provenance'][number]): void {
  if (mark.orientation === 'horizontal') {
    writeText(context, mark.text, mark.region, mark, mark.color, false)
    return
  }
  const x = mark.orientation === 'vertical-up' ? mark.region.x : mark.region.x + mark.region.width
  const y = mark.orientation === 'vertical-up' ? mark.region.y + mark.region.height : mark.region.y
  const turn = mark.orientation === 'vertical-up' ? -Math.PI / 2 : Math.PI / 2
  rotated(context, x, y, () => {
    context.rotate(turn)
    context.fillStyle = css(mark.color)
    context.font = fontOf(mark)
    context.fillText(mark.text, 0, mark.size)
  }, false)
}

function paintPlay(context: CanvasRenderingContext2D, region: Rect, track: Scene['music']): void {
  if (!track) return
  const foreground = track.transparentBackground ? track.color : track.background
  const background = track.transparentBackground ? null : track.color
  const centerX = region.x + region.width / 2
  const centerY = region.y + region.height / 2
  const radius = Math.min(region.width, region.height) / 2
  if (background !== null) {
    context.beginPath()
    context.fillStyle = css(background)
    context.arc(centerX, centerY, radius, 0, Math.PI * 2)
    context.fill()
  }
  context.beginPath()
  context.fillStyle = css(foreground)
  context.moveTo(centerX - radius * 0.25, centerY - radius * 0.43)
  context.lineTo(centerX + radius * 0.47, centerY)
  context.lineTo(centerX - radius * 0.25, centerY + radius * 0.43)
  context.closePath()
  context.fill()
}

function writeText(
  context: CanvasRenderingContext2D,
  text: string,
  region: Rect,
  face: { family: string; size: number; weight: number; italic: boolean; line: number },
  color: number,
  preserveBreaks: boolean,
): void {
  context.save()
  context.beginPath()
  context.rect(region.x, region.y, region.width, region.height)
  context.clip()
  context.fillStyle = css(color)
  context.font = fontOf(face)
  const lines = preserveBreaks ? text.split('\n') : [text]
  lines.forEach((line, index) => {
    context.fillText(line, region.x, region.y + face.size + index * face.line)
  })
  context.restore()
}

function clipShape(context: CanvasRenderingContext2D, grammar: string, region: Rect, shape: PhotoShape): void {
  context.beginPath()
  if (shape === 'ellipse') {
    context.ellipse(
      region.x + region.width / 2,
      region.y + region.height / 2,
      region.width / 2,
      region.height / 2,
      0, 0, Math.PI * 2,
    )
  } else if (shape === 'round') {
    const radii = roundRadii(grammar, region.width, region.height)
    context.roundRect(region.x, region.y, region.width, region.height, [
      { x: radii[0] ?? 0, y: radii[1] ?? 0 },
      { x: radii[2] ?? 0, y: radii[3] ?? 0 },
      { x: radii[4] ?? 0, y: radii[5] ?? 0 },
      { x: radii[6] ?? 0, y: radii[7] ?? 0 },
    ])
  } else {
    context.rect(region.x, region.y, region.width, region.height)
  }
  context.clip()
}

function fill(context: CanvasRenderingContext2D, color: number, region: Rect): void {
  context.fillStyle = css(color)
  context.fillRect(region.x, region.y, region.width, region.height)
}

function strokeRect(context: CanvasRenderingContext2D, color: number, region: Rect, width: number): void {
  context.strokeStyle = css(color)
  context.lineWidth = width
  context.strokeRect(region.x + width / 2, region.y + width / 2, region.width - width, region.height - width)
}

function fontOf(face: { family: string; size: number; weight: number; italic: boolean }): string {
  return `${face.italic ? 'italic' : 'normal'} ${face.weight} ${face.size}px ${face.family}`
}

function textWidth(context: CanvasRenderingContext2D, text: string, face: { family: string; size: number; weight: number; italic: boolean }): number {
  context.font = fontOf(face)
  return context.measureText(text).width
}

function rotated(context: CanvasRenderingContext2D, x: number, y: number, draw: () => void, turn = true): void {
  context.save()
  context.translate(x, y)
  if (turn) context.rotate(Math.PI / 2)
  draw()
  context.restore()
}

function css(color: number): string {
  const value = color >>> 0
  return `rgba(${(value >>> 16) & 255}, ${(value >>> 8) & 255}, ${value & 255}, ${((value >>> 24) & 255) / 255})`
}
