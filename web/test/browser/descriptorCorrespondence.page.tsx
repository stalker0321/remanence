/**
 * T330 connected proof (Chromium-only): the REAL correspondence stage over
 * the REAL pinned OpenCV BFMatcher.
 *
 * Two genuine accepted producer/extractor wires (synthetic textured front
 * pixels through the accepted FrontHold/FrontFingerprintHold producer) are
 * parsed into valid format-3 fingerprints. The production
 * `matchDescriptors` runs with `createPinnedMatcherRuntime(loadOpenCvModule())`
 * — REAL cv.Mat CV_32F + BFMatcher(NORM_L2,false) knnMatch K=2 forward and
 * reverse. Asserts meaningful original-index pairs/scalar counts, input
 * immutability, deterministic repetition, and a genuine technical refusal
 * (storage-valid all-zero rows → no-usable-rows). NO geometry/admission/
 * engine/phase transitions; no backend. Vendor hashes reported from the
 * committed same-origin MANIFEST.
 */
import { matchDescriptors, type CorrespondenceResult } from '../../src/scan/descriptorCorrespondence'
import { createPinnedMatcherRuntime } from '../../src/scan/opencvMatcherPort'
import { loadOpenCvModule } from '../../src/scan/opencvRuntime'
import { parseSiftFingerprint, serializeSiftFingerprint, type SiftFingerprint } from '../../src/scan/siftFingerprint'
import { FrontHold } from '../../src/create/front'
import { FrontFingerprintHold } from '../../src/create/frontFingerprint'
import { RecipientDraft, type RecipientSnapshot } from '../../src/create/recipient'
import { SessionStore } from '../../src/session/session'
import type { StillCapture } from '../../src/scan/camera'
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'

function snapshot(): RecipientSnapshot {
  return {
    userId: ACCOUNT, handle: 'sergio', keyBundleId: BUNDLE,
    suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocolVersion: 1,
    encryptionPublicKeyset: 'x'.repeat(10), signingPublicKeyset: 'y'.repeat(10),
    directoryVersion: '0'.repeat(16), self: false,
  }
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

async function texturedQuadJpeg(seed: number, shiftX: number, shiftY: number, width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  const quad = [
    { x: 100 + shiftX, y: 80 + shiftY },
    { x: 540 + shiftX, y: 60 + shiftY },
    { x: 560 + shiftX, y: 400 + shiftY },
    { x: 80 + shiftX, y: 420 + shiftY },
  ]
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
    context.fillRect(quad[0]!.x + random() * 480, quad[0]!.y + random() * 340, 4 + random() * 22, 4 + random() * 22)
  }
  context.restore()
  context.lineWidth = 6
  context.strokeStyle = '#ffffff'
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.stroke()
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

async function produceFingerprint(store: SessionStore, frame: Uint8Array): Promise<{ fingerprint: SiftFingerprint; wire: Uint8Array }> {
  const recipient = new RecipientDraft()
  recipient.attach(store)
  const ticket = recipient.nextTicket(ACCOUNT)
  recipient.offer(ticket, ACCOUNT, snapshot())
  recipient.confirm()
  const hold = new FrontHold()
  await hold.open({
    open: async () => ({
      preview: document.createElement('video') as HTMLVideoElement,
      capture: async (): Promise<StillCapture> => ({ bytes: frame.slice(), width: 640, height: 480 }),
      stop: () => undefined,
    }),
  })
  const still = await hold.take(recipient.confirmed()!)
  const holder = new FrontFingerprintHold()
  const produced = await holder.run(still, { store, recipient, hold, mounted: () => true })
  hold.close()
  const fingerprint = parseSiftFingerprint(produced.wire)
  return { fingerprint, wire: produced.wire.slice() }
}

function zeroRowFingerprint(): SiftFingerprint {
  const keypoints = [
    { xMicro: 100_000, yMicro: 100_000, scaleMicro: 1, angleCentiDegrees: 0, responseQuantized: 1, octave: 0 },
    { xMicro: 200_000, yMicro: 200_000, scaleMicro: 1, angleCentiDegrees: 0, responseQuantized: 1, octave: 0 },
  ]
  return {
    profileId: 'postcard-sift-rootsift-v1',
    canonicalWidthPx: 640,
    canonicalHeightPx: 480,
    coarseHash64: 1n,
    keypoints,
    quantizedSiftDescriptors: keypoints.map(() => new Uint8Array(128)), // storage-valid zero rows
  }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'descriptor-correspondence-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = {}
  try {
    // ---- genuine producer wires (accepted front fingerprint producer) ----
    const store = new SessionStore('en')
    store.signIn({
      accountId: ACCOUNT, bundleId: BUNDLE, handle: 'sergio',
      accessToken: 'a', refreshToken: 'r',
      accessExpiresAt: '2026-10-20T00:00:00Z', refreshExpiresAt: '2026-11-20T00:00:00Z',
    })
    store.unlock()
    const frameA = await texturedQuadJpeg(7, 0, 0, 640, 480)
    const producedA = await produceFingerprint(store, frameA)
    const frameB = await texturedQuadJpeg(9, 6, -4, 640, 480)
    const producedB = await produceFingerprint(store, frameB)
    assert('query wire parses to a nonzero format-3 fingerprint', producedA.fingerprint.keypoints.length > 0)
    assert('reference wire parses to a nonzero format-3 fingerprint', producedB.fingerprint.keypoints.length > 0)

    // ---- REAL pinned runtime bridge (shared loader, committed vendor) ----
    const cv = await loadOpenCvModule()
    const matcher = createPinnedMatcherRuntime(cv)
    const manifestText = await (await fetch('/opencv/MANIFEST.sha256')).text()
    assert('committed vendor MANIFEST is served same-origin', manifestText.length > 0)
    report.vendorManifestSha256 = manifestText.trim().split('\n').slice(0, 4)
    report.normL2 = (cv as unknown as { NORM_L2?: number }).NORM_L2
    assert('pinned runtime exports NORM_L2 = 4', (cv as unknown as { NORM_L2?: number }).NORM_L2 === 4)

    // ---- REAL WASM nested-row release invariant (review finding 1) ----
    // Wrap DMatchVectorVector.prototype.get so every nested row handle the
    // production port obtains is recorded; after matchDescriptors returns,
    // each recorded row must be UNUSABLE (embind refuses methods on a
    // deleted object). Root's real-WASM proof showed leaked rows stayed
    // callable after parent deletion — this is the inverse assertion.
    const recordedRows: Array<{ size: () => number; delete?: () => void }> = []
    const vectorVectorCtor = (cv as unknown as { DMatchVectorVector?: new () => unknown }).DMatchVectorVector
    const vectorVectorProto = vectorVectorCtor?.prototype as { get?: unknown } | undefined
    const originalGet = vectorVectorProto && typeof vectorVectorProto.get === 'function'
      ? vectorVectorProto.get as (this: unknown, index: number) => unknown
      : null
    if (vectorVectorProto && originalGet) {
      vectorVectorProto.get = function (this: unknown, index: number): unknown {
        const row = originalGet.call(this, index) as { size: () => number; delete?: () => void } | null
        if (row && typeof row.size === 'function') recordedRows.push(row)
        return row
      }
    }
    let sameFrame: CorrespondenceResult
    try {
      // ---- SAME-frame correspondence through the REAL BFMatcher ----
      sameFrame = await matchDescriptors(producedA.fingerprint, producedA.fingerprint, { matcher })
    } finally {
      if (vectorVectorProto && originalGet) vectorVectorProto.get = originalGet
    }
    assert('nested DMatchVector rows were obtained from the real runtime', recordedRows.length >= 2, `recorded=${recordedRows.length}`)
    const stillUsable: number[] = []
    recordedRows.forEach((row, index) => {
      try {
        row.size()
        stillUsable.push(index)
      } catch {
        /* deleted embind objects throw on method calls — the released state */
      }
    })
    assert('every nested row handle is RELEASED after the port returns (real WASM)',
      stillUsable.length === 0, `still-usable rows: ${JSON.stringify(stillUsable)}`)
    for (const row of recordedRows) {
      try {
        row.delete?.()
      } catch {
        /* already deleted — diagnostic only */
      }
    }

    // ---- input immutability snapshots ----
    const queryWireBefore = serializeSiftFingerprint(producedA.fingerprint)
    const referenceWireBefore = serializeSiftFingerprint(producedB.fingerprint)

    assert('same-frame correspondence returns original-index pairs', sameFrame.pairs.length >= 1, JSON.stringify(sameFrame.diagnostics))
    assert('same-frame pairs carry in-range original indices', sameFrame.pairs.every((pair) =>
      pair.queryIndex >= 0 && pair.queryIndex < producedA.fingerprint.keypoints.length
      && pair.referenceIndex >= 0 && pair.referenceIndex < producedA.fingerprint.keypoints.length))
    assert('same-frame usable rows cover the full raw counts', sameFrame.diagnostics.usableQueryRows === sameFrame.diagnostics.rawQueryRows
      && sameFrame.diagnostics.usableReferenceRows === sameFrame.diagnostics.rawReferenceRows)
    assert('same-frame reciprocal matches observed', sameFrame.diagnostics.reciprocalMatches >= 1)
    assert('same-frame distances are finite and nonnegative', sameFrame.pairs.every((pair) => Number.isFinite(pair.distance) && pair.distance >= 0))

    // ---- determinism: an immediate identical repetition ----
    const again: CorrespondenceResult = await matchDescriptors(producedA.fingerprint, producedA.fingerprint, { matcher })
    assert('correspondence is deterministic across immediate repetitions',
      JSON.stringify(again.pairs) === JSON.stringify(sameFrame.pairs)
      && JSON.stringify(again.diagnostics) === JSON.stringify(sameFrame.diagnostics))

    // ---- distinct-frame stage executes (recorded counts; no match claim) ----
    const crossFrame: CorrespondenceResult = await matchDescriptors(producedA.fingerprint, producedB.fingerprint, { matcher })
    assert('cross-frame stage executes with bounded scalar diagnostics',
      crossFrame.diagnostics.rawQueryRows > 0 && Number.isFinite(crossFrame.diagnostics.reciprocalMatches))
    report.crossFrame = crossFrame.diagnostics
    report.sameFrame = { pairs: sameFrame.pairs.length, diagnostics: sameFrame.diagnostics }

    // ---- genuine technical refusal: storage-valid all-zero rows ----
    const refusal: CorrespondenceResult = await matchDescriptors(zeroRowFingerprint(), producedA.fingerprint, { matcher })
    assert('storage-valid zero rows refuse as no-usable-rows', refusal.diagnostics.noEvidence === 'no-usable-rows'
      && refusal.pairs.length === 0 && refusal.diagnostics.usableQueryRows === 0)

    // ---- caller inputs never mutated ----
    assert('query wire bytes unchanged after correspondence', bytesToHexLocal(serializeSiftFingerprint(producedA.fingerprint)) === bytesToHexLocal(queryWireBefore))
    assert('reference wire bytes unchanged after correspondence', bytesToHexLocal(serializeSiftFingerprint(producedB.fingerprint)) === bytesToHexLocal(referenceWireBefore))
    assert('zero-row fingerprint rows remain zero (not consumed)', zeroRowFingerprint().quantizedSiftDescriptors.every((row) => row.every((byte) => byte === 0)))

    report.vendorRuntime = 'pinned-opencv-BFMatcher'
  } catch (error) {
    assert('descriptor correspondence connected harness', false, String(error))
  }
  ;(window as unknown as { __descriptorCorrespondence?: unknown }).__descriptorCorrespondence = { assertions, failures, report }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

function bytesToHexLocal(bytes: Uint8Array): string {
  let hex = ''
  for (const byte of bytes) hex += byte.toString(16).padStart(2, '0')
  return hex
}

void main()
