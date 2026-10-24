/**
 * Frozen-matcher features in the shared fingerprint container (S3 profile),
 * byte-compatible with Android's `FrozenMatcherS3Fingerprint`.
 *
 * Keypoints are stored relative to the crop in micro units; only positions
 * and descriptors are read. A recipient query also carries its ROI placement
 * in the 2:3 frame and the mask component count, packed into `coarseHash64`
 * (never published: queries stay on the device).
 */
import { S3_FINGERPRINT_PROFILE_ID, type SiftFingerprint } from '../siftFingerprint'
import { featureCount, type Features } from './frozenMatcher'

export interface QueryFrame {
  readonly originX: number
  readonly originY: number
  readonly frameWidth: number
  readonly frameHeight: number
  readonly components: number
}

const MICRO = 1_000_000
const MASK13 = (1n << 13n) - 1n
const QUERY_MARK = 1n << 63n

export function isS3(fingerprint: SiftFingerprint): boolean {
  return fingerprint.profileId === S3_FINGERPRINT_PROFILE_ID
}

function pack(frame: QueryFrame): bigint {
  const fields = [frame.originX, frame.originY, frame.frameWidth, frame.frameHeight]
  if (fields.some((v) => !Number.isInteger(v) || v < 0 || v >= 1 << 13)) throw new Error('query frame out of range')
  const raw = (BigInt(frame.originX) << 47n) | (BigInt(frame.originY) << 34n) | (BigInt(frame.frameWidth) << 21n) |
    (BigInt(frame.frameHeight) << 8n) | BigInt(Math.min(frame.components, 255)) | QUERY_MARK
  return BigInt.asIntN(64, raw)
}

export function queryFrame(fingerprint: SiftFingerprint): QueryFrame | null {
  const value = BigInt.asUintN(64, fingerprint.coarseHash64)
  if ((value & QUERY_MARK) === 0n) return null
  return {
    originX: Number((value >> 47n) & MASK13),
    originY: Number((value >> 34n) & MASK13),
    frameWidth: Number((value >> 21n) & MASK13),
    frameHeight: Number((value >> 8n) & MASK13),
    components: Number(value & 0xffn),
  }
}

const micro = (value: number, size: number) => Math.min(MICRO, Math.max(0, Math.round(value / size * MICRO)))

export function toFingerprint(features: Features, frame: QueryFrame | null = null): SiftFingerprint {
  const n = featureCount(features)
  if (n < 1) throw new Error('no features')
  return {
    profileId: S3_FINGERPRINT_PROFILE_ID,
    canonicalWidthPx: features.width,
    canonicalHeightPx: features.height,
    coarseHash64: frame ? pack(frame) : 0n,
    keypoints: Array.from({ length: n }, (_, i) => ({
      xMicro: micro(features.xy[2 * i]!, features.width),
      yMicro: micro(features.xy[2 * i + 1]!, features.height),
      scaleMicro: 0,
      angleCentiDegrees: 0,
      responseQuantized: 0,
      octave: 0,
    })),
    quantizedSiftDescriptors: Array.from({ length: n }, (_, i) => features.descriptors.slice(i * 128, (i + 1) * 128)),
  }
}

export function toFeatures(fingerprint: SiftFingerprint): Features {
  if (!isS3(fingerprint)) throw new Error('not an S3 fingerprint')
  const n = fingerprint.keypoints.length
  const w = fingerprint.canonicalWidthPx
  const h = fingerprint.canonicalHeightPx
  const xy = new Float32Array(2 * n)
  const descriptors = new Uint8Array(128 * n)
  fingerprint.keypoints.forEach((k, i) => {
    xy[2 * i] = k.xMicro / MICRO * w
    xy[2 * i + 1] = k.yMicro / MICRO * h
  })
  fingerprint.quantizedSiftDescriptors.forEach((row, i) => descriptors.set(row, i * 128))
  const frame = queryFrame(fingerprint)
  return {
    xy, descriptors, width: w, height: h,
    originX: frame?.originX ?? 0, originY: frame?.originY ?? 0,
    frameWidth: frame?.frameWidth ?? w, frameHeight: frame?.frameHeight ?? h,
  }
}
