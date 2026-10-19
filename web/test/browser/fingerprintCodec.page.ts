/**
 * T308 fingerprint-codec browser gate (Chromium-only).
 *
 * Loads the committed genuine Kotlin vectors and executes the REAL production
 * `serializeSiftFingerprint`/`parseSiftFingerprint` in a pinned browser: exact
 * wire bytes, proto3 defaults, signed bigint fixed64 (0/-1/MIN/MAX/high-bit),
 * descriptor-row ownership, and typed refusals. This proves BROWSER CODEC
 * PORTABILITY ONLY — not image->fingerprint extraction, scan, quality, matching,
 * or field accuracy. No fake extractor/grant/UI.
 */
import { bytesToHex, hexToBytes } from '../../src/protocol/bytes'
import {
  parseSiftFingerprint,
  serializeSiftFingerprint,
  SiftFingerprintError,
  SIFT_FINGERPRINT_PROFILE_ID,
  type SiftFingerprint,
} from '../../src/scan/siftFingerprint'
import vectors from '../fingerprint-vectors/fingerprint-vectors.json'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

interface Vector {
  name: string
  canonicalWidthPx: number
  canonicalHeightPx: number
  coarseHash64: string
  keypoints: Array<{ xMicro: number; yMicro: number; scaleMicro: number; angleCentiDegrees: number; responseQuantized: number; octave: number }>
  descriptorsHex: string[]
  wireHex: string
}

const ALL = vectors.vectors as Vector[]

function domainFor(vector: Vector): SiftFingerprint {
  return {
    profileId: SIFT_FINGERPRINT_PROFILE_ID,
    canonicalWidthPx: vector.canonicalWidthPx,
    canonicalHeightPx: vector.canonicalHeightPx,
    coarseHash64: BigInt(vector.coarseHash64),
    keypoints: vector.keypoints.map((keypoint) => ({ ...keypoint })),
    quantizedSiftDescriptors: vector.descriptorsHex.map(hexToBytes),
  }
}

function reason(bytes: Uint8Array): string {
  try {
    parseSiftFingerprint(bytes)
    return 'ok'
  } catch (error) {
    return error instanceof SiftFingerprintError ? error.code : 'other'
  }
}

function main(): void {
  const status = document.createElement('p')
  status.id = 'fingerprint-codec-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  try {
    const zero = ALL.find((vector) => vector.name === 'zero_defaults')!
    const good = zero.wireHex

    let serializedOk = 0
    let parsedOk = 0
    for (const vector of ALL) {
      const domain = domainFor(vector)
      if (bytesToHex(serializeSiftFingerprint(domain)) === vector.wireHex) serializedOk += 1
      if (JSON.stringify(parseSiftFingerprint(hexToBytes(vector.wireHex)), (_k, v) => (typeof v === 'bigint' ? `${v}` : v)) === JSON.stringify(domain, (_k, v) => (typeof v === 'bigint' ? `${v}` : v))) parsedOk += 1
    }
    assert('serializes the exact Kotlin wire bytes for every vector', serializedOk === ALL.length, `${serializedOk}/${ALL.length}`)
    assert('parses each Kotlin vector back to the domain', parsedOk === ALL.length, `${parsedOk}/${ALL.length}`)
    assert('proto3 omits coarse_hash64=0 and keeps an empty keypoint message', good.startsWith('08031219706f7374636172642d736966742d726f6f74736966742d763118c00c20b009') && !good.includes('290000000000000000') && good.includes('3200'))

    const baseDomain = domainFor(zero)
    const hashes = [0n, -1n, -(2n ** 63n), 2n ** 63n - 1n, -(2n ** 63n) + 1n, 9007199254740993n]
    const hashOk = hashes.every((hash) => parseSiftFingerprint(serializeSiftFingerprint({ ...baseDomain, coarseHash64: hash })).coarseHash64 === hash)
    assert('signed bigint fixed64 round-trips 0/-1/MIN/MAX/high-bit (no Number loss)', hashOk)

    const input = hexToBytes(good)
    const parsed = parseSiftFingerprint(input)
    input.fill(0xab)
    assert('parsed descriptor rows are owned copies (independent of input)', parsed.quantizedSiftDescriptors[0]!.every((byte) => byte === 0))

    const multi = ALL.find((vector) => vector.name === 'multi_points')!
    const multiParsed = parseSiftFingerprint(hexToBytes(multi.wireHex))
    assert('preserves authored keypoint order', JSON.stringify(multiParsed.keypoints) === JSON.stringify(multi.keypoints))
    assert('returns exactly one 128-byte row per keypoint', multiParsed.quantizedSiftDescriptors.length === multi.keypoints.length && multiParsed.quantizedSiftDescriptors.every((row) => row.length === 128))
    const multiDomain = domainFor(multi)
    const callerRows = multiDomain.quantizedSiftDescriptors.map((row) => bytesToHex(row))
    serializeSiftFingerprint(multiDomain)
    assert('serialize does not mutate caller descriptor rows', multiDomain.quantizedSiftDescriptors.every((row, index) => bytesToHex(row) === callerRows[index]))
    assert('refuses a >1 MiB input as size', reason(new Uint8Array((1 << 20) + 1)) === 'size')

    const refusals: Array<[string, string]> = [
      ['empty input', ''],
      ['unknown field 8', `${good}4000`],
      ['duplicate field 1', `0803${good}`],
      ['field 1 wrong wire', `0a0103${good.slice(4)}`],
      ['reordered fields', `${good.slice(4)}${good.slice(0, 4)}`],
      ['non-minimal varint', `088300${good.slice(4)}`],
      ['truncated', good.slice(0, good.length - 20)],
      ['invalid UTF-8 profile', '08031202fffe'],
      ['format_version != 3', `0804${good.slice(4)}`],
      ['wrong profile', `${good.slice(0, 10)}6e${good.slice(12)}`],
      ['width out of range', good.replace('18c00c', '18a18d06')],
      ['descriptor misalignment', `${good.replace('3a8001', '3a8101')}00`],
    ]
    const expected: Record<string, string> = {
      'empty input': 'size',
      'unknown field 8': 'encoding',
      'duplicate field 1': 'encoding',
      'field 1 wrong wire': 'encoding',
      'reordered fields': 'noncanonical',
      'non-minimal varint': 'noncanonical',
      truncated: 'encoding',
      'invalid UTF-8 profile': 'encoding',
      'format_version != 3': 'version',
      'wrong profile': 'profile',
      'width out of range': 'range',
      'descriptor misalignment': 'descriptors',
    }
    for (const [name, hex] of refusals) {
      assert(`refuses ${name} with ${expected[name]}`, reason(hexToBytes(hex)) === expected[name], reason(hexToBytes(hex)))
    }
  } catch (error) {
    assert('fingerprint codec harness', false, String(error))
  }
  ;(window as unknown as { __fingerprintCodec?: unknown }).__fingerprintCodec = { assertions, failures }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

main()
