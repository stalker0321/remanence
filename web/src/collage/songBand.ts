import type { Rect } from './model'

/**
 * The song band, 9 November 2026 rules. The band fits the words, not the
 * other way round:
 *
 * - `plate`: a filled band (64 px in every layout). The cover is a square the
 *   full band height on the left; without one, the play button sits in that
 *   square. The title is one line in the style's own music type, cut with an
 *   ellipsis; the artist is a small capitals line under it.
 * - `caption`: no fill, a rule on top, the cover or a play circle, then
 *   title and artist on ONE line. Offered only when the whole line fits; a
 *   recipient whose metrics disagree draws the plate instead.
 * - `card`: the layout 6 spine (56 px). The title runs bottom to top along
 *   the whole spine, larger than the band type, then the artist, then the
 *   play button at the foot.
 *
 * One layout feeds the recipient's check, the painter and the sender's
 * choice, so they cannot drift apart. Old modes keep their frozen layout.
 */

export const SONG_BAND_MODES = ['plate', 'caption', 'card'] as const
export type SongBandMode = typeof SONG_BAND_MODES[number]

export function isSongBandMode(mode: string): mode is SongBandMode {
  return (SONG_BAND_MODES as readonly string[]).includes(mode)
}

export interface SongFace {
  family: string
  size: number
  weight: number
  italic: boolean
  line: number
}

export interface SongRun {
  text: string
  face: SongFace
  /** Horizontal runs: the line box's top-left. Vertical runs: the line box's left edge and the run's bottom end. */
  x: number
  y: number
  vertical: boolean
}

export interface SongBandLayout {
  mode: SongBandMode
  /** The cover's square, or null when the band shows no cover. */
  cover: Rect | null
  play: Rect
  /** `circle`: a filled disc with the glyph; `ring`: an outlined disc; `glyph`: the bare triangle. */
  playStyle: 'circle' | 'ring' | 'glyph'
  /** A divider beside the play square (plate without a cover). */
  divider: Rect | null
  /** The band is filled with the sound colour (plate, card) or left as paper under a rule (caption). */
  filled: boolean
  runs: SongRun[]
}

export interface SongWords {
  title: string
  artistDisplay: string
}

/** Width of one unwrapped run in this face. */
export type RunWidth = (text: string, face: SongFace) => number

/** The artist line: small capitals in the bundled sans. */
const ARTIST: SongFace = { family: 'StudySans', size: 12, weight: 400, italic: false, line: 14 }
const ELLIPSIS = '…'

function upper(text: string): string {
  return text.toUpperCase()
}

/** The longest prefix (ellipsis added when cut) that fits `width`; null when not even one letter fits. */
export function fitRun(text: string, face: SongFace, width: number, measure: RunWidth): string | null {
  const whole = text.trim()
  if (whole === '') return null
  if (measure(whole, face) <= width) return whole
  const chars = [...whole]
  let low = 1
  let high = chars.length - 1
  let best: string | null = null
  while (low <= high) {
    const mid = (low + high) >> 1
    const candidate = `${chars.slice(0, mid).join('').trimEnd()}${ELLIPSIS}`
    if (measure(candidate, face) <= width) {
      best = candidate
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return best
}

export function songBandLayout(region: Rect, mode: SongBandMode, type: SongFace, words: SongWords, measure: RunWidth, cover: boolean): SongBandLayout | null {
  if (mode === 'card') return card(region, type, words, measure)
  if (mode === 'caption') return caption(region, type, words, measure, cover)
  return plate(region, type, words, measure, cover)
}

function plate(region: Rect, type: SongFace, words: SongWords, measure: RunWidth, cover: boolean): SongBandLayout | null {
  const side = region.height
  const left = region.x + side + 12
  const glyph = { x: region.x + region.width - 14 - 16, y: region.y + Math.floor((side - 18) / 2), width: 16, height: 18 }
  const right = cover ? glyph.x - 12 : region.x + region.width - 14
  const block = type.line + 3 + ARTIST.line
  if (right - left < 40 || block > side - 8) return null
  const title = fitRun(words.title, type, right - left, measure)
  if (title === null) return null
  const artist = fitRun(upper(words.artistDisplay), ARTIST, right - left, measure)
  const top = region.y + Math.floor((side - (artist === null ? type.line : block)) / 2)
  const square = { x: region.x, y: region.y, width: side, height: side }
  const runs: SongRun[] = [{ text: title, face: type, x: left, y: top, vertical: false }]
  if (artist !== null) runs.push({ text: artist, face: ARTIST, x: left, y: top + type.line + 3, vertical: false })
  return cover
    ? { mode: 'plate', cover: square, play: glyph, playStyle: 'glyph', divider: null, filled: true, runs }
    : {
        mode: 'plate',
        cover: null,
        play: { x: region.x + Math.floor((side - 20) / 2), y: region.y + Math.floor((side - 22) / 2), width: 20, height: 22 },
        playStyle: 'glyph',
        divider: { x: region.x + side - 1, y: region.y, width: 1, height: side },
        filled: true,
        runs,
      }
}

function caption(region: Rect, type: SongFace, words: SongWords, measure: RunWidth, cover: boolean): SongBandLayout | null {
  const body = { x: region.x, y: region.y + 8, width: region.width, height: region.height - 8 }
  const lead = cover ? 44 : 36
  const leadBox = { x: body.x, y: body.y + Math.floor((body.height - lead) / 2), width: lead, height: lead }
  const ring = { x: body.x + body.width - 28, y: body.y + Math.floor((body.height - 28) / 2), width: 28, height: 28 }
  const left = leadBox.x + lead + 12
  const right = cover ? ring.x - 12 : body.x + body.width
  const artistFace: SongFace = { family: type.family, size: Math.max(12, Math.round(type.size * 0.7)), weight: 400, italic: type.italic, line: Math.ceil(Math.max(12, Math.round(type.size * 0.7)) * 1.12) }
  const title = words.title.trim()
  const artist = words.artistDisplay.trim()
  if (title === '' || type.line > body.height) return null
  const titleWidth = measure(title, type)
  const artistWidth = artist === '' ? 0 : measure(artist, artistFace)
  // The whole line or nothing: a caption never cuts the words.
  if (left + titleWidth + (artist === '' ? 0 : 8 + artistWidth) > right) return null
  const top = body.y + Math.floor((body.height - type.line) / 2)
  const runs: SongRun[] = [{ text: title, face: type, x: left, y: top, vertical: false }]
  // Baselines align: the artist's line box sits so both baselines meet.
  if (artist !== '') runs.push({ text: artist, face: artistFace, x: left + titleWidth + 8, y: top + type.size - artistFace.size, vertical: false })
  return cover
    ? { mode: 'caption', cover: leadBox, play: ring, playStyle: 'ring', divider: null, filled: false, runs }
    : { mode: 'caption', cover: null, play: leadBox, playStyle: 'circle', divider: null, filled: false, runs }
}

function card(region: Rect, type: SongFace, words: SongWords, measure: RunWidth): SongBandLayout | null {
  const titleSize = Math.min(30, Math.round(type.size * 1.3))
  const titleFace: SongFace = { ...type, size: titleSize, line: Math.ceil(titleSize * 1.12) }
  const button = 34
  if (titleFace.line > region.width - 8 || region.height < 200) return null
  const play = { x: region.x + Math.floor((region.width - button) / 2), y: region.y + region.height - 14 - button, width: button, height: button }
  const foot = play.y - 16
  const artist = fitRun(upper(words.artistDisplay), ARTIST, Math.min(200, Math.floor((foot - region.y - 24) / 3)), measure)
  const artistLength = artist === null ? 0 : measure(artist, ARTIST)
  const titleFoot = foot - (artist === null ? 0 : artistLength + 16)
  const title = fitRun(words.title, titleFace, titleFoot - (region.y + 24), measure)
  if (title === null) return null
  const titleLength = measure(title, titleFace)
  const runs: SongRun[] = [{
    text: title,
    face: titleFace,
    x: region.x + Math.floor((region.width - titleFace.line) / 2),
    // Centred in the length above the artist.
    y: titleFoot - Math.floor((titleFoot - (region.y + 24) - titleLength) / 2),
    vertical: true,
  }]
  if (artist !== null) runs.push({ text: artist, face: ARTIST, x: region.x + Math.floor((region.width - ARTIST.line) / 2), y: foot, vertical: true })
  return { mode: 'card', cover: null, play, playStyle: 'ring', divider: null, filled: true, runs }
}
