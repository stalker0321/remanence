import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
import {
  grammar5Applicability,
  grammar5NoteState,
  grammar5Plans,
  grammar5SourceOrderAssignment,
  stubTextMeasure,
  type Grammar5Input,
  type Grammar5Plan,
} from '../src/collage/planGrammar5'
import reference from './g5-plans-reference.txt?raw'

/**
 * Independent golden parity for the grammar-5 geometry port.
 *
 * Expected geometry comes ONLY from `g5-plans-reference.txt` (the
 * grammar-5 slice of Android `v5_plans_reference.txt`, recorded by the
 * frozen `ref_plans.js` runtime — see that fixture header). Nothing here
 * is computed by the port under test. Note text is the reference's
 * `"Probe note text."` with the line-height-stub measurement model.
 *
 * In-scope cases (music none, 3–5 photos, note 0/1) are compared
 * tile-by-tile against every recorded plan. Reference cases with music
 * text/cover are a documented coverage gap: applicability must still
 * match, but geometry needs closing-with-music and the port refuses
 * music input with a typed error instead of inventing it.
 */

const TOL = 1e-5
const NOTE_TEXT = 'Probe note text.'

interface CaseSpec {
  id: string
  photos: number
  music: string
  note: boolean
  aspects: number[]
}

interface RefRect {
  x: number
  y: number
  w: number
  h: number
}

interface RefTile {
  photoId: string
  shape: string
  rect: RefRect
  zooms: number[]
}

interface RefNote {
  state: string
  rect: RefRect
}

interface RefMark {
  role: string
  rect: RefRect
  size: number
  orientation: string
  align: string
  italic: boolean
  weight: number
  font: string
}

interface PlanSpec {
  branch: string
  tiles: RefTile[]
  note: RefNote | null
  prov: RefMark[]
  decor: RefDecor[]
}

interface RefDecor {
  kind: string
  rect: RefRect
}

function rect(text: string): RefRect {
  const parts = text.split(',').map(Number)
  return { x: parts[0], y: parts[1], w: parts[2], h: parts[3] }
}

function parseTiles(text: string): RefTile[] {
  if (text === '') return []
  return text.split(';').map((entry) => {
    const fields = entry.split(':')
    return {
      photoId: fields[0],
      shape: fields[1],
      rect: rect(fields[2]),
      zooms: fields[3] === undefined || fields[3] === '' ? [1] : fields[3].split('|').map(Number),
    }
  })
}

function parseProv(text: string): RefMark[] {
  if (text === '') return []
  return text.split(';').map((entry) => {
    const fields = entry.split(':')
    return {
      role: fields[0],
      rect: rect(fields[1]),
      size: Number(fields[2]),
      orientation: fields[3],
      align: fields[4],
      italic: fields[5] === '1',
      weight: Number(fields[6]),
      font: fields[7],
    }
  })
}

function parseDecor(text: string): RefDecor[] {
  if (text === '') return []
  return text.split(';').map((entry) => {
    const fields = entry.split(':')
    return { kind: fields[0], rect: rect(fields[1]) }
  })
}

interface Fixture {
  cases: CaseSpec[]
  apps: Map<string, { ok: boolean }>
  plans: Map<string, PlanSpec[]>
}

function loadFixture(): Fixture {
  const cases: CaseSpec[] = []
  const apps = new Map<string, { ok: boolean }>()
  const plans = new Map<string, PlanSpec[]>()
  let current = ''
  for (const raw of reference.split('\n')) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    if (line === '' || line.startsWith('#')) continue
    const fields = line.split('\t')
    if (fields[0] === 'CASE') {
      current = fields[1]
      cases.push({
        id: fields[1],
        photos: Number(fields[2]),
        music: fields[3],
        note: fields[4] === '1',
        aspects: fields[6].split(',').map(Number),
      })
    } else if (fields[0] === 'APP') {
      apps.set(`${current}:${fields[1]}`, { ok: fields[2] === '1' })
    } else if (fields[0] === 'PLAN') {
      const note = fields[6] === '-' ? null : (() => {
        const parts = fields[6].split(':')
        return { state: parts[0], rect: rect(parts[1]) }
      })()
      const list = plans.get(current) ?? []
      list.push({ branch: fields[2], tiles: parseTiles(fields[4]), note, prov: parseProv(fields[10] ?? ''), decor: parseDecor(fields[12] ?? '') })
      plans.set(current, list)
    }
  }
  return { cases, apps, plans }
}

function toInput(spec: CaseSpec): Grammar5Input {
  const photos = spec.aspects.map((aspect, i) => ({ id: `p${i}`, width: aspect * 1000, height: 1000 }))
  const music = spec.music === 'none' ? null : { artwork: spec.music === 'cover' }
  return { id: spec.id, sender: 'T151', photos, note: spec.note ? NOTE_TEXT : null, music }
}

function near(actual: number, expected: number, label: string): void {
  expect(Math.abs(actual - expected) <= TOL, `${label}: got ${actual}, want ${expected}`).toBe(true)
}

function checkRect(actual: { x: number; y: number; width: number; height: number }, expected: RefRect, label: string): void {
  near(actual.x, expected.x, `${label}.x`)
  near(actual.y, expected.y, `${label}.y`)
  near(actual.width, expected.w, `${label}.w`)
  near(actual.height, expected.h, `${label}.h`)
}

function checkPlan(expected: PlanSpec, actual: Grammar5Plan, label: string): void {
  expect(actual.branch, `${label} branch`).toBe(expected.branch)
  expect(actual.tiles.length, `${label} tile count`).toBe(expected.tiles.length)
  expected.tiles.forEach((tile, j) => {
    const got = actual.tiles[j]
    expect(got.photoId, `${label} tile[${j}].photoId`).toBe(tile.photoId)
    expect(got.shape, `${label} tile[${j}].shape`).toBe(tile.shape)
    checkRect(got.rect, tile.rect, `${label} tile[${j}]`)
    expect(got.zooms.length, `${label} tile[${j}].zooms`).toBe(tile.zooms.length)
    tile.zooms.forEach((zoom, z) => near(got.zooms[z], zoom, `${label} tile[${j}].zoom[${z}]`))
  })
  if (expected.note === null) {
    expect(actual.note, `${label} note must be null`).toBeNull()
  } else {
    expect(actual.note, `${label} note missing`).not.toBeNull()
    expect(actual.note?.state, `${label} note.state`).toBe(expected.note.state)
    checkRect(actual.note?.rect ?? { x: NaN, y: NaN, width: NaN, height: NaN }, expected.note.rect, `${label} note`)
  }
  expect(actual.music, `${label} music must be null`).toBeNull()
  expect(actual.provenance.length, `${label} prov count`).toBe(expected.prov.length)
  expected.prov.forEach((mark, j) => {
    const got = actual.provenance[j]
    expect(got.role, `${label} prov[${j}].role`).toBe(mark.role)
    checkRect(got.rect, mark.rect, `${label} prov[${j}]`)
    near(got.size, mark.size, `${label} prov[${j}].size`)
    expect(got.orientation, `${label} prov[${j}].orientation`).toBe(mark.orientation)
    expect(got.align, `${label} prov[${j}].align`).toBe(mark.align)
    expect(got.italic, `${label} prov[${j}].italic`).toBe(mark.italic)
    expect(got.weight, `${label} prov[${j}].weight`).toBe(mark.weight)
    expect(got.font, `${label} prov[${j}].font`).toBe(mark.font)
  })
  expect(actual.decor.length, `${label} decor count`).toBe(expected.decor.length)
}

function rejected(input: Grammar5Input): void {
  try {
    grammar5Plans(input)
  } catch (error) {
    expect(error).toBeInstanceOf(CollageError)
    expect(error).toMatchObject({ code: 'REJECTED' })
    return
  }
  expect.unreachable('grammar5Plans must refuse unsupported input')
}

describe('grammar-5 geometry golden parity', () => {
  it('compares every in-scope reference plan tile-by-tile', () => {
    const fx = loadFixture()
    expect(fx.cases.length).toBe(30)
    let parityCases = 0
    let parityPlans = 0
    let refusedCases = 0
    for (const spec of fx.cases) {
      const app = fx.apps.get(`${spec.id}:5`)
      expect(app, `APP 5 for ${spec.id}`).toEqual({ ok: true })
      const input = toInput(spec)
      const actualApp = grammar5Applicability(input)
      expect(actualApp.ok, `applicability ${spec.id}`).toBe(true)
      expect(actualApp.reasons, `reasons ${spec.id}`).toEqual([])
      if (spec.music !== 'none') {
        rejected(input)
        refusedCases += 1
        continue
      }
      const state = grammar5NoteState(input, stubTextMeasure)
      const actual = grammar5Plans(input, state)
      const expected = fx.plans.get(spec.id) ?? []
      expect(actual.length, `plan count ${spec.id}`).toBe(expected.length)
      expect(actual.length > 0, `non-empty ${spec.id}`).toBe(true)
      expected.forEach((plan, i) => checkPlan(plan, actual[i], `${spec.id} #${i}`))
      for (let i = 0; i < actual.length; i++) {
        const plan = actual[i]
        const refBelowFloor = expected[i].tiles.some(
          (tile) => Math.min(tile.rect.w, tile.rect.h) < 84 || tile.rect.w * tile.rect.h < 9500,
        )
        const assignment = grammar5SourceOrderAssignment(plan)
        // The gate must agree with the independent golden geometry:
        // below-floor reference tiles are infeasible, the rest read out
        // in authored photo order.
        expect(assignment.feasible, `${spec.id} ${plan.branch} readability`).toBe(!refBelowFloor)
        if (assignment.feasible) {
          expect(assignment.readingOrder).toEqual(plan.tiles.map((tile) => tile.photoId))
        } else {
          expect(assignment.readingOrder).toEqual([])
        }
      }
      parityCases += 1
      parityPlans += expected.length
    }
    expect(parityCases).toBe(10)
    expect(refusedCases).toBe(20)
    expect(parityPlans).toBeGreaterThan(100)
  })

  it('is deterministic for the same input', () => {
    const fx = loadFixture()
    const spec = fx.cases.find((entry) => entry.id === 'n5_mnone_note1_f0_a1.5-1-0.6667-1.2-0.8')
    expect(spec).toBeDefined()
    const input = toInput(spec ?? fx.cases[0])
    const first = JSON.stringify(grammar5Plans(input))
    for (let i = 0; i < 10; i++) {
      expect(JSON.stringify(grammar5Plans(input))).toBe(first)
    }
  })

  it('refuses unsupported photo counts, music, and dimensions with typed errors', () => {
    const base: Grammar5Input = {
      id: 'x',
      sender: 's',
      photos: [
        { id: 'p0', width: 1000, height: 1000 },
        { id: 'p1', width: 1000, height: 1000 },
        { id: 'p2', width: 1000, height: 1000 },
      ],
      note: null,
    }
    rejected({ ...base, photos: base.photos.slice(0, 2) })
    rejected({ ...base, photos: [...base.photos, ...base.photos] })
    rejected({ ...base, photos: [] })
    rejected({ ...base, photos: base.photos.map((p, i) => (i === 0 ? { ...p, width: 0 } : p)) })
    rejected({ ...base, photos: base.photos.map((p, i) => (i === 1 ? { ...p, height: -4 } : p)) })
    rejected({ ...base, photos: base.photos.map((p, i) => (i === 2 ? { ...p, width: NaN } : p)) })
    rejected({ ...base, photos: base.photos.map((p, i) => (i === 0 ? { ...p, height: Infinity } : p)) })
    rejected({ ...base, music: { artwork: false } })
    rejected({ ...base, music: { artwork: true } })
    rejected({ ...base, id: '  ' })
    rejected({ ...base, sender: '' })
    rejected({ ...base, note: undefined as unknown as null })
    rejected({ ...base, photos: base.photos.map((p, i) => (i === 0 ? { ...p, id: '' } : p)) })
  })

  it('emits the frozen count reason text for unsupported counts', () => {
    const two = {
      id: 'x',
      sender: 's',
      photos: [
        { id: 'p0', width: 1000, height: 1000 },
        { id: 'p1', width: 1000, height: 1000 },
      ],
      note: null,
    }
    expect(grammar5Applicability(two)).toEqual({ ok: false, reasons: ['Supports 3 / 4 / 5 photos; input has 2'] })
  })

  it('flags the readability floor without touching geometry', () => {
    const tiny = {
      branch: 'editorial-tiny',
      tiles: [{ photoId: 'p0', rect: { x: 0, y: 0, width: 50, height: 50 }, shape: 'rect', zooms: [1] }],
      note: null,
      music: null,
      provenance: [],
      decor: [],
    }
    expect(grammar5SourceOrderAssignment(tiny)).toEqual({ feasible: false, readingOrder: [] })
  })
})
