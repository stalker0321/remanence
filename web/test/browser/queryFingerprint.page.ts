/**
 * T343 identity-free receiving-query producer (Chromium-only, synthetic).
 *
 * A textured JPEG goes through the NEW shared query seam (plain StillCapture,
 * no recipient identity) and the unchanged sender wrapper. Both wires must be
 * byte-identical for the same source; the query output carries no owner fields.
 * The query wire then feeds the accepted connected single-side pipeline
 * (`matchSide` with the real pinned matcher/geometry) against the same-frame
 * sender reference and must reach truthful weak/strong results and >=6 pairs.
 * Synthetic positive only: no camera/field/admission/native-bit claim.
 */
import { produceFrontStillFingerprint } from '../../src/scan/frontStillProducer'
import { produceQueryFingerprint } from '../../src/scan/queryFingerprint'
import type { FrontStill } from '../../src/create/front'
import type { StillCapture } from '../../src/scan/camera'
import { matchSide } from '../../src/scan/sideMatcher'
import { parseSiftFingerprint } from '../../src/scan/siftFingerprint'
import { bytesToHex } from '../../src/protocol/bytes'

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
    state = (state + 0x6d2b79f5) >>> 0
    let value = Math.imul(state ^ (state >>> 15), 1 | state)
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296
  }
}

async function texturedQuadJpeg(seed: number, width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  const quad = [
    { x: 100, y: 80 },
    { x: 540, y: 60 },
    { x: 560, y: 400 },
    { x: 80, y: 420 },
  ]
  context.save()
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let index = 1; index < quad.length; index++) context.lineTo(quad[index]!.x, quad[index]!.y)
  context.closePath()
  context.clip()
  const random = mulberry32(seed)
  for (let index = 0; index < 260; index++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    context.fillRect(quad[0]!.x + random() * 480, quad[0]!.y + random() * 340, 4 + random() * 22, 4 + random() * 22)
  }
  context.restore()
  context.lineWidth = 6
  context.strokeStyle = '#ffffff'
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let index = 1; index < quad.length; index++) context.lineTo(quad[index]!.x, quad[index]!.y)
  context.closePath()
  context.stroke()
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

function senderStill(bytes: Uint8Array, width: number, height: number): FrontStill {
  return { bytes, width, height, userId: 't343-owner', keyBundleId: 't343-bundle', directoryVersion: 't343-directory' }
}

function plainStill(bytes: Uint8Array, width: number, height: number): StillCapture {
  return { bytes, width, height }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'query-fingerprint-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = { pass: false }
  try {
    const jpeg = await texturedQuadJpeg(7, 640, 480)
    const senderBytes = jpeg.slice()
    const queryBytes = jpeg.slice()

    const query = await produceQueryFingerprint(plainStill(queryBytes, 640, 480), { fence: () => true })
    const sender = await produceFrontStillFingerprint(senderStill(senderBytes, 640, 480), { fence: () => true })

    assert('query output has no recipient field', !('recipient' in query))
    assert(
      'query output has no owner identifiers',
      !('userId' in query) && !('keyBundleId' in query) && !('directoryVersion' in query),
    )
    assert('sender output still carries the captured recipient', sender.recipient.userId === 't343-owner')
    assert('same JPEG yields byte-identical sender/query wires', bytesToHex(query.wire) === bytesToHex(sender.wire))
    assert('same JPEG yields the same profile id', query.profileId === sender.profileId)
    assert('same JPEG yields the same crop provenance', JSON.stringify(query.provenance) === JSON.stringify(sender.provenance))

    const distinctJpeg = await texturedQuadJpeg(99, 640, 480)
    const distinct = await produceQueryFingerprint(plainStill(distinctJpeg, 640, 480), { fence: () => true })
    assert('distinct frame yields a distinct query wire', bytesToHex(distinct.wire) !== bytesToHex(query.wire))
    report.distinctWireHex = bytesToHex(distinct.wire)

    const queryFingerprint = parseSiftFingerprint(query.wire)
    const referenceFingerprint = parseSiftFingerprint(sender.wire)
    assert('query wire parses to a nonempty fingerprint', queryFingerprint.keypoints.length > 0)
    report.wireHex = bytesToHex(query.wire)

    const side = await matchSide(queryFingerprint, referenceFingerprint)
    assert('query↔same-frame reference reaches >=6 unique pairs', side.correspondence.diagnostics.uniqueMatches >= 6, String(side.correspondence.diagnostics.uniqueMatches))
    assert('query↔reference geometry is attempted', side.diagnostics.geometryAttempted === true)
    assert('query↔reference weak gate passes', side.report.weakGatePassed === true)
    assert('query↔reference strong gate passes', side.report.strongGatePassed === true)
    assert('query↔reference has inliers', side.signals.ransacInliers > 0, String(side.signals.ransacInliers))
    report.positive = {
      uniqueMatches: side.correspondence.diagnostics.uniqueMatches,
      inliers: side.signals.ransacInliers,
      sideScore: side.report.sideScore,
      weak: side.report.weakGatePassed,
      strong: side.report.strongGatePassed,
    }

    // EXIF-once / decoded-bound semantics: a JPEG with orientation 6 decodes
    // upright exactly once for both entries, yielding the same wire.
    const exifJpeg = injectExifOrientation(jpeg, 6)
    const exifQuery = await produceQueryFingerprint(plainStill(exifJpeg.slice(), 640, 480), { fence: () => true })
    const exifSender = await produceFrontStillFingerprint(senderStill(exifJpeg.slice(), 640, 480), { fence: () => true })
    assert('EXIF orientation is applied once for both entries', bytesToHex(exifQuery.wire) === bytesToHex(exifSender.wire))
    assert('EXIF query output stays identity-free', !('recipient' in exifQuery))

    // A pre-aborted query refuses without producing a wire.
    const controller = new AbortController()
    controller.abort()
    let refused = false
    try {
      await produceQueryFingerprint(plainStill(jpeg.slice(), 640, 480), { fence: () => true, signal: controller.signal })
    } catch (error) {
      refused = (error as { code?: string }).code === 'cancelled'
    }
    assert('pre-aborted query refuses typed cancelled', refused)

    report.pass = failures.length === 0
  } catch (error) {
    assert('query producer harness', false, String(error))
  }
  ;(window as unknown as { __queryFingerprint?: unknown }).__queryFingerprint = { assertions, failures, report }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

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

void main()
