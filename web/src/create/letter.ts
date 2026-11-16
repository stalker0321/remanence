import { LETTER_MAX_UTF8_BYTES } from '../collage/wire'
import { NOTE_MAX_UTF8_BYTES } from './note'

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

/** A text this short, in at most this many lines, stays whole on the front. */
export const FRONT_WHOLE_CHARS = 240
const FRONT_WHOLE_LINES = 4
/** The front's share of a longer text. */
export const FRONT_LEAD_CHARS = 160

export interface LetterSplit {
  /** What the layout prints on the front (the sealed note). */
  front: string
  /** The whole text for the back, or null when it all fits on the front. */
  letter: string | null
}

/**
 * One text field, two sides (ADR-021 §2). A short text is the note. A longer
 * one goes whole to the back; the front takes whole sentences from its start
 * up to {@link FRONT_LEAD_CHARS}, or words with an ellipsis when the first
 * sentence is longer than that. Android `LetterSplit` is the same rule.
 */
export function splitLetter(text: string): LetterSplit {
  const whole = text.trim()
  if (whole === '') return { front: '', letter: null }
  const chars = [...whole]
  if (chars.length <= FRONT_WHOLE_CHARS && whole.split('\n').length <= FRONT_WHOLE_LINES && encoder.encode(whole).length <= NOTE_MAX_UTF8_BYTES) {
    return { front: whole, letter: null }
  }
  return { front: lead(whole), letter: whole }
}

function lead(whole: string): string {
  // The first paragraph is the most the front takes.
  const paragraph = whole.split(/\n/)[0]!.trim()
  const sentences = paragraph.match(/[^.!?…]+(?:[.!?…]+["»”)]*|$)\s*/gu) ?? [paragraph]
  let out = ''
  for (const sentence of sentences) {
    if ([...(out + sentence).trimEnd()].length > FRONT_LEAD_CHARS) break
    out += sentence
  }
  out = out.trimEnd()
  if (out !== '') return out
  // The first sentence alone is too long: whole words, then an ellipsis.
  const words = paragraph.split(/\s+/u)
  for (const word of words) {
    const next = out === '' ? word : `${out} ${word}`
    if ([...next].length > FRONT_LEAD_CHARS - 1) break
    out = next
  }
  if (out === '') out = [...paragraph].slice(0, FRONT_LEAD_CHARS - 1).join('')
  return `${out.replace(/[\s,;:—–-]+$/u, '')}…`
}
