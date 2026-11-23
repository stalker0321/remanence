import { describe, expect, it } from 'vitest'
import { fitRun, songBandLayout, voiceBandLayout, voiceLength, VOICE_PEAKS, type SongFace } from '../src/collage/songBand'

/** 0.5 em per glyph: widths are easy to reason about. */
const width = (text: string, face: SongFace) => [...text].length * face.size * 0.5
const TYPE: SongFace = { family: 'StudySans', size: 20, weight: 400, italic: false, line: 23 }
const BAND = { x: 16, y: 539, width: 328, height: 64 }
const SPINE = { x: 152, y: 0, width: 56, height: 591 }

describe('song band', () => {
  it('cuts a long title with an ellipsis and keeps the rest', () => {
    expect(fitRun('Moon', TYPE, 100, width)).toBe('Moon')
    expect(fitRun('A very long song title', TYPE, 100, width)).toBe('A very lo…')
    expect(fitRun('   ', TYPE, 100, width)).toBeNull()
  })

  it('lays a plate out with the cover as a full-height square and the title on one line', () => {
    const band = songBandLayout(BAND, 'plate', TYPE, { title: 'Bohemian Rhapsody', artistDisplay: 'Queen' }, width, true)!
    expect(band.cover).toEqual({ x: 16, y: 539, width: 64, height: 64 })
    expect(band.runs.map((run) => run.text)).toEqual(['Bohemian Rhapsody', 'QUEEN'])
    expect(band.runs[0]!.x).toBe(16 + 64 + 12)
    expect(band.playStyle).toBe('glyph')
    const plain = songBandLayout(BAND, 'plate', TYPE, { title: 'Moon', artistDisplay: 'Ada' }, width, false)!
    expect(plain.cover).toBeNull()
    expect(plain.divider).not.toBeNull()
  })

  it('offers a caption only when title and artist fit one line', () => {
    expect(songBandLayout(BAND, 'caption', TYPE, { title: 'Moon', artistDisplay: 'Ada' }, width, false)?.mode).toBe('caption')
    expect(songBandLayout(BAND, 'caption', TYPE, { title: 'A long title that never fits here', artistDisplay: 'Somebody' }, width, false)).toBeNull()
  })

  it('runs the spine title along the whole spine, bottom to top, with the play button at the foot', () => {
    const band = songBandLayout(SPINE, 'card', TYPE, { title: 'Под небом голубым', artistDisplay: 'Аквариум' }, width, false)!
    expect(band.runs.every((run) => run.vertical)).toBe(true)
    expect(band.runs[0]!.face.size).toBe(26)
    expect(band.play.y + band.play.height).toBe(591 - 14)
    expect(band.runs[0]!.y).toBeLessThan(band.runs[1]!.y)
  })

  const voice = { peaks: Uint8Array.from({ length: VOICE_PEAKS }, (_, index) => (index * 37) % 256), durationMs: 42_400 }
  const inside = (outer: { x: number; y: number; width: number; height: number }) => (rect: { x: number; y: number; width: number; height: number }) =>
    rect.x >= outer.x && rect.y >= outer.y && rect.x + rect.width <= outer.x + outer.width && rect.y + rect.height <= outer.y + outer.height

  it("writes a voice note's length as whole seconds, never zero", () => {
    expect(voiceLength(42_400)).toBe('0:42')
    expect(voiceLength(60_000)).toBe('1:00')
    expect(voiceLength(200)).toBe('0:01')
  })

  it('draws a voice note on the plate: play in the square, one bar per peak, the microphone and the length under them', () => {
    const band = voiceBandLayout(BAND, 'plate', TYPE, voice, width)
    expect(band.cover).toBeNull()
    expect(band.divider).not.toBeNull()
    expect(band.bars).toHaveLength(VOICE_PEAKS)
    expect(band.bars!.every(inside(BAND))).toBe(true)
    expect(band.bars![0]!.x).toBe(16 + 64 + 12 + 1)
    expect(band.runs.map((run) => run.text)).toEqual(['0:42'])
    expect(band.mic!.y).toBeGreaterThan(Math.max(...band.bars!.map((bar) => bar.y + bar.height)))
    // A louder peak is a taller bar.
    expect(band.bars![1]!.height).toBeGreaterThan(band.bars![0]!.height)
  })

  it('draws a voice note as a caption without refusing and as a spine with the bars along it', () => {
    const caption = voiceBandLayout(BAND, 'caption', TYPE, voice, width)
    expect(caption.mode).toBe('caption')
    expect(caption.mic).toBeNull()
    expect(caption.bars!.every(inside(BAND))).toBe(true)
    expect(Math.max(...caption.bars!.map((bar) => bar.x + bar.width))).toBeLessThan(caption.runs[0]!.x)
    const spine = voiceBandLayout(SPINE, 'card', TYPE, voice, width)
    expect(spine.bars).toHaveLength(VOICE_PEAKS)
    expect(spine.bars!.every(inside(SPINE))).toBe(true)
    expect(spine.runs[0]!.vertical).toBe(true)
    expect(Math.max(...spine.bars!.map((bar) => bar.y + bar.height))).toBeLessThan(spine.runs[0]!.y - width('0:42', spine.runs[0]!.face))
    expect(spine.play.y + spine.play.height).toBe(591 - 14)
  })
})
