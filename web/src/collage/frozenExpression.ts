import type { CreatorFreeze } from '../create/creatorFreeze'
import { CollageError } from './errors'
import { encodeExpression } from './codec'
import { FONT_VERSION } from './fonts'
import type { FrozenRenderedRect } from './frozenRenderedPlan'
import {
  EXPRESSION_CONTRACT_V4,
  type GeneratorInput,
  type PhotoRef,
  type Placement,
  type ProvenanceMark,
  type ResolvedExpression,
  type TextType,
  validateResolved,
} from './model'
import { sourceRect } from './prepare'
import { PALETTE_VERSION, styleById, styleDigest, type CollageStyle } from './styles'

/**
 * T255 — pure local adapter: ONE frozen creator snapshot -> the EXISTING V4
 * `ResolvedExpression` + `encodeExpression` bytes.
 *
 * Mirrors the accepted Android `AllFamilyV4Adapter.kt` mapping (canvasVersion
 * 1, grammarId `g5`, grammarVersion 1, `noteTreatment 'regular-region'`,
 * `diagnostics []`, the bundled style binding + `styleDigest`, plan-state note
 * face, `noteType` from the bundled style ONLY when no note was painted, g5
 * `decor` -> null, authored-order placements/provenance) but reads the geometry
 * ONLY from the already-painted deep-immutable `CreatorFreeze` — never from
 * `previewCandidates`/`grammar5Plans`/live props/a measurer. Nothing is
 * floored, rounded, or regenerated here: T252 already mounted the producer to
 * integers, so any fractional wire value is an honest typed refusal.
 *
 * The `epoch` is a REQUIRED explicit caller value; there is no default, no
 * derivation from `renderedKey`/`SessionStore`, and no new Create-epoch policy.
 * The output bytes are an UNSIGNED local expression — not a seal, grant, or
 * send authorization. The input snapshot is never mutated.
 */

export type FrozenExpressionRefusal =
  | 'epoch-invalid'
  | 'style-unknown'
  | 'style-mismatch'
  | 'unsupported'
  | 'slot-mismatch'
  | 'render-mismatch'
  | 'content-mismatch'
  | 'fraction'
  | 'source-mismatch'
  | 'note-mismatch'
  | 'contract'

export interface FrozenExpressionOk {
  ok: true
  expression: ResolvedExpression
  bytes: Uint8Array
}

export interface FrozenExpressionRefused {
  ok: false
  reason: FrozenExpressionRefusal
  detail?: string
}

export type FrozenExpressionResult = FrozenExpressionOk | FrozenExpressionRefused

const INT64_MAX = (2n ** 63n) - 1n

function refuse(reason: FrozenExpressionRefusal, detail?: string): FrozenExpressionRefused {
  return detail === undefined ? { ok: false, reason } : { ok: false, reason, detail }
}

function isInt(value: number): boolean {
  return Number.isInteger(value)
}

function intRect(rect: { x: number; y: number; width: number; height: number }): boolean {
  return isInt(rect.x) && isInt(rect.y) && isInt(rect.width) && isInt(rect.height)
}

function copyRect(rect: FrozenRenderedRect): { x: number; y: number; width: number; height: number } {
  return { x: rect.x, y: rect.y, width: rect.width, height: rect.height }
}

/**
 * The V4 note `TextType` for an ABSENT note: the Android adapter binds the
 * bundled style note face (`textType(style.note)`, `line = ceil(size * 1.12)`),
 * `noteRegion`/`input.note` stay null and nothing is painted from it.
 */
function absentNoteType(style: CollageStyle): TextType {
  return {
    family: style.note.font,
    size: style.note.size,
    weight: style.note.weight,
    italic: style.note.italic,
    line: Math.ceil(style.note.size * 1.12),
  }
}

export async function buildFrozenExpression(frozen: CreatorFreeze, epoch: bigint): Promise<FrozenExpressionResult> {
  // Explicit caller epoch. No default, no policy: reject wrong type, negative,
  // or anything outside signed int64 instead of letting the codec's
  // `setBigInt64` silently wrap.
  if (typeof epoch !== 'bigint') return refuse('epoch-invalid', 'epoch must be a bigint')
  if (epoch < 0n || epoch > INT64_MAX) return refuse('epoch-invalid', `epoch out of int64 range: ${epoch}`)

  if (frozen.grammarId !== 'g5' || frozen.renderedPlan.grammarId !== 'g5') {
    return refuse('unsupported', `grammar ${frozen.grammarId}`)
  }
  if (frozen.renderedPlan.branchId !== frozen.branchId) {
    return refuse('render-mismatch', `rendered plan branch ${frozen.renderedPlan.branchId} != ${frozen.branchId}`)
  }
  // g5 emits no decor; the model rejects an empty decor list, so anything but
  // empty is out of scope here.
  if (frozen.renderedPlan.decor.length !== 0) return refuse('unsupported', 'decor is out of scope')

  const style = styleById(frozen.styleId)
  if (!style) return refuse('style-unknown', frozen.styleId)
  if (style.version !== frozen.styleVersion) {
    return refuse('style-mismatch', `bundled ${style.version} != frozen ${frozen.styleVersion}`)
  }
  const digest = await styleDigest(frozen.styleId)
  if (!digest || digest.length !== 32) return refuse('style-unknown', `${frozen.styleId} has no bundled digest`)

  const planSlots = frozen.renderedPlan.slots
  if (frozen.slots.length < 3 || frozen.slots.length > 5 || planSlots.length !== frozen.slots.length) {
    return refuse('slot-mismatch', `${frozen.slots.length} slots vs ${planSlots.length} plan slots`)
  }

  const seenIds = new Set<string>()
  const seenHashes = new Set<string>()
  const photos: PhotoRef[] = []
  const placements: Placement[] = []
  for (let index = 0; index < frozen.slots.length; index++) {
    const slot = frozen.slots[index]!
    const planSlot = planSlots[index]!
    if (slot.ordinal !== index) return refuse('slot-mismatch', `slot ${index} ordinal ${slot.ordinal}`)
    if (slot.contentId !== planSlot.contentId) return refuse('slot-mismatch', `slot ${index} id ${slot.contentId} != ${planSlot.contentId}`)
    if (typeof slot.contentId !== 'string' || slot.contentId.trim() === '') return refuse('content-mismatch', `slot ${index} blank id`)
    if (!/^[0-9a-f]{64}$/.test(slot.contentHash)) return refuse('content-mismatch', `slot ${index} hash`)
    if (!isInt(slot.widthPx) || !isInt(slot.heightPx) || slot.widthPx <= 0 || slot.heightPx <= 0) {
      return refuse('content-mismatch', `slot ${index} dimensions`)
    }
    if (seenIds.has(slot.contentId)) return refuse('content-mismatch', `duplicate id ${slot.contentId}`)
    if (seenHashes.has(slot.contentHash)) return refuse('content-mismatch', `duplicate hash for ${slot.contentId}`)
    seenIds.add(slot.contentId)
    seenHashes.add(slot.contentHash)

    const destination = planSlot.destination
    if (!intRect(destination) || !intRect(slot.crop)) {
      return refuse('fraction', `slot ${index} has a non-integer destination/crop`)
    }
    // The frozen source pixel window must be exactly the existing sourceRect
    // math on the outer crop/dimensions — validation only, never recomputed.
    const expectedSource = sourceRect(slot.crop, slot.widthPx, slot.heightPx)
    if (
      expectedSource === null ||
      expectedSource.x !== planSlot.source.x ||
      expectedSource.y !== planSlot.source.y ||
      expectedSource.width !== planSlot.source.width ||
      expectedSource.height !== planSlot.source.height
    ) {
      return refuse('source-mismatch', `slot ${index} source window does not match the frozen render`)
    }

    photos.push({
      contentId: slot.contentId,
      ordinal: index,
      widthPx: slot.widthPx,
      heightPx: slot.heightPx,
      contentHash: slot.contentHash,
    })
    placements.push({
      contentId: slot.contentId,
      x: destination.x,
      y: destination.y,
      width: destination.width,
      height: destination.height,
      crop: { x: slot.crop.x, y: slot.crop.y, width: slot.crop.width, height: slot.crop.height },
      maskId: null,
      contentRect: null,
      shape: planSlot.shape,
    })
  }

  const frozenNote = frozen.renderedPlan.note
  let noteRegion: { x: number; y: number; width: number; height: number } | null = null
  let noteType: TextType
  if (frozen.note !== null) {
    if (frozen.noteRegion === null || frozenNote === null) return refuse('note-mismatch', 'note text without a frozen region/face')
    const planRegion = copyRect(frozenNote.region)
    if (!intRect(planRegion) || !intRect(frozen.noteRegion)) return refuse('fraction', 'note region is not integral')
    if (
      planRegion.x !== frozen.noteRegion.x ||
      planRegion.y !== frozen.noteRegion.y ||
      planRegion.width !== frozen.noteRegion.width ||
      planRegion.height !== frozen.noteRegion.height
    ) {
      return refuse('note-mismatch', 'note region differs from the frozen render')
    }
    noteRegion = planRegion
    // EXACT frozen painted face/density (16/20 or 27/30), never the nominal
    // style size.
    noteType = {
      family: frozenNote.family,
      size: frozenNote.size,
      weight: frozenNote.weight,
      italic: frozenNote.italic,
      line: frozenNote.line,
    }
  } else {
    if (frozen.noteRegion !== null || frozenNote !== null) return refuse('note-mismatch', 'frozen region/face without note text')
    noteType = absentNoteType(style)
  }

  if (frozen.renderedPlan.provenance.length === 0) return refuse('unsupported', 'provenance is empty')
  if (frozen.renderedPlan.provenance.some((mark) => !intRect(mark.region))) {
    return refuse('fraction', 'provenance region is not integral')
  }
  const provenance: ProvenanceMark[] = frozen.renderedPlan.provenance.map((mark) => ({
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
  }))
  const senderMark = provenance.find((mark) => mark.kind === 'sender')
  if (senderMark && senderMark.text !== `From ${frozen.senderHandle}`) {
    return refuse('contract', 'sender provenance does not bind the frozen sender handle')
  }

  const input: GeneratorInput = {
    ownerId: frozen.ownerId,
    epoch,
    photos,
    note: frozen.note,
    music: null,
  }

  const expression: ResolvedExpression = {
    canvasVersion: 1,
    grammarId: 'g5',
    grammarVersion: 1,
    branchId: frozen.branchId,
    input,
    placements,
    noteTreatment: 'regular-region',
    diagnostics: [],
    fontVersion: FONT_VERSION,
    paletteVersion: PALETTE_VERSION,
    expressionContractVersion: EXPRESSION_CONTRACT_V4,
    noteRegion,
    style: { styleId: frozen.styleId, styleVersion: frozen.styleVersion, digest: digest.slice() },
    noteType,
    musicRegion: null,
    provenance,
    decor: null,
  }

  const validation = validateResolved(expression)
  if (!validation.ok) return refuse('contract', validation.reasons.join('; '))

  let bytes: Uint8Array
  try {
    bytes = encodeExpression(expression)
  } catch (error) {
    if (error instanceof CollageError) return refuse('contract', error.code)
    throw error
  }
  return { ok: true, expression, bytes }
}
