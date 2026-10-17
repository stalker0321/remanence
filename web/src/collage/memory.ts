import { arrayBuffer } from '../protocol/bytes'
import type { SessionStore } from '../session/session'
import type { PublicSession } from '../session/session'
import { CollageError } from './errors'
import type { OpenedPostcard } from './open'
import type { Scene } from './prepare'

export interface UrlPort {
  create(bytes: Uint8Array, type: string): string
  revoke(url: string): void
}

export interface HeldSheet {
  scene: Scene
  photos: ReadonlyMap<string, string>
  artwork: string | null
}

export const browserUrls: UrlPort = {
  create(bytes, type) {
    return URL.createObjectURL(new Blob([arrayBuffer(bytes)], { type }))
  },
  revoke(url) {
    URL.revokeObjectURL(url)
  },
}

/**
 * One admitted sheet. Bytes and object URLs are dropped on every phase
 * except presentation-admitted. Hold happens while the phase is still
 * postcard-matched, and the following admit does not clear it.
 */
export class PresentationMemory {
  #sheet: HeldSheet | null = null
  #bytes: Uint8Array[] = []
  #urls: string[] = []
  #listeners = new Set<() => void>()

  constructor(private readonly urls: UrlPort = browserUrls) {}

  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener)
    return () => {
      this.#listeners.delete(listener)
    }
  }

  getSnapshot = (): HeldSheet | null => this.#sheet

  hold(view: PublicSession, opened: OpenedPostcard): void {
    if (view.phase !== 'postcard-matched') throw new CollageError('GRANT')
    this.#release()
    try {
      const photos = new Map<string, string>()
      for (const [id, bytes] of opened.photos) {
        const copy = bytes.slice()
        this.#bytes.push(copy)
        const url = this.urls.create(copy, 'image/jpeg')
        this.#urls.push(url)
        photos.set(id, url)
      }
      let artwork: string | null = null
      if (opened.artwork) {
        const copy = opened.artwork.slice()
        this.#bytes.push(copy)
        artwork = this.urls.create(copy, 'image/webp')
        this.#urls.push(artwork)
      }
      this.#sheet = { scene: opened.scene, photos, artwork }
      this.#emit()
    } catch (error) {
      this.#release()
      throw error
    }
  }

  clear(): void {
    if (this.#sheet === null && this.#urls.length === 0) return
    this.#release()
    this.#emit()
  }

  attach(session: SessionStore): () => void {
    return session.subscribe(() => {
      if (session.getSnapshot().phase !== 'presentation-admitted') this.clear()
    })
  }

  #release(): void {
    for (const url of this.#urls) this.urls.revoke(url)
    for (const bytes of this.#bytes) bytes.fill(0)
    this.#urls = []
    this.#bytes = []
    this.#sheet = null
  }

  #emit(): void {
    for (const listener of this.#listeners) listener()
  }
}

export function showGrantedPostcard(session: SessionStore, memory: PresentationMemory, opened: OpenedPostcard): void {
  memory.hold(session.getSnapshot(), opened)
  try {
    session.admitPresentation()
  } catch (error) {
    memory.clear()
    throw error
  } finally {
    for (const bytes of opened.photos.values()) bytes.fill(0)
    opened.artwork?.fill(0)
  }
}
