import { arrayBuffer, bytesToHex } from '../protocol/bytes'
import { normalizedPhoto } from '../protocol/limits'
import type { PhotoDraft, PhotoRefusal } from './photos'

/**
 * Real photo normalization for Create (T228): decode once, apply EXIF
 * orientation once, downscale so the long edge fits the protocol maximum
 * (never upscale), and re-encode from pixels only (metadata is dropped by
 * construction — no EXIF survives a canvas re-encode). Steps the JPEG
 * quality ladder until the normalized bytes fit the plaintext budget.
 *
 * Mirrors Android `PhotoNormalizer` + `CaptureDecoder` + the protocol
 * numbers in `protocol/fixtures/limits-v1.json`. Pixel/encoded-byte
 * equality across platform JPEG implementations is NOT promised.
 */

/** Shared with the protocol fixture; unit tests lock these to limits-v1.json. */
export const NORMALIZED_PHOTO_MEDIA_TYPE: string = normalizedPhoto.mediaType
export const NORMALIZED_PHOTO_MAX_LONG_EDGE_PX: number = normalizedPhoto.maxLongEdgePx
export const NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES: number = normalizedPhoto.maxPlaintextBytes
/** Strictly descending re-encode qualities, tried in order until the size fits. */
export const NORMALIZED_PHOTO_QUALITY_LADDER = [90, 80, 70, 55, 40] as const

/**
 * Defensive source-input budget, distinct from the normalized protocol cap:
 * raw picked files may exceed 8 MiB (downscale usually absorbs them) but must
 * stay within a bounded working set before `decode` runs. Documented, not a
 * protocol number; decoding larger input fails closed as `oversize`.
 */
export const SOURCE_PHOTO_MAX_BYTES = 64 * 1024 * 1024
/** Decoded dimensions beyond this are refused without allocating a canvas. */
export const SOURCE_PHOTO_MAX_EDGE_PX = 16384

export interface NormalizedPhoto {
  bytes: Uint8Array
  width: number
  height: number
  contentHash: string
  quality: number
}

export type NormalizerRefusal = 'decode' | 'budget' | 'oversize'

export type NormalizeOutcome =
  | { ok: true; photo: NormalizedPhoto }
  | { ok: false; reason: NormalizerRefusal }

/**
 * Upright pixels with the EXIF orientation already applied exactly once.
 * The adapter owns the pixel lifetime; `release` runs best-effort.
 */
export interface DecodedUpright {
  width: number
  height: number
  encodeJpeg(width: number, height: number, quality: number): Promise<Uint8Array>
  release(): void
}

/** Injected decode/encode port: fake in unit tests, browser adapter in production. */
export interface PhotoCodec {
  decode(bytes: Uint8Array): Promise<DecodedUpright>
}

/**
 * Pure dimension plan: scale so the long edge fits `maxLongEdge`, never
 * upscale, round like Android (`Math.round`, minimum 1). Returns null for
 * invalid or absurd dimensions (refused before any canvas work).
 */
export function planNormalization(
  width: number,
  height: number,
  maxLongEdge: number = NORMALIZED_PHOTO_MAX_LONG_EDGE_PX,
): { width: number; height: number } | null {
  if (!Number.isInteger(width) || !Number.isInteger(height)) return null
  if (width < 1 || height < 1) return null
  if (width > SOURCE_PHOTO_MAX_EDGE_PX || height > SOURCE_PHOTO_MAX_EDGE_PX) return null
  if (maxLongEdge < 1) return null
  if (Math.max(width, height) <= maxLongEdge) return { width, height }
  const scale = maxLongEdge / Math.max(width, height)
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

export interface NormalizerOptions {
  maxLongEdge?: number
  maxBytes?: number
  ladder?: readonly number[]
}

function validLadder(ladder: readonly number[]): boolean {
  if (ladder.length === 0) return false
  for (let index = 0; index < ladder.length; index += 1) {
    const quality = ladder[index] as number
    if (!Number.isInteger(quality) || quality < 1 || quality > 100) return false
    if (index > 0 && (ladder[index - 1] as number) <= quality) return false
  }
  return true
}

export class PhotoNormalizer {
  readonly maxLongEdge: number
  readonly maxBytes: number
  readonly ladder: readonly number[]

  constructor(
    private readonly codec: PhotoCodec,
    options: NormalizerOptions = {},
  ) {
    const maxLongEdge = options.maxLongEdge ?? NORMALIZED_PHOTO_MAX_LONG_EDGE_PX
    const maxBytes = options.maxBytes ?? NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES
    const ladder = options.ladder ?? NORMALIZED_PHOTO_QUALITY_LADDER
    if (!Number.isInteger(maxLongEdge) || maxLongEdge < 1) throw new RangeError('maxLongEdge')
    if (!Number.isInteger(maxBytes) || maxBytes < 1) throw new RangeError('maxBytes')
    if (!validLadder(ladder)) throw new RangeError('ladder')
    this.maxLongEdge = maxLongEdge
    this.maxBytes = maxBytes
    this.ladder = ladder
  }

  async normalize(input: Uint8Array, options: NormalizerOptions = {}): Promise<NormalizeOutcome> {
    const maxLongEdge = options.maxLongEdge ?? this.maxLongEdge
    const maxBytes = options.maxBytes ?? this.maxBytes
    const ladder = options.ladder ?? this.ladder
    if (!Number.isInteger(maxLongEdge) || maxLongEdge < 1) return { ok: false, reason: 'decode' }
    if (!Number.isInteger(maxBytes) || maxBytes < 1) return { ok: false, reason: 'decode' }
    if (!validLadder(ladder)) return { ok: false, reason: 'decode' }
    if (input.byteLength > SOURCE_PHOTO_MAX_BYTES) {
      return { ok: false, reason: 'oversize' }
    }
    if (input.byteLength === 0) {
      return { ok: false, reason: 'decode' }
    }
    let decoded: DecodedUpright
    try {
      decoded = await this.codec.decode(input)
    } catch {
      return { ok: false, reason: 'decode' }
    }
    try {
      const plan = planNormalization(decoded.width, decoded.height, maxLongEdge)
      if (!plan) return { ok: false, reason: 'decode' }
      for (const quality of ladder) {
        let bytes: Uint8Array
        try {
          bytes = await decoded.encodeJpeg(plan.width, plan.height, quality)
        } catch {
          return { ok: false, reason: 'decode' }
        }
        if (bytes.byteLength === 0 || bytes.byteLength > maxBytes) continue
        if (bytes[0] !== 0xff || bytes[1] !== 0xd8) return { ok: false, reason: 'decode' }
        const digest = await crypto.subtle.digest('SHA-256', arrayBuffer(bytes))
        return {
          ok: true,
          photo: {
            bytes,
            width: plan.width,
            height: plan.height,
            contentHash: bytesToHex(new Uint8Array(digest)),
            quality,
          },
        }
      }
      return { ok: false, reason: 'budget' }
    } finally {
      try {
        decoded.release()
      } catch {
        /* best-effort */
      }
    }
  }
}

/** Normalized photo as carried into the draft (quality stays a normalizer detail). */
export interface NormalizedPhotoInput {
  bytes: Uint8Array
  width: number
  height: number
  contentHash: string
}

/** Minimal file shape: the real `File` satisfies this; tests use fakes. */
export interface IntakeFile {
  readonly size: number
  arrayBuffer(): Promise<ArrayBuffer>
}

/**
 * Fence captured when a pick starts. After EVERY await the live fence is
 * re-read and must still match: same owner, same session incarnation, same
 * recipient confirmation object, still mounted. Anything else drops the
 * pending output — no late photos, no success message.
 */
export interface IntakeFence {
  owner: string | null
  generation: number
  confirmed: unknown
  mounted: boolean
}

export interface IntakeOutcome {
  added: number
  refused: boolean
  completed: boolean
}

/**
 * Serialized, fenced photo intake shared by unit tests and `App.addPhotos`.
 * Picks queue behind earlier picks, so order and the 3..5 count survive
 * concurrent picks. Dropped output is zeroed best-effort, never added.
 */
export class PhotoIntake {
  #tail: Promise<void> = Promise.resolve()
  #epoch = 0
  #disposed = false

  constructor(
    private readonly normalizer: PhotoNormalizer,
    private readonly draft: PhotoDraft,
  ) {}

  /** Unmount: pending output is dropped; the queue drains without side effects. */
  dispose(): void {
    this.#disposed = true
    this.#epoch += 1
  }

  /** Cancel in-flight work without unmounting. */
  cancel(): void {
    this.#epoch += 1
  }

  addFiles(files: ArrayLike<IntakeFile>, guard: () => IntakeFence): Promise<IntakeOutcome> {
    const epoch = this.#epoch
    const fence = guard()
    const run = this.#tail.then(() => this.#drain(files, guard, fence, epoch))
    this.#tail = run.then(
      () => undefined,
      () => undefined,
    )
    return run
  }

  private fenceAlive(guard: () => IntakeFence, fence: IntakeFence, epoch: number): boolean {
    if (this.#disposed || epoch !== this.#epoch) return false
    const live = guard()
    return (
      live.mounted === true &&
      live.owner === fence.owner &&
      live.generation === fence.generation &&
      live.confirmed === fence.confirmed
    )
  }

  async #drain(
    files: ArrayLike<IntakeFile>,
    guard: () => IntakeFence,
    fence: IntakeFence,
    epoch: number,
  ): Promise<IntakeOutcome> {
    let added = 0
    let refused = false
    for (const file of Array.from(files)) {
      if (!this.fenceAlive(guard, fence, epoch)) return { added, refused, completed: false }
      if (!Number.isInteger(file.size) || file.size <= 0 || file.size > SOURCE_PHOTO_MAX_BYTES) {
        refused = true
        continue
      }
      let raw: Uint8Array
      try {
        raw = new Uint8Array(await file.arrayBuffer())
      } catch {
        refused = true
        continue
      }
      try {
        if (!this.fenceAlive(guard, fence, epoch)) return { added, refused, completed: false }
        const result = await this.normalizer.normalize(raw)
        if (!this.fenceAlive(guard, fence, epoch)) return { added, refused, completed: false }
        if (!result.ok) {
          refused = true
          continue
        }
        const reason: PhotoRefusal | null = this.draft.add(result.photo)
        if (!this.fenceAlive(guard, fence, epoch)) return { added, refused, completed: false }
        if (reason) refused = true
        else added += 1
      } finally {
        raw.fill(0)
      }
    }
    return { added, refused, completed: true }
  }
}
