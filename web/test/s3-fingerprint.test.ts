import { describe, expect, it } from 'vitest'
import { parseSiftFingerprint, serializeSiftFingerprint, S3_FINGERPRINT_PROFILE_ID, S3_MAX_KEYPOINTS } from '../src/scan/siftFingerprint'
import { isS3, queryFrame, toFeatures, toFingerprint } from '../src/scan/s3/s3Fingerprint'
import { match, pyRound, strictCombined, type Candidate } from '../src/scan/s3/frozenMatcher'

const features = (n: number, w = 518, h = 371) => ({
  xy: Float32Array.from({ length: 2 * n }, (_, i) => (i % 2 === 0 ? (i * 7) % w : (i * 3) % h) + 0.25),
  descriptors: Uint8Array.from({ length: 128 * n }, (_, i) => (i * 31) & 0xff),
  width: w, height: h, originX: 0, originY: 0, frameWidth: w, frameHeight: h,
})

describe('frozen matcher (S3) fingerprints', () => {
  it('round-trips sender features through the S3 codec at the 6x6x45 cap', () => {
    const original = features(S3_MAX_KEYPOINTS)
    const wire = serializeSiftFingerprint(toFingerprint(original))
    const parsed = parseSiftFingerprint(wire)
    expect(parsed.profileId).toBe(S3_FINGERPRINT_PROFILE_ID)
    expect(isS3(parsed)).toBe(true)
    expect(queryFrame(parsed)).toBeNull()
    const back = toFeatures(parsed)
    expect(Array.from(back.descriptors)).toEqual(Array.from(original.descriptors))
    back.xy.forEach((v, i) => expect(Math.abs(v - original.xy[i]!)).toBeLessThan(0.001))
  })

  it('keeps a recipient query placement in the frame (Android-compatible packing)', () => {
    const frame = { originX: 37, originY: 120, frameWidth: 374, frameHeight: 561, components: 2 }
    const parsed = parseSiftFingerprint(serializeSiftFingerprint(toFingerprint(features(10, 300, 200), frame)))
    expect(queryFrame(parsed)).toEqual(frame)
    expect(parsed.coarseHash64 < 0n).toBe(true)
    expect(toFeatures(parsed).originY).toBe(120)
  })

  it('rounds like Python and gates like strict_combined', () => {
    expect([0.5, 1.5, 2.5, -0.5, 2.4].map((v) => pyRound(v) + 0)).toEqual([0, 2, 2, 0, 2])
    const strong: Candidate = { id: 'a', finalScore: 120, inliers: 120, matches: 130, inlierRatio: 0.92, coverage: 0.4, geometryVerified: true, centreDistance: 0 }
    expect(strictCombined(strong, 1)).toBe(true)
    expect(strictCombined({ ...strong, coverage: 0.19 }, 1)).toBe(false)
    expect(strictCombined({ ...strong, coverage: 0.4 }, 2)).toBe(false)
    expect(strictCombined({ ...strong, coverage: 0.6, inlierRatio: 0.95 }, 2)).toBe(true)
    expect(typeof match).toBe('function')
  })
})
