import { LETTER_MAX_UTF8_BYTES } from '../collage/wire'

const encoder = new TextEncoder()

/** Typing past the letter bound keeps what fits, cut between characters. */
export function clipLetter(text: string): string {
  if (encoder.encode(text).length <= LETTER_MAX_UTF8_BYTES) return text
  let bytes = 0
  let out = ''
  for (const char of text) {
    const size = encoder.encode(char).length
    if (bytes + size > LETTER_MAX_UTF8_BYTES) break
    bytes += size
    out += char
  }
  return out
}
