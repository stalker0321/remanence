import { describe, expect, it } from 'vitest'
import { fitRun, songBandLayout, type SongFace } from '../src/collage/songBand'

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
})
