import { describe, expect, it } from 'vitest'
import { creatorChoices, creatorScene, sceneNoteLayout, type CreatorCandidateView } from '../src/collage/creatorPreview'
import { encodeContentManifest } from '../src/collage/manifest'
import { parseContentManifest } from '../src/collage/wire'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { buildFrozenExpression } from '../src/collage/frozenExpression'
import { prepare, type TextMeasurer } from '../src/collage/prepare'
import { styleById } from '../src/collage/styles'
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
    const width = Math.max(...lines.map((line) => line.length * glyph))
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

async function sealAndOpen(view: CreatorCandidateView, source: ReturnType<typeof photos>, note: string | null, withTrack: boolean) {
  const style = styleById(view.styleId)!
  const scene = creatorScene({
    view,
    styleId: view.styleId,
    senderHandle: SENDER,
    noteText: note,
    photos: new Map(source.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
    track: withTrack ? TRACK : null,
    measurer,
  })
  const noteLayout = sceneNoteLayout(scene, measurer)?.layout ?? null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId: view.styleId, styleVersion: style.version })
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
