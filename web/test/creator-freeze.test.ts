import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
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
  }
}

describe('creator freeze', () => {
  it('produces an immutable snapshot with the contracted fields', () => {
    const result = freezeSelection({
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      slots: slots(),
      note: null,
      noteRegion: null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'owner-1',
      sessionGeneration: 7,
      recipient: { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' },
      createdAtMs: 123,
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const frozen = result.frozen
    expect(frozen.grammarId).toBe('g5')
    expect(frozen.branchId).toBe('editorial-1+2-equal-1')
    expect(frozen.slots.length).toBe(3)
    expect(frozen.recipient).toEqual({ userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' })
    expect(Object.isFrozen(frozen)).toBe(true)
    expect(Object.isFrozen(frozen.slots)).toBe(true)
    expect(Object.isFrozen(frozen.slots[0])).toBe(true)
    expect(freezeValid(frozen, current())).toEqual({ ok: true })
  })

  it('refuses music, missing recipient, and note inconsistencies with typed reasons', () => {
    const base = {
      branchId: 'b',
      styleId: 'press',
      styleVersion: 'v',
      slots: slots(),
      note: null as string | null,
      noteRegion: null as { x: number; y: number; width: number; height: number } | null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'o',
      sessionGeneration: 1,
      recipient: { userId: 'u', keyBundleId: 'b', directoryVersion: 'd' },
    }
    expect(freezeSelection({ ...base, musicSelected: true })).toEqual({ ok: false, reason: 'music-not-supported' })
    expect(freezeSelection({ ...base, recipient: null })).toEqual({ ok: false, reason: 'no-recipient' })
    expect(
      freezeSelection({ ...base, note: 'hi', noteRegion: null, noteFits: true }),
    ).toEqual({ ok: false, reason: 'note-no-region' })
    expect(
      freezeSelection({
        ...base,
        note: null,
        noteRegion: { x: 0, y: 0, width: 10, height: 10 },
        noteFits: true,
      }),
    ).toEqual({ ok: false, reason: 'note-missing' })
    expect(
      freezeSelection({
        ...base,
        note: 'hi',
        noteRegion: { x: 0, y: 0, width: 10, height: 10 },
        noteFits: false,
      }),
    ).toEqual({ ok: false, reason: 'note-overflow' })
  })

  it('rejects malformed slot lists instead of freezing them', () => {
    const base = {
      branchId: 'b',
      styleId: 'press',
      styleVersion: 'v',
      slots: slots(),
      note: null as string | null,
      noteRegion: null as { x: number; y: number; width: number; height: number } | null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'o',
      sessionGeneration: 1,
      recipient: { userId: 'u', keyBundleId: 'b', directoryVersion: 'd' },
    }
    expect(() => freezeSelection({ ...base, slots: slots().slice(0, 2) })).toThrowError(CollageError)
    expect(() =>
      freezeSelection({ ...base, slots: slots().map((slot, i) => (i === 1 ? { ...slot, ordinal: 5 } : slot)) }),
    ).toThrowError(CollageError)
    expect(() => freezeSelection({ ...base, branchId: '' })).toThrowError(CollageError)
  })

  it('invalidates on every listed input and context change', () => {
    const settledResult = freezeSelection({
      branchId: 'editorial-1+2-equal-1',
      styleId: 'press',
      styleVersion: 'style-study-1',
      slots: slots(),
      note: 'Probe note text.',
      noteRegion: { x: 16, y: 573, width: 328, height: 30 },
      noteFits: true,
      musicSelected: false,
      ownerId: 'owner-1',
      sessionGeneration: 7,
      recipient: { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' },
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
    };
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
        [(next) => (next.ownerId = 'owner-2'), 'owner changed'],
        [(next) => (next.sessionGeneration = 8), 'session changed'],
        [(next) => (next.recipient = null), 'recipient cleared'],
        [(next) => (next.recipient = { userId: 'u-2', keyBundleId: 'b-1', directoryVersion: 'd-1' }), 'recipient changed'],
      ] as Array<[(next: FreezeCurrent) => void, string]>
    ).forEach(([change, reason]) => mutate(change, reason))
  })

  it('holds no photo bytes', () => {
    const result = freezeSelection({
      branchId: 'b',
      styleId: 'press',
      styleVersion: 'v',
      slots: slots(),
      note: null,
      noteRegion: null,
      noteFits: true,
      musicSelected: false,
      ownerId: 'o',
      sessionGeneration: 1,
      recipient: { userId: 'u', keyBundleId: 'b', directoryVersion: 'd' },
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(JSON.stringify(result.frozen)).not.toContain('Uint8Array')
    expect(JSON.stringify(result.frozen)).not.toContain('base64')
  })
})
