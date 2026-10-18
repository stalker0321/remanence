import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
import type { FrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { freezeSelection, freezeValid, type FreezeCurrent } from '../src/create/creatorFreeze'

function hexHash(index: number): string {
  return `${index.toString(16).padStart(2, '0')}`.repeat(32)
}

function slots() {
  return [0, 1, 2].map((i) => ({
    contentId: `p${i}`,
    ordinal: i,
    contentHash: hexHash(i + 1),
    widthPx: 1000,
    heightPx: 1000,
    crop: { x: 0, y: 0, width: 10000 - i, height: 10000 },
  }))
}

/** Minimal coherent rendered plan for the freeze inputs (identity matched). */
function plan(branchId: string, slotList: ReadonlyArray<{ contentId: string }>): FrozenRenderedPlan {
  return Object.freeze({
    canvasVersion: 1,
    grammarId: 'g5' as const,
    branchId,
    canvas: Object.freeze({ width: 360, height: 640, paper: 0, ink: 0, sound: 0, soundInk: 0 }),
    style: Object.freeze({ styleId: 'press', styleVersion: 'style-study-1' }),
    fontVersion: 1,
    paletteVersion: 1,
    slots: Object.freeze(
      slotList.map((slot, index) =>
        Object.freeze({
          contentId: slot.contentId,
          ordinal: index,
          destination: Object.freeze({ x: 0, y: 0, width: 100, height: 100 }),
          source: Object.freeze({ x: 0, y: 0, width: 50, height: 50 }),
          shape: 'rect' as const,
        }),
      ),
    ),
    note: null,
    provenance: Object.freeze([]),
    decor: Object.freeze([]),
  })
}

const RENDERED_KEY = 'render-key-1'

function current(): FreezeCurrent {
  return {
    branchId: 'editorial-1+2-equal-1',
    styleId: 'press',
    styleVersion: 'style-study-1',
    slots: slots(),
    note: null,
    ownerId: 'owner-1',
    sessionGeneration: 7,
    recipient: { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' },
    senderHandle: 'sergio',
    musicSelected: false,
    renderedKey: RENDERED_KEY,
  }
}

describe('creator freeze', () => {
  it('produces an immutable snapshot with the contracted fields and rendered plan', () => {
    const result = freezeSelection({
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      senderHandle: 'sergio',
      slots: slots(),
      note: null,
      noteRegion: null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'owner-1',
      sessionGeneration: 7,
      recipient: { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' },
      renderedKey: RENDERED_KEY,
      renderedPlan: plan('editorial-1+2-equal-1', slots()),
      createdAtMs: 123,
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const frozen = result.frozen
    expect(frozen.grammarId).toBe('g5')
    expect(frozen.branchId).toBe('editorial-1+2-equal-1')
    expect(frozen.slots.length).toBe(3)
    expect(frozen.renderedKey).toBe(RENDERED_KEY)
    expect(frozen.renderedPlan.slots.map((slot) => slot.contentId)).toEqual(['p0', 'p1', 'p2'])
    expect(frozen.recipient).toEqual({ userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' })
    expect(Object.isFrozen(frozen)).toBe(true)
    expect(Object.isFrozen(frozen.slots)).toBe(true)
    expect(Object.isFrozen(frozen.slots[0])).toBe(true)
    expect(freezeValid(frozen, current())).toEqual({ ok: true })
  })

  it('refuses incoherent or missing rendered render data', () => {
    const base = {
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      senderHandle: 'sergio',
      slots: slots(),
      note: null as string | null,
      noteRegion: null as { x: number; y: number; width: number; height: number } | null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'owner-1',
      sessionGeneration: 7,
      recipient: { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' },
    }
    // Blank key.
    expect(() => freezeSelection({ ...base, renderedKey: '  ', renderedPlan: plan(base.branchId, base.slots) })).toThrowError(CollageError)
    // Wrong branch.
    expect(() => freezeSelection({ ...base, renderedKey: RENDERED_KEY, renderedPlan: plan('other', base.slots) })).toThrowError(CollageError)
    // Slot count/identity mismatch.
    expect(() => freezeSelection({ ...base, renderedKey: RENDERED_KEY, renderedPlan: plan(base.branchId, base.slots.slice(0, 2)) })).toThrowError(CollageError)
  })

  it('refuses music, missing recipient, and note inconsistencies with typed reasons', () => {
    const base = {
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      senderHandle: 'sergio',
      slots: slots(),
      note: null as string | null,
      noteRegion: null as { x: number; y: number; width: number; height: number } | null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'o',
      sessionGeneration: 1,
      recipient: { userId: 'u', keyBundleId: 'b', directoryVersion: 'd' } as { userId: string; keyBundleId: string; directoryVersion: string } | null,
      renderedKey: RENDERED_KEY,
      renderedPlan: plan('editorial-1+2-equal-1', slots()),
    }
    expect(freezeSelection({ ...base, musicSelected: true })).toEqual({ ok: false, reason: 'music-not-supported' })
    expect(freezeSelection({ ...base, recipient: null })).toEqual({ ok: false, reason: 'no-recipient' })
    expect(() => freezeSelection({ ...base, senderHandle: '  ' })).toThrowError(CollageError)
    expect(
      freezeSelection({ ...base, note: 'hi', noteRegion: null, noteFits: true }),
    ).toEqual({ ok: false, reason: 'note-no-region' })
    expect(
      freezeSelection({ ...base, note: null, noteRegion: { x: 0, y: 0, width: 10, height: 10 }, noteFits: true }),
    ).toEqual({ ok: false, reason: 'note-missing' })
    expect(
      freezeSelection({ ...base, note: 'hi', noteRegion: { x: 0, y: 0, width: 10, height: 10 }, noteFits: false }),
    ).toEqual({ ok: false, reason: 'note-overflow' })
  })

  it('rejects malformed slot lists instead of freezing them', () => {
    const input = {
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      senderHandle: 'sergio',
      slots: slots(),
      note: null as string | null,
      noteRegion: null as { x: number; y: number; width: number; height: number } | null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'o',
      sessionGeneration: 1,
      recipient: { userId: 'u', keyBundleId: 'b', directoryVersion: 'd' },
      renderedKey: RENDERED_KEY,
      renderedPlan: plan('editorial-1+2-equal-1', slots()),
    }
    expect(() => freezeSelection({ ...input, slots: slots().slice(0, 2) })).toThrowError(CollageError)
    expect(() =>
      freezeSelection({ ...input, slots: slots().map((slot, i) => (i === 1 ? { ...slot, ordinal: 5 } : slot)) }),
    ).toThrowError(CollageError)
    expect(() => freezeSelection({ ...input, branchId: '' })).toThrowError(CollageError)
  })

  it('invalidates on every listed input and context change', () => {
    const settledResult = freezeSelection({
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      senderHandle: 'sergio',
      slots: slots(),
      note: 'Probe note text.',
      noteRegion: { x: 16, y: 573, width: 328, height: 30 },
      noteFits: true,
      musicSelected: false,
      ownerId: 'owner-1',
      sessionGeneration: 7,
      recipient: { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' },
      renderedKey: RENDERED_KEY,
      renderedPlan: plan('editorial-1+2-equal-1', slots()),
      createdAtMs: 1,
    })
    if (!settledResult.ok) throw new Error(`expected freeze, got ${settledResult.reason}`)
    const frozen = settledResult.frozen
    const mutate = (change: (next: FreezeCurrent) => void, reason: string) => {
      const next = current()
      next.note = 'Probe note text.'
      change(next)
      const invalidated = freezeValid(frozen, next)
      expect(invalidated, reason).toEqual({ ok: false, invalidated: reason })
    }
    (
      [
        [(next) => next.slots.pop(), 'photo set changed'],
        [(next) => (next.slots[0] = { ...next.slots[0], contentId: 'px' }), 'photo order changed'],
        [(next) => (next.slots[1] = { ...next.slots[1], contentHash: hexHash(9) }), 'photo content changed'],
        [(next) => (next.slots[2] = { ...next.slots[2], widthPx: 999 }), 'photo content changed'],
        [(next) => (next.slots[0] = { ...next.slots[0], crop: { x: 1, y: 0, width: 9999, height: 10000 } }), 'crop changed'],
        [(next) => (next.note = 'changed'), 'note changed'],
        [(next) => (next.branchId = 'editorial-2+1-equal-1'), 'branch changed'],
        [(next) => (next.styleId = 'poster'), 'style changed'],
        [(next) => (next.renderedKey = 'render-key-2'), 'render changed'],
        [(next) => (next.ownerId = 'owner-2'), 'owner changed'],
        [(next) => (next.sessionGeneration = 8), 'session changed'],
        [(next) => (next.recipient = null), 'recipient cleared'],
        [(next) => (next.recipient = { userId: 'u-2', keyBundleId: 'b-1', directoryVersion: 'd-1' }), 'recipient changed'],
        [(next) => (next.senderHandle = 'ada'), 'sender changed'],
        [(next) => (next.senderHandle = null), 'sender changed'],
        [(next) => (next.musicSelected = true), 'music selected'],
      ] as Array<[(next: FreezeCurrent) => void, string]>
    ).forEach(([change, reason]) => mutate(change, reason))
  })

  it('holds no photo bytes', () => {
    const result = freezeSelection({
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      senderHandle: 'sergio',
      slots: slots(),
      note: null,
      noteRegion: null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'o',
      sessionGeneration: 1,
      recipient: { userId: 'u', keyBundleId: 'b', directoryVersion: 'd' },
      renderedKey: RENDERED_KEY,
      renderedPlan: plan('editorial-1+2-equal-1', slots()),
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(JSON.stringify(result.frozen)).not.toContain('Uint8Array')
    expect(JSON.stringify(result.frozen)).not.toContain('base64')
    expect(JSON.stringify(result.frozen)).not.toContain('ImageBitmap')
  })
})
