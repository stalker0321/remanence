import type { SessionStore } from '../session/session'

/** Counts and the plaintext cap from protocol/fixtures/limits-v1.json. Tests lock these numbers to that file. */
export const PHOTO_COUNT_MIN = 3
export const PHOTO_COUNT_MAX = 5
export const PHOTO_MAX_PLAINTEXT_BYTES = 8388608

export interface DraftPhoto {
  id: string
  bytes: Uint8Array
}

export type PhotoRefusal = 'type' | 'size' | 'full'

/** Local JPEG list. Adding a file does not decode, resize, or upload it. */
export class PhotoDraft {
  #photos: DraftPhoto[] = []
  #next = 1

  photos(): readonly DraftPhoto[] {
    return this.#photos
  }

  add(bytes: Uint8Array): PhotoRefusal | null {
    if (this.#photos.length >= PHOTO_COUNT_MAX) return 'full'
    if (bytes.byteLength < 3 || bytes.byteLength > PHOTO_MAX_PLAINTEXT_BYTES) return 'size'
    if (bytes[0] !== 0xff || bytes[1] !== 0xd8) return 'type'
    this.#photos.push({ id: String(this.#next), bytes: bytes.slice() })
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

  /** Null until there are 3 to 5 photos. The long edge is not known here. */
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
