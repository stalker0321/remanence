import type { CreatorComposerPhoto } from '../collage/CreatorComposer'
import type { DraftPhoto } from './photos'

/**
 * Owner/generation/confirmed/mount-fenced bridge from normalized
 * `DraftPhoto.bytes` (the ONLY intake) to the `CanvasImageSource` the
 * creator composer draws. Decoded `ImageBitmap`s are cached by content
 * hash, so reorder/re-add reuse them instead of re-decoding; every bitmap
 * it owns is closed on replacement/dispose, and a stale completion closes
 * only its own freshly-decoded bitmaps and publishes nothing.
 *
 * A local preview bridge, not a sealed expression or a send authorization.
 */

export type CreatorImageDecode = (bytes: Uint8Array) => Promise<ImageBitmap>

export interface CreatorImageFence {
  owner: string | null
  generation: number
  confirmed: unknown
  mounted: boolean
}

export type CreatorImageLoad =
  | { ok: true; photos: CreatorComposerPhoto[] }
  | { ok: false; stale: boolean }

/** Production browser decode: upright pixels, EXIF applied exactly once. */
export function browserImageDecode(): CreatorImageDecode {
  return async (bytes) => {
    try {
      return await createImageBitmap(
        new Blob([bytes.slice().buffer as ArrayBuffer], { type: 'image/jpeg' }),
        { imageOrientation: 'from-image' },
      )
    } catch {
      throw new Error('image decode failed')
    }
  }
}

export class CreatorImageBridge {
  #epoch = 0
  #key: string | null = null
  #current: CreatorComposerPhoto[] | null = null
  #cache = new Map<string, ImageBitmap>()

  constructor(private readonly decode: CreatorImageDecode) {}

  /** Release every owned bitmap and fence all in-flight decodes. */
  dispose(): void {
    this.#epoch += 1
    this.#key = null
    this.#current = null
    for (const bitmap of this.#cache.values()) {
      try {
        bitmap.close()
      } catch {
        /* best-effort */
      }
    }
    this.#cache.clear()
  }

  /**
   * Resolve `accepted` to decoded images for `key`. Reuses cached bitmaps
   * by content hash. A completion whose fence no longer matches
   * (mounted/owner/generation/confirmed) is stale: its freshly-decoded
   * bitmaps are closed and nothing is published. Decode/dimension
   * failures close partial work and report `stale:false`.
   */
  async load(accepted: readonly DraftPhoto[], key: string, fence: () => CreatorImageFence): Promise<CreatorImageLoad> {
    // A cache hit is still a new selection: retire any older decode before
    // returning, so removing a just-added photo cannot let it publish later.
    const epoch = ++this.#epoch
    const start = fence()
    const alive = (): boolean => {
      const live = fence()
      return live.mounted === true && live.owner === start.owner && live.generation === start.generation && live.confirmed === start.confirmed
    }
    if (!start.mounted || !alive()) return { ok: false, stale: true }
    if (key === this.#key && this.#current !== null && this.#current.length === accepted.length) {
      return { ok: true, photos: this.#current }
    }
    const fresh: ImageBitmap[] = []
    const fail = (stale: boolean): CreatorImageLoad => {
      for (const bitmap of fresh) {
        try {
          bitmap.close()
        } catch {
          /* best-effort */
        }
      }
      // The previously published set may reference evicted/closed handles;
      // force the next load to resolve again instead of reusing it.
      // A retired completion owns only its fresh handles, not the metadata
      // of a newer committed preview (or a newer cached selection).
      if (epoch === this.#epoch) {
        this.#key = null
        this.#current = null
      }
      return { ok: false, stale }
    }
    const images: ImageBitmap[] = []
    // Within ONE load, a repeated content hash must decode ONCE and share a
    // single owned handle; otherwise the per-slot duplicates would never be
    // adopted into `#cache` and would leak on dispose.
    const freshByHash = new Map<string, ImageBitmap>()
    for (const photo of accepted) {
      if (!alive() || epoch !== this.#epoch) return fail(true)
      if (photo.width < 1 || photo.height < 1 || photo.contentHash === '') return fail(false)
      const resolved = this.#cache.get(photo.contentHash) ?? freshByHash.get(photo.contentHash)
      if (resolved) {
        if (resolved.width !== photo.width || resolved.height !== photo.height) return fail(false)
        images.push(resolved)
        continue
      }
      let image: ImageBitmap
      try {
        image = await this.decode(photo.bytes)
      } catch {
        return fail(!alive() || epoch !== this.#epoch)
      }
      if (image.width !== photo.width || image.height !== photo.height) {
        try {
          image.close()
        } catch {
          /* best-effort */
        }
        return fail(false)
      }
      fresh.push(image)
      freshByHash.set(photo.contentHash, image)
      images.push(image)
    }
    if (!alive() || epoch !== this.#epoch) return fail(true)
    // Commit: evict bitmaps no longer referenced, adopt every fresh handle
    // (one per distinct hash) so dispose closes all of them.
    const hashes = new Set(accepted.map((photo) => photo.contentHash))
    for (const [hash, bitmap] of [...this.#cache]) {
      if (hashes.has(hash)) continue
      try {
        bitmap.close()
      } catch {
        /* best-effort */
      }
      this.#cache.delete(hash)
    }
    for (const [hash, image] of freshByHash) {
      if (!this.#cache.has(hash)) this.#cache.set(hash, image)
    }
    this.#key = key
    this.#current = accepted.map((photo, index) => ({
      contentId: photo.id,
      width: photo.width,
      height: photo.height,
      contentHash: photo.contentHash,
      image: images[index] as ImageBitmap,
    }))
    return { ok: true, photos: this.#current }
  }
}
