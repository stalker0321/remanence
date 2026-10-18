import { CollageError } from '../collage/errors'

/**
 * Immutable LOCAL creator freeze — a snapshot of the creator's explicit
 * choice, not a sealed expression, grant, publication, or server action.
 *
 * A freeze records: grammar/branch/style identity, per-slot content
 * id/hash/dimensions/crop, the note text, owner/session generation, and
 * the confirmed recipient tuple. It holds NO photo bytes and creates NO
 * key store. Any listed input or context change invalidates it; the
 * holder must drop the frozen value instead of reusing it.
 */

export interface CreatorFreezeRecipient {
  userId: string
  keyBundleId: string
  directoryVersion: string
}

export interface CreatorSlotFreeze {
  contentId: string
  ordinal: number
  contentHash: string
  widthPx: number
  heightPx: number
  crop: { x: number; y: number; width: number; height: number }
}

export interface CreatorFreeze {
  readonly grammarId: 'g5'
  readonly branchId: string
  readonly styleId: string
  readonly styleVersion: string
  readonly slots: readonly CreatorSlotFreeze[]
  readonly note: string | null
  readonly noteRegion: { x: number; y: number; width: number; height: number } | null
  readonly ownerId: string
  readonly sessionGeneration: number
  readonly recipient: CreatorFreezeRecipient
  readonly createdAtMs: number
}

export type FreezeRefusal =
  | 'music-not-supported'
  | 'no-recipient'
  | 'note-missing'
  | 'note-no-region'
  | 'note-overflow'

export type FreezeResult = { ok: true; frozen: CreatorFreeze } | { ok: false; reason: FreezeRefusal }

export interface FreezeInput {
  branchId: string
  styleId: string
  styleVersion: string
  slots: CreatorSlotFreeze[]
  note: string | null
  noteRegion: { x: number; y: number; width: number; height: number } | null
  noteFits: boolean
  musicSelected: boolean
  ownerId: string
  sessionGeneration: number
  recipient: CreatorFreezeRecipient | null
  createdAtMs?: number
}

function refuse(reason: FreezeRefusal): FreezeResult {
  return { ok: false, reason }
}

/**
 * Freeze the current explicit selection. Refuses (never silently
 * drops) when music is selected, when no confirmed recipient is
 * present, or when the note/measurement pair is inconsistent: a sealed
 * note region requires text, text requires a region, and an overflowing
 * measurement fails closed.
 */
export function freezeSelection(input: FreezeInput): FreezeResult {
  if (input.musicSelected) return refuse('music-not-supported')
  if (input.recipient === null) return refuse('no-recipient')
  if (input.note !== null && input.noteRegion === null) return refuse('note-no-region')
  if (input.note === null && input.noteRegion !== null) return refuse('note-missing')
  if (input.note !== null && !input.noteFits) return refuse('note-overflow')
  if (input.branchId.trim() === '' || input.styleId.trim() === '' || input.styleVersion.trim() === '') {
    throw new CollageError('REJECTED')
  }
  if (input.slots.length < 3 || input.slots.length > 5) {
    throw new CollageError('REJECTED')
  }
  const frozen: CreatorFreeze = {
    grammarId: 'g5',
    branchId: input.branchId,
    styleId: input.styleId,
    styleVersion: input.styleVersion,
    slots: Object.freeze(
      input.slots.map((slot, index) => {
        if (slot.ordinal !== index) throw new CollageError('REJECTED')
        return Object.freeze({
          contentId: slot.contentId,
          ordinal: slot.ordinal,
          contentHash: slot.contentHash,
          widthPx: slot.widthPx,
          heightPx: slot.heightPx,
          crop: Object.freeze({ ...slot.crop }),
        })
      }),
    ),
    note: input.note,
    noteRegion: input.noteRegion === null ? null : Object.freeze({ ...input.noteRegion }),
    ownerId: input.ownerId,
    sessionGeneration: input.sessionGeneration,
    recipient: Object.freeze({ ...input.recipient }),
    createdAtMs: input.createdAtMs ?? Date.now(),
  }
  return { ok: true, frozen: Object.freeze(frozen) }
}

export interface FreezeCurrent {
  branchId: string
  styleId: string
  styleVersion: string
  slots: CreatorSlotFreeze[]
  note: string | null
  ownerId: string
  sessionGeneration: number
  recipient: CreatorFreezeRecipient | null
}

function sameCrop(a: CreatorSlotFreeze['crop'], b: CreatorSlotFreeze['crop']): boolean {
  return a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height
}

/**
 * Explicit invalidation: any photo add/remove/reorder/content change,
 * note change, music/style/crop edit, recipient change, or owner/
 * session-generation change voids the frozen value. Returns the first
 * mismatch; the holder drops the snapshot on any mismatch.
 */
export function freezeValid(frozen: CreatorFreeze, current: FreezeCurrent): { ok: true } | { ok: false; invalidated: string } {
  const bad = (invalidated: string) => ({ ok: false as const, invalidated })
  if (current.branchId !== frozen.branchId) return bad('branch changed')
  if (current.styleId !== frozen.styleId || current.styleVersion !== frozen.styleVersion) return bad('style changed')
  if (current.note !== frozen.note) return bad('note changed')
  if (current.ownerId !== frozen.ownerId) return bad('owner changed')
  if (current.sessionGeneration !== frozen.sessionGeneration) return bad('session changed')
  if (current.recipient === null) return bad('recipient cleared')
  if (
    current.recipient.userId !== frozen.recipient.userId ||
    current.recipient.keyBundleId !== frozen.recipient.keyBundleId ||
    current.recipient.directoryVersion !== frozen.recipient.directoryVersion
  ) {
    return bad('recipient changed')
  }
  if (current.slots.length !== frozen.slots.length) return bad('photo set changed')
  for (let i = 0; i < current.slots.length; i++) {
    const next = current.slots[i]
    const prev = frozen.slots[i]
    if (next.contentId !== prev.contentId || next.ordinal !== prev.ordinal) return bad('photo order changed')
    if (next.contentHash !== prev.contentHash || next.widthPx !== prev.widthPx || next.heightPx !== prev.heightPx) {
      return bad('photo content changed')
    }
    if (!sameCrop(next.crop, prev.crop)) return bad('crop changed')
  }
  return { ok: true }
}
