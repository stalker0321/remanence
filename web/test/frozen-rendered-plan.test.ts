import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
import { layoutCreatorNote } from '../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../src/collage/creatorPreview'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { FONT_VERSION } from '../src/collage/fonts'
import { PALETTE_VERSION, styleById } from '../src/collage/styles'
import type { TextMeasurer } from '../src/collage/prepare'

/** Deterministic 10px/glyph measurer (softWrap:false primitive + planner). */
const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

function photos(count = 3) {
  return Array.from({ length: count }, (_, index) => ({
    contentId: `p${index}`,
    width: 1000,
    height: 1000,
    contentHash: String.fromCharCode(97 + index).repeat(64),
  }))
}

const SENDER = 'sergio'
const RECIPIENT = 'ada'
const STYLE_ID = 'press'

function capturedPlan(note: string | null) {
  const style = styleById(STYLE_ID)
  if (!style) throw new Error('missing press style')
  const views = previewCandidates(
    { id: 'creator', sender: SENDER, photos: photos(), note },
    undefined,
    plannerFromTextMeasurer(measurer, 640, 640, style.note),
  )
  const view = views[0]
  if (!view) throw new Error('no view')
  const scene = creatorScene({
    view,
    styleId: STYLE_ID,
    senderHandle: SENDER,
    noteText: note,
    photos: new Map(photos().map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
  })
  const noteLayout = scene.note
    ? layoutCreatorNote(scene.note.text, {
        font: scene.note.family,
        weight: scene.note.weight,
        italic: scene.note.italic,
        size: scene.note.size,
        line: scene.note.line,
      }, scene.note.region, (line) =>
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
        }).width)
    : null
  const plan = captureFrozenRenderedPlan({
    scene,
    noteLayout,
    branchId: view.branch,
    styleId: STYLE_ID,
    styleVersion: style.version,
  })
  return { plan, view, scene, style }
}

describe('frozen rendered plan', () => {
  it('captures the exact painted geometry, copied from the view/scene', () => {
    const { plan, view, scene, style } = capturedPlan('Probe note text.')
    expect(plan.grammarId).toBe('g5')
    expect(plan.canvasVersion).toBe(1)
    expect(plan.canvas).toEqual({ width: 360, height: 640, paper: scene.paper, ink: scene.ink, sound: scene.sound, soundInk: scene.soundInk })
    expect(plan.style).toEqual({ styleId: STYLE_ID, styleVersion: style.version })
    expect(plan.fontVersion).toBe(FONT_VERSION)
    expect(plan.paletteVersion).toBe(PALETTE_VERSION)
    expect(plan.slots.map((slot) => slot.contentId)).toEqual(view.slots.map((slot) => slot.photoId))
    plan.slots.forEach((slot, index) => {
      const source = view.slots[index]!
      expect(slot.ordinal).toBe(index)
      expect(slot.destination).toEqual(source.destination)
      expect(slot.source).toEqual(source.sourcePx)
      expect(slot.shape).toBe(source.shape)
      // T252: every frozen rect is the integer-mounted canvas geometry the
      // wire `CanonicalWriter.int` accepts, not a fractional plan rect.
      expect([slot.destination.x, slot.destination.y, slot.destination.width, slot.destination.height].every(Number.isInteger)).toBe(true)
      expect([slot.source.x, slot.source.y, slot.source.width, slot.source.height].every(Number.isInteger)).toBe(true)
    })
    if (plan.note) {
      expect([plan.note.region.x, plan.note.region.y, plan.note.region.width, plan.note.region.height].every(Number.isInteger)).toBe(true)
      plan.note.rows.forEach((row) => expect(Number.isInteger(row.x)).toBe(true))
    }
    plan.provenance.forEach((mark) => {
      expect([mark.region.x, mark.region.y, mark.region.width, mark.region.height].every(Number.isInteger)).toBe(true)
    })
    expect(plan.decor).toEqual([])
  })

  it('uses the actual plan-state note face/size/line, never the nominal style size', () => {
    const { plan, style } = capturedPlan('Probe note text.')
    expect(plan.note).not.toBeNull()
    expect(plan.note?.family).toBe(style.note.font)
    expect(plan.note?.size).not.toBe(style.note.size) // press nominal is 22
    expect([16, 27]).toContain(plan.note?.size)
    expect([20, 30]).toContain(plan.note?.line)
    expect(plan.note?.size === 16 ? plan.note?.line === 20 : plan.note?.line === 30).toBe(true)
    // rows are the exact wrapped layout painted
    expect(plan.note?.rows.map((row) => row.text).join(' ').split(/\s+/)).toEqual(
      plan.note?.text.split(/\s+/),
    )
    expect(plan.note?.rows.every((row) => row.x === plan.note?.region.x)).toBe(true)
  })

  it('binds provenance text to the sender, not the recipient, with tracking/color', () => {
    const { plan, scene } = capturedPlan(null)
    expect(plan.note).toBeNull()
    const sender = plan.provenance.find((mark) => mark.kind === 'sender')
    const brand = plan.provenance.find((mark) => mark.kind === 'brand')
    expect(sender?.text).toBe(`From ${SENDER}`)
    expect(sender?.text).not.toContain(RECIPIENT)
    expect(brand?.text).toBe('Remanence')
    expect(plan.provenance.map((mark) => mark.region)).toEqual(scene.provenance.map((mark) => mark.region))
    expect(plan.provenance.map((mark) => mark.tracking)).toEqual(scene.provenance.map((mark) => mark.tracking))
    expect(plan.provenance.map((mark) => mark.color)).toEqual(scene.provenance.map((mark) => mark.color))
  })

  it('deep-copies and deep-freezes: source mutation and mutation attempts cannot change it', () => {
    const { plan, scene } = capturedPlan('Probe note text.')
    const before = JSON.stringify(plan)
    // Mutate the source scene after capture.
    if (scene.note) scene.note.region.x = 9999
    scene.photos[0]!.destination.x = 9999
    scene.photos[0]!.source.width = 1
    expect(JSON.stringify(plan)).toBe(before)
    expect(Object.isFrozen(plan)).toBe(true)
    expect(Object.isFrozen(plan.slots)).toBe(true)
    expect(Object.isFrozen(plan.slots[0])).toBe(true)
    expect(Object.isFrozen(plan.slots[0]!.destination)).toBe(true)
    expect(Object.isFrozen(plan.note?.rows)).toBe(true)
    expect(() => {
      ;(plan.slots[0]!.destination as { x: number }).x = 5
    }).toThrow()
    expect(JSON.stringify(plan)).toBe(before)
  })

  it('is deterministic for the same input (no hidden timestamp)', () => {
    const first = JSON.stringify(capturedPlan('Probe note text.').plan)
    for (let index = 0; index < 10; index++) {
      expect(JSON.stringify(capturedPlan('Probe note text.').plan)).toBe(first)
    }
  })

  it('refuses a note without its painted row layout instead of dropping it', () => {
    const style = styleById(STYLE_ID)!
    const views = previewCandidates(
      { id: 'creator', sender: SENDER, photos: photos(), note: 'Probe note text.' },
      undefined,
      plannerFromTextMeasurer(measurer, 640, 640, style.note),
    )
    const scene = creatorScene({
      view: views[0]!,
      styleId: STYLE_ID,
      senderHandle: SENDER,
      noteText: 'Probe note text.',
      photos: new Map(photos().map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
    })
    expect(() =>
      captureFrozenRenderedPlan({ scene, noteLayout: null, branchId: views[0]!.branch, styleId: STYLE_ID, styleVersion: style.version }),
    ).toThrowError(CollageError)
  })

  it('holds no image bytes / DOM capability', () => {
    const { plan } = capturedPlan('Probe note text.')
    const json = JSON.stringify(plan)
    expect(json).not.toContain('ImageBitmap')
    expect(json).not.toContain('Uint8Array')
    // only plain numbers/strings/booleans/null nested in the DTO
    const isPlain = (value: unknown): boolean => {
      if (value === null || typeof value === 'number' || typeof value === 'string' || typeof value === 'boolean') return true
      if (Array.isArray(value)) return value.every(isPlain)
      return typeof value === 'object' && Object.values(value as Record<string, unknown>).every(isPlain)
    }
    expect(isPlain(plan)).toBe(true)
  })
})
