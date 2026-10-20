/**
 * T346 connected receiving engine (Chromium-only, synthetic).
 *
 * REAL production factory: identity-free query producer → strict format-3
 * parse → real `matchSide` (pinned OpenCV matcher/geometry + accepted scorer)
 * → accepted FRONT policy → existing MatchResult. A synthetic in-memory index
 * of GENUINE sender-extracted references (test harness only) proves unique →
 * one, two distinct IDs sharing one genuine reference → many, unrelated
 * reference → none, and an incomplete page → bounded refusal. No camera,
 * field, grant, admission or crypto journey claim.
 */
import { produceFrontStillFingerprint } from '../../src/scan/frontStillProducer'
import { parseSiftFingerprint, type SiftFingerprint, type SiftFingerprintKeypoint } from '../../src/scan/siftFingerprint'
import { createRecognitionEngine, RecognitionEngineError } from '../../src/scan/recognitionEngine'
import type { RecognitionIndex } from '../../src/scan/recognitionIndex'
import type { IncomingCatalog, IncomingCapsule } from '../../src/sync/incoming'
import type { FrontStill } from '../../src/create/front'
import type { StillCapture } from '../../src/scan/camera'

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
  return { bytes, width, height, userId: 't346-owner', keyBundleId: 't346-bundle', directoryVersion: 't346-directory' }
}

function zeroRowFingerprint(): SiftFingerprint {
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

function indexOf(
  entries: Array<{ capsuleId: string; fingerprint: SiftFingerprint }>,
  options: { hasMore?: boolean; nextCursor?: string | null } = {},
): RecognitionIndex {
  return {
    entries: () => entries,
    hasMore: options.hasMore ?? false,
    nextCursor: options.nextCursor ?? null,
    disposed: false,
  } as unknown as RecognitionIndex
}

function catalogOf(capsuleIds: string[]): IncomingCatalog {
  return {
    items: capsuleIds.map((capsuleId) => ({ capsuleId }) as IncomingCapsule),
    hasMore: false,
    nextCursor: null,
  }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'recognition-engine-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = { pass: false }
  try {
    const jpeg = await texturedQuadJpeg(7, 640, 480)
    const jpegSnapshot = jpeg.slice()
    const produced = await produceFrontStillFingerprint(senderStill(jpeg.slice(), 640, 480), { fence: () => true })
    const reference = parseSiftFingerprint(produced.wire)
    const referenceRows = reference.quantizedSiftDescriptors.map((row) => Array.from(row))
    assert('genuine sender reference has descriptors', reference.quantizedSiftDescriptors.length > 0)

    const capture: StillCapture = { bytes: jpeg, width: 640, height: 480 }
    const engine = createRecognitionEngine()

    const unique = await engine.match(capture, catalogOf(['c1']), { index: indexOf([{ capsuleId: 'c1', fingerprint: reference }]) })
    assert('unique strong reference yields one exact capsule', unique.kind === 'one' && unique.capsuleId === 'c1', JSON.stringify(unique))
    report.unique = unique

    const again = await engine.match(capture, catalogOf(['c1']), { index: indexOf([{ capsuleId: 'c1', fingerprint: reference }]) })
    assert('repeat matching is deterministic', JSON.stringify(again) === JSON.stringify(unique))

    const many = await engine.match(capture, catalogOf(['c1', 'c2']), {
      index: indexOf([
        { capsuleId: 'c1', fingerprint: reference },
        { capsuleId: 'c2', fingerprint: reference },
      ]),
    })
    assert('two distinct IDs sharing the genuine reference yield many', many.kind === 'many', JSON.stringify(many))
    report.many = many

    const none = await engine.match(capture, catalogOf(['z1']), {
      index: indexOf([{ capsuleId: 'z1', fingerprint: zeroRowFingerprint() }]),
    })
    assert('unrelated zero-row reference yields no accepted one', none.kind === 'none', JSON.stringify(none))
    report.none = none

    let incompleteCode: string | null = null
    try {
      const incompleteCatalog: IncomingCatalog = { items: [{ capsuleId: 'c1' } as IncomingCapsule], hasMore: true, nextCursor: 'next' }
      await engine.match(capture, incompleteCatalog, { index: indexOf([{ capsuleId: 'c1', fingerprint: reference }]) })
    } catch (error) {
      incompleteCode = error instanceof RecognitionEngineError ? error.code : 'other'
    }
    assert('incomplete page fails closed with a bounded refusal', incompleteCode === 'incomplete', String(incompleteCode))

    assert('caller capture bytes are unchanged', Array.from(capture.bytes).every((byte, i) => byte === jpegSnapshot[i]))
    assert(
      'original index reference descriptor rows are unchanged',
      reference.quantizedSiftDescriptors.every((row, index) => JSON.stringify(Array.from(row)) === JSON.stringify(referenceRows[index])),
    )

    report.pass = failures.length === 0
  } catch (error) {
    assert('recognition engine harness', false, String(error))
  }
  ;(window as unknown as { __recognitionEngine?: unknown }).__recognitionEngine = { assertions, failures, report }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
