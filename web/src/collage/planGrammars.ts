import { CollageError } from './errors'

/**
 * All-family pure plan geometry: a faithful TypeScript port of Android
 * `FrozenGrammarPlans` (the 14 active families, their applicability,
 * note state, base plans and expansion plans, in the same enumeration
 * order). Output is geometry plan descriptors only — tiles, note, song
 * band, provenance and decor — never a sealed expression.
 *
 * Parity is checked against `test/v5-plans-reference.txt`, a copy of
 * Android `v5_plans_reference.txt`, recorded by the frozen `ref_plans.js`
 * runtime. Every rectangle of every family is compared.
 *
 * The note's measured face is reported beside its rect (`size`, `line`)
 * so the creator paints the note at the density its region was sized for.
 */

export const CANVAS_WIDTH = 360
export const CANVAS_HEIGHT = 640

/**
 * Song band rules (owner, 9 November 2026): every horizontal band is a 64 px
 * plate that fits two lines in the style's own type, and the layout 6 spine
 * is 56 px wide and carries the title along its length. Bands that are tiles
 * (11, 24's column), the pulse (24) and the sleeve (20) keep their frozen form.
 */
export const BAND = 64
export const SPINE = 56
/** Layout 6 hinge: band, sender line and the gap to the lower photos. */
const HINGE = 4 + BAND + 2 + 18 + 4

export const ACTIVE_GRAMMAR_IDS = [5, 6, 11, 12, 17, 18, 20, 21, 22, 23, 24, 25, 26, 27] as const

export interface GrammarDef {
  id: number
  music: 'optional' | 'required'
  artwork: 'optional' | 'required' | 'ignored'
  note: 'optional' | 'required'
  counts: number[]
}

export const GRAMMAR_DEFS: GrammarDef[] = [
  { id: 5, music: 'optional', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 6, music: 'required', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 11, music: 'required', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 12, music: 'optional', artwork: 'ignored', note: 'optional', counts: [3, 4, 5] },
  { id: 17, music: 'optional', artwork: 'ignored', note: 'optional', counts: [3, 4, 5] },
  { id: 18, music: 'optional', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 20, music: 'required', artwork: 'required', note: 'optional', counts: [3, 4, 5] },
  { id: 21, music: 'optional', artwork: 'ignored', note: 'required', counts: [3, 4, 5] },
  { id: 22, music: 'optional', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 23, music: 'optional', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 24, music: 'required', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 25, music: 'optional', artwork: 'optional', note: 'optional', counts: [3] },
  { id: 26, music: 'optional', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
  { id: 27, music: 'optional', artwork: 'optional', note: 'optional', counts: [3, 4, 5] },
]

export function grammarDef(id: number): GrammarDef {
  const def = GRAMMAR_DEFS.find((entry) => entry.id === id)
  if (!def) throw new CollageError('REJECTED')
  return def
}

// ------------------------------------------------------------------ inputs

export interface PlanPhoto {
  id: string
  width: number
  height: number
  hasProminentFace?: boolean
}

export interface PlanInput {
  id: string
  sender: string
  photos: PlanPhoto[]
  note: string | null
  music?: { artwork: boolean } | null
}

export interface PlanTextMetric {
  height: number
  lines: number
}

export type PlanMeasure = (text: string, width: number, size: number, line: number, font: string) => PlanTextMetric

export interface NoteState {
  width: number
  height: number
  state: string
  text: string
  /** The face the region was measured at. */
  size: number
  line: number
}

/** T153 line-height stub: the document model the frozen reference was recorded with. */
export const stubTextMeasure: PlanMeasure = (_text, _width, _size, line) => {
  const height = Math.ceil(line)
  return { height, lines: Math.round(height / line) }
}

// ----------------------------------------------------------------- outputs

export interface PlanRect {
  x: number
  y: number
  width: number
  height: number
}

export interface PlanNote {
  rect: PlanRect
  state: string
  size: number
  line: number
}

export interface PlanMusic {
  rect: PlanRect
  mode: string
}

export interface PlanMark {
  role: string
  rect: PlanRect
  size: number
  orientation: string
  align: string
  italic: boolean
  weight: number
  font: string
}

export interface PlanDecor {
  kind: 'carrier' | 'acid-ribbon' | 'print-plane'
  rect: PlanRect
}

export interface PlanTile {
  photoId: string
  rect: PlanRect
  shape: string
  zooms: number[]
  assignmentRole?: string
}

export interface Plan {
  grammarId: number
  branch: string
  tiles: PlanTile[]
  note: PlanNote | null
  music: PlanMusic | null
  provenance: PlanMark[]
  decor: PlanDecor[]
}

export interface Applicability {
  ok: boolean
  reasons: string[]
}

// ------------------------------------------------------------------ checks

function fail(_message: string): never {
  throw new CollageError('REJECTED')
}

function checked(input: PlanInput): Required<PlanInput> {
  if (typeof input.id !== 'string' || input.id.trim() === '') fail('plan input id must not be blank')
  if (typeof input.sender !== 'string') fail('plan input sender must be a string')
  const photos = input.photos
  if (!Array.isArray(photos) || photos.length < 3 || photos.length > 5) fail('plans support 3..5 photos')
  photos.forEach((photo) => {
    if (typeof photo.id !== 'string' || photo.id.trim() === '') fail('photo id must not be blank')
    if (!Number.isFinite(photo.width) || !Number.isFinite(photo.height) || photo.width <= 0 || photo.height <= 0) {
      fail('photo dimensions must be finite and positive')
    }
  })
  const music = input.music ?? null
  if (music !== null && typeof music.artwork !== 'boolean') fail('music artwork must be a boolean')
  if (input.note !== null && typeof input.note !== 'string') fail('note must be a string or null')
  return { id: input.id, sender: input.sender, photos, note: input.note, music }
}

// ------------------------------------------------------------------ public

export function applicability(input: PlanInput, g: GrammarDef): Applicability {
  const reasons: string[] = []
  const photos = Array.isArray(input.photos) ? input.photos : []
  const music = input.music ?? null
  if (g.id === 22 && !photos.some((photo) => photo.hasProminentFace === true)) {
    reasons.push('A prominent detected human anchor is required')
  }
  if (!g.counts.includes(photos.length)) {
    reasons.push(`Supports ${g.counts.join(' / ')} photos; input has ${photos.length}`)
  }
  if (g.music === 'required' && music === null) reasons.push('Music required: it is a structural part of this grammar')
  if (g.note === 'required' && input.note === null) reasons.push('A note is required to organise this composition')
  if (g.artwork === 'required' && music?.artwork !== true) reasons.push('Music artwork required for the sleeve')
  return { ok: reasons.length === 0, reasons }
}

export function noteState(input: PlanInput, g: GrammarDef, measure: PlanMeasure = stubTextMeasure): NoteState {
  const text = input.note
  if (text === null || text === undefined) return { width: 0, height: 0, state: 'absent', text: '', size: 0, line: 0 }
  const width = g.id === 21 ? 140 : g.id === 18 ? 290 : g.id === 6 || g.id === 20 ? 300 : 328
  const font = g.id === 5 || g.id === 21 ? 'StudySerif' : g.id === 12 ? 'StudyMono' : 'StudySans'
  const size = g.id === 5 || g.id === 21 ? 27 : g.id === 12 ? 17 : 23
  const line = Math.ceil(size * 1.1)
  const first = measure(text, width, size, line, font)
  if (first.lines <= 3 && first.height <= 90) return { width, height: first.height, state: 'short', text, size, line }
  const regular = measure(text, width, 16, 20, font)
  return { width, height: regular.height, state: 'regular', text, size: 16, line: 20 }
}

export function plans(input: PlanInput, g: GrammarDef, note?: NoteState, measure: PlanMeasure = stubTextMeasure): Plan[] {
  const d = checked(input)
  const n = note ?? noteState(d, g, measure)
  const out: Omit<Plan, 'grammarId'>[] = []
  if (g.id < 22 && g.id !== 21 && g.id !== 20) out.push(...basePlans(d, g, n))
  let result = out
  switch (g.id) {
    case 6:
      addMusicTransitionSpines(d, measure, out)
      result = [...out.slice(-2), ...out.slice(0, -2)]
      break
    case 20: addSleeveBridge(d, measure, out); break
    case 21: result = plansLetterMargin(d, g, n, measure, out); break
    case 22: addFirstLight(d, measure, out); break
    case 23: addOpenWindows(d, measure, out); break
    case 24: addPulse(d, measure, out); break
    case 25: addOffsetPrints(d, measure, out); break
    case 26: addRibbon(d, measure, out); break
    case 27: addMonument(d, measure, out); break
  }
  return result.map((plan) => ({ grammarId: g.id, ...plan }))
}

/** Frozen readability floor: 84 px short side, 9500 px² area. */
export const MIN_SHORT_SIDE = 84
export const MIN_AREA = 9500

export interface Assignment {
  feasible: boolean
  readingOrder: string[]
}

/** Tiles map 1:1 to the authored order; a tile below the readability floor makes the branch infeasible. */
export function sourceOrderAssignment(plan: Pick<Plan, 'tiles'>): Assignment {
  const below = plan.tiles.some((tile) => Math.min(tile.rect.width, tile.rect.height) < MIN_SHORT_SIDE || tile.rect.width * tile.rect.height < MIN_AREA)
  if (below) return { feasible: false, readingOrder: [] }
  return { feasible: true, readingOrder: plan.tiles.map((tile) => tile.photoId) }
}

/** Android `FrozenResolve.validate`: every region on the canvas, none overlapping another by more than 0.8 px. */
export function validatePlan(plan: Plan): boolean {
  const regions: PlanRect[] = plan.tiles.map((tile) => tile.rect)
  if (plan.note) regions.push(plan.note.rect)
  if (plan.music) regions.push(plan.music.rect)
  for (const mark of plan.provenance) regions.push(mark.rect)
  for (const r of regions) {
    if (r.x < -0.01 || r.y < -0.01 || r.x + r.width > CANVAS_WIDTH + 0.01 || r.y + r.height > CANVAS_HEIGHT + 0.01 || r.width <= 0 || r.height <= 0) {
      return false
    }
  }
  for (let i = 0; i < regions.length; i++) {
    for (let j = i + 1; j < regions.length; j++) {
      const a = regions[i]!
      const b = regions[j]!
      if (
        Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x) > 0.8 &&
        Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y) > 0.8
      ) {
        return false
      }
    }
  }
  return true
}

/** Android `FrozenCandidatePolicy`: at most two layouts per grammar, twelve in all, grammar breadth first. */
export const MAX_PER_GRAMMAR = 2
export const MAX_TOTAL = 12

export function rankBuckets<T>(buckets: Array<[number, T[]]>, maxPerGrammar = MAX_PER_GRAMMAR, maxTotal = MAX_TOTAL): Array<{ grammarId: number; item: T }> {
  const queues = buckets.map(([grammarId, items]) => ({ grammarId, queue: items.slice(0, maxPerGrammar) }))
  const out: Array<{ grammarId: number; item: T }> = []
  while (out.length < maxTotal && queues.some((entry) => entry.queue.length > 0)) {
    for (const entry of queues) {
      const next = entry.queue.shift()
      if (next !== undefined) out.push({ grammarId: entry.grammarId, item: next })
      if (out.length >= maxTotal) break
    }
  }
  return out
}

/** Android producer's geometry identity of a plan. */
export function geometryKey(plan: Pick<Plan, 'tiles'>): string {
  return plan.tiles.map((tile) => [tile.rect.x, tile.rect.y, tile.rect.width, tile.rect.height, tile.shape].join(',')).join(';')
}

// --------------------------------------------------------------- base plans

type Draft = Omit<Plan, 'grammarId'>
type Input = Required<PlanInput>

function rect(x: number, y: number, width: number, height: number): PlanRect {
  return { x, y, width, height }
}

function basePlans(d: Input, g: GrammarDef, n: NoteState): Draft[] {
  switch (g.id) {
    case 5: return baseEditorial(d, n)
    case 6: return baseMusicTransition(d, n)
    case 11: return baseInterlock(d, n)
    case 12: return baseStrips(d, n)
    case 17: return baseMass(d, n)
    case 18: return baseMount(d, n)
    case 21: return baseLetterOpening(d, n)
    default: return []
  }
}

function baseEditorial(d: Input, n: NoteState): Draft[] {
  const p = d.photos
  const r0 = closing(d, n, 603, 16, 328)
  const prov = [
    brand(rect(12, 4, 340, 75), 64, 'StudySerif'),
    sender(rect(16, 613, 328, 23), 20, 'StudySerif', 'horizontal', true, 400, 'right'),
  ]
  const r = rect(10, 101, 340, r0.end - 101)
  const out: Draft[] = []
  for (const rec of recipes(p.length)) {
    for (const power of [0, 1]) {
      const weightSets = [
        rec.map((_, i) => (i === 0 ? 1.3 : 1)),
        rec.map(() => 1),
        rec.map((_, i) => (i === 0 ? 0.45 : 1)),
      ]
      for (const weights of weightSets) {
        out.push(plan(
          `editorial-${rec.join('+')}-${power !== 0 ? 'proportional' : 'equal'}-${num(weights[0]!)}`,
          bands(p, rec, r, { gap: 5, weights, power, zooms: [1.05, 1] }),
          r0.note, r0.music, prov, [],
        ))
      }
    }
  }
  return out
}

function baseMusicTransition(d: Input, n: NoteState): Draft[] {
  const p = d.photos
  const N = p.length
  const note = n.height > 0 ? noteOf(rect(40, 628 - n.height, 300, n.height), n) : null
  const end = note !== null ? note.rect.y - 12 : 634
  const out: Draft[] = []
  const splits = N === 3 ? [1, 2] : N === 4 ? [2] : [2, 3]
  for (const split of splits) {
    for (const frac of [0.52, 0.43, 0.60, 0.28]) {
      for (const power of [0, 1]) {
        for (const stacked of [false, true]) {
          const h1 = (end - HINGE) * frac
          const h2 = end - HINGE - h1
          const music: PlanMusic = { rect: rect(32, h1 + 4, 328, BAND), mode: 'plate' }
          const prov = [
            brand(rect(3, 8, 24, 612), 22, 'Narrow', 'vertical-up', 700),
            sender(rect(42, h1 + 4 + BAND + 2, 304, 18), 14, 'StudySans'),
          ]
          const a = p.slice(0, split)
          const b = p.slice(split)
          out.push(plan(
            `hinge-${split}+${N - split}-${num(frac)}-${power !== 0 ? 'ratio' : 'equal'}-${stacked ? 'stacked' : 'paired'}`,
            [
              ...bands(a, partRecipe(a.length, stacked), rect(32, 0, 328, h1), { gap: 4, power, zooms: [1.04, 1] }),
              ...bands(b, partRecipe(b.length, stacked), rect(32, h1 + HINGE, 328, h2), { gap: 4, power, zooms: [1.04, 1] }),
            ],
            note, music, prov, [],
          ))
        }
      }
    }
  }
  return out
}

function baseInterlock(d: Input, n: NoteState): Draft[] {
  const p = d.photos
  const N = p.length
  const note = n.height > 0 ? noteOf(rect(16, 608 - n.height, 328, n.height), n) : null
  const end = note !== null ? note.rect.y - 12 : 610
  const out: Draft[] = []
  for (const frac of [0.57, 0.46, 0.65, 0.34]) {
    for (const power of [0.55, 1]) {
      for (const stacked of [false, true]) {
        const upperCount = N - 2
        const uh = (end - 69) * frac
        const by = uh + 69
        const bh = end - by
        const music: PlanMusic = { rect: rect(250, by, 104, bh), mode: d.music?.artwork === true ? 'tile-art' : 'tile-text' }
        const prov = [
          brand(rect(10, uh + 11, 340, 49), 46, 'Gothic'),
          sender(rect(16, 615, 328, 20), 16, 'Gothic', 'horizontal', false, 400, 'right'),
        ]
        const upperRecipe = stacked && upperCount === 2 ? [1, 1] : [upperCount]
        out.push(plan(
          `interlock-${upperCount}/2+music-${num(frac)}-${num(power)}-${stacked ? 'stacked' : 'paired'}`,
          [
            ...bands(p.slice(0, upperCount), upperRecipe, rect(6, 4, 348, uh), { power, shape: 'round', gap: 7, zooms: [1.04, 1] }),
            ...bands(p.slice(N - 2), [2], rect(6, by, 237, bh), { power, shape: 'round', gap: 7, zooms: [1.04, 1] }),
          ],
          note, music, prov, [],
        ))
      }
    }
  }
  return out
}

function baseStrips(d: Input, n: NoteState): Draft[] {
  const p = d.photos
  const N = p.length
  const r0 = closing(d, n, 602, 28, 322)
  const r = rect(28, 0, 332, r0.end)
  const prov = [
    brand(rect(2, 8, 23, 570), 18, 'StudyMono', 'vertical-down'),
    sender(rect(28, 615, 316, 20), 15, 'StudyMono', 'horizontal', false, 400, 'right'),
  ]
  const out: Draft[] = []
  for (const mode of ['equal', 'weighted']) {
    const ws = p.map((photo) => Math.min(1 / (photo.width / photo.height), 1 / 1.65))
    const sum = ws.reduce((a, b) => a + b, 0)
    let y = 0
    const tiles = p.map((photo, i) => {
      const h = Math.min(332 / 1.65, mode === 'equal' ? (r.height - 4 * (N - 1)) / N : ((r.height - 4 * (N - 1)) * ws[i]!) / sum)
      const t = tile(photo, rect(28, y, 332, h), 'rect', [1])
      y += h + 4
      return t
    })
    out.push(plan(`score-${N}-${mode}`, tiles, r0.note, r0.music, prov, []))
  }
  return out
}

function baseMass(d: Input, n: NoteState): Draft[] {
  const p = d.photos
  const N = p.length
  const r0 = closing(d, n, 532, 16, 328)
  const prov = [
    sender(rect(10, 543, 340, 19), 15, 'StudySans', 'horizontal', false, 400, 'right'),
    brand(rect(6, 565, 350, 75), 72, 'Narrow', 'horizontal', 700),
  ]
  const r = rect(0, 0, 360, r0.end)
  const recs = N === 3 ? [[1, 2], [2, 1]] : N === 4 ? [[2, 2], [1, 3]] : [[2, 3], [3, 2]]
  const out: Draft[] = []
  for (const rec of recs) {
    for (const weights of [[1.2, 1], [0.85, 1], [0.40, 1]]) {
      for (const power of [0, 0.8]) {
        out.push(plan(
          `mass-${rec.join('+')}-${num(weights[0]!)}-${num(power)}`,
          bands(p, rec, r, { weights, power, gap: 2, zooms: [1.08, 1] }),
          r0.note, r0.music, prov, [],
        ))
      }
    }
  }
  return out
}

function baseMount(d: Input, n: NoteState): Draft[] {
  const p = d.photos
  const r0 = closing(d, n, 587, 44, 290)
  const r = rect(34, 14, 316, r0.end - 14)
  const prov = [
    brand(rect(10, 23, 22, 555), 19, 'StudySans', 'vertical-up'),
    sender(rect(44, 606, 290, 20), 16, 'StudySans'),
  ]
  const out: Draft[] = []
  for (const rec of recipes(p.length)) {
    for (const power of [0.4, 1]) {
      const weightSets = [
        rec.map((_, i) => (i === 0 ? 1.2 : 1)),
        rec.map(() => 1),
        rec.map((_, i) => (i === 0 ? 0.40 : 1)),
      ]
      for (const weights of weightSets) {
        out.push(plan(
          `mount-${rec.join('+')}-${num(power)}-${num(weights[0]!)}`,
          bands(p, rec, r, { gap: 5, power, weights, shape: 'rect', zooms: [1.04, 1] }),
          r0.note, r0.music, prov,
          [{ kind: 'carrier', rect: rect(6, 5, 349, 627) }],
        ))
      }
    }
  }
  return out
}

function baseLetterOpening(d: Input, n: NoteState): Draft[] {
  const p = d.photos
  const N = p.length
  const end = d.music !== null ? 634 - BAND - 10 : 637
  const out: Draft[] = []
  for (const openingH of [205, 166, 245]) {
    for (const power of [0, 1]) {
      const note = noteOf(rect(12, openingH + 58, 140, n.height), n)
      const right = rect(171, openingH + 14, 189, end - openingH - 14)
      const prov = [
        sender(rect(12, openingH + 16, 151, 33), 25, 'StudySerif', 'horizontal', true),
        brand(rect(12, end - 28, 145, 24), 22, 'StudySerif'),
      ]
      const music: PlanMusic | null = d.music !== null ? { rect: rect(12, 634 - BAND, 336, BAND), mode: 'plate' } : null
      out.push(plan(
        `letter-opening-${num(openingH)}-${num(power)}`,
        [
          ...bands(p.slice(0, N - 2), [N - 2], rect(0, 0, 360, openingH), { power, gap: 4, zooms: [1.05, 1] }),
          ...bands(p.slice(N - 2), [1, 1], right, { gap: 8, zooms: [1.05, 1] }),
        ],
        note, music, prov, [],
      ))
    }
  }
  return out
}

// ---------------------------------------------------------- expansion plans

function addMusicTransitionSpines(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  const N = p.length
  const note = noteAt(d, 16, 0, 328, 24, 'StudySans', 3, measure)
  const bottom = note !== null ? 612 - note.rect.height : 615
  if (note !== null) note.rect.y = bottom + 3
  for (const frac of [0.52, 0.43]) {
    const leftCount = Math.ceil(N / 2)
    const rightCount = N - leftCount
    const music: PlanMusic = { rect: rect(152, 0, SPINE, bottom - 24), mode: 'card' }
    const a = bands(p.slice(0, leftCount), ones(leftCount), rect(0, 0, 149, bottom - 24), { gap: 5, zooms: [1.08, 1] })
    const b = bands(p.slice(leftCount), ones(rightCount), rect(211, 0, 149, bottom - 24), { gap: 5, zooms: [1.08, 1] })
    out.push(plan(
      `sound-spine-${leftCount}/${rightCount}-${num(frac)}`,
      [...a, ...b], note, music,
      [
        sender(rect(12, bottom - 20, 330, 21), 18, 'StudySerif', 'horizontal', true),
        brand(rect(12, 618, 330, 19), 17, 'StudySans', 'horizontal', 400, 'right'),
      ],
      [],
    ))
  }
}

function addSleeveBridge(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  const N = p.length
  for (const sleeveH of [145, 126]) {
    const note = noteAt(d, 15, 0, 330, 25, 'StudySerif', 3, measure)
    const end = note !== null ? 606 - note.rect.height : 606
    if (note !== null) note.rect.y = end
    const music = sound(d, rect(14, end - sleeveH - 9, 332, sleeveH), 'sleeve')
    if (music === null) continue
    const ph = music.rect.y - 38
    const recs = N === 3 ? [[1, 2], [2, 1]] : N === 4 ? [[2, 2], [1, 1, 2]] : [[2, 3], [1, 2, 2]]
    for (const rec of recs) {
      out.push(plan(
        `sleeve-bridge-${rec.join('+')}-${num(sleeveH)}`,
        bands(p, rec, rect(0, 0, 360, ph), { gap: 4, power: 0.5, zooms: [1.1, 1.03, 1] }),
        note, music,
        [
          sender(rect(14, ph + 5, 330, 28), 23, 'StudySerif', 'horizontal', true),
          brand(rect(15, 617, 330, 20), 18, 'StudySans', 'horizontal', 400, 'right'),
        ],
        [],
      ))
    }
  }
}

function plansLetterMargin(d: Input, g: GrammarDef, n: NoteState, measure: PlanMeasure, out: Draft[]): Draft[] {
  const p = d.photos
  const N = p.length
  const full = noteAt(d, 18, 0, 324, 29, 'StudySerif', 3, measure)
  if (full !== null) {
    for (const topH of [240, 290, 340]) {
      const sy = topH + 14
      const ny = sy + 36
      const music: PlanMusic | null = d.music !== null ? { rect: rect(18, 633 - BAND, 324, BAND), mode: 'plate' } : null
      const note: PlanNote = { ...full, rect: rect(full.rect.x, ny, full.rect.width, full.rect.height) }
      const end = music !== null ? 627 - BAND : 611
      if (note.rect.y + note.rect.height <= end) {
        const rec = N === 3 ? [1, 2] : N === 4 ? [2, 2] : [2, 3]
        out.push(plan(
          `letter-crown-${num(topH)}`,
          bands(p, rec, rect(0, 0, 360, topH), { gap: 5, zooms: [1.05, 1] }),
          note, music,
          [
            sender(rect(18, sy, 324, 31), 27, 'StudySerif', 'horizontal', true),
            brand(rect(18, music !== null ? 609 - BAND : 617, 324, 20), 18, 'StudySerif', 'horizontal', 400, 'right'),
          ],
          [],
        ))
      }
    }
  }
  if (d.note !== null && N <= 4) {
    const text = noteAt(d, 10, 51, 140, 26, 'StudySerif', 3, measure)
    // With a song the brand sits above the taller band, so the letter column is shorter.
    const tallest = d.music !== null ? 492 - (BAND - 49) : 492
    if (text !== null && text.rect.height >= 360 && text.rect.height <= tallest) {
      const end = d.music !== null ? 632 - BAND - 9 : 611
      const tiles = bands(p, p.map(() => 1), rect(162, 0, 198, end), {
        gap: 7,
        weights: p.map((photo) => Math.min(1.6, photo.height / photo.width)),
        zooms: [1.04, 1],
      })
      out.unshift(plan(
        'letter-full-height-counterpoint',
        tiles,
        { ...text, rect: rect(10, 51, 140, text.rect.height) },
        d.music !== null ? sound(d, rect(10, 632 - BAND, 340, BAND), 'plate') : null,
        [
          sender(rect(10, 10, 143, 29), 24, 'StudySerif', 'horizontal', true),
          brand(rect(10, d.music !== null ? 550 - (BAND - 49) : 550, 141, 23), 21, 'StudySerif'),
        ],
        [],
      ))
    }
  }
  if (N <= 4) {
    const old = basePlans(d, g, n)
    const fullHeight = out.filter((entry) => entry.branch === 'letter-full-height-counterpoint')
    const crown = out.filter((entry) => entry.branch !== 'letter-full-height-counterpoint')
    return [...fullHeight, ...old, ...crown]
  }
  return out
}

function addFirstLight(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  const N = p.length
  const rhythms = [recs(N - 1), ones(N - 1)]
  for (const heroH of [294, 254, 222, 330, 360]) {
    for (const rhythm of rhythms) {
      const note = noteAt(d, 18, heroH + 47, 324, 27, 'StudySerif', 3, measure)
      const ny = note !== null ? note.rect.y + note.rect.height + 13 : heroH + 48
      const music = sound(d, rect(12, 610 - BAND, 336, BAND), 'plate')
      const ph = (music !== null ? 602 - BAND : 606) - ny
      const hero = tile(p[0]!, rect(0, 0, 360, heroH), 'rect', [1.07, 1], 'human-anchor')
      out.push(plan(
        `human-hinge-${num(heroH)}-${rhythm.join('+')}`,
        [hero, ...bands(p.slice(1), rhythm, rect(8, ny, 344, ph), { gap: 5, zooms: [1.06, 1] })],
        note, music,
        [
          sender(rect(18, heroH + 6, 324, 35), 31, 'StudySerif', 'horizontal', true),
          brand(rect(12, 616, 336, 20), 18, 'StudySerif', 'horizontal', 400, 'right'),
        ],
        [],
      ))
    }
  }
}

function addOpenWindows(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  const N = p.length
  for (const heroH of [218, 268, 175]) {
    for (const split of [0.58, 0.43]) {
      const note = noteAt(d, 14, 0, 332, 24, 'StudySans', 3, measure)
      const bottom = note !== null ? 603 - note.rect.height : 603
      if (note !== null) note.rect.y = bottom
      const music = sound(d, rect(12, bottom - 15 - BAND, 336, BAND), 'plate')
      const end = music !== null ? music.rect.y - 10 : bottom - 12
      const y = heroH + 39
      const h = end - y
      const tiles: PlanTile[] = [tile(p[0]!, rect(0, 0, 360, heroH), 'rect', [1.08, 1])]
      if (N === 3) {
        tiles.push(...bands(p.slice(1), [2], rect(0, y, 360, h), { gap: 5, weights: [1], zooms: [1.09, 1] }))
      }
      if (N >= 4) {
        const leftN = N === 4 ? 1 : 2
        const rightN = 2
        const lw = 360 * split - 3
        const rw = 360 - lw - 5
        tiles.push(...bands(p.slice(1, 1 + leftN), ones(leftN), rect(0, y, lw, h), { gap: 5, weights: leftN === 2 ? [0.64, 1] : [1], zooms: [1.08, 1] }))
        tiles.push(...bands(p.slice(1 + leftN), ones(rightN), rect(lw + 5, y, rw, h), { gap: 5, weights: [1, 0.7], zooms: [1.08, 1] }))
      }
      out.push(plan(
        `spanning-window-${num(heroH)}-${num(split)}`,
        tiles, note, music,
        [
          brand(rect(9, heroH + 3, 342, 33), 30, 'Gothic', 'horizontal', 700),
          sender(rect(14, 616, 332, 20), 17, 'StudySans'),
        ],
        [{ kind: 'acid-ribbon', rect: rect(0, heroH, 360, 39) }],
      ))
    }
  }
}

function addPulse(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  const N = p.length
  for (const heroH of [285, 330, 260, 222]) {
    const note = noteAt(d, 13, 0, 334, 27, 'StudySerif', 3, measure)
    const noteH = note?.rect.height ?? 0
    const by = heroH + 43
    const end = 610 - noteH - (note !== null ? 12 : 0)
    const my = end - 76
    if (note !== null) note.rect.y = end + 9
    const hero = tile(p[0]!, rect(0, 0, 360, heroH), 'rect', [1.13, 1.06, 1], 'object-anchor')
    const music = sound(d, rect(10, my, 340, 76), 'pulse')
    const rhythms = [recs(N - 1), N === 3 ? [1, 1] : recs(N - 1)]
    for (const rec of rhythms) {
      out.push(plan(
        `pulse-${num(heroH)}-${rec.join('+')}`,
        [hero, ...bands(p.slice(1), rec, rect(7, by, 346, my - by - 32), { gap: 5, zooms: [1.08, 1] })],
        note, music,
        [
          brand(rect(17, heroH + 4, 334, 34), 31, 'Gothic', 'horizontal', 700),
          sender(rect(12, my - 25, 336, 22), 18, 'StudySans'),
        ],
        [{ kind: 'acid-ribbon', rect: rect(0, heroH, 360, 40) }],
      ))
    }
  }
  if (N === 3) {
    const copies: Draft[] = []
    for (const q of out.filter((entry) => entry.branch.endsWith('-2'))) {
      for (const share of [0.62, 0.38]) {
        const tiles = q.tiles.map((entry) => ({ ...entry, rect: { ...entry.rect } }))
        const a = tiles[1]!
        const b = tiles[2]!
        const w = a.rect.width + b.rect.width + 5
        const newAw = (w - 5) * share
        const newBx = a.rect.x + newAw + 5
        const newBw = (w - 5) * (1 - share)
        tiles[1] = { ...a, rect: { ...a.rect, width: newAw } }
        tiles[2] = { ...b, rect: { ...b.rect, x: newBx, width: newBw } }
        copies.push({ ...q, tiles, branch: `${q.branch}-unequal-${num(share)}` })
      }
    }
    for (const copy of copies) out.unshift(copy)
    for (const heroW of [212, 226, 234]) {
      for (const rowH of [90, 100, 119, 140]) {
        const note = noteAt(d, 16, heroW === 234 ? 436 : 478, 328, heroW === 234 ? 36 : 28, 'StudySerif', 3, measure)
        const hh = heroW === 234 ? 379 : 421
        const rw = 354 - heroW
        const hero = tile(p[0]!, rect(0, 0, heroW, hh), 'rect', [1.08, 1], 'object-anchor')
        const tiles = [
          hero,
          tile(p[1]!, rect(heroW + 6, 0, rw, rowH), 'rect', [1.04, 1]),
          tile(p[2]!, rect(heroW + 6, rowH + 6, rw, rowH), 'rect', [1.04, 1]),
        ]
        const music: PlanMusic = {
          rect: rect(heroW + 6, 2 * rowH + 12, rw, hh - 2 * rowH - 12),
          mode: d.music?.artwork === true ? 'tile-art' : 'tile-text',
        }
        out.unshift(plan(
          `pulse-object-and-sound-column-${num(heroW)}-${num(rowH)}`,
          tiles, note, music,
          [
            brand(rect(16, hh + 11, 330, 32), 29, 'Gothic', 'horizontal', 700),
            sender(rect(16, 614, 328, 23), 20, 'StudySerif', 'horizontal', true),
          ],
          [{ kind: 'acid-ribbon', rect: rect(0, hh + 6, 360, 39) }],
        ))
      }
    }
  }
}

function addOffsetPrints(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  for (const h of [151, 135, 168]) {
    const note = noteAt(d, 17, 0, 326, 26, 'StudySerif', 3, measure)
    const end = note !== null ? 599 - note.rect.height : 599
    if (note !== null) note.rect.y = end
    const music = sound(d, rect(12, end - 13 - BAND, 336, BAND), 'plate')
    const photoEnd = music !== null ? music.rect.y - 35 : end - 35
    const gap = 9
    const total = h * 3 + gap * 2
    if (total > photoEnd - 20) continue
    const xs = [10, 50, 18]
    const ww = [298, 296, 304]
    const tiles = p.map((photo, i) => tile(photo, rect(xs[i]!, 13 + i * (h + gap), ww[i]!, h), 'rect', [1.06, 1]))
    out.push(plan(
      `shifted-three-planes-${num(h)}`,
      tiles, note, music,
      [
        sender(rect(18, total + 26, 324, 25), 22, 'StudySerif', 'horizontal', true),
        brand(rect(18, 616, 324, 20), 18, 'StudySerif', 'horizontal', 400, 'right'),
      ],
      tiles.map((entry) => printPlane(entry.rect)),
    ))
  }
  const note = noteAt(d, 17, 0, 326, 26, 'StudySerif', 3, measure)
  const end = note !== null ? 599 - note.rect.height : 599
  if (note !== null) note.rect.y = end
  const music = sound(d, rect(12, end - 13 - BAND, 336, BAND), 'plate')
  const photoEnd = music !== null ? music.rect.y - 35 : end - 35
  for (const topH of [222, 260]) {
    const tiles = [
      tile(p[0]!, rect(10, 10, 168, topH), 'rect', [1.05, 1]),
      tile(p[1]!, rect(192, 29, 153, topH - 19), 'rect', [1.05, 1]),
      tile(p[2]!, rect(18, topH + 26, 326, photoEnd - topH - 26), 'rect', [1.05, 1]),
    ]
    out.push(plan(
      `offset-planes-pair-and-wide-${num(topH)}`,
      tiles, note, music,
      [
        sender(rect(18, photoEnd + 5, 326, 25), 22, 'StudySerif', 'horizontal', true),
        brand(rect(18, 616, 326, 20), 18, 'StudySerif', 'horizontal', 400, 'right'),
      ],
      tiles.map((entry) => printPlane(entry.rect)),
    ))
  }
}

function addRibbon(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  const N = p.length
  const note = noteAt(d, 18, 0, 324, 26, 'StudySerif', 3, measure)
  const bottom = note !== null ? 594 - note.rect.height : 594
  if (note !== null) note.rect.y = bottom
  const music = sound(d, rect(14, bottom - 8 - BAND, 332, BAND), 'plate')
  const end = music !== null ? music.rect.y - 8 : bottom - 8
  for (const rhythm of [1, 1.35]) {
    const heights = p.map((_, i) => (i === 1 ? rhythm : 1))
    const sum = heights.reduce((a, b) => a + b, 0)
    let y = 0
    const tiles = p.map((photo, i) => {
      if (i === 1) y += 29
      const h = ((end - 29 - 5 * (N - 1)) * heights[i]!) / sum
      const x = i % 2 === 1 ? 22 : 0
      const w = i % 2 === 1 ? 338 : 340
      const t = tile(photo, rect(x, y, w, h), 'rect', [1.05, 1])
      y += h + 5
      return t
    })
    const seam = tiles[0]!.rect.height + 5
    out.push(plan(
      `offset-ribbon-${N}-${num(rhythm)}`,
      tiles, note, music,
      [
        sender(rect(18, seam, 324, 24), 20, 'StudySerif', 'horizontal', true, 400, 'right'),
        brand(rect(12, 610, 340, 30), 28, 'Gothic', 'horizontal', 700),
      ],
      [],
    ))
  }
  const ribbonRecipes = N === 3 ? [[1, 2], [2, 1]] : N === 4 ? [[1, 1, 2], [2, 2]] : [[1, 2, 2], [2, 1, 2]]
  for (const rec of ribbonRecipes) {
    for (const power of [0, 0.8]) {
      let y = 0
      let offset = 0
      const hh = (end - 34 - 5 * (rec.length - 1)) / rec.length
      const tiles: PlanTile[] = []
      rec.forEach((count, i) => {
        if (i === 1) y += 34
        const x = i % 2 === 1 ? 26 : 0
        const w = i % 2 === 1 ? 334 : 340
        tiles.push(...bands(p.slice(offset, offset + count), [count], rect(x, y, w, hh), { gap: 5, power, zooms: [1.07, 1] }))
        offset += count
        y += hh + 5
      })
      out.push(plan(
        `ribbon-turns-${rec.join('+')}-${num(power)}`,
        tiles, note, music,
        [
          sender(rect(18, hh + 8, 324, 25), 21, 'StudySerif', 'horizontal', true, 400, 'right'),
          brand(rect(12, 610, 340, 30), 28, 'Gothic', 'horizontal', 700),
        ],
        [],
      ))
    }
  }
}

function addMonument(d: Input, measure: PlanMeasure, out: Draft[]): void {
  const p = d.photos
  const N = p.length
  for (const sideW of [148, 170]) {
    const note = noteAt(d, 14, 0, 332, 25, 'StudySerif', 3, measure)
    const bottom = note !== null ? 598 - note.rect.height : 598
    if (note !== null) note.rect.y = bottom
    const music = sound(d, rect(10, bottom - 12 - BAND, 340, BAND), 'plate')
    const end = music !== null ? music.rect.y - 30 : bottom - 30
    const hero = tile(p[0]!, rect(0, 0, 355 - sideW, end), 'rect', [1.08, 1], 'vertical-anchor')
    const replies = bands(p.slice(1), ones(N - 1), rect(360 - sideW, 0, sideW, end), { gap: 6, zooms: [1.04, 1] })
    out.push(plan(
      `monument-replies-${num(sideW)}`,
      [hero, ...replies], note, music,
      [
        sender(rect(12, end + 5, 336, 23), 20, 'StudySerif'),
        brand(rect(12, 615, 336, 21), 19, 'StudySans', 'horizontal', 400, 'right'),
      ],
      [],
    ))
  }
}

// ------------------------------------------------------------------- helper

function plan(branch: string, tiles: PlanTile[], note: PlanNote | null, music: PlanMusic | null, provenance: PlanMark[], decor: PlanDecor[]): Draft {
  return { branch, tiles, note: note === null ? null : { ...note, rect: { ...note.rect } }, music, provenance, decor }
}

function noteOf(r: PlanRect, n: NoteState): PlanNote {
  return { rect: r, state: n.state, size: n.size, line: n.line }
}

function noteAt(d: Input, x: number, y: number, w: number, size: number, font: string, maxShort: number, measure: PlanMeasure): PlanNote | null {
  const text = d.note
  if (text === null) return null
  let line = Math.ceil(size * 1.12)
  let face = size
  let m = measure(text, w, size, line, font)
  let state = 'short'
  if (m.lines > maxShort) {
    face = 16
    line = 20
    m = measure(text, w, 16, 20, font)
    state = m.lines > 10 ? 'long' : 'medium'
  }
  return { rect: rect(x, y, w, m.height), state, size: face, line }
}

function sound(d: Input, r: PlanRect, mode: string): PlanMusic | null {
  const music = d.music
  if (music === null) return null
  return { rect: r, mode: music.artwork ? mode : mode === 'sleeve' ? 'sleeve-empty' : mode }
}

function closing(d: Input, n: NoteState, end: number, x: number, w: number): { note: PlanNote | null; music: PlanMusic | null; end: number } {
  const note = n.height > 0 ? noteOf(rect(x, end - n.height, n.width, n.height), n) : null
  let top = note !== null ? note.rect.y - 10 : end
  const music: PlanMusic | null = d.music !== null ? { rect: rect(x, top - BAND, w, BAND), mode: 'plate' } : null
  if (music !== null) top = music.rect.y - 8
  return { note, music, end: top }
}

interface BandsOptions {
  gap?: number
  weights?: number[]
  power?: number
  shape?: string
  zooms?: number[]
}

function bands(p: PlanPhoto[], recipe: number[], r: PlanRect, options: BandsOptions = {}): PlanTile[] {
  const gap = options.gap ?? 5
  const heights = options.weights ?? recipe.map(() => 1)
  const power = options.power ?? 0
  const shape = options.shape ?? 'rect'
  const zooms = options.zooms ?? [1]
  const sum = heights.reduce((a, b) => a + b, 0)
  let pos = 0
  let y = r.y
  const out: PlanTile[] = []
  for (let k = 0; k < recipe.length; k++) {
    const count = recipe[k]!
    const group = p.slice(pos, pos + count)
    const h = ((r.height - gap * (recipe.length - 1)) * heights[k]!) / sum
    const ws = group.map((photo) => Math.pow(clamp(photo.width / photo.height, 0.45, 2), power))
    const wSum = ws.reduce((a, b) => a + b, 0)
    let x = r.x
    for (let j = 0; j < group.length; j++) {
      const w = ((r.width - gap * (count - 1)) * ws[j]!) / wSum
      out.push(tile(group[j]!, rect(x, y, w, h), shape, zooms))
      x += w + gap
    }
    y += h + gap
    pos += count
  }
  return out
}

function tile(photo: PlanPhoto, r: PlanRect, shape = 'rect', zooms: number[] = [1], assignmentRole?: string): PlanTile {
  const out: PlanTile = { photoId: photo.id, rect: r, shape, zooms: zooms.slice() }
  if (assignmentRole !== undefined) out.assignmentRole = assignmentRole
  return out
}

function printPlane(r: PlanRect): PlanDecor {
  return { kind: 'print-plane', rect: rect(r.x - 4, r.y - 4, r.width + 8, r.height + 8) }
}

function brand(r: PlanRect, size: number, font = 'StudySans', orientation = 'horizontal', weight = 400, align = 'left'): PlanMark {
  return { role: 'Remanence', rect: r, size, orientation, align, italic: false, weight, font }
}

function sender(r: PlanRect, size: number, font = 'StudySans', orientation = 'horizontal', italic = false, weight = 400, align = 'left'): PlanMark {
  return { role: 'sender', rect: r, size, orientation, align, italic, weight, font }
}

function recipes(n: number): number[][] {
  if (n === 3) return [[1, 2], [2, 1]]
  if (n === 4) return [[1, 1, 2], [2, 2]]
  return [[1, 2, 2], [2, 3], [3, 2]]
}

function recs(n: number): number[] {
  if (n === 1) return [1]
  if (n === 2) return [2]
  if (n === 3) return [1, 2]
  if (n === 4) return [2, 2]
  return [2, 3]
}

function ones(n: number): number[] {
  return Array.from({ length: n }, () => 1)
}

function partRecipe(len: number, stacked: boolean): number[] {
  if (len === 3) return [1, 2]
  if (stacked && len === 2) return [1, 1]
  return [len]
}

function clamp(n: number, a: number, b: number): number {
  return Math.max(a, Math.min(b, n))
}

/** Android `num()`: integral doubles print without a decimal point. */
function num(value: number): string {
  return String(value)
}
