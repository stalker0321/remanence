import { describe, expect, it } from 'vitest'
import { layoutCreatorNote } from '../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../src/collage/creatorPreview'
import { decodeExpression, encodeExpression } from '../src/collage/codec'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { buildFrozenExpression, type FrozenExpressionRefusal } from '../src/collage/frozenExpression'
import { prepare } from '../src/collage/prepare'
import { validateResolved, type ResolvedExpression } from '../src/collage/model'
import { styleById } from '../src/collage/styles'
import { freezeSelection, type CreatorFreeze } from '../src/create/creatorFreeze'
import { bytesToHex } from '../src/protocol/bytes'
import type { TextMeasurer } from '../src/collage/prepare'

const SENDER = 'sergio'
const RECIPIENT = { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' }

/** Deterministic 10px/glyph measurer: the shared softWrap:false wrap primitive. */
const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

function hexHash(index: number): string {
  return `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32)
}

function photos(count = 3) {
  return Array.from({ length: count }, (_, index) => ({
    contentId: `p${index}`,
    width: 1000,
    height: 1000,
    contentHash: hexHash(index),
  }))
}

function makeFrozen(note: string | null, styleId = 'press') {
  const style = styleById(styleId)!
  const source = photos()
  const views = previewCandidates(
    { id: 'creator', sender: SENDER, photos: source, note },
    undefined,
    plannerFromTextMeasurer(measurer, 640, 640, style.note),
  )
  const view = views[0]!
  const scene = creatorScene({
    view,
    styleId,
    senderHandle: SENDER,
    noteText: note,
    photos: new Map(source.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
  })
  const noteLayout = scene.note
    ? layoutCreatorNote(
        scene.note.text,
        { font: scene.note.family, weight: scene.note.weight, italic: scene.note.italic, size: scene.note.size, line: scene.note.line },
        scene.note.region,
        (line) =>
          measurer.measure({
            text: line,
            family: scene.note!.family,
            size: scene.note!.size,
            weight: scene.note!.weight,
            italic: scene.note!.italic,
            line: scene.note!.line,
            maxWidth: Number.POSITIVE_INFINITY,
            maxHeight: Number.POSITIVE_INFINITY,
            softWrap: false,
          }).width,
      )
    : null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId, styleVersion: style.version })
  const result = freezeSelection({
    branchId: view.branch,
    styleId,
    styleVersion: style.version,
    senderHandle: SENDER,
    slots: view.slots.map((slot, index) => ({
      contentId: slot.photoId,
      ordinal: index,
      contentHash: hexHash(index),
      widthPx: source[index]!.width,
      heightPx: source[index]!.height,
      crop: { ...slot.crop },
    })),
    note,
    noteRegion: scene.note ? { ...scene.note.region } : null,
    noteFits: true,
    musicSelected: false,
    ownerId: 'owner-1',
    sessionGeneration: 7,
    recipient: RECIPIENT,
    renderedKey: 'rk-1',
    renderedPlan,
    createdAtMs: 1,
  })
  if (!result.ok) throw new Error(`freeze failed: ${result.reason}`)
  return { frozen: result.frozen, view, scene, renderedPlan, style, source }
}

function draftOf(frozen: CreatorFreeze, change: (draft: any) => void): CreatorFreeze {
  const draft = structuredClone(frozen)
  change(draft)
  return draft
}

async function refusalReason(frozen: CreatorFreeze, epoch: bigint): Promise<FrozenExpressionRefusal | 'ok'> {
  const result = await buildFrozenExpression(frozen, epoch)
  return result.ok ? 'ok' : result.reason
}

const sources = new Map(photos().map((photo) => [photo.contentId, { width: photo.width, height: photo.height }]))

describe('frozen expression adapter', () => {
  it('maps a real frozen snapshot to the V4 expression + bytes from the snapshot only', async () => {
    const note = 'Probe note text.'
    const { frozen, view, renderedPlan, style } = makeFrozen(note)
    const before = JSON.stringify(frozen)
    const result = await buildFrozenExpression(frozen, 17n)
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const expression = result.expression
    expect(validateResolved(expression).ok).toBe(true)
    expect(expression.canvasVersion).toBe(1)
    expect(expression.grammarId).toBe('g5')
    expect(expression.grammarVersion).toBe(1)
    expect(expression.branchId).toBe(view.branch)
    expect(expression.noteTreatment).toBe('regular-region')
    expect(expression.diagnostics).toEqual([])
    expect(expression.fontVersion).toBe(1)
    expect(expression.paletteVersion).toBe(1)
    expect(expression.expressionContractVersion).toBe(4)
    expect(expression.musicRegion).toBeNull()
    expect(expression.decor).toBeNull()
    expect(expression.style).toEqual({ styleId: 'press', styleVersion: style.version, digest: expression.style!.digest })
    expect(expression.style!.digest.length).toBe(32)

    expect(expression.input.ownerId).toBe('owner-1')
    expect(expression.input.epoch).toBe(17n)
    expect(expression.input.note).toBe(note)
    expect(expression.input.music).toBeNull()
    expect(expression.input.photos).toEqual(
      frozen.slots.map((slot, index) => ({ contentId: slot.contentId, ordinal: index, widthPx: slot.widthPx, heightPx: slot.heightPx, contentHash: slot.contentHash })),
    )
    expect(expression.placements.map((placement) => placement.contentId)).toEqual(view.slots.map((slot) => slot.photoId))
    expression.placements.forEach((placement, index) => {
      const plan = renderedPlan.slots[index]!
      const outer = frozen.slots[index]!
      expect(placement).toEqual({
        contentId: outer.contentId,
        x: plan.destination.x,
        y: plan.destination.y,
        width: plan.destination.width,
        height: plan.destination.height,
        crop: outer.crop,
        maskId: null,
        contentRect: null,
        shape: plan.shape,
      })
      const crop = placement.crop!
      expect([placement.x, placement.y, placement.width, placement.height, crop.x, crop.y, crop.width, crop.height].every(Number.isInteger)).toBe(true)
    })
    expect(expression.noteRegion).toEqual(renderedPlan.note!.region)
    expect(expression.noteType).toEqual({
      family: renderedPlan.note!.family,
      size: renderedPlan.note!.size,
      weight: renderedPlan.note!.weight,
      italic: renderedPlan.note!.italic,
      line: renderedPlan.note!.line,
    })
    expect(expression.noteType!.size).not.toBe(style.note.size) // plan-state 16/27, never nominal 22
    expect(expression.provenance).toEqual(
      renderedPlan.provenance.map((mark) => ({
        kind: mark.kind,
        text: mark.text,
        x: mark.region.x,
        y: mark.region.y,
        width: mark.region.width,
        height: mark.region.height,
        family: mark.family,
        size: mark.size,
        weight: mark.weight,
        italic: mark.italic,
        line: mark.line,
        orientation: mark.orientation,
        align: mark.align,
      })),
    )
    expect(expression.provenance!.map((mark) => mark.text)).toEqual(['Remanence', `From ${SENDER}`])

    // bytes = the existing encoder; the source snapshot is unchanged.
    expect(bytesToHex(result.bytes)).toBe(bytesToHex(encodeExpression(expression)))
    expect(JSON.stringify(frozen)).toBe(before)
    expect(Object.isFrozen(frozen)).toBe(true)
    expect(Object.isFrozen(frozen.slots[0])).toBe(true)
  })

  it('round-trips through decode + production prepare, reattaching the manifest-carried note region', async () => {
    const note = 'Probe note text.'
    const { frozen } = makeFrozen(note)
    const result = await buildFrozenExpression(frozen, 17n)
    expect(result.ok).toBe(true)
    if (!result.ok) return
    // GENEX04 expression bytes deliberately do NOT carry noteRegion
    // (`codec.ts` writeV4); the projection/manifest carries it
    // (`writeProjectionPrefix`), so the receiver reattaches the frozen region
    // and must reproduce the adapter expression exactly.
    const decoded = decodeExpression(result.bytes)
    expect(decoded.noteRegion).toBeNull()
    const receiver = { ...decoded, noteRegion: result.expression.noteRegion } as ResolvedExpression
    expect(receiver).toEqual(result.expression)
    const prepared = await prepare(receiver, receiver.input.note, null, sources, measurer)
    expect(prepared.kind).toBe('ready')
    if (prepared.kind === 'ready') {
      expect(prepared.scene.photos.map((photo) => photo.contentId)).toEqual(['p0', 'p1', 'p2'])
      expect(prepared.scene.note?.region).toEqual(result.expression.noteRegion)
    }
  })

  it('uses the bundled style note type only for an absent note (null region/text)', async () => {
    const { frozen } = makeFrozen(null)
    const result = await buildFrozenExpression(frozen, 3n)
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.expression.noteRegion).toBeNull()
    expect(result.expression.input.note).toBeNull()
    expect(result.expression.noteType).toEqual({ family: 'StudySans', size: 22, weight: 400, italic: false, line: 25 })
    const decoded = decodeExpression(result.bytes)
    expect(decoded.input.note).toBeNull()
    expect(decoded.noteType).toEqual(result.expression.noteType)
    const prepared = await prepare({ ...decoded, noteRegion: null }, null, null, sources, measurer)
    expect(prepared.kind).toBe('ready')
    if (prepared.kind === 'ready') expect(prepared.scene.note).toBeNull()
  })

  it('is deterministic across 10 runs', async () => {
    const { frozen } = makeFrozen('Probe note text.')
    const first = await buildFrozenExpression(frozen, 17n)
    expect(first.ok).toBe(true)
    if (!first.ok) return
    const hex = bytesToHex(first.bytes)
    for (let index = 0; index < 10; index++) {
      const next = await buildFrozenExpression(frozen, 17n)
      expect(next.ok).toBe(true)
      if (next.ok) expect(bytesToHex(next.bytes)).toBe(hex)
    }
  })

  it('refuses an invalid explicit epoch instead of defaulting or wrapping', async () => {
    const { frozen } = makeFrozen('Probe note text.')
    expect(await refusalReason(frozen, -1n)).toBe('epoch-invalid')
    expect(await refusalReason(frozen, 2n ** 63n)).toBe('epoch-invalid')
    expect(await refusalReason(frozen, (17 as unknown) as bigint)).toBe('epoch-invalid')
    expect(await refusalReason(frozen, 0n)).toBe('ok')
    expect(await refusalReason(frozen, (2n ** 63n) - 1n)).toBe('ok')
  })

  it('refuses wrong style/version, out-of-scope renders, and incoherent slots', async () => {
    const { frozen } = makeFrozen('Probe note text.')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.styleId = 'no-such-style' }), 17n)).toBe('style-unknown')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.styleVersion = 'style-study-9' }), 17n)).toBe('style-mismatch')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.decor = [{ kind: 'carrier', region: { x: 1, y: 1, width: 10, height: 10 }, background: 0, border: null, borderWidth: 0 }] }), 17n)).toBe('unsupported')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.provenance = [] }), 17n)).toBe('unsupported')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.branchId = 'editorial-other' }), 17n)).toBe('render-mismatch')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.slots.pop() }), 17n)).toBe('slot-mismatch')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.slots[1].contentId = draft.slots[0].contentId }), 17n)).toBe('slot-mismatch')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.slots[0].contentHash = 'short' }), 17n)).toBe('content-mismatch')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.slots[1].contentHash = draft.slots[0].contentHash }), 17n)).toBe('content-mismatch')
  })

  it('refuses fractional wire geometry with no send-time flooring', async () => {
    const { frozen } = makeFrozen('Probe note text.')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.slots[0].destination.width += 0.5 }), 17n)).toBe('fraction')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.slots[1].crop.x += 0.25 }), 17n)).toBe('fraction')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.note.region.x += 0.1 }), 17n)).toBe('fraction')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.provenance[0].region.y += 0.5 }), 17n)).toBe('fraction')
  })

  it('refuses a source window that disagrees with the frozen render and a note mismatch', async () => {
    const { frozen } = makeFrozen('Probe note text.')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.slots[0].source.x += 1 }), 17n)).toBe('source-mismatch')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.note = null }), 17n)).toBe('note-mismatch')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.noteRegion.x += 1 }), 17n)).toBe('note-mismatch')
    const absent = makeFrozen(null).frozen
    expect(await refusalReason(draftOf(absent, (draft) => { draft.renderedPlan.note = makeFrozen('Probe note text.').renderedPlan.note }), 17n)).toBe('note-mismatch')
  })

  it('propagates a contract refusal instead of relabeling or inventing data', async () => {
    const { frozen } = makeFrozen('Probe note text.')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.renderedPlan.provenance[0].text = 'NotRemanence' }), 17n)).toBe('contract')
    expect(await refusalReason(draftOf(frozen, (draft) => { draft.senderHandle = 'someone-else' }), 17n)).toBe('contract')
  })
})
