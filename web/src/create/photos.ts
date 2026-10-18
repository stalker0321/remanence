import type { SessionStore } from '../session/session'
import {
  NORMALIZED_PHOTO_MAX_LONG_EDGE_PX,
  NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES,
  type NormalizedPhotoInput,
} from './normalize'

/** Counts and the plaintext cap from protocol/fixtures/limits-v1.json. Tests lock these numbers to that file. */
export const PHOTO_COUNT_MIN = 3
export const PHOTO_COUNT_MAX = 5
export const PHOTO_MAX_PLAINTEXT_BYTES = NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES

export interface DraftPhoto {
  id: string
  bytes: Uint8Array
  width: number
  height: number
  contentHash: string
}

/**
 * `type` means the payload is not a JPEG at all; `decode` means it is
 * JPEG-shaped but unusable (invalid dimensions or malformed content hash).
 * Both are explicit and never a silent skip.
 */
export type PhotoRefusal = 'type' | 'size' | 'full' | 'decode'

function validDimensions(width: number, height: number): boolean {
  // Normalized photos only: bounded by the normalized long edge (2560),
  // not the larger source-input working cap.
  return (
    Number.isInteger(width) &&
    Number.isInteger(height) &&
    width >= 1 &&
    height >= 1 &&
    width <= NORMALIZED_PHOTO_MAX_LONG_EDGE_PX &&
    height <= NORMALIZED_PHOTO_MAX_LONG_EDGE_PX
  )
}

const CONTENT_HASH = /^[0-9a-f]{64}$/

/**
 * Local normalized-photo list. `add` takes normalized output only (upright
 * dimensions + SHA-256 of the normalized bytes, carried for later frozen
 * composition). Originals/raw EXIF are never kept here. Thumbnails render
 * from these normalized bytes.
 */
export class PhotoDraft {
  #photos: DraftPhoto[] = []
  #next = 1

  photos(): readonly DraftPhoto[] {
    return this.#photos
  }

  add(photo: NormalizedPhotoInput): PhotoRefusal | null {
    if (this.#photos.length >= PHOTO_COUNT_MAX) return 'full'
    if (photo.bytes.byteLength < 3 || photo.bytes.byteLength > PHOTO_MAX_PLAINTEXT_BYTES) return 'size'
    if (photo.bytes[0] !== 0xff || photo.bytes[1] !== 0xd8) return 'type'
    if (!validDimensions(photo.width, photo.height)) return 'decode'
    if (!CONTENT_HASH.test(photo.contentHash)) return 'decode'
    this.#photos.push({
      id: String(this.#next),
      bytes: photo.bytes.slice(),
      width: photo.width,
      height: photo.height,
      contentHash: photo.contentHash,
    })
    this.#next += 1
    return null
  }

  remove(id: string): void {
    const photo = this.#photos.find((item) => item.id === id)
    photo?.bytes.fill(0)
    this.#photos = this.#photos.filter((item) => item.id !== id)
  }

  move(id: string, direction: -1 | 1): void {
    const index = this.#photos.findIndex((item) => item.id === id)
    const target = index + direction
    if (index < 0 || target < 0 || target >= this.#photos.length) return
    const next = this.#photos.slice()
    const [item] = next.splice(index, 1)
    if (!item) return
    next.splice(target, 0, item)
    this.#photos = next
  }

  /** Null until there are 3 to 5 photos. Dimensions and hashes ride along. */
  accepted(): readonly DraftPhoto[] | null {
    if (this.#photos.length < PHOTO_COUNT_MIN || this.#photos.length > PHOTO_COUNT_MAX) return null
    return this.#photos
  }

  wipe(): void {
    for (const photo of this.#photos) photo.bytes.fill(0)
    this.#photos = []
  }

  attach(session: SessionStore): () => void {
    return session.subscribe(() => {
      const view = session.getSnapshot()
      if (view.phase === 'signed-out' || view.phase === 'authenticated') this.wipe()
    })
  }
}
