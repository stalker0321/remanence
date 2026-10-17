import { decodeExpression, encodeExpression, projectionBytes } from '../src/collage/codec'
import type { ResolvedExpression, TextType } from '../src/collage/model'
import type { TextMeasurer } from '../src/collage/prepare'
import { styleDigest } from '../src/collage/styles'
import { bytesToHex, hexToBytes } from '../src/protocol/bytes'
import goldens from './fixtures/expression-goldens.json'

const noteType: TextType = { family: 'StudySans', size: 16, weight: 400, italic: false, line: 18 }
const digest = new Uint8Array(32).fill(7)

function photo(id: string, ordinal: number, width: number, height: number, hash: string) {
  return { contentId: id, ordinal, widthPx: width, heightPx: height, contentHash: hash }
}

function placement(id: string, x: number, shape: 'rect' | 'round' | 'ellipse' = 'rect', contentRect: ResolvedExpression['placements'][number]['contentRect'] = null) {
  return {
    contentId: id,
    x,
    y: 0,
    width: 120,
    height: 213,
    crop: { x: 100, y: 200, width: 5000, height: 6000 },
    maskId: null,
    contentRect,
    shape,
  }
}

const artwork = {
  provider: 'catalog-sample',
  url: 'https://art.example.org/cover/ab12.webp',
  sha256: Uint8Array.from({ length: 32 }, (_, index) => index),
  mimeType: 'image/webp',
  width: 640,
  height: 640,
}

/** Android GeneratorExpressionContractV3Test.expressionV3Artwork. Codec golden, not a paintable sheet. */
export function artworkExpression(): ResolvedExpression {
  const hashes = ['a', 'b', 'c']
  const sizes = [[1080, 1920], [1920, 1080], [1080, 1080]]
  return {
    canvasVersion: 1,
    grammarId: 'g-hold',
    grammarVersion: 3,
    branchId: 'b-rows',
    input: {
      ownerId: 'owner-1',
      epoch: 7n,
      photos: hashes.map((hash, ordinal) => photo(`p${ordinal + 1}`, ordinal, sizes[ordinal]?.[0] ?? 0, sizes[ordinal]?.[1] ?? 0, hash.repeat(64))),
      note: 'hello',
      music: null,
    },
    placements: ['p1', 'p2', 'p3'].map((id) => placement(id, 0, 'rect', { x: 0, y: 0, width: 120, height: 213 })),
    noteTreatment: 'regular-region',
    diagnostics: ['ok'],
    fontVersion: 2,
    paletteVersion: 4,
    expressionContractVersion: 3,
    noteRegion: null,
    style: { styleId: 'press', styleVersion: 'style-study-1', digest },
    noteType,
    musicRegion: { x: 16, y: 480, width: 328, height: 64, mode: 'thumbnail', type: noteType, artwork },
    provenance: null,
    decor: null,
  }
}

/** N3 projection fixture: same rectangle, mode text, artwork absent. */
export function textProjectionExpression(): ResolvedExpression {
  return {
    ...artworkExpression(),
    musicRegion: { x: 16, y: 480, width: 328, height: 64, mode: 'text', type: noteType, artwork: null },
  }
}

/** Android GeneratorExpressionV4Test.expressionV4. */
export function expressionV4(): ResolvedExpression {
  return {
    canvasVersion: 1,
    grammarId: '6',
    grammarVersion: 3,
    branchId: 'hinge-2/1-0.52-equal-paired',
    input: {
      ownerId: 'owner-1',
      epoch: 7n,
      photos: [0, 1, 2].map((ordinal) => photo(`c${ordinal}`, ordinal, 1000, 1000, String.fromCharCode(97 + ordinal).repeat(64))),
      note: 'hello',
      music: null,
    },
    placements: [
      placement('c0', 0, 'rect'),
      placement('c1', 120, 'round'),
      placement('c2', 240, 'rect'),
    ],
    noteTreatment: 'regular-region',
    diagnostics: ['ok'],
    fontVersion: 2,
    paletteVersion: 4,
    expressionContractVersion: 4,
    noteRegion: null,
    style: { styleId: 'signal', styleVersion: 'style-study-1', digest: digest.slice() },
    noteType,
    musicRegion: {
      x: 10, y: 500, width: 340, height: 76, mode: 'pulse',
      type: { family: 'Narrow', size: 24, weight: 700, italic: false, line: 26 },
      artwork,
    },
    provenance: [
      {
        kind: 'brand', text: 'Remanence', x: 12, y: 4, width: 340, height: 75,
        family: 'StudySerif', size: 64, weight: 400, italic: false, line: 70,
        orientation: 'horizontal', align: 'left',
      },
      {
        kind: 'sender', text: 'From alice', x: 16, y: 613, width: 328, height: 23,
        family: 'StudySerif', size: 20, weight: 400, italic: true, line: 21,
        orientation: 'horizontal', align: 'right',
      },
    ],
    decor: [{ kind: 'carrier', x: 6, y: 5, width: 349, height: 627 }],
  }
}

export function goldenBytes(name: keyof typeof goldens): Uint8Array {
  return hexToBytes(goldens[name])
}

export function blobs(): Map<string, Uint8Array> {
  return new Map(['p1', 'p2', 'p3', 'c0', 'c1', 'c2'].map((id) => [id, new Uint8Array(16).fill(1)]))
}

export const fitText: TextMeasurer = {
  measure: () => ({ width: 8, height: 8, hasVisualOverflow: false }),
}

export const overflowText: TextMeasurer = {
  measure: () => ({ width: 10_000, height: 10_000, hasVisualOverflow: true }),
}

export async function pressSheet(styleId = 'press'): Promise<ResolvedExpression> {
  const bundled = await styleDigest(styleId)
  if (!bundled) throw new Error('missing style')
  const base = artworkExpression()
  return {
    ...base,
    grammarId: 'g5',
    fontVersion: 1,
    paletteVersion: 1,
    noteRegion: { x: 16, y: 360, width: 328, height: 90 },
    placements: base.placements.map((item, index) => ({ ...item, x: index * 120, contentRect: null })),
    style: { styleId, styleVersion: 'style-study-1', digest: bundled },
    musicRegion: {
      x: 16, y: 480, width: 328, height: 80, mode: 'text',
      type: noteType,
      artwork: null,
    },
    input: {
      ...base.input,
      photos: base.input.photos.map((item) => ({ ...item, widthPx: 64, heightPx: 48 })),
      music: { trackId: 'track-1', artworkId: null },
    },
  }
}

export function roundTrip(bytes: Uint8Array): string {
  return bytesToHex(encodeExpression(decodeExpression(bytes)))
}

export function project(expression: ResolvedExpression, ids = blobs()): string {
  return bytesToHex(projectionBytes(expression, 'cand-1', ids))
}
