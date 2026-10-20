/**
 * T348 requested-quality receiving-query browser gate (Chromium-only).
 *
 * A textured JPEG goes through the REAL production identity-free query with
 * `quality: true` (shared cached RawCv -> wrapOpenCvRuntime + accepted native
 * meter), and the default/sender paths stay diagnostic-free with byte-identical
 * wires. A counting wrapper around the REAL runtime/meter/decode proves exactly
 * one decode, one warp and one post-warp measurement. Synthetic only.
 */
import {
  createCaptureQualityMeter,
  evaluateCaptureQualityGate,
  type CaptureQualityMeter,
  type CaptureQualitySignals,
} from '../../src/scan/captureQuality'
import { decodeJpegUpright, produceFrontStillFingerprint } from '../../src/scan/frontStillProducer'
import { produceQueryFingerprint } from '../../src/scan/queryFingerprint'
import { loadOpenCvModule, wrapOpenCvRuntime } from '../../src/scan/opencvRuntime'
import type { CvContourRuntime } from '../../src/scan/opencvContour'
import type { QualityRawCv } from '../../src/scan/opencvQualityPort'
import type { StillCapture } from '../../src/scan/camera'
import type { FrontStill } from '../../src/create/front'
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

function plainStill(bytes: Uint8Array, width: number, height: number): StillCapture {
  return { bytes, width, height }
}
function senderStill(bytes: Uint8Array, width: number, height: number): FrontStill {
  return { bytes, width, height, userId: 't348-owner', keyBundleId: 't348-bundle', directoryVersion: 't348-directory' }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'query-quality-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = { pass: false }
  try {
    const jpeg = await texturedQuadJpeg(7, 640, 480)

    // Requested quality: REAL production query over the shared cached RawCv.
    const requested = await produceQueryFingerprint(plainStill(jpeg.slice(), 640, 480), {
      fence: () => true,
      quality: true,
    })
    assert('requested query returns quality diagnostics', requested.quality !== undefined)
    const signals: CaptureQualitySignals | undefined = requested.quality?.signals
    assert(
      'requested quality has four finite scalars',
      !!signals &&
        [signals.laplacianVariance, signals.nearBlackFraction, signals.clippedWhiteFraction, signals.largestGlareFraction].every(
          (value) => typeof value === 'number' && Number.isFinite(value),
        ),
      JSON.stringify(signals),
    )
    const expectedReasons = evaluateCaptureQualityGate({
      signals: signals!,
      detectedAreaRatio: requested.provenance.areaRatio,
      rectangularity: requested.provenance.rectangularity,
      cropAspectRatio: requested.provenance.warpedWidth / requested.provenance.warpedHeight,
      croppedShortEdgePx: Math.min(requested.provenance.warpedWidth, requested.provenance.warpedHeight),
    })
    assert(
      'requested reasons match the accepted gate on the actual provenance',
      JSON.stringify(requested.quality?.reasons) === JSON.stringify(expectedReasons),
      JSON.stringify({ actual: requested.quality?.reasons, expected: expectedReasons }),
    )
    assert('requested wire parses', parseSiftFingerprint(requested.wire).keypoints.length > 0)

    // Default query: unchanged, no diagnostics.
    const plain = await produceQueryFingerprint(plainStill(jpeg.slice(), 640, 480), { fence: () => true })
    assert('default query has no quality diagnostics', !('quality' in plain))
    assert('requested and default wires are byte-identical', bytesToHex(requested.wire) === bytesToHex(plain.wire))

    // Sender: quality is ignored even when requested in the shared shape.
    const sender = await produceFrontStillFingerprint(senderStill(jpeg.slice(), 640, 480), {
      fence: () => true,
      quality: true,
    })
    assert('sender never includes quality diagnostics', !('quality' in sender))
    assert('sender and query wires are byte-identical', bytesToHex(sender.wire) === bytesToHex(plain.wire))
    assert('sender still carries the recipient', sender.recipient.userId === 't348-owner')

    // Counting run: wrapper calls the REAL runtime/meter/decode exactly once.
    const cv = await loadOpenCvModule()
    const realRuntime = wrapOpenCvRuntime(cv)
    const realMeter = createCaptureQualityMeter(cv as unknown as QualityRawCv)
    let decodeCount = 0
    let warpCount = 0
    let meterCount = 0
    const countingRuntime = new Proxy(realRuntime, {
      get(target, prop) {
        if (prop === 'warpRgbaQuad') {
          return (...args: unknown[]) => {
            warpCount += 1
            const run = target.warpRgbaQuad as (...a: unknown[]) => { bytes: Uint8Array; width: number; height: number }
            return run.apply(target, args)
          }
        }
        const value = (target as unknown as Record<string | symbol, unknown>)[prop]
        return typeof value === 'function' ? value.bind(target) : value
      },
    }) as CvContourRuntime
    const countingMeter: CaptureQualityMeter = {
      measure(bytes, width, height) {
        meterCount += 1
        return realMeter.measure(bytes, width, height)
      },
    }
    const countingDecode = (bytes: Uint8Array) => {
      decodeCount += 1
      return decodeJpegUpright(document, bytes)
    }
    const counted = await produceQueryFingerprint(plainStill(jpeg.slice(), 640, 480), {
      fence: () => true,
      quality: true,
      runtime: countingRuntime,
      meter: countingMeter,
      decode: countingDecode,
    })
    assert('requested query decoded exactly once', decodeCount === 1, String(decodeCount))
    assert('requested query warped exactly once', warpCount === 1, String(warpCount))
    assert('requested query measured exactly once', meterCount === 1, String(meterCount))
    assert('counted wire equals the default query wire', bytesToHex(counted.wire) === bytesToHex(plain.wire))
    assert('counted quality has finite scalars', !!counted.quality && Number.isFinite(counted.quality.signals.laplacianVariance))

    report.quality = signals
    report.reasons = requested.quality?.reasons
    report.counts = { decode: decodeCount, warp: warpCount, meter: meterCount }
    report.wireEqual = bytesToHex(requested.wire) === bytesToHex(plain.wire)
    report.senderQualityAbsent = !('quality' in sender)
    report.reasonsExpected = expectedReasons
    report.pass = failures.length === 0
  } catch (error) {
    assert('query quality harness', false, String(error))
  }
  ;(window as unknown as { __queryQuality?: unknown }).__queryQuality = { assertions, failures, report }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
