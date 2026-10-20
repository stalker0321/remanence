/**
 * T351 connected stage export proof (Chromium-only).
 *
 * Mounts the ACTUAL exported `CollageStage` with real bundled fonts, real
 * `paintScene` and real synthetic JPEG/object-URL photos. It proves the export
 * button is gated until the current painting is ready, the downloaded PNG is
 * exactly the frozen composition canvas (IHDR 360x640, no viewport/DOM), the
 * decoded PNG pixels agree with the stage canvas, and null/throw/failure paths
 * are handled. Deferred image/encode seams are isolated test overrides; the
 * normal path uses the production image/font/toBlob pipeline.
 */
import { createRoot, type Root } from 'react-dom/client'
import { CollageStage, type StagePorts } from '../../src/collage/stage'
import type { HeldSheet } from '../../src/collage/memory'
import type { PaintImage } from '../../src/collage/paint'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { styleById } from '../../src/collage/styles'
import type { TextMeasurer } from '../../src/collage/prepare'
import { catalog, type MessageKey } from '../../src/i18n/catalog'
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
const notices: MessageKey[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const t = (key: MessageKey): string => catalog.en[key]
const NOTE = 'T351 composition export proof.'
const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

const originalCreate = URL.createObjectURL.bind(URL)
const exportBlobs: Blob[] = []
const created: string[] = []
const revoked: string[] = []
const downloads: Array<{ href: string; download: string }> = []
URL.createObjectURL = (blob: Blob): string => {
  const url = `blob:t351-export/${exportBlobs.length}`
  exportBlobs.push(blob)
  created.push(url)
  return url
}
URL.revokeObjectURL = (url: string): void => { revoked.push(url) }
HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement): void {
  downloads.push({ href: this.href, download: this.download })
}

function canvasJpeg(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    try {
      canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('no jpeg')), 'image/jpeg', 0.9)
    } catch (error) {
      reject(error)
    }
  })
}

function tintedPhoto(index: number, tint: string): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = 800
  canvas.height = 800
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = tint
  context.fillRect(0, 0, 800, 800)
  context.fillStyle = index % 2 === 0 ? '#ffffff' : '#222222'
  context.fillRect(100 + index * 40, 120, 260, 420)
  context.fillStyle = '#c4554a'
  context.fillRect(420, 540, 250, 120)
  return canvas
}

async function buildHeld(tint: string): Promise<{ held: HeldSheet; urls: string[] }> {
  const canvases = [0, 1, 2].map((index) => tintedPhoto(index, tint))
  const urls = await Promise.all(canvases.map(async (canvas) => originalCreate(await canvasJpeg(canvas))))
  const photos = canvases.map((canvas, index) => ({
    contentId: `p${index}`,
    width: canvas.width,
    height: canvas.height,
    contentHash: `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32),
  }))
  const style = styleById('press')!
  const view = previewCandidates({ id: 'creator', sender: 'sergio', photos, note: NOTE }, undefined, plannerFromTextMeasurer(measurer, 640, 640, style.note))[0]!
  const scene = creatorScene({
    view,
    styleId: 'press',
    senderHandle: 'sergio',
    noteText: NOTE,
    photos: new Map(photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
  })
  return {
    held: {
      scene,
      photos: new Map(photos.map((photo, index) => [photo.contentId, urls[index]!])),
      artwork: null,
    },
    urls,
  }
}

function loadImage(url: string): Promise<PaintImage> {
  const image = new Image()
  image.src = url
  return image.decode().then(() => ({ source: image, width: image.naturalWidth, height: image.naturalHeight }))
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((accept, refuse) => {
    resolve = accept
    reject = refuse
  })
  return { promise, resolve, reject }
}

async function waitFor(condition: () => boolean, what: string, timeoutMs = 30000): Promise<void> {
  const deadline = performance.now() + timeoutMs
  while (performance.now() < deadline) {
    if (condition()) return
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  throw new Error(`timeout: ${what}`)
}

function buttonByText(text: string): HTMLButtonElement | null {
  return [...document.querySelectorAll('button')].find((button) => button.textContent?.trim() === text) as HTMLButtonElement | null
}

function stageCanvas(): HTMLCanvasElement {
  return document.querySelector('.stage canvas') as HTMLCanvasElement
}

function canvasStatus(): string {
  return document.querySelector('.stage .status')?.textContent ?? ''
}

function exportButton(): HTMLButtonElement {
  const button = buttonByText(t('exportComposition'))
  if (!button) throw new Error('export button missing')
  return button
}

function stageText(): string {
  return document.querySelector('.stage')?.textContent ?? ''
}

function pngSize(bytes: Uint8Array): { width: number; height: number } {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const signature = [137, 80, 78, 71, 13, 10, 26, 10]
  if (!signature.every((value, index) => view.getUint8(index) === value)) throw new Error('not PNG')
  return { width: view.getUint32(16), height: view.getUint32(20) }
}

async function pixelSample(canvas: HTMLCanvasElement): Promise<number[]> {
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no canvas context')
  const points = [[10, 10], [180, 20], [180, 320], [40, 520], [320, 620], [90, 240], [270, 400], [180, 160], [350, 639]]
  return points.flatMap(([x, y]) => [...context.getImageData(x!, y!, 1, 1).data])
}

async function main(): Promise<void> {
  const statusNode = document.createElement('p')
  statusNode.id = 'stage-export-status'
  statusNode.textContent = 'starting'
  document.body.appendChild(statusNode)
  const report: Record<string, unknown> = { pass: false, synthetic: true }
  try {
    const a = await buildHeld('#b33b2e')
    const b = await buildHeld('#245a9c')
    const c = await buildHeld('#2e8b57')

    const root: { current: Root | null } = { current: null }
    const host = document.createElement('div')
    document.body.appendChild(host)
    const render = (held: HeldSheet, ports?: Partial<StagePorts>): void => {
      root.current ??= createRoot(host)
      root.current.render(
        <CollageStage held={held} t={t} onClose={() => undefined} ports={ports} />,
      )
    }

    // A pending decode keeps export disabled and says so accessibly.
    const pending = deferred<PaintImage>()
    render(a.held, {
      loadImage: () => pending.promise,
    })
    await waitFor(() => exportButton() !== null, 'pending stage mounted')
    assert('pending: export starts disabled', exportButton().disabled)
    assert('pending: truthful preparing status', canvasStatus() === t('sheetPreparing'))
    assert('pending: no export/donload before ready', exportBlobs.length === 0 && downloads.length === 0)
    const realA = await loadImage(a.urls[0]!)
    pending.resolve(realA)
    await waitFor(() => !exportButton().disabled, 'pending painted ready')
    assert('ready: export enabled only after paint', canvasStatus() === '')
    assert('ready: canvas marked painted', stageCanvas().dataset.painted === 'ready')

    // Real current composition exports exactly the composition canvas.
    exportButton().click()
    await waitFor(() => downloads.length === 1, 'composition download')
    assert('export: exactly one download', downloads.length === 1)
    assert('export: download is a PNG named remanence.png', downloads[0]!.download === 'remanence.png')
    assert('export: one temporary URL created', created.length === 1)
    assert('export: temporary URL revoked', revoked.length === 1 && revoked[0] === created[0])
    const png = new Uint8Array(await exportBlobs[0]!.arrayBuffer())
    const size = pngSize(png)
    assert('export: IHDR is the exact composition 360x640', size.width === 360 && size.height === 640, JSON.stringify(size))
    assert('export: canvas is the frozen composition size', stageCanvas().width === size.width && stageCanvas().height === size.height)
    const bitmap = await createImageBitmap(exportBlobs[0]!)
    const flat = document.createElement('canvas')
    flat.width = size.width
    flat.height = size.height
    flat.getContext('2d')!.drawImage(bitmap, 0, 0)
    const pngPixels = await pixelSample(flat)
    const canvasPixels = await pixelSample(stageCanvas())
    assert('export: decoded PNG pixels agree with the painted composition', pngPixels.length === canvasPixels.length && pngPixels.every((value, index) => value === canvasPixels[index]), `${pngPixels.join(',')} vs ${canvasPixels.join(',')}`)
    const downloadsAfterSuccess = downloads.length

    // A missing image refuses honestly, stays disabled, and leaves a cleared canvas.
    const broken: HeldSheet = { ...b.held, photos: new Map([...b.held.photos].slice(0, 2)) }
    render(broken)
    await waitFor(() => canvasStatus() === t('sheetUnavailable'), 'missing image refused')
    assert('missing image: export disabled', exportButton().disabled)
    assert('missing image: refused status', canvasStatus() === t('sheetUnavailable'))
    assert('missing image: no download', downloads.length === downloadsAfterSuccess)
    assert('missing image: canvas cleared', stageCanvas().dataset.painted === 'refused' && [...stageCanvas().getContext('2d')!.getImageData(0, 0, 360, 640).data].every((value) => value === 0))

    // A rejected decode is handled, never an unhandled rejection.
    render(b.held, { loadImage: () => Promise.reject(new Error('decode refused')) })
    await waitFor(() => canvasStatus() === t('sheetUnavailable'), 'decode rejection refused')
    assert('decode rejection: refused status', canvasStatus() === t('sheetUnavailable'))
    assert('decode rejection: export disabled', exportButton().disabled)

    // Null and thrown encodes are honest save failures with no URL/download.
    render(a.held)
    await waitFor(() => !exportButton().disabled, 'null-encode stage ready')
    const beforeNull = { created: created.length, downloads: downloads.length, revoked: revoked.length }
    render(a.held, { encode: () => Promise.resolve(null) })
    await waitFor(() => !exportButton().disabled, 'null-encode ready')
    exportButton().click()
    await waitFor(() => canvasStatus() === t('sheetSaveFailed'), 'null encode failure')
    assert('null encode: truthful save failure', canvasStatus() === t('sheetSaveFailed'))
    assert('null encode: no URL/download', created.length === beforeNull.created && downloads.length === beforeNull.downloads)
    assert('null encode: export re-enabled after honest failure', !exportButton().disabled)

    const beforeThrow = { created: created.length, downloads: downloads.length, revoked: revoked.length }
    render(a.held, { encode: () => Promise.reject(new Error('SecurityError: tainted')) })
    await waitFor(() => !exportButton().disabled, 'throw-encode ready')
    exportButton().click()
    await waitFor(() => canvasStatus() === t('sheetSaveFailed'), 'throw encode failure')
    assert('thrown/tainted encode: truthful save failure', canvasStatus() === t('sheetSaveFailed'))
    assert('thrown/tainted encode: no URL/download', created.length === beforeThrow.created && downloads.length === beforeThrow.downloads)

    // Replacing the sheet while an image is pending never commits stale paint.
    const staleLoad = deferred<PaintImage>()
    render(a.held, { loadImage: () => staleLoad.promise })
    await waitFor(() => exportButton().disabled, 'stale-paint stage pending')
    render(b.held)
    await waitFor(() => stageCanvas().dataset.painted === 'ready' && !exportButton().disabled, 'replacement painted')
    const beforeStale = stageCanvas().toDataURL()
    staleLoad.resolve(realA)
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert('stale paint: replaced sheet is not overwritten', stageCanvas().toDataURL() === beforeStale)
    assert('stale paint: replacement remains ready', stageCanvas().dataset.painted === 'ready')

    // Replacing while toBlob awaits drops the late blob and creates no URL/download.
    render(b.held)
    await waitFor(() => !exportButton().disabled, 'stale-export stage ready')
    const lateEncode = deferred<Blob | null>()
    render(b.held, { encode: () => lateEncode.promise })
    await waitFor(() => !exportButton().disabled, 'stale-export ready with deferral')
    const beforeLate = { created: created.length, downloads: downloads.length, revoked: revoked.length }
    exportButton().click()
    await new Promise((resolve) => setTimeout(resolve, 30))
    render(c.held)
    lateEncode.resolve(new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' }))
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert('late export after replacement: no URL', created.length === beforeLate.created)
    assert('late export after replacement: no download', downloads.length === beforeLate.downloads)
    assert('late export after replacement: no revoke churn', revoked.length === beforeLate.revoked)

    // Unmount while toBlob awaits drops the late blob with no URL/download.
    render(b.held)
    await waitFor(() => !exportButton().disabled, 'unmount-export stage ready')
    const unmountEncode = deferred<Blob | null>()
    render(b.held, { encode: () => unmountEncode.promise })
    await waitFor(() => !exportButton().disabled, 'unmount-export ready with deferral')
    const beforeUnmount = { created: created.length, downloads: downloads.length }
    exportButton().click()
    await new Promise((resolve) => setTimeout(resolve, 30))
    root.current?.unmount()
    root.current = null
    unmountEncode.resolve(new Blob([new Uint8Array([4, 5, 6])], { type: 'image/png' }))
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert('late export after unmount: no URL', created.length === beforeUnmount.created)
    assert('late export after unmount: no download', downloads.length === beforeUnmount.downloads)

    assert('all temporary export URLs were revoked', created.every((url) => revoked.includes(url)), `${created.length} created vs ${revoked.length} revoked`)
    report.png = { ...size, bytes: png.length }
    report.downloads = downloads.length
    report.created = created.length
    report.revoked = revoked.length
    report.pass = failures.length === 0
  } catch (error) {
    fail('stage export connected harness', String(error) + ' | stage=' + stageText().slice(-400))
  }
  ;(window as unknown as { __stageExport?: unknown }).__stageExport = { assertions, failures, notices, report }
  statusNode.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
