import { describe, expect, it } from 'vitest'
import { creatorChoices, creatorScene, sceneNoteLayout, type CreatorCandidateView } from '../src/collage/creatorPreview'
import { encodeContentManifest } from '../src/collage/manifest'
import { parseContentManifest } from '../src/collage/wire'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { buildFrozenExpression } from '../src/collage/frozenExpression'
import { prepare, type TextMeasurer } from '../src/collage/prepare'
import { CHARACTER_VERSION, opaqueColor, styleById, styleIds } from '../src/collage/styles'
import type { ArtworkRef } from '../src/collage/model'
import { freezeSelection } from '../src/create/creatorFreeze'

/**
 * The site's pool against the recipient: every layout the sender is offered
 * must seal to an expression that the recipient's own `prepare` opens, with
 * the same geometry, marks, decor and song band that were painted.
 */

const SENDER = 'sergio'
const RECIPIENT = { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' }

/** Proportional glyphs, one line box per wrapped line. */
const measurer: TextMeasurer = {
  measure(request) {
    const glyph = request.size * 0.5
    const lines = request.text.split('\n')
    const spacing = (request.tracking ?? 0) * request.size
    const width = Math.max(...lines.map((line) => line.length * (glyph + spacing)))
    if (request.softWrap === false || width <= request.maxWidth) {
      const height = lines.length * request.line
      return { width, height, hasVisualOverflow: height > request.maxHeight }
    }
    const perLine = Math.max(1, Math.floor(request.maxWidth / glyph))
    const count = lines.reduce((sum, line) => sum + Math.max(1, Math.ceil(line.length / perLine)), 0)
    const height = count * request.line
    return { width: Math.min(width, perLine * glyph), height, hasVisualOverflow: height > request.maxHeight }
  },
}

function hexHash(index: number): string {
  return `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32)
}

function photos(aspects: number[]) {
  return aspects.map((aspect, index) => ({
    contentId: `p${index}`,
    width: Math.round(aspect * 1200),
    height: 1200,
    contentHash: hexHash(index),
  }))
}

const TRACK = { trackId: '0198f0a0-1111-7000-8000-000000000001', title: 'Moon', artistDisplay: 'Ada' }

const COVER: ArtworkRef = {
  provider: 'coverartarchive',
  url: 'https://archive.org/download/mbid-956fbc58-362d-43b8-b880-3779e0508559/mbid-956fbc58-362d-43b8-b880-3779e0508559-34025419985_thumb500.jpg',
  sha256: new Uint8Array(32).fill(7),
  mimeType: 'image/jpeg',
  width: 500,
  height: 500,
}

async function sealAndOpen(view: CreatorCandidateView, source: ReturnType<typeof photos>, note: string | null, withTrack: boolean, cover: ArtworkRef | null = null) {
  const style = styleById(view.styleId)!
  const scene = creatorScene({
    view,
    styleId: view.styleId,
    senderHandle: SENDER,
    noteText: note,
    photos: new Map(source.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
    track: withTrack ? TRACK : null,
    measurer,
    cover,
  })
  const noteLayout = sceneNoteLayout(scene, measurer)?.layout ?? null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId: view.styleId, styleVersion: style.version, cover })
  const frozen = freezeSelection({
    branchId: view.branch,
    styleId: view.styleId,
    styleVersion: style.version,
    senderHandle: SENDER,
    slots: view.slots.map((slot, index) => ({
      contentId: slot.photoId,
      ordinal: index,
      contentHash: source[index]!.contentHash,
      widthPx: source[index]!.width,
      heightPx: source[index]!.height,
      crop: { ...slot.crop },
    })),
    note,
    noteRegion: scene.note ? { ...scene.note.region } : null,
    noteFits: true,
    track: withTrack ? { ...TRACK, version: null, durationMs: null } : null,
    ownerId: 'owner-1',
    sessionGeneration: 1,
    recipient: RECIPIENT,
    renderedKey: 'rk',
    renderedPlan,
  })
  if (!frozen.ok) throw new Error(`freeze: ${frozen.reason}`)
  const sealed = await buildFrozenExpression(frozen.frozen, 3n)
  if (!sealed.ok) throw new Error(`${view.grammarId}/${view.styleId}/${view.branch}: ${sealed.reason} ${sealed.detail ?? ''}`)
  // The recipient's path: the content manifest, parsed, then its own prepare.
  const blobs = new Map(source.map((photo, index) => [photo.contentId, Uint8Array.from({ length: 16 }, () => index + 1)]))
  const encoded = await encodeContentManifest({
    expression: sealed.expression,
    capsuleId: Uint8Array.from({ length: 16 }, (_value, index) => 0xa0 + index),
    candidateId: 'cand-1',
    blobIdByContentId: blobs,
    ...(withTrack ? { track: { ...TRACK, version: null, durationMs: null } } : {}),
  })
  if (!encoded.ok) throw new Error(`manifest: ${encoded.reason} ${encoded.detail ?? ''}`)
  const parsed = await parseContentManifest(encoded.bytes)
  const expression = parsed.expression
  const opened = await prepare(
    expression,
    parsed.note,
    parsed.track,
    new Map(source.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
    measurer,
    // The recipient's checked cover: the sealed size once the bytes matched.
    expression.musicRegion?.artwork ? { width: expression.musicRegion.artwork.width, height: expression.musicRegion.artwork.height } : null,
  )
  return { scene, opened, expression }
}

describe('creator choices: the Android pool on the site', () => {
  it('offers many grammars and styles, one layout per grammar and style', () => {
    const views = creatorChoices({
      preview: { id: 'c', sender: SENDER, photos: photos([1.5, 0.75, 1]), note: 'Summer by the sea.' },
      measurer,
      track: null,
      seed: 'draft-1',
    })
    const grammars = new Set(views.map((view) => view.grammarId))
    expect(grammars.size).toBeGreaterThanOrEqual(6)
    const keys = views.map((view) => `${view.grammarId}/${view.styleId}`)
    expect(new Set(keys).size).toBe(keys.length)
    for (const view of views) expect(`g${styleById(view.styleId)!.grammar}`).toBe(view.grammarId)
    // One card per grammar comes before any grammar's second style.
    expect(new Set(views.slice(0, grammars.size).map((view) => view.grammarId)).size).toBe(grammars.size)
    // A draft keeps its order; another draft may differ.
    const again = creatorChoices({
      preview: { id: 'c', sender: SENDER, photos: photos([1.5, 0.75, 1]), note: 'Summer by the sea.' },
      measurer,
      track: null,
      seed: 'draft-1',
    })
    expect(again.map((view) => `${view.grammarId}/${view.styleId}/${view.branch}`)).toEqual(views.map((view) => `${view.grammarId}/${view.styleId}/${view.branch}`))
  })

  it('never offers the face grammar or the cover sleeve without analysis and a cover', () => {
    const views = creatorChoices({
      preview: { id: 'c', sender: SENDER, photos: photos([1, 1, 1, 1]), note: null, music: { artwork: false } },
      measurer,
      track: TRACK,
      seed: 's',
    })
    expect(views.some((view) => view.grammarId === 'g22' || view.grammarId === 'g20')).toBe(false)
  })

  for (const [label, aspects, note, withTrack] of [
    ['3 photos, note', [1.5, 0.75, 1], 'Summer by the sea.', false],
    ['4 photos, no note', [1, 1.33, 0.75, 1], null, false],
    ['5 photos, long note', [1.5, 1, 0.66, 1.2, 0.8], 'We walked for hours along the coast and it rained twice, and the bread from the market was still warm.', false],
    ['3 photos, note and a song', [1, 1, 1], 'Our song.', true],
    ['4 photos and a song', [0.75, 0.75, 1.5, 1], null, true],
  ] as const) {
    it(`seals every offered layout so the recipient opens it: ${label}`, async () => {
      const source = photos([...aspects])
      const views = creatorChoices({
        preview: { id: 'c', sender: SENDER, photos: source, note, music: withTrack ? { artwork: false } : null },
        measurer,
        track: withTrack ? TRACK : null,
        seed: label,
      })
      expect(views.length).toBeGreaterThan(0)
      for (const view of views) {
        const { scene, opened, expression } = await sealAndOpen(view, source, note, withTrack)
        expect(opened.kind, `${view.grammarId}/${view.styleId}/${view.branch}: ${opened.kind === 'rejected' ? opened.reason : ''}`).toBe('ready')
        if (opened.kind !== 'ready') continue
        expect(expression.grammarId).toBe(view.grammarId)
        expect(opened.scene.photos.map((photo) => photo.destination)).toEqual(scene.photos.map((photo) => photo.destination))
        expect(opened.scene.provenance.map((mark) => [mark.family, mark.size, mark.region])).toEqual(scene.provenance.map((mark) => [mark.family, mark.size, mark.region]))
        expect(opened.scene.decor).toEqual(scene.decor)
        expect(opened.scene.music?.region ?? null).toEqual(scene.music?.region ?? null)
        expect(opened.scene.music?.mode ?? null).toEqual(scene.music?.mode ?? null)
      }
      if (withTrack) expect(views.every((view) => view.music !== null)).toBe(true)
    })
  }

  it('seals a checked cover in the bands that hold one, and the recipient paints it', async () => {
    const source = photos([1, 1, 1])
    const views = creatorChoices({
      preview: { id: 'c', sender: SENDER, photos: source, note: null, music: { artwork: true } },
      measurer,
      track: TRACK,
      seed: 'cover',
      cover: COVER,
    })
    expect(views.some((view) => view.grammarId === 'g20')).toBe(true)
    let covered = 0
    for (const view of views) {
      const { scene, opened, expression } = await sealAndOpen(view, source, null, true, COVER)
      expect(opened.kind, `${view.grammarId}/${view.styleId}/${view.branch}: ${opened.kind === 'rejected' ? opened.reason : ''}`).toBe('ready')
      if (scene.artwork === null) continue
      covered++
      expect(expression.musicRegion?.artwork?.url).toBe(COVER.url)
      expect(expression.input.music?.artworkId ?? null).toBeNull()
      if (opened.kind === 'ready') expect(opened.scene.artwork).not.toBeNull()
    }
    expect(covered).toBeGreaterThan(0)
  })

  it('song layouts exist: the site can now send a song', () => {
    const views = creatorChoices({
      preview: { id: 'c', sender: SENDER, photos: photos([1, 1, 1]), note: null, music: { artwork: false } },
      measurer,
      track: TRACK,
      seed: 'song',
    })
    expect(views.length).toBeGreaterThan(3)
  })
})

describe('creator choices with a video square', () => {
  const cases: Array<[string, number[], string | null, boolean]> = [
    ['3 mixed', [1.5, 0.75, 1], null, false],
    ['3 mixed, note', [1.5, 0.75, 1], 'Summer by the sea.', false],
    ['4 landscape', [1.5, 1.5, 1.33, 1], null, false],
    ['4 portrait, song', [0.75, 0.75, 0.8, 1], 'Hi!', true],
    ['5 mixed', [1.5, 0.75, 0.75, 1.5, 1], 'Summer by the sea.', false],
    ['5 portrait, song', [0.75, 0.75, 0.8, 0.66, 1], null, true],
  ]
  for (const [name, aspects, note, song] of cases) {
    it(`seats the first frame in a square tile of at least 140 px (${name})`, () => {
      const source = photos(aspects)
      const poster = source[source.length - 1]!.contentId
      const views = creatorChoices({
        preview: { id: 'c', sender: SENDER, photos: source, note, music: song ? { artwork: false } : null },
        measurer,
        track: song ? TRACK : null,
        seed: 'draft-1',
        posterContentId: poster,
      })
      const grammars = new Set(views.map((view) => view.grammarId))
      expect(grammars.size).toBeGreaterThanOrEqual(3)
      for (const view of views) {
        // Slots stay in authored order; only the tiles move.
        expect(view.slots.map((item) => item.photoId)).toEqual(source.map((photo) => photo.contentId))
        const slot = view.slots.find((item) => item.photoId === poster)!
        const { width, height } = slot.destination
        expect(Math.min(width, height)).toBeGreaterThanOrEqual(140)
        expect(Math.abs(width - height)).toBeLessThanOrEqual(0.05 * Math.max(width, height))
      }
    })
  }
})

describe('character styles on the site', () => {
  const characterIds = styleIds().filter((id) => styleById(id)!.version === CHARACTER_VERSION)

  it('stay hidden from senders until offered, and then replace blue-letter', () => {
    const preview = { id: 'c', sender: SENDER, photos: photos([1.5, 0.75, 1]), note: 'Summer by the sea.' }
    const hidden = creatorChoices({ preview, measurer, track: null, seed: 's' })
    expect(hidden.some((view) => characterIds.includes(view.styleId))).toBe(false)
    const offered = creatorChoices({ preview, measurer, track: null, seed: 's', characterOffered: true })
    expect(offered.some((view) => characterIds.includes(view.styleId))).toBe(true)
    expect(offered.some((view) => view.styleId === 'blue-letter')).toBe(false)
  })

  for (const [label, aspects, note, withTrack] of [
    ['3 photos, note', [1.5, 0.75, 1], 'Summer by the sea.', false],
    ['4 photos and a song', [0.75, 0.75, 1.5, 1], null, true],
    ['5 photos, note', [1.5, 1, 0.66, 1.2, 0.8], 'Rain twice.', false],
  ] as const) {
    it(`seal the word as Remanence and open it as the style shows it: ${label}`, async () => {
      const source = photos([...aspects])
      const views = creatorChoices({
        preview: { id: 'c', sender: SENDER, photos: source, note, music: withTrack ? { artwork: false } : null },
        measurer,
        track: withTrack ? TRACK : null,
        seed: label,
        styleIds: characterIds,
        characterOffered: true,
      })
      expect(views.length).toBeGreaterThan(0)
      for (const view of views) {
        const style = styleById(view.styleId)!
        const at = `${view.grammarId}/${view.styleId}/${view.branch}`
        const { scene, opened, expression } = await sealAndOpen(view, source, note, withTrack)
        expect(opened.kind, `${at}: ${opened.kind === 'rejected' ? opened.reason : ''}`).toBe('ready')
        if (opened.kind !== 'ready') continue
        const sealed = expression.provenance!.find((mark) => mark.kind === 'brand')!
        const shown = opened.scene.provenance.find((mark) => mark.kind === 'brand')!
        expect(sealed.text, at).toBe('Remanence')
        expect(shown.text, at).toBe(scene.provenance.find((mark) => mark.kind === 'brand')!.text)
        if (style.wordmark?.case === 'UPPER') expect(shown.text, at).toBe('REMANENCE')
        if (style.wordmark?.case === 'LOWER') expect(shown.text, at).toBe('remanence')
        if (style.wordmark?.ink) expect(shown.color, at).toBe(opaqueColor(style.wordmark.ink))
        if (style.wordmark?.align) expect(sealed.align, at).toBe(style.wordmark.align.toLowerCase())
        if (style.wordmark?.orientation) expect(sealed.orientation, at).toBe(style.wordmark.orientation.toLowerCase().replace('_', '-'))
        expect(opened.scene.provenance.map((mark) => [mark.family, mark.size, mark.region, mark.align])).toEqual(scene.provenance.map((mark) => [mark.family, mark.size, mark.region, mark.align]))
      }
    })
  }

  it('swap the word into the sender line and set the sender right', () => {
    const preview = { id: 'c', sender: SENDER, photos: photos([1.5, 0.75, 1]), note: 'Summer by the sea.' }
    const plain = creatorChoices({ preview, measurer, track: null, seed: 's', styleIds: ['saffron'] })
    const swapped = creatorChoices({ preview, measurer, track: null, seed: 's', styleIds: ['seam'], characterOffered: true })
    const before = plain.find((view) => view.grammarId === 'g26')
    const after = swapped.find((view) => view.branch === before?.branch)
    expect(before && after).toBeTruthy()
    const mark = (view: CreatorCandidateView, kind: 'brand' | 'sender') => view.provenance.find((item) => item.kind === kind)!
    expect(mark(after!, 'brand').region).toEqual(mark(before!, 'sender').region)
    expect(mark(after!, 'sender').region).toEqual(mark(before!, 'brand').region)
    expect(mark(after!, 'brand').align).toBe('left')
    expect(mark(after!, 'sender').align).toBe(mark(before!, 'brand').orientation === 'horizontal' ? 'right' : 'left')
  })
})

