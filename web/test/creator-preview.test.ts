import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
import { creatorScene, previewCandidates, previewSlot } from '../src/collage/creatorPreview'
import { grammar5Plans } from '../src/collage/planGrammar5'
import reference from './g5-plans-reference.txt?raw'
import { CROP_ASPECT_TOLERANCE } from '../src/collage/crop'

interface CaseSpec {
  id: string
  photos: number
  music: string
  note: boolean
  aspects: number[]
}

function loadCases(): CaseSpec[] {
  const cases: CaseSpec[] = []
  for (const raw of reference.split('\n')) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    if (line === '' || line.startsWith('#')) continue
    const fields = line.split('\t')
    if (fields[0] !== 'CASE') continue
    cases.push({
      id: fields[1],
      photos: Number(fields[2]),
      music: fields[3],
      note: fields[4] === '1',
      aspects: fields[6].split(',').map(Number),
    })
  }
  return cases
}

function hexHash(index: number): string {
  return `${index.toString(16).padStart(2, '0')}`.repeat(32)
}

function photosFor(aspects: number[]): { contentId: string; width: number; height: number; contentHash: string }[] {
  return aspects.map((aspect, i) => ({
    contentId: `p${i}`,
    width: Math.round(aspect * 1000),
    height: 1000,
    contentHash: hexHash(i + 1),
  }))
}

describe('creator preview adapter', () => {
  it('keeps every golden plan aspect-exact after rounding across source mixes', () => {
    const cases = loadCases().filter((spec) => spec.music === 'none')
    expect(cases.length).toBe(10)
    let plans = 0
    let slots = 0
    for (const spec of cases) {
      const input = {
        id: spec.id,
        sender: 'T236',
        photos: photosFor(spec.aspects),
        note: spec.note ? 'Probe note text.' : null,
      }
      const views = previewCandidates(input)
      expect(views.length).toBeGreaterThan(0)
      const branches = new Set(views.map((view) => view.branch))
      expect(branches.size).toBe(views.length)
      for (const view of views) {
        expect(view.slots.map((slot) => slot.photoId)).toEqual(input.photos.map((photo) => photo.contentId))
        for (const slot of view.slots) {
          const sourceAspect = slot.sourcePx.width / slot.sourcePx.height
          const destinationAspect = slot.destination.width / slot.destination.height
          expect(Math.abs(sourceAspect - destinationAspect) / destinationAspect).toBeLessThanOrEqual(CROP_ASPECT_TOLERANCE)
          expect(slot.sourcePx.width).toBeGreaterThanOrEqual(1)
          expect(slot.sourcePx.height).toBeGreaterThanOrEqual(1)
          expect(Number.isInteger(slot.crop.x)).toBe(true)
          expect(Number.isInteger(slot.crop.y)).toBe(true)
          expect(Number.isInteger(slot.crop.width)).toBe(true)
          expect(Number.isInteger(slot.crop.height)).toBe(true)
          expect(slot.crop.x + slot.crop.width).toBeLessThanOrEqual(10000)
          expect(slot.crop.y + slot.crop.height).toBeLessThanOrEqual(10000)
          slots += 1
        }
        plans += 1
      }
    }
    expect(plans).toBe(132)
    expect(slots).toBeGreaterThan(300)
  })

  it('is deterministic plan-for-plan', () => {
    const input = {
      id: 'det',
      sender: 'T236',
      photos: photosFor([1.5, 1, 0.6667, 1.2, 0.8]),
      note: 'Probe note text.',
    }
    const first = JSON.stringify(previewCandidates(input))
    for (let i = 0; i < 10; i++) {
      expect(JSON.stringify(previewCandidates(input))).toBe(first)
    }
  })

  it('refuses duplicates, missing photos, and invalid dimensions instead of stretching', () => {
    const base = {
      id: 'x',
      sender: 's',
      photos: photosFor([1, 1, 1]),
      note: null as string | null,
    }
    expect(() => previewCandidates({ ...base, photos: [base.photos[0], base.photos[0], base.photos[2]] })).toThrowError(CollageError)
    expect(() => previewCandidates({ ...base, photos: base.photos.slice(0, 2) })).toThrowError(CollageError)
    expect(() =>
      previewCandidates({ ...base, photos: base.photos.map((photo, i) => (i === 0 ? { ...photo, width: 0 } : photo)) }),
    ).toThrowError(CollageError)
    const slot = { photoId: 'p0', rect: { x: 10, y: 101, width: 340, height: 248.5 }, shape: 'rect' }
    expect(() => previewSlot(slot, { contentId: 'p0', width: 0, height: 100, contentHash: hexHash(9) }, null)).toThrowError(
      CollageError,
    )
    expect(() =>
      previewSlot({ ...slot, rect: { ...slot.rect, width: 0 } }, { contentId: 'p0', width: 100, height: 100, contentHash: hexHash(9) }, null),
    ).toThrowError(CollageError)
  })

  it('refuses degenerate windows instead of stretching', () => {
    const tile = { photoId: 'p0', rect: { x: 10, y: 101, width: 340, height: 248.5 }, shape: 'rect' }
    for (const [width, height] of [
      [1, 100000],
      [100000, 1],
      [1, 1],
    ]) {
      expect(() => previewSlot(tile, { contentId: 'p0', width, height, contentHash: hexHash(7) }, null)).toThrowError(
        CollageError,
      )
    }
  })

  it('builds a paintable scene from a golden plan without a sealed expression', () => {
    const input = { id: 'x', sender: 'ada', photos: photosFor([1, 1, 1]), note: 'Probe note text.' as string | null }
    const [view] = previewCandidates(input)
    const scene = creatorScene({
      view,
      styleId: 'press',
      senderHandle: 'ada',
      noteText: 'Probe note text.',
      photos: new Map(input.photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
    })
    expect(scene.grammarId).toBe('g5')
    expect(scene.photos.length).toBe(3)
    expect(scene.photos.map((photo) => photo.contentId)).toEqual(['p0', 'p1', 'p2'])
    expect(scene.note?.text).toBe('Probe note text.')
    expect(scene.music).toBeNull()
    expect(scene.artwork).toBeNull()
    expect(scene.provenance.map((mark) => mark.text)).toEqual(['Remanence', 'From ada'])
    expect(scene.decor).toEqual([])
  })

  it('carries no music geometry and keeps plan order authored', () => {
    const expected = grammar5Plans({
      id: 'x',
      sender: 's',
      photos: photosFor([1, 1, 1]).map((photo) => ({ id: photo.contentId, width: photo.width, height: photo.height })),
      note: null,
    }).map((plan) => plan.branch)
    const views = previewCandidates({ id: 'x', sender: 's', photos: photosFor([1, 1, 1]), note: null })
    expect(views.map((view) => view.branch)).toEqual(expected)
    for (const view of views) {
      expect('music' in view).toBe(false)
      for (const slot of view.slots) expect('music' in slot).toBe(false)
    }
  })
})
