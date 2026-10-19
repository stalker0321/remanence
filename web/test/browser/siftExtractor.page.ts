/**
 * T312 — genuine image -> fingerprint browser extraction gate (Chromium-only).
 *
 * Loads the vendored custom OpenCV.js WASM (real `SIFT` + `dct`) on loopback and
 * runs the REAL production `extractSiftFingerprint` from a deterministic
 * already-canonical RGBA frame through real SIFT detect -> selection -> compute
 * -> metadata/row quantization -> coarse-hash DCT -> accepted format-3 codec.
 * This proves a real-runtime image->fingerprint pipeline and format-3 validity;
 * it is NOT localization/warp from a photographed postcard, field accuracy,
 * RootSIFT, matching, quality policy, or Android/native numerical parity.
 */
import { bytesToHex } from '../../src/protocol/bytes'
import {
  extractSiftFingerprint,
  SiftExtractionError,
} from '../../src/scan/siftExtractor'
import {
  MAX_KEYPOINTS,
  parseSiftFingerprint,
  serializeSiftFingerprint,
} from '../../src/scan/siftFingerprint'
import { canonicalFrame } from '../fixtures/canonicalFrame'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

async function reasonOf(action: () => Promise<unknown>): Promise<string> {
  try {
    await action()
    return 'ok'
  } catch (error) {
    return error instanceof SiftExtractionError ? error.code : 'other'
  }
}

const WIDTH = 384
const HEIGHT = 288
const status = document.createElement('p')
status.id = 'sift-extractor-status'
status.textContent = 'starting'
document.body.appendChild(status)

interface Exposed {
  assertions: CheckResult[]
  failures: string[]
  wireHex: string
  distinctWireHex: string
  keypointCount: number
  coarseHash64: string
}

async function main(): Promise<void> {
  const texture = WIDTH * HEIGHT
  assert('the synthetic frame is a non-trivial size', texture > 0)

  const frame = canonicalFrame(WIDTH, HEIGHT, 1)
  const inputSnapshot = frame.pixels.slice()

  const fingerprint = await extractSiftFingerprint(frame)
  const wire = serializeSiftFingerprint(fingerprint)
  assert('real SIFT detects a non-empty keypoint set', fingerprint.keypoints.length > 0, `count=${fingerprint.keypoints.length}`)
  assert('keypoint count stays within the bounded cap', fingerprint.keypoints.length <= MAX_KEYPOINTS, `count=${fingerprint.keypoints.length}`)
  assert('feature content is non-trivial (not a lone detection)', fingerprint.keypoints.length >= 20, `count=${fingerprint.keypoints.length}`)
  assert(
    'descriptor rows align one-per-keypoint at 128 bytes',
    fingerprint.quantizedSiftDescriptors.length === fingerprint.keypoints.length &&
      fingerprint.quantizedSiftDescriptors.every((row) => row.length === 128),
  )
  assert(
    'keypoint metadata is inside the format-3 ranges',
    fingerprint.keypoints.every(
      (keypoint) =>
        keypoint.xMicro >= 0 && keypoint.xMicro <= 1_000_000 &&
        keypoint.yMicro >= 0 && keypoint.yMicro <= 1_000_000 &&
        keypoint.scaleMicro >= 0 && keypoint.scaleMicro <= 1_000_000 &&
        keypoint.angleCentiDegrees >= 0 && keypoint.angleCentiDegrees <= 35_999 &&
        keypoint.responseQuantized >= 0 && keypoint.responseQuantized <= 1_000_000 &&
        keypoint.octave >= -8 && keypoint.octave <= 8,
    ),
  )
  assert('a coarse hash is produced', typeof fingerprint.coarseHash64 === 'bigint')
  assert(
    'keypoint coordinates stay inside the canonical frame',
    fingerprint.keypoints.every((keypoint) => Math.abs(keypoint.xMicro) <= 1_000_000 && Math.abs(keypoint.yMicro) <= 1_000_000),
  )
  const distinctRows = new Set(fingerprint.quantizedSiftDescriptors.map((row) => bytesToHex(row)))
  assert('descriptor rows carry real, varied content', distinctRows.size > 5, `distinct=${distinctRows.size}`)
  assert('some descriptor row is matcher-usable (nonzero)', fingerprint.quantizedSiftDescriptors.some((row) => row.some((byte) => byte !== 0)))
  assert('the caller frame pixels are not mutated', bytesToHex(frame.pixels) === bytesToHex(inputSnapshot))

  const reparsed = parseSiftFingerprint(wire)
  assert('the produced wire canonicalizes through the accepted codec', reparsed.keypoints.length === fingerprint.keypoints.length && reparsed.coarseHash64 === fingerprint.coarseHash64)

  const repeat = await extractSiftFingerprint(canonicalFrame(WIDTH, HEIGHT, 1))
  assert('the same frame reproducibly yields identical wire bytes', bytesToHex(serializeSiftFingerprint(repeat)) === bytesToHex(wire))

  const otherFrame = canonicalFrame(WIDTH, HEIGHT, 7)
  const otherWire = serializeSiftFingerprint(await extractSiftFingerprint(otherFrame))
  assert('a distinct textured frame yields distinct wire bytes', bytesToHex(otherWire) !== bytesToHex(wire))

  assert('a blank frame is refused as no-features', (await reasonOf(() => extractSiftFingerprint(canonicalFrame(64, 64, 0, true)))) === 'no-features')

  const controller = new AbortController()
  controller.abort()
  assert('an already-aborted signal is refused as aborted', (await reasonOf(() => extractSiftFingerprint(canonicalFrame(64, 64, 2), { signal: controller.signal }))) === 'aborted')

  const exposed: Exposed = {
    assertions,
    failures,
    wireHex: bytesToHex(wire),
    distinctWireHex: bytesToHex(otherWire),
    keypointCount: fingerprint.keypoints.length,
    coarseHash64: `${fingerprint.coarseHash64}`,
  }
  ;(window as unknown as { __siftExtractor?: Exposed }).__siftExtractor = exposed
}

main()
  .then(() => {
    status.textContent = failures.length === 0 ? 'done' : 'failed'
  })
  .catch((error) => {
    assert('sift extraction harness', false, String(error))
    status.textContent = 'failed'
    ;(window as unknown as { __siftExtractor?: Exposed }).__siftExtractor = {
      assertions,
      failures,
      wireHex: '',
      distinctWireHex: '',
      keypointCount: 0,
      coarseHash64: '0',
    }
  })
