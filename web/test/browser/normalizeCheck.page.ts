/**
 * Browser normalization check (T228, Chromium-only gate).
 *
 * Runs INSIDE the browser (bundled by Vite from this file + the real
 * production modules). It manufactures asymmetric EXIF 1–8 JPEG fixtures
 * in-page (canvas baseline + hand-built minimal APP1 orientation segment,
 * same shape as Android `CaptureDecoderTest`), then exercises the REAL
 * production path: `browserPhotoCodec` + `PhotoNormalizer` + `PhotoDraft`.
 *
 * Orientation reference: the stored (unoriented) bitmap is read with
 * `imageOrientation: 'none'` and mapped to upright with the standard
 * per-orientation transform; the `from-image` bitmap must match it at
 * sampled points (dims + dominant colour class, JPEG-tolerant). This
 * proves the platform applies EXIF exactly once — the adapter itself
 * never transforms.
 *
 * Reports `window.__normCheck = { assertions, failures }` for the node
 * runner. No network, no provider, no uploads.
 */
import { browserPhotoCodec } from '../../src/create/browserPhoto'
import { PhotoNormalizer, planNormalization } from '../../src/create/normalize'
import { PhotoDraft } from '../../src/create/photos'

export interface NormAssertion {
  name: string
  ok: boolean
  detail?: string
}

const assertions: NormAssertion[] = []
const failures: string[] = []

function pass(name: string): void {
  assertions.push({ name, ok: true })
}

function fail(name: string, detail: string): void {
  assertions.push({ name, ok: false, detail })
  failures.push(`${name}: ${detail}`)
}

function assert(name: string, cond: boolean, detail = ''): void {
  if (cond) pass(name)
  else fail(name, detail)
}

/** Minimal little-endian EXIF APP1 carrying one SHORT orientation tag. */
function injectExifOrientation(jpeg: Uint8Array, orientation: number): Uint8Array {
  if (jpeg[0] !== 0xff || jpeg[1] !== 0xd8) throw new Error('not a JPEG')
  const exif = Uint8Array.of(
    0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
    0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x01, 0x00,
    0x12, 0x01, 0x03, 0x00,
    0x01, 0x00, 0x00, 0x00,
    orientation, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00,
  )
  const app1 = new Uint8Array(2 + 2 + exif.length)
  app1[0] = 0xff
  app1[1] = 0xe1
  app1[2] = (exif.length + 2) >> 8
  app1[3] = (exif.length + 2) & 0xff
  app1.set(exif, 4)
  const out = new Uint8Array(2 + app1.length + jpeg.length - 2)
  out.set(jpeg.subarray(0, 2), 0)
  out.set(app1, 2)
  out.set(jpeg.subarray(2), 2 + app1.length)
  return out
}

function canvas(w: number, h: number): [HTMLCanvasElement, CanvasRenderingContext2D] {
  const element = document.createElement('canvas')
  element.width = w
  element.height = h
  const context = element.getContext('2d', { willReadFrequently: true })
  if (!context) throw new Error('no 2d context')
  return [element, context]
}

async function canvasJpeg(element: HTMLCanvasElement, quality: number): Promise<Uint8Array> {
  const blob = await new Promise<Blob | null>((resolve) => {
    element.toBlob((result) => resolve(result), 'image/jpeg', quality)
  })
  if (!blob) throw new Error('canvas encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

/** Stored 24x16 asymmetric fixture: TL red, TR green, BL blue, BR white. */
const STORED_W = 24
const STORED_H = 16
async function storedFixture(): Promise<Uint8Array> {
  const [element, context] = canvas(STORED_W, STORED_H)
  const halfW = STORED_W / 2
  const halfH = STORED_H / 2
  context.fillStyle = 'rgb(255,0,0)'
  context.fillRect(0, 0, halfW, halfH)
  context.fillStyle = 'rgb(0,255,0)'
  context.fillRect(halfW, 0, halfW, halfH)
  context.fillStyle = 'rgb(0,0,255)'
  context.fillRect(0, halfH, halfW, halfH)
  context.fillStyle = 'rgb(255,255,255)'
  context.fillRect(halfW, halfH, halfW, halfH)
  return canvasJpeg(element, 0.95)
}

async function bitmapOf(bytes: Uint8Array, orientation: 'from-image' | 'none'): Promise<ImageBitmap> {
  const copy = bytes.slice()
  return createImageBitmap(new Blob([copy.buffer as ArrayBuffer], { type: 'image/jpeg' }), {
    imageOrientation: orientation,
  })
}

function pixels(bitmap: ImageBitmap): ImageData {
  const [, context] = canvas(bitmap.width, bitmap.height)
  context.drawImage(bitmap, 0, 0)
  return context.getImageData(0, 0, bitmap.width, bitmap.height)
}

/** Dominant colour class of one pixel, JPEG-tolerant (margin 60). */
function colourClass(data: ImageData, x: number, y: number): string {
  const offset = (y * data.width + x) * 4
  const r = data.data[offset] as number
  const g = data.data[offset + 1] as number
  const b = data.data[offset + 2] as number
  if (r > g + 60 && r > b + 60) return 'R'
  if (g > r + 60 && g > b + 60) return 'G'
  if (b > r + 60 && b > g + 60) return 'B'
  if (r > 195 && g > 195 && b > 195) return 'W'
  return '?'
}

/**
 * Standard stored→upright map for EXIF orientations (W,H = stored size).
 * Upright dims are W×H for 1–4 and H×W for 5–8.
 */
function mapUpright(orientation: number, x: number, y: number, w: number, h: number): [number, number] {
  switch (orientation) {
    case 1:
      return [x, y]
    case 2:
      return [w - 1 - x, y]
    case 3:
      return [w - 1 - x, h - 1 - y]
    case 4:
      return [x, h - 1 - y]
    case 5:
      return [y, x]
    case 6:
      return [y, h - 1 - x]
    case 7:
      return [w - 1 - y, h - 1 - x]
    case 8:
      return [w - 1 - y, x]
    default:
      throw new Error('bad orientation')
  }
}

function hasExifSegment(bytes: Uint8Array): boolean {
  const needle = [0x45, 0x78, 0x69, 0x66, 0x00]
  outer: for (let i = 0; i + needle.length <= bytes.length; i += 1) {
    for (let k = 0; k < needle.length; k += 1) {
      if (bytes[i + k] !== needle[k]) continue outer
    }
    return true
  }
  return false
}

function sofSize(bytes: Uint8Array): { width: number; height: number } | null {
  let pos = 2
  while (pos + 4 < bytes.length) {
    if (bytes[pos] !== 0xff) return null
    pos += 1
    while (pos < bytes.length && bytes[pos] === 0xff) pos += 1
    const marker = bytes[pos] as number
    pos += 1
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd9)) {
      if (marker === 0xd9 || marker === 0xda) return null
      continue
    }
    if (pos + 1 >= bytes.length) return null
    const length = ((bytes[pos] as number) << 8) | (bytes[pos + 1] as number)
    if (length < 2 || pos + length > bytes.length) return null
    if (marker === 0xc0 || marker === 0xc2) {
      if (length < 7) return null
      return {
        height: ((bytes[pos + 3] as number) << 8) | (bytes[pos + 4] as number),
        width: ((bytes[pos + 5] as number) << 8) | (bytes[pos + 6] as number),
      }
    }
    pos += length
  }
  return null
}

function samplePoints(w: number, h: number): [number, number][] {
  // Quadrant centres of the 2x2 upright grid: far from every edge, so JPEG
  // blocking cannot flip the dominant colour class.
  return [
    [Math.floor(w / 4), Math.floor(h / 4)],
    [Math.floor((3 * w) / 4), Math.floor(h / 4)],
    [Math.floor(w / 4), Math.floor((3 * h) / 4)],
    [Math.floor((3 * w) / 4), Math.floor((3 * h) / 4)],
  ]
}

async function main(): Promise<void> {
  try {
    // plan() sanity inside the browser too (same pure core as unit tests).
    const planned = planNormalization(4000, 2000)
    assert('plan 4000x2000 -> 2560x1280', planned?.width === 2560 && planned?.height === 1280)

    const stored = await storedFixture()
    assert('stored fixture is a JPEG', stored[0] === 0xff && stored[1] === 0xd8)
    const raw = await bitmapOf(stored, 'none')
    assert('stored decodes 24x16 unoriented', raw.width === STORED_W && raw.height === STORED_H)
    const rawPixels = pixels(raw)
    assert(
      'stored quadrants hold the asymmetric pattern',
      colourClass(rawPixels, 6, 4) === 'R' &&
        colourClass(rawPixels, 18, 4) === 'G' &&
        colourClass(rawPixels, 6, 12) === 'B' &&
        colourClass(rawPixels, 18, 12) === 'W',
      'baseline fixture itself must be asymmetric',
    )

    const codec = browserPhotoCodec(document)
    const normalizer = new PhotoNormalizer(codec)

    // All 8 EXIF orientations: platform dims + pixel agreement with the
    // manual-transform reference (proves exactly-once application).
    for (let orientation = 1; orientation <= 8; orientation += 1) {
      const bytes = injectExifOrientation(stored, orientation)
      const seen = await bitmapOf(bytes, 'from-image')
      const swap = orientation >= 5
      const ew = swap ? STORED_H : STORED_W
      const eh = swap ? STORED_W : STORED_H
      assert(`ori${orientation} upright dims ${ew}x${eh}`, seen.width === ew && seen.height === eh)
      const seenPixels = pixels(seen)
      let matched = 0
      let total = 0
      for (const [x, y] of samplePoints(ew, eh)) {
        const [sx, sy] = mapUpright(orientation, x, y, STORED_W, STORED_H)
        const expected = colourClass(rawPixels, sx, sy)
        const actual = colourClass(seenPixels, x, y)
        total += 1
        if (expected !== '?' && expected === actual) matched += 1
      }
      assert(`ori${orientation} pixels match reference (${matched}/${total})`, matched === total && total > 0)
      seen.close()
    }
    raw.close()

    // Full production normalize() on an EXIF-6 input.
    const exif6 = injectExifOrientation(stored, 6)
    const normal = await normalizer.normalize(exif6)
    assert('normalize ok on exif6', normal.ok)
    if (normal.ok) {
      assert('normalized upright dims', normal.photo.width === STORED_H && normal.photo.height === STORED_W)
      assert('normalized fits budget', normal.photo.bytes.byteLength <= 8388608)
      assert('normalized hash is 64 hex', /^[0-9a-f]{64}$/.test(normal.photo.contentHash))
      const size = sofSize(normal.photo.bytes)
      assert(
        'encoded SOF matches reported dims',
        size !== null && size.width === normal.photo.width && size.height === normal.photo.height,
      )
      assert('encoded metadata stripped (no Exif)', !hasExifSegment(normal.photo.bytes))
      const redecoded = await bitmapOf(normal.photo.bytes, 'from-image')
      assert('normalized output re-decodes upright', redecoded.width === STORED_H && redecoded.height === STORED_W)
      redecoded.close()
    }

    // Quality ladder: noisy input with a small test-only budget must step down.
    const [noisy, nctx] = canvas(1200, 900)
    const image = nctx.createImageData(1200, 900)
    let seed = 123456789
    for (let i = 0; i < image.data.length; i += 4) {
      seed = (seed * 1103515245 + 12345) & 0x7fffffff
      image.data[i] = seed & 0xff
      image.data[i + 1] = (seed >> 8) & 0xff
      image.data[i + 2] = (seed >> 16) & 0xff
      image.data[i + 3] = 255
    }
    nctx.putImageData(image, 0, 0)
    const noisyJpeg = await canvasJpeg(noisy, 0.95)
    const stepped = await normalizer.normalize(noisyJpeg, { maxBytes: 30000 })
    assert('ladder ok on noisy input', stepped.ok)
    if (stepped.ok) {
      assert('ladder stepped below 90', stepped.photo.quality < 90, `quality=${stepped.photo.quality}`)
      assert('ladder output fits test budget', stepped.photo.bytes.byteLength <= 30000)
    }
    const impossible = await normalizer.normalize(noisyJpeg, { maxBytes: 64 })
    assert(
      'impossible budget fails closed',
      !impossible.ok && (impossible as { ok: false; reason: string }).reason === 'budget',
    )
    const garbage = await normalizer.normalize(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]))
    assert(
      'garbage fails closed as decode',
      !garbage.ok && (garbage as { ok: false; reason: string }).reason === 'decode',
    )

    // Draft intake of normalized output + thumbnail URL decodes (Create seam).
    const draft = new PhotoDraft()
    if (normal.ok) {
      const reason = draft.add(normal.photo)
      assert('draft accepts normalized photo', reason === null)
      const url = URL.createObjectURL(new Blob([normal.photo.bytes.slice().buffer as ArrayBuffer], { type: 'image/jpeg' }))
      const probe = new Image()
      const loaded = await new Promise<boolean>((resolve) => {
        probe.onload = () => resolve(true)
        probe.onerror = () => resolve(false)
        probe.src = url
      })
      assert('thumbnail URL decodes in an image element', loaded)
      URL.revokeObjectURL(url)
      draft.remove(draft.photos()[0]?.id ?? '')
      assert('draft remove keeps semantics', draft.photos().length === 0)
    }
  } catch (error) {
    fail('harness', String(error))
  }
  ;(window as unknown as { __normCheck: unknown }).__normCheck = { assertions, failures }
  const status = document.createElement('p')
  status.id = 'norm-status'
  status.textContent = failures.length === 0 ? `PASS ${assertions.length}` : `FAIL ${failures.length}/${assertions.length}`
  document.body.append(status)
}

void main()
