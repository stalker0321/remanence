import { describe, expect, it } from 'vitest'
import { CollageError } from '../src/collage/errors'
import {
  ACTIVE_GRAMMAR_IDS,
  applicability,
  grammarDef,
  noteState,
  plans,
  rankBuckets,
  sourceOrderAssignment,
  stubTextMeasure,
  validatePlan,
  type Plan,
  type PlanInput,
} from '../src/collage/planGrammars'
import reference from './v5-plans-reference.txt?raw'

/**
 * Golden parity for the all-family geometry port.
 *
 * Expected geometry comes ONLY from `v5-plans-reference.txt`, a copy of
 * Android `v5_plans_reference.txt` recorded by the frozen `ref_plans.js`
 * runtime (the same file Android `FrozenGrammarPlansTest` reads). Every
 * case, every family and every rectangle is compared, applicability and
 * its reasons included.
 */

const TOL = 1e-5
const NOTE_TEXT = 'Probe note text.'

interface CaseSpec {
  id: string
  music: string
  note: boolean
  faces: boolean
  aspects: number[]
}

interface RefRect { x: number; y: number; w: number; h: number }

interface PlanSpec {
  branch: string
  tiles: Array<{ photoId: string; shape: string; rect: RefRect; zooms: number[] }>
  note: { state: string; rect: RefRect } | null
  music: { state: string; rect: RefRect } | null
  prov: Array<{ role: string; rect: RefRect; size: number; orientation: string; align: string; italic: boolean; weight: number; font: string }>
  decor: Array<{ kind: string; rect: RefRect }>
}

function rect(text: string): RefRect {
  const [x, y, w, h] = text.split(',').map(Number)
  return { x: x!, y: y!, w: w!, h: h! }
}

function split(text: string | undefined): string[] {
  return text === undefined || text === '' ? [] : text.split(';')
}

function loadFixture(): { cases: CaseSpec[]; apps: Map<string, { ok: boolean; reasons: string[] }>; plans: Map<string, PlanSpec[]> } {
  const cases: CaseSpec[] = []
  const apps = new Map<string, { ok: boolean; reasons: string[] }>()
  const plansByKey = new Map<string, PlanSpec[]>()
  let current = ''
  for (const raw of reference.split('\n')) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    if (line === '' || line.startsWith('#')) continue
    const f = line.split('\t')
    if (f[0] === 'CASE') {
      current = f[1]!
      cases.push({ id: f[1]!, music: f[3]!, note: f[4] === '1', faces: f[5] === '1', aspects: f[6]!.split(',').map(Number) })
    } else if (f[0] === 'APP') {
      apps.set(`${current}:${f[1]}`, { ok: f[2] === '1', reasons: f[3] === '-' ? [] : f[3]!.split(' | ') })
    } else if (f[0] === 'PLAN') {
      const key = `${current}:${f[1]}`
      const part = (text: string) => (text === '-' ? null : { state: text.split(':')[0]!, rect: rect(text.split(':')[1]!) })
      const list = plansByKey.get(key) ?? []
      list.push({
        branch: f[2]!,
        tiles: split(f[4]).map((entry) => {
          const t = entry.split(':')
          return { photoId: t[0]!, shape: t[1]!, rect: rect(t[2]!), zooms: t[3] === undefined || t[3] === '' ? [1] : t[3].split('|').map(Number) }
        }),
        note: part(f[6]!),
        music: part(f[8]!),
        prov: split(f[10]).map((entry) => {
          const m = entry.split(':')
          return { role: m[0]!, rect: rect(m[1]!), size: Number(m[2]), orientation: m[3]!, align: m[4]!, italic: m[5] === '1', weight: Number(m[6]), font: m[7]! }
        }),
        decor: split(f[12]).map((entry) => {
          const m = entry.split(':')
          return { kind: m[0]!, rect: rect(m[1]!) }
        }),
      })
      plansByKey.set(key, list)
    }
  }
  return { cases, apps, plans: plansByKey }
}

function toInput(spec: CaseSpec): PlanInput {
  return {
    id: spec.id,
    sender: 'T151',
    photos: spec.aspects.map((aspect, i) => ({ id: `p${i}`, width: aspect * 1000, height: 1000, hasProminentFace: spec.faces })),
    note: spec.note ? NOTE_TEXT : null,
    music: spec.music === 'none' ? null : { artwork: spec.music === 'cover' },
  }
}

function near(actual: number, expected: number, label: string): void {
  expect(Math.abs(actual - expected) <= TOL, `${label}: got ${actual}, want ${expected}`).toBe(true)
}

function checkRect(actual: { x: number; y: number; width: number; height: number } | undefined, expected: RefRect, label: string): void {
  expect(actual, `${label} missing`).toBeDefined()
  near(actual!.x, expected.x, `${label}.x`)
  near(actual!.y, expected.y, `${label}.y`)
  near(actual!.width, expected.w, `${label}.w`)
  near(actual!.height, expected.h, `${label}.h`)
}

function checkPlan(expected: PlanSpec, actual: Plan, label: string): void {
  expect(actual.branch, `${label} branch`).toBe(expected.branch)
  expect(actual.tiles.length, `${label} tiles`).toBe(expected.tiles.length)
  expected.tiles.forEach((tile, j) => {
    const got = actual.tiles[j]!
    expect(got.photoId, `${label} tile[${j}].photoId`).toBe(tile.photoId)
    expect(got.shape, `${label} tile[${j}].shape`).toBe(tile.shape)
    checkRect(got.rect, tile.rect, `${label} tile[${j}]`)
    expect(got.zooms.length, `${label} tile[${j}].zooms`).toBe(tile.zooms.length)
    tile.zooms.forEach((zoom, z) => near(got.zooms[z]!, zoom, `${label} tile[${j}].zoom[${z}]`))
  })
  if (expected.note === null) expect(actual.note, `${label} note`).toBeNull()
  else {
    expect(actual.note?.state, `${label} note.state`).toBe(expected.note.state)
    checkRect(actual.note?.rect, expected.note.rect, `${label} note`)
  }
  if (expected.music === null) expect(actual.music, `${label} music`).toBeNull()
  else {
    expect(actual.music?.mode, `${label} music.mode`).toBe(expected.music.state)
    checkRect(actual.music?.rect, expected.music.rect, `${label} music`)
  }
  expect(actual.provenance.length, `${label} prov`).toBe(expected.prov.length)
  expected.prov.forEach((mark, j) => {
    const got = actual.provenance[j]!
    expect([got.role, got.orientation, got.align, got.italic, got.weight, got.font], `${label} prov[${j}]`)
      .toEqual([mark.role, mark.orientation, mark.align, mark.italic, mark.weight, mark.font])
    near(got.size, mark.size, `${label} prov[${j}].size`)
    checkRect(got.rect, mark.rect, `${label} prov[${j}]`)
  })
  expect(actual.decor.length, `${label} decor`).toBe(expected.decor.length)
  expected.decor.forEach((decor, j) => {
    expect(actual.decor[j]!.kind, `${label} decor[${j}].kind`).toBe(decor.kind)
    checkRect(actual.decor[j]!.rect, decor.rect, `${label} decor[${j}]`)
  })
}

describe('all-family geometry golden parity', () => {
  it('matches every reference case, family and rectangle', () => {
    const fx = loadFixture()
    expect(fx.cases.length).toBe(30)
    let compared = 0
    const familiesSeen = new Set<number>()
    for (const spec of fx.cases) {
      const input = toInput(spec)
      for (const gid of ACTIVE_GRAMMAR_IDS) {
        const g = grammarDef(gid)
        const app = fx.apps.get(`${spec.id}:${gid}`)
        expect(app, `APP ${gid} for ${spec.id}`).toBeDefined()
        expect(applicability(input, g), `applicability ${spec.id} g${gid}`).toEqual(app)
        const expected = fx.plans.get(`${spec.id}:${gid}`) ?? []
        if (!app!.ok) {
          expect(expected.length, `${spec.id} g${gid} has no plans when inapplicable`).toBe(0)
          continue
        }
        const actual = plans(input, g, noteState(input, g, stubTextMeasure), stubTextMeasure)
        expect(actual.length, `plan count ${spec.id} g${gid}`).toBe(expected.length)
        expected.forEach((plan, i) => checkPlan(plan, actual[i]!, `${spec.id} g${gid} #${i}`))
        actual.forEach((plan) => expect(plan.grammarId).toBe(gid))
        compared += expected.length
        if (expected.length > 0) familiesSeen.add(gid)
      }
    }
    expect([...familiesSeen].sort((a, b) => a - b)).toEqual([...ACTIVE_GRAMMAR_IDS].sort((a, b) => a - b))
    expect(compared).toBeGreaterThan(3000)
  })

  it('is deterministic for the same input', () => {
    const input: PlanInput = {
      id: 'x', sender: 's', note: NOTE_TEXT, music: { artwork: false },
      photos: [1.5, 1, 0.6667, 1.2, 0.8].map((aspect, i) => ({ id: `p${i}`, width: aspect * 1000, height: 1000 })),
    }
    for (const gid of ACTIVE_GRAMMAR_IDS) {
      const g = grammarDef(gid)
      if (!applicability(input, g).ok) continue
      const first = JSON.stringify(plans(input, g))
      expect(JSON.stringify(plans(input, g))).toBe(first)
    }
  })

  it('refuses unsupported input with a typed error', () => {
    const base: PlanInput = { id: 'x', sender: 's', note: null, photos: [0, 1, 2].map((i) => ({ id: `p${i}`, width: 1000, height: 1000 })) }
    for (const bad of [
      { ...base, photos: base.photos.slice(0, 2) },
      { ...base, photos: base.photos.map((p, i) => (i === 0 ? { ...p, width: 0 } : p)) },
      { ...base, id: ' ' },
      { ...base, music: { artwork: 'yes' as unknown as boolean } },
    ]) {
      expect(() => plans(bad, grammarDef(5))).toThrow(CollageError)
    }
  })

  it('validates plans and applies the readability floor', () => {
    const tiny = { tiles: [{ photoId: 'p0', rect: { x: 0, y: 0, width: 50, height: 50 }, shape: 'rect', zooms: [1] }] }
    expect(sourceOrderAssignment(tiny)).toEqual({ feasible: false, readingOrder: [] })
    const overlapping: Plan = {
      grammarId: 5, branch: 'x', note: null, music: null, provenance: [], decor: [],
      tiles: [
        { photoId: 'p0', rect: { x: 0, y: 0, width: 200, height: 200 }, shape: 'rect', zooms: [1] },
        { photoId: 'p1', rect: { x: 100, y: 100, width: 200, height: 200 }, shape: 'rect', zooms: [1] },
      ],
    }
    expect(validatePlan(overlapping)).toBe(false)
    expect(validatePlan({ ...overlapping, tiles: overlapping.tiles.slice(0, 1) })).toBe(true)
  })

  it('ranks grammar breadth first, two per grammar, twelve in all', () => {
    const ranked = rankBuckets<string>([[5, ['a', 'b', 'c']], [6, ['d']], [11, ['e', 'f']]])
    expect(ranked.map((entry) => entry.item)).toEqual(['a', 'd', 'e', 'b', 'f'])
    const many = rankBuckets<number>(Array.from({ length: 10 }, (_, i) => [i, [i * 10, i * 10 + 1]] as [number, number[]]))
    expect(many.length).toBe(12)
  })
})
