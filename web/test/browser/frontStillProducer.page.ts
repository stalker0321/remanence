/**
 * T316 front-still producer browser gate (Chromium-only).
 *
 * Generates deterministic synthetic JPEGs on canvas (no fixtures, no camera)
 * and runs the REAL production `produceFrontStillFingerprint` — real DOM
 * decode, real WASM contour detection/selection/warp, real SIFT, accepted
 * serializer — in a pinned browser. Proves functional localization/warp ONLY:
 * no real-postcard field accuracy, exact native numerics, scan-admission,
 * receive or AppSend. No fake detector, grant, or UI integration.
 */
import { bytesToHex } from '../../src/protocol/bytes'
import { orderClockwiseFromTopLeft } from '../../src/scan/cornerGeometry'
import { selectCrop } from '../../src/scan/cropSelection'
import { planWarp, targetCorners } from '../../src/scan/warpGeometry'
import {
  FrontStillError,
  decodeJpegUpright,
  produceFrontStillFingerprint,
} from '../../src/scan/frontStillProducer'
import { loadOpenCvRuntime } from '../../src/scan/opencvRuntime'
import { parseSiftFingerprint } from '../../src/scan/siftFingerprint'
import type { FrontStill } from '../../src/create/front'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

function mulberry32(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state |= 0
    state = (state + 0x6d2b79f5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function canvasOf(width: number, height: number): [HTMLCanvasElement, CanvasRenderingContext2D] {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) throw new Error('no 2d context')
  return [canvas, context]
}

async function toJpeg(canvas: HTMLCanvasElement): Promise<Uint8Array> {
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('jpeg encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

/** Fills a quad with deterministic texture and a bright border for Canny. */
function drawTexturedQuad(
  context: CanvasRenderingContext2D,
  quad: Array<{ x: number; y: number }>,
  seed: number,
  markerColors?: string[],
): void {
  context.save()
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.clip()
  const random = mulberry32(seed)
  for (let i = 0; i < 260; i++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    const x = quad[0]!.x + random() * 480
    const y = quad[0]!.y + random() * 340
    context.fillRect(x, y, 4 + random() * 22, 4 + random() * 22)
  }
  context.restore()
  context.lineWidth = 6
  context.strokeStyle = '#ffffff'
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.stroke()
  if (markerColors) {
    // Corner color markers (TL red, TR green, BR yellow, BL blue).
    for (let i = 0; i < 4; i++) {
      context.fillStyle = markerColors[i]!
      context.beginPath()
      context.arc(quad[i]!.x, quad[i]!.y, 14, 0, 2 * Math.PI)
      context.fill()
    }
  }
}

function stillFor(bytes: Uint8Array, width: number, height: number): FrontStill {
  return { bytes, width, height, userId: 'u-1', keyBundleId: 'k-1', directoryVersion: 'd-1' }
}

/** Minimal EXIF APP1 (orientation tag) inserted after SOI. */
function injectExifOrientation(jpeg: Uint8Array, orientation: number): Uint8Array {
  const app1 = new Uint8Array([
    0xff, 0xe1, 0x00, 0x22,
    0x45, 0x78, 0x69, 0x66, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
    orientation, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  ])
  const out = new Uint8Array(jpeg.length + app1.length)
  out.set(jpeg.slice(0, 2), 0)
  out.set(app1, 2)
  out.set(jpeg.slice(2), 2 + app1.length)
  return out
}

function reason(promise: Promise<unknown>): Promise<string> {
  return promise.then(
    () => 'ok',
    (error) => (error instanceof FrontStillError ? error.code : `other:${String(error)}`),
  )
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'front-still-producer-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, string> = {}
  try {
    const W = 640
    const H = 480
    const quad = [
      { x: 100, y: 80 },
      { x: 540, y: 60 },
      { x: 560, y: 400 },
      { x: 80, y: 420 },
    ]
    // Clean quad (no markers): corner markers would bulge the Canny outline
    // so approxPolyDP could not yield four points; colors are proven on a
    // separate marked scene warped with the known corners.
    const [scene, sceneCtx] = canvasOf(W, H)
    sceneCtx.fillStyle = '#101418'
    sceneCtx.fillRect(0, 0, W, H)
    drawTexturedQuad(sceneCtx, quad, 7)
    const jpegA = await toJpeg(scene)
    const jpegA2 = await toJpeg(scene)

    assert('same canvas encodes byte-identical JPEGs', bytesToHex(jpegA) === bytesToHex(jpegA2))

    // Full producer on the known quad (real decode + runtime + serializer).
    const produced = await produceFrontStillFingerprint(stillFor(jpegA, W, H), { fence: () => true })
    assert('detected source is LEGACY_CONTOUR', produced.provenance.source === 'LEGACY_CONTOUR', produced.provenance.source)
    assert('warped long edge is 1600', produced.provenance.warpedWidth === 1600, String(produced.provenance.warpedWidth))
    assert('profile id is format-3', produced.profileId === 'postcard-sift-rootsift-v1', produced.profileId)
    assert('recipient tuple captured', produced.recipient.userId === 'u-1' && produced.recipient.directoryVersion === 'd-1')
    const parsed = parseSiftFingerprint(produced.wire)
    assert('wire parses to a nonempty fingerprint', parsed.keypoints.length > 0, String(parsed.keypoints.length))
    report.wireHex = bytesToHex(produced.wire)

    // Repeat determinism.
    const producedAgain = await produceFrontStillFingerprint(stillFor(jpegA, W, H), { fence: () => true })
    assert('same image yields identical wire bytes', bytesToHex(producedAgain.wire) === report.wireHex)

    // Distinct frame -> distinct wire.
    const [sceneB, sceneBCtx] = canvasOf(W, H)
    sceneBCtx.fillStyle = '#101418'
    sceneBCtx.fillRect(0, 0, W, H)
    const quadB = quad.map((p) => ({ x: p.x + 24, y: p.y - 12 }))
    drawTexturedQuad(sceneBCtx, quadB, 99)
    const jpegB = await toJpeg(sceneB)
    const producedB = await produceFrontStillFingerprint(stillFor(jpegB, W, H), { fence: () => true })
    assert('distinct frame yields a distinct wire', bytesToHex(producedB.wire) !== report.wireHex)
    report.distinctWireHex = bytesToHex(producedB.wire)

    // Direct geometry: decode + detect + warp with the real runtime.
    const runtime = await loadOpenCvRuntime()
    const decoded = await decodeJpegUpright(document, jpegA)
    assert('bitmap upright dims are authoritative (no EXIF here)', decoded.width === W && decoded.height === H, `${decoded.width}x${decoded.height}`)
    const rgbaMat = runtime.matFromRgba(decoded.rgba, decoded.width, decoded.height)
    try {
      const detected = runtime.detectQuadCandidates(rgbaMat, decoded.width, decoded.height)
      assert('real detection finds the known quad', detected.length >= 1, String(detected.length))
      const first = orderClockwiseFromTopLeft(detected[0]!.points)
      const ordered = [first[0]!, first[1]!, first[2]!, first[3]!] as const
      const selection = selectCrop(
        detected.map((d) => {
          const corners = orderClockwiseFromTopLeft(d.points)
          return {
            corners: [corners[0]!, corners[1]!, corners[2]!, corners[3]!] as const,
            areaRatio: d.quadArea / (decoded.width * decoded.height),
            rectangularity: d.contourArea / d.quadArea,
          }
        }),
        decoded.width,
        decoded.height,
      )
      assert('selection uses the detected contour', selection.source === 'LEGACY_CONTOUR', selection.source)
      const TOL = 12
      const near = ordered.every((p, i) => Math.hypot(p.x - quad[i]!.x, p.y - quad[i]!.y) <= TOL)
      assert('detected corners match the known quad within 12px', near, JSON.stringify(ordered))
      const plan = planWarp(selection.candidate.corners)
      assert('warp plan keeps 1600 long edge', plan.targetWidth === 1600)
      const warped = runtime.warpRgbaQuad(rgbaMat, plan.source, targetCorners(plan.targetWidth, plan.targetHeight), plan.targetWidth, plan.targetHeight)
      try {
        assert('warped frame is 1600 long edge', warped.width === 1600)
        assert('warped bytes match the planned dims', warped.bytes.length === warped.width * warped.height * 4)
      } finally {
        warped.bytes.fill(0)
      }
    } finally {
      try {
        rgbaMat.data.fill(0)
      } catch { /* best effort */ }
      rgbaMat.delete()
      decoded.rgba.fill(0)
    }

    // Marked scene: the same quad with corner color markers (TL red, TR
    // green, BR yellow, BL blue), warped with the KNOWN ordered corners.
    // Markers would bulge the Canny outline, so detection is proven on the
    // clean scene above; this proves the warp maps corners in canonical order.
    const [sceneC, sceneCCtx] = canvasOf(W, H)
    sceneCCtx.fillStyle = '#101418'
    sceneCCtx.fillRect(0, 0, W, H)
    drawTexturedQuad(sceneCCtx, quad, 7, ['#ff0000', '#00ff00', '#ffff00', '#0000ff'])
    const jpegC = await toJpeg(sceneC)
    const decodedC = await decodeJpegUpright(document, jpegC)
    try {
      const rgbaC = runtime.matFromRgba(decodedC.rgba, decodedC.width, decodedC.height)
      try {
        const known = orderClockwiseFromTopLeft(quad)
        const planC = planWarp([known[0]!, known[1]!, known[2]!, known[3]!])
        const warpedC = runtime.warpRgbaQuad(rgbaC, planC.source, targetCorners(planC.targetWidth, planC.targetHeight), planC.targetWidth, planC.targetHeight)
        try {
          const sampleC = (fx: number, fy: number): [number, number, number] => {
            const x = Math.floor(fx * (warpedC.width - 1))
            const y = Math.floor(fy * (warpedC.height - 1))
            const i = (y * warpedC.width + x) * 4
            return [warpedC.bytes[i]!, warpedC.bytes[i + 1]!, warpedC.bytes[i + 2]!]
          }
          const [rTL, gTL, bTL] = sampleC(0.02, 0.02)
          const [rTR, gTR, bTR] = sampleC(0.98, 0.02)
          const [rBR, gBR, bBR] = sampleC(0.98, 0.98)
          const [rBL, gBL, bBL] = sampleC(0.02, 0.98)
          const MARGIN = 40
          assert('warped TL marker is red', rTL > gTL + MARGIN && rTL > bTL + MARGIN, `${rTL},${gTL},${bTL}`)
          assert('warped TR marker is green', gTR > rTR + MARGIN && gTR > bTR + MARGIN, `${rTR},${gTR},${bTR}`)
          assert('warped BR marker is yellow', rBR > bBR + MARGIN && gBR > bBR + MARGIN, `${rBR},${gBR},${bBR}`)
          assert('warped BL marker is blue', bBL > rBL + MARGIN && bBL > gBL + MARGIN, `${rBL},${gBL},${bBL}`)
        } finally {
          warpedC.bytes.fill(0)
        }
      } finally {
        try {
          rgbaC.data.fill(0)
        } catch { /* best effort */ }
        rgbaC.delete()
      }
    } finally {
      decodedC.rgba.fill(0)
    }

    // EXIF-once: stored landscape JPEG with orientation 6 -> upright portrait.
    const exifJpeg = injectExifOrientation(jpegA, 6)
    const exifDecoded = await decodeJpegUpright(document, exifJpeg)
    try {
      assert('EXIF orientation applied exactly once (dims swapped)', exifDecoded.width === H && exifDecoded.height === W, `${exifDecoded.width}x${exifDecoded.height}`)
      const exifProduced = await produceFrontStillFingerprint(stillFor(exifJpeg, W, H), { fence: () => true })
      assert('EXIF-upright frame extracts', exifProduced.provenance.warpedWidth === 1600)
    } finally {
      exifDecoded.rgba.fill(0)
    }

    // Guide fallback success: full-frame texture, no quad.
    const [tex, texCtx] = canvasOf(320, 240)
    const texRandom = mulberry32(11)
    for (let i = 0; i < 900; i++) {
      const gray = Math.floor(texRandom() * 256)
      texCtx.fillStyle = `rgb(${gray},${gray},${gray})`
      texCtx.fillRect(Math.floor(texRandom() * 320), Math.floor(texRandom() * 240), 6, 6)
    }
    const texJpeg = await toJpeg(tex)
    const guided = await produceFrontStillFingerprint(stillFor(texJpeg, 320, 240), { fence: () => true })
    assert('uncontoured texture uses labelled GUIDE_FALLBACK', guided.provenance.source === 'GUIDE_FALLBACK', guided.provenance.source)

    // Blank frame -> SIFT no-features (no fake pass).
    const [blank, blankCtx] = canvasOf(320, 240)
    blankCtx.fillStyle = '#808080'
    blankCtx.fillRect(0, 0, 320, 240)
    const blankJpeg = await toJpeg(blank)
    assert('blank frame refuses features', (await reason(produceFrontStillFingerprint(stillFor(blankJpeg, 320, 240), { fence: () => true }))) === 'features')

    // Malformed bytes -> decode refusal; stale fence and abort paths.
    assert('malformed bytes refuse decode', (await reason(produceFrontStillFingerprint(stillFor(new Uint8Array([1, 2, 3]), 4, 4), { fence: () => true }))) === 'decode')
    assert('stale fence refuses stale', (await reason(produceFrontStillFingerprint(stillFor(jpegA, W, H), { fence: () => false }))) === 'stale')
    const controller = new AbortController()
    controller.abort()
    assert('pre-aborted signal refuses cancelled', (await reason(produceFrontStillFingerprint(stillFor(jpegA, W, H), { fence: () => true, signal: controller.signal }))) === 'cancelled')
  } catch (error) {
    assert('front-still producer harness', false, String(error))
  }
  ;(window as unknown as { __frontStillProducer?: unknown }).__frontStillProducer = { assertions, failures, report }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
