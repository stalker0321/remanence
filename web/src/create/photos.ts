import type { SessionStore } from '../session/session'
import {
  NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES,
  SOURCE_PHOTO_MAX_EDGE_PX,
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
 * `decode` means the payload could not be carried as a normalized photo
 * (not SOI JPEG, invalid dimensions, or malformed content hash). It is
 * explicit and never a silent skip: normalized input always validates.
 */
export type PhotoRefusal = 'type' | 'size' | 'full' | 'decode'

function validDimensions(width: number, height: number): boolean {
  return (
    Number.isInteger(width) &&
    Number.isInteger(height) &&
    width >= 1 &&
    height >= 1 &&
    width <= SOURCE_PHOTO_MAX_EDGE_PX &&
    height <= SOURCE_PHOTO_MAX_EDGE_PX
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
    if (photo.bytes[0] !== 0xff || photo.bytes[1] !== 0xd8) return 'decode'
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
