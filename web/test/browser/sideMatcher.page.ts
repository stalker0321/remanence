/**
 * T341 connected real single-side pipeline (Chromium-only).
 *
 * Genuine producer/extractor format3 wire → the adapter's DEFAULT real pipeline
 * (`matchSide` with the real pinned OpenCV matcher + real pinned geometry
 * estimator + unchanged scorer) → full-card policy. Same-frame known positive
 * must reach >=6 unique pairs and truthful weak/strong results and repeat
 * deterministically; a synthetic all-zero-row fingerprint is an explicitly
 * synthetic negative (no usable rows, estimator skipped). This proves a real
 * connected technical pipeline only — no engine/grant/field/native-bit claim.
 */
import { produceFrontStillFingerprint } from '../../src/scan/frontStillProducer'
import type { FrontStill } from '../../src/create/front'
import { matchSide } from '../../src/scan/sideMatcher'
import {
  parseSiftFingerprint,
  type SiftFingerprint,
  type SiftFingerprintKeypoint,
} from '../../src/scan/siftFingerprint'
import { bytesToHex } from '../../src/protocol/bytes'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

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

function stillFor(bytes: Uint8Array, width: number, height: number): FrontStill {
  return { bytes, width, height, userId: 't341-owner', keyBundleId: 't341-bundle', directoryVersion: 't341-directory' }
}

function syntheticZeroRowFingerprint(): SiftFingerprint {
  const keypoints: SiftFingerprintKeypoint[] = Array.from({ length: 6 }, (_, index) => ({
    xMicro: (index + 1) * 100000,
    yMicro: (index + 1) * 100000,
    scaleMicro: 1000,
    angleCentiDegrees: 0,
    responseQuantized: 1000,
    octave: 0,
  }))
  return {
    profileId: 'postcard-sift-rootsift-v1',
    canonicalWidthPx: 1600,
    canonicalHeightPx: 1200,
    coarseHash64: 0n,
    keypoints,
    quantizedSiftDescriptors: keypoints.map(() => new Uint8Array(128)),
  }
}

function serializeDomain(fingerprint: SiftFingerprint): string {
  return JSON.stringify(fingerprint, (_key, value) => (typeof value === 'bigint' ? `${value}` : value))
}

const status = document.createElement('p')
status.id = 'side-matcher-status'
status.textContent = 'starting'
document.body.appendChild(status)

interface Report {
  positive: { uniqueMatches: number; inliers: number; sideScore: number; weak: boolean; strong: boolean; plausible: boolean } | null
  repeatUnique: number | null
  negativeFailure: string | null
  wireHex: string
}

async function main(): Promise<void> {
  const report: Report = { positive: null, repeatUnique: null, negativeFailure: null, wireHex: '' }
  try {
    const jpeg = await texturedQuadJpeg(7, 640, 480)
    const produced = await produceFrontStillFingerprint(stillFor(jpeg, 640, 480), { fence: () => true })
    const fingerprint = parseSiftFingerprint(produced.wire)
    report.wireHex = bytesToHex(produced.wire)
    assert('genuine producer wire parses to a nonempty fingerprint', fingerprint.keypoints.length > 0)
    const inputSnapshot = serializeDomain(fingerprint)

    const positive = await matchSide(fingerprint, fingerprint)
    report.positive = {
      uniqueMatches: positive.correspondence.diagnostics.uniqueMatches,
      inliers: positive.diagnostics.inliers,
      sideScore: positive.report.sideScore,
      weak: positive.report.weakGatePassed,
      strong: positive.report.strongGatePassed,
      plausible: positive.signals.homographyPlausible,
    }
    assert('same-frame known positive reaches >=6 unique pairs', positive.correspondence.diagnostics.uniqueMatches >= 6, String(positive.correspondence.diagnostics.uniqueMatches))
    assert('positive geometry was attempted', positive.diagnostics.geometryAttempted === true)
    assert('positive geometry is present', positive.geometry !== null)
    assert('positive carries inliers', positive.signals.ransacInliers > 0, String(positive.signals.ransacInliers))
    assert('positive homography is plausible', positive.signals.homographyPlausible === true)
    assert('positive weak gate passes', positive.report.weakGatePassed === true)
    assert('positive strong gate passes', positive.report.strongGatePassed === true)
    assert('positive geometry is marked found', positive.geometry?.found === true)
    assert('positive side score stays within [0,1]', positive.report.sideScore >= 0 && positive.report.sideScore <= 1, String(positive.report.sideScore))

    const repeat = await matchSide(fingerprint, fingerprint)
    report.repeatUnique = repeat.correspondence.diagnostics.uniqueMatches
    assert('repeat unique-pair count is deterministic', repeat.correspondence.diagnostics.uniqueMatches === positive.correspondence.diagnostics.uniqueMatches)
    assert('repeat side score is deterministic', repeat.report.sideScore === positive.report.sideScore)
    assert('repeat signals are identical', JSON.stringify(repeat.signals) === JSON.stringify(positive.signals))

    const negative = await matchSide(fingerprint, syntheticZeroRowFingerprint())
    report.negativeFailure = negative.diagnostics.failure
    assert('synthetic zero-row negative is truthful no-usable-rows', negative.diagnostics.failure === 'NO_USABLE_ROWS', negative.diagnostics.failure)
    assert('synthetic negative skips the estimator', negative.diagnostics.geometryAttempted === false && negative.geometry === null)
    assert('synthetic negative yields no weak/strong acceptance', negative.report.weakGatePassed === false && negative.report.strongGatePassed === false)

    assert('adapter does not mutate caller fingerprint rows/keypoints/dims', serializeDomain(fingerprint) === inputSnapshot)
    assert('adapter result is plain data (no descriptor rows exposed)', !('quantizedSiftDescriptors' in positive.signals) && !('matrix' in positive.signals))
    ;(window as unknown as { __sideMatcher?: unknown }).__sideMatcher = { assertions, failures, report }
    status.textContent = failures.length === 0 ? 'done' : 'failed'
  } catch (error) {
    fail('connected side pipeline harness', String(error))
    ;(window as unknown as { __sideMatcher?: unknown }).__sideMatcher = { assertions, failures, report }
    status.textContent = 'failed'
  }
}

void main()
