import type { SessionStore } from '../session/session'

/** Wire rule from protocol/fixtures/limits-v1.json. The test locks this number to that file. */
export const NOTE_MAX_UTF8_BYTES = 1000

const encoder = new TextEncoder()

export function noteUtf8Bytes(text: string): number {
  return encoder.encode(text).byteLength
}

/** Editable note. Over-limit text stays for editing and is not an accepted note. */
export class NoteDraft {
  #text = ''

  text(): string {
    return this.#text
  }

  bytes(): number {
    return noteUtf8Bytes(this.#text)
  }

  fits(): boolean {
    return this.bytes() <= NOTE_MAX_UTF8_BYTES
  }

  /** Empty is no note. Over the byte limit is not a note. */
  accepted(): string | null {
    if (!this.fits()) return null
    return this.#text.length === 0 ? null : this.#text
  }

  edit(text: string): void {
    this.#text = text
  }

  wipe(): void {
    this.#text = ''
  }

  attach(session: SessionStore): () => void {
    return session.subscribe(() => {
      const view = session.getSnapshot()
      if (view.phase === 'signed-out' || view.phase === 'authenticated') this.wipe()
    })
  }
}
