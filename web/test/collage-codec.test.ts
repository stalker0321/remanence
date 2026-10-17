import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
import { decodeExpression, encodeExpression } from '../src/collage/codec'
import { decorStroke, drawList } from '../src/collage/paint'
import { prepare, sourceRect } from '../src/collage/prepare'
import { styleDigestHex } from '../src/collage/styles'
import { bytesToHex } from '../src/protocol/bytes'
import {
  artworkExpression,
  expressionV4,
  fitText,
  goldenBytes,
  overflowText,
  pressSheet,
  project,
  roundTrip,
  textProjectionExpression,
} from './sheets'

const track = { title: 'Hi', artistDisplay: 'Ada' }

describe('frozen expression bytes', () => {
  it('reproduces the Android GENEX03, BEXPR02, and GENEX04 goldens', () => {
    const artwork = artworkExpression()
    expect(bytesToHex(encodeExpression(artwork))).toBe(bytesToHex(goldenBytes('genex03Artwork')))
    expect(project(artwork)).toBe(bytesToHex(goldenBytes('bexpr02Artwork')))
    expect(project(textProjectionExpression())).toBe(bytesToHex(goldenBytes('bexpr02NoArt')))
    expect(bytesToHex(encodeExpression(expressionV4()))).toBe(bytesToHex(goldenBytes('genex04')))
    expect(roundTrip(goldenBytes('genex03Artwork'))).toBe(bytesToHex(goldenBytes('genex03Artwork')))
    expect(roundTrip(goldenBytes('genex04'))).toBe(bytesToHex(goldenBytes('genex04')))
  })

  it('refuses an older expression instead of inventing a grid', () => {
    const legacy = goldenBytes('genex03Artwork').slice()
    legacy.set([0x47, 0x45, 0x4e, 0x45, 0x58, 0x30, 0x31])
    expect(() => decodeExpression(legacy)).toThrow(CollageError)
    try {
      decodeExpression(legacy)
    } catch (error) {
      expect(error).toMatchObject({ code: 'UNSUPPORTED' })
    }
  })

  it('pins the three published style digests', async () => {
    expect(await styleDigestHex('press')).toBe('1f68610fa70b5210a72643d58f795c777d7a0e1eae262c522426d4b79a9a9a93')
    expect(await styleDigestHex('blue-letter')).toBe('da2d58fba44cb343a7020a8598d559d89d5e59a55f3651334b5c5647ef1872c3')
    expect(await styleDigestHex('poster')).toBe('628aec674a6d38afda3029e690f7a8b8ac13db90ed3bf3b2bb6bcadceccce5d4')
  })
})

describe('collage prepare', () => {
  it('rejects the codec goldens and paints a bundled press sheet', async () => {
    const sources = new Map(artworkExpression().input.photos.map((photo) => [photo.contentId, { width: photo.widthPx, height: photo.heightPx }]))
    expect((await prepare(decodeExpression(goldenBytes('genex03Artwork')), 'hello', null, sources, fitText)).kind).toBe('rejected')
    expect((await prepare(decodeExpression(goldenBytes('genex04')), 'hello', track, new Map([['c0', { width: 1000, height: 1000 }], ['c1', { width: 1000, height: 1000 }], ['c2', { width: 1000, height: 1000 }]]), fitText, { width: 640, height: 640 })).kind).toBe('rejected')

    const press = await pressSheet()
    const ready = await prepare(press, 'hello', track, new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]]), fitText)
    expect(ready.kind).toBe('ready')
    if (ready.kind !== 'ready') return
    const ops = drawList(ready.scene, new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]]))
    expect(ops?.map((op) => op.kind)).toEqual(['paper', 'photo', 'photo', 'photo', 'note', 'music-background', 'music-text', 'music-play'])
    expect(ready.scene.artwork).toBeNull()
    expect((await prepare(press, 'hello', track, new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]]), overflowText)).kind).toBe('rejected')
  })

  it('degrades a missing cover to text and keeps a verified cover off the play control', async () => {
    const press = await pressSheet()
    const region = press.musicRegion
    if (!region) throw new Error('music')
    const covered = {
      ...press,
      musicRegion: {
        ...region,
        mode: 'thumbnail',
        artwork: {
          provider: 'catalog-sample',
          url: 'https://art.example.org/cover/ab12.webp',
          sha256: new Uint8Array(32).fill(4),
          mimeType: 'image/webp',
          width: 640,
          height: 640,
        },
      },
    }
    const sources = new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]])
    const without = await prepare(covered, 'hello', track, sources, fitText, null)
    expect(without.kind).toBe('ready')
    if (without.kind === 'ready') {
      expect(without.scene.artwork).toBeNull()
      expect(without.scene.music).not.toBeNull()
    }
    const withCover = await prepare(covered, 'hello', track, sources, fitText, { width: 640, height: 640 })
    expect(withCover.kind).toBe('ready')
    if (withCover.kind === 'ready') {
      expect(withCover.scene.artwork).not.toBeNull()
      expect(withCover.scene.music).toBeNull()
      expect(drawList(withCover.scene, sources)?.some((op) => op.kind === 'music-play')).toBe(false)
      expect(drawList(withCover.scene, sources)?.some((op) => op.kind === 'music-artwork')).toBe(true)
    }
  })

  it('uses a transparent band for blue-letter and does not paint print-plane shadow', async () => {
    const sheet = { ...(await pressSheet('blue-letter')), expressionContractVersion: 4 }
    const ready = await prepare(sheet, 'hello', track, new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]]), fitText)
    expect(ready.kind).toBe('ready')
    if (ready.kind === 'ready') expect(ready.scene.music?.transparentBackground).toBe(true)
    const v4 = {
      ...(await pressSheet()),
      expressionContractVersion: 4,
      grammarId: '6',
      provenance: null,
      decor: [{ kind: 'print-plane' as const, x: 20, y: 20, width: 200, height: 280 }],
    }
    const painted = await prepare(v4, 'hello', track, new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]]), fitText)
    expect(painted.kind).toBe('ready')
    if (painted.kind !== 'ready') return
    expect(painted.scene.decor[0]?.shadow).toBe(true)
    expect(decorStroke(painted.scene.decor[0]!).border).toBeNull()
    expect(drawList(painted.scene, new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]]))?.[1]).toEqual({ kind: 'decor', index: 0 })
  })

  it('closes the crop on the far edge of the bitmap', () => {
    expect(sourceRect({ x: 0, y: 0, width: 10000, height: 10000 }, 64, 48)).toEqual({ x: 0, y: 0, width: 64, height: 48 })
    expect(sourceRect({ x: 100, y: 200, width: 5000, height: 6000 }, 1080, 1920)).toEqual({ x: 10, y: 38, width: 540, height: 1152 })
  })
})
