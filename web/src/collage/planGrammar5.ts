import { CollageError } from './errors'

/**
 * Grammar 5 ("editorial") pure plan geometry — T233 bounded prerequisite.
 *
 * Faithful TypeScript port of the Android `FrozenGrammarPlans`
 * grammar-5 subset only (`baseEditorial` + `closing`/`bands`/`recipes`/
 * `sourceOrderAssignment` + the grammar-5 `noteState`/`applicability`
 * rows). Supports 3–5 photos, music:none, present/absent note text —
 * the input cases covered by the frozen reference. Emits geometry plan
 * descriptors ONLY (tiles/text/provenance inputs + branch identity);
 * never a frozen V4 expression, never a full-source frozen crop, never
 * fabricated analysis vectors. The next package connects a real
 * preview/manual-crop/freeze adapter after T228 acceptance.
 *
 * Provenance: parity is checked against
 * `test/g5-plans-reference.txt`, the grammar-5 slice of Android
 * `v5_plans_reference.txt`, which was recorded by the frozen
 * `ref_plans.js` runtime (see that fixture header). The JS reference is
 * NOT real Android ciphertext and NOT a shipped-vector claim.
 *
 * Coverage gap (documented, not silent): reference cases with music
 * text/cover are applicability-checked only. Their geometry needs
 * `closing`-with-music, which shifts every tile rect and is out of
 * scope here; `grammar5Plans` refuses music input with a typed error.
 */

export interface Grammar5Photo {
  id: string
  width: number
  height: number
}

export interface Grammar5Input {
  id: string
  sender: string
  photos: Grammar5Photo[]
  note: string | null
  /** Package scope is music:none; any non-null value is refused. */
  music?: { artwork: boolean } | null
}

export interface Grammar5Rect {
  x: number
  y: number
  width: number
  height: number
}

export interface Grammar5Tile {
  photoId: string
  rect: Grammar5Rect
  shape: string
  zooms: number[]
}

export interface Grammar5Note {
  rect: Grammar5Rect
  state: string
}

export interface Grammar5Mark {
  role: string
  rect: Grammar5Rect
  size: number
  orientation: string
  align: string
  italic: boolean
  weight: number
  font: string
}

export interface Grammar5Plan {
  branch: string
  tiles: Grammar5Tile[]
  note: Grammar5Note | null
  music: null
  provenance: Grammar5Mark[]
  decor: never[]
}

export interface Grammar5Applicability {
  ok: boolean
  reasons: string[]
}

export interface Grammar5NoteState {
  width: number
  height: number
  state: string
  text: string
}

export interface Grammar5TextMetric {
  height: number
  lines: number
}

export type Grammar5Measure = (
  text: string,
  width: number,
  size: number,
  line: number,
  font: string,
) => Grammar5TextMetric

export interface Grammar5Assignment {
  feasible: boolean
  readingOrder: string[]
}

export const GRAMMAR5_ID = 5
export const GRAMMAR5_COUNTS = [3, 4, 5]
/** Frozen readability floor: 84 px short side, 9500 px² area. */
export const GRAMMAR5_MIN_SHORT_SIDE = 84
export const GRAMMAR5_MIN_AREA = 9500

/**
 * T153 line-height stub model (`getBoundingClientRect().height == line`):
 * the same document model the frozen reference was recorded with. Browser
 * text measurement stays an explicit residual for the next package.
 */
export const stubTextMeasure: Grammar5Measure = (_text, _width, _size, line, _font) => {
  const height = Math.ceil(line)
  return { height, lines: Math.round(height / line) }
}

function fail(_message: string): never {
  throw new CollageError('REJECTED')
}

function checkedInput(input: Grammar5Input): { photos: Grammar5Photo[]; note: string | null } {
  if (typeof input.id !== 'string' || input.id.trim() === '') fail('grammar5 input id must not be blank')
  if (typeof input.sender !== 'string' || input.sender.trim() === '') fail('grammar5 input sender must not be blank')
  const photos = input.photos
  if (!Array.isArray(photos) || photos.length < 3 || photos.length > 5) {
    fail(`grammar5 supports 3..5 photos, got ${Array.isArray(photos) ? photos.length : 'none'}`)
  }
  photos.forEach((photo, index) => {
    if (typeof photo.id !== 'string' || photo.id.trim() === '') fail(`grammar5 photos[${index}].id must not be blank`)
    if (!Number.isFinite(photo.width) || !Number.isFinite(photo.height) || photo.width <= 0 || photo.height <= 0) {
      fail(`grammar5 photos[${index}] dimensions must be finite and positive`)
    }
  })
  if (input.music !== undefined && input.music !== null) fail('grammar5 geometry plans do not support music')
  if (input.note !== null && typeof input.note !== 'string') fail('grammar5 note must be a string or null')
  return { photos, note: input.note }
}

/**
 * Android-faithful applicability for grammar 5 (`optional/optional/
 * optional`, counts 3/4/5). Counts are the only row that can refuse
 * grammar 5; music stays applicable here exactly as in Android and is
 * refused separately by the package-scope entry gate.
 */
export function grammar5Applicability(input: Grammar5Input): Grammar5Applicability {
  const count = Array.isArray(input.photos) ? input.photos.length : 0
  if (!GRAMMAR5_COUNTS.includes(count)) {
    return { ok: false, reasons: [`Supports ${GRAMMAR5_COUNTS.join(' / ')} photos; input has ${count}`] }
  }
  return { ok: true, reasons: [] }
}

/** Grammar-5 note state: width 328, StudySerif, size 27. */
export function grammar5NoteState(input: Grammar5Input, measure: Grammar5Measure = stubTextMeasure): Grammar5NoteState {
  const text = input.note
  if (text === null || text === undefined) return { width: 0, height: 0, state: 'absent', text: '' }
  const width = 328
  const font = 'StudySerif'
  const first = measure(text, width, 27, Math.ceil(27 * 1.1), font)
  if (first.lines <= 3 && first.height <= 90) return { width, height: first.height, state: 'short', text }
  const regular = measure(text, width, 16, 20, font)
  return { width, height: regular.height, state: 'regular', text }
}

interface Closing {
  note: Grammar5Note | null
  music: null
  end: number
}

function closing(note: Grammar5NoteState, end: number, x: number): Closing {
  const noteOut = note.height > 0
    ? { rect: { x, y: end - note.height, width: note.width, height: note.height }, state: note.state }
    : null
  const top = noteOut !== null ? noteOut.rect.y - 10 : end
  return { note: noteOut, music: null, end: top }
}

function tile(photo: Grammar5Photo, rect: Grammar5Rect, shape = 'rect', zooms: number[] = [1]): Grammar5Tile {
  return { photoId: photo.id, rect, shape, zooms: zooms.slice() }
}

function brandMark(rect: Grammar5Rect, size: number, font = 'StudySans', orientation = 'horizontal', weight = 400, align = 'left'): Grammar5Mark {
  return { role: 'Remanence', rect, size, orientation, align, italic: false, weight, font }
}

function senderMark(rect: Grammar5Rect, size: number, font = 'StudySans', orientation = 'horizontal', italic = false, weight = 400, align = 'left'): Grammar5Mark {
  return { role: 'sender', rect, size, orientation, align, italic, weight, font }
}

function recipes(photoCount: number): number[][] {
  if (photoCount === 3) return [[1, 2], [2, 1]]
  if (photoCount === 4) return [[1, 1, 2], [2, 2]]
  return [[1, 2, 2], [2, 3], [3, 2]]
}

function clamp(value: number, low: number, high: number): number {
  return Math.max(low, Math.min(high, value))
}

/** Android `num()`: integral doubles print without a decimal point. */
function num(value: number): string {
  if (Number.isFinite(value) && value === Math.floor(value) && Math.abs(value) < 1e15) return String(value)
  return String(value)
}

interface BandsOptions {
  gap?: number
  weights?: number[]
  power?: number
  shape?: string
  zooms?: number[]
}

function bands(
  photos: Grammar5Photo[],
  recipe: number[],
  region: Grammar5Rect,
  options: BandsOptions = {},
): Grammar5Tile[] {
  const gap = options.gap ?? 5
  const weights = options.weights ?? recipe.map(() => 1)
  const power = options.power ?? 0
  const shape = options.shape ?? 'rect'
  const zooms = options.zooms ?? [1]
  let pos = 0
  let y = region.y
  const sum = weights.reduce((a, b) => a + b, 0)
  const out: Grammar5Tile[] = []
  for (let k = 0; k < recipe.length; k++) {
    const count = recipe[k]
    const group = photos.slice(pos, pos + count)
    const h = ((region.height - gap * (recipe.length - 1)) * weights[k]) / sum
    const ws = group.map((photo) => Math.pow(clamp(photo.width / photo.height, 0.45, 2.0), power))
    const wSum = ws.reduce((a, b) => a + b, 0)
    let x = region.x
    for (let j = 0; j < group.length; j++) {
      const w = ((region.width - gap * (count - 1)) * ws[j]) / wSum
      out.push(tile(group[j], { x, y, width: w, height: h }, shape, zooms))
      x += w + gap
    }
    y += h + gap
    pos += count
  }
  return out
}

/**
 * Complete supported grammar-5 branch enumeration (`baseEditorial`):
 * 2 recipes × equal/proportional × 3 first-tile weights for 3–4 photos
 * (12 plans), 3 recipes for 5 photos (18 plans). Deterministic for the
 * same input; no invented branches.
 */
export function grammar5Plans(input: Grammar5Input, note?: Grammar5NoteState, measure?: Grammar5Measure): Grammar5Plan[] {
  const checked = checkedInput(input)
  const photos = checked.photos
  const state = note ?? grammar5NoteState({ ...input, note: checked.note }, measure)
  const done = closing(state, 603, 16)
  const provenance = [
    brandMark({ x: 12, y: 4, width: 340, height: 75 }, 64, 'StudySerif'),
    senderMark({ x: 16, y: 613, width: 328, height: 23 }, 20, 'StudySerif', 'horizontal', true, 400, 'right'),
  ]
  const region: Grammar5Rect = { x: 10, y: 101, width: 340, height: done.end - 101 }
  const out: Grammar5Plan[] = []
  for (const rec of recipes(photos.length)) {
    for (const power of [0, 1]) {
      const weightSets = [
        rec.map((_, i) => (i === 0 ? 1.3 : 1)),
        rec.map(() => 1),
        rec.map((_, i) => (i === 0 ? 0.45 : 1)),
      ]
      for (const weights of weightSets) {
        out.push({
          branch: `editorial-${rec.join('+')}-${power !== 0 ? 'proportional' : 'equal'}-${num(weights[0])}`,
          tiles: bands(photos, rec, region, { gap: 5, weights, power, zooms: [1.05, 1] }),
          note: done.note,
          music: null,
          provenance,
          decor: [],
        })
      }
    }
  }
  return out
}

/**
 * Frozen readability gate: a tile below the 84 px / 9500 px² floor makes
 * the branch infeasible; otherwise the reading order is the authored
 * photo order.
 */
export function grammar5SourceOrderAssignment(plan: Grammar5Plan): Grammar5Assignment {
  const below = plan.tiles.some(
    (entry) => Math.min(entry.rect.width, entry.rect.height) < GRAMMAR5_MIN_SHORT_SIDE
      || entry.rect.width * entry.rect.height < GRAMMAR5_MIN_AREA,
  )
  if (below) return { feasible: false, readingOrder: [] }
  return { feasible: true, readingOrder: plan.tiles.map((entry) => entry.photoId) }
}
