import { describe, expect, it } from 'vitest'
import { bytesToHex, hexToBytes } from '../src/protocol/bytes'
import {
  parseSiftFingerprint,
  serializeSiftFingerprint,
  SiftFingerprintError,
  SIFT_FINGERPRINT_PROFILE_ID,
  DESCRIPTOR_BYTES,
  MAX_KEYPOINTS,
  type SiftFingerprint,
} from '../src/scan/siftFingerprint'
import vectors from './fingerprint-vectors/fingerprint-vectors.json'

interface Vector {
  name: string
  canonicalWidthPx: number
  canonicalHeightPx: number
  coarseHash64: string
  keypoints: Array<{ xMicro: number; yMicro: number; scaleMicro: number; angleCentiDegrees: number; responseQuantized: number; octave: number }>
  descriptorsHex: string[]
  wireHex: string
}

const VECTORS = vectors.vectors as Vector[]

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

const ZERO = VECTORS.find((vector) => vector.name === 'zero_defaults')!
const GOOD = ZERO.wireHex

describe('format-3 fingerprint codec (genuine Kotlin vectors)', () => {
  it('exports only the production codec + error + the T330 additive validator', async () => {
    const mod = await import('../src/scan/siftFingerprint')
    expect(Object.keys(mod).sort()).toEqual(['DESCRIPTOR_BYTES', 'MAX_KEYPOINTS', 'MAX_SERIALIZED_BYTES', 'MIN_KEYPOINTS', 'S3_FINGERPRINT_FORMAT_VERSION', 'S3_FINGERPRINT_PROFILE_ID', 'S3_MAX_KEYPOINTS', 'SIFT_FINGERPRINT_FORMAT_VERSION', 'SIFT_FINGERPRINT_PROFILE_ID', 'SiftFingerprintError', 'parseSiftFingerprint', 'serializeSiftFingerprint', 'validateSiftFingerprintShape'])
  })

  it('serializes the exact Kotlin canonical bytes and parses back the domain for every vector', () => {
    expect(VECTORS.length).toBeGreaterThanOrEqual(8)
    for (const vector of VECTORS) {
      const domain = domainFor(vector)
      const bytes = serializeSiftFingerprint(domain)
      expect(bytesToHex(bytes), vector.name).toBe(vector.wireHex)
      const parsed = parseSiftFingerprint(hexToBytes(vector.wireHex))
      expect(parsed, vector.name).toEqual(domain)
      expect(bytesToHex(serializeSiftFingerprint(parsed)), vector.name).toBe(vector.wireHex)
    }
  })

  it('omits proto3 zero defaults (coarse_hash64=0 and zero nested keypoint fields)', () => {
    const domain = domainFor(ZERO)
    expect(domain.coarseHash64).toBe(0n)
    // field1 version, field2 profile, field3/4 dims, field6 empty keypoint, field7 descriptors only.
    expect(GOOD.startsWith('08031219706f7374636172642d736966742d726f6f74736966742d763118c00c20b009')).toBe(true)
    expect(GOOD.includes('290000000000000000')).toBe(false) // no fixed64 zero
    expect(GOOD.includes('3200')).toBe(true) // empty keypoint message present
    expect(serializeSiftFingerprint(domain).length).toBe(GOOD.length / 2)
  })

  it('round-trips signed bigint fixed64 across the full Long range', () => {
    for (const hash of [0n, -1n, -(2n ** 63n), 2n ** 63n - 1n, -(2n ** 63n) + 1n, 9007199254740993n]) {
      const base = domainFor(ZERO)
      const parsed = parseSiftFingerprint(serializeSiftFingerprint({ ...base, coarseHash64: hash }))
      expect(parsed.coarseHash64).toBe(hash)
    }
  })

  it('refuses malformed / non-canonical / out-of-range input with typed bounded codes', () => {
    expect(reason(new Uint8Array(0))).toBe('size')
    expect(reason(new Uint8Array((1 << 20) + 1))).toBe('size')
    expect(reason(hexToBytes(`${GOOD}4000`))).toBe('encoding') // unknown field 8
    expect(reason(hexToBytes(`0803${GOOD}`))).toBe('encoding') // duplicate field 1
    expect(reason(hexToBytes(`0a0103${GOOD.slice(4)}`))).toBe('encoding') // field 1 wrong wire
    expect(reason(hexToBytes(`${GOOD.slice(4)}${GOOD.slice(0, 4)}`))).toBe('noncanonical') // reordered
    expect(reason(hexToBytes(`088300${GOOD.slice(4)}`))).toBe('noncanonical') // non-minimal varint
    expect(reason(hexToBytes(GOOD.slice(0, GOOD.length - 20)))).toBe('encoding') // truncated
    expect(reason(hexToBytes('08031202fffe'))).toBe('encoding') // invalid UTF-8 profile
    expect(reason(hexToBytes(`0804${GOOD.slice(4)}`))).toBe('version') // format_version != 3
    const wrongProfile = `${GOOD.slice(0, 10)}6e${GOOD.slice(12)}`
    expect(reason(hexToBytes(wrongProfile))).toBe('profile')
    expect(reason(hexToBytes(GOOD.replace('18c00c', '18a18d06')))).toBe('range') // width 100001
    expect(reason(hexToBytes(`${GOOD.replace('3a8001', '3a8101')}00`))).toBe('descriptors') // length 129
  })

  it('owns fresh descriptor rows: input and parsed rows are independent', () => {
    const input = hexToBytes(GOOD)
    const parsed = parseSiftFingerprint(input)
    input.fill(0xab)
    expect(parsed.quantizedSiftDescriptors[0]!.every((byte) => byte === 0)).toBe(true)

    const again = parseSiftFingerprint(hexToBytes(GOOD))
    const first = again.quantizedSiftDescriptors[0]!
    const before = first.slice()
    first[0] = 0xff
    expect(again.quantizedSiftDescriptors[0]![0]).toBe(0xff)
    expect(again.quantizedSiftDescriptors[0]).toBe(first)
    void before
  })

  it('does not mutate caller descriptor rows on serialize', () => {
    const domain = domainFor(VECTORS.find((vector) => vector.name === 'multi_points')!)
    const before = domain.quantizedSiftDescriptors.map((row) => row.slice())
    serializeSiftFingerprint(domain)
    domain.quantizedSiftDescriptors.forEach((row, index) => {
      expect(bytesToHex(row)).toBe(bytesToHex(before[index]!))
    })
    expect(DESCRIPTOR_BYTES).toBe(128)
  })

  it('caps repeated keypoints at MAX_KEYPOINTS with a typed refusal (1500 stays valid)', () => {
    // 1500 keypoints with 1500 aligned descriptor rows remain valid + canonical.
    const zeroPoint = { xMicro: 0, yMicro: 0, scaleMicro: 0, angleCentiDegrees: 0, responseQuantized: 0, octave: 0 }
    const atLimit: SiftFingerprint = {
      ...domainFor(ZERO),
      keypoints: Array.from({ length: MAX_KEYPOINTS }, () => ({ ...zeroPoint })),
      quantizedSiftDescriptors: Array.from({ length: MAX_KEYPOINTS }, () => new Uint8Array(DESCRIPTOR_BYTES)),
    }
    expect(reason(serializeSiftFingerprint(atLimit))).toBe('ok')

    // 1501 empty keypoint messages (below 1 MiB) must refuse with 'range'
    // before the 1501st message is read/parsed.
    const overLimit = GOOD.replace('3200', '3200'.repeat(MAX_KEYPOINTS + 1))
    expect(reason(hexToBytes(overLimit))).toBe('range')
  })
})
