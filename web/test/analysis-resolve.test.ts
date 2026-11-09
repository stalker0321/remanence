import { describe, expect, it } from 'vitest'
import { GRID_SIZE, toCropWindow, type CollageAnalysis } from '../src/collage/analysis/cropSolver'
import { assign, assignmentOk, isObjectPhoto, matrixCell } from '../src/collage/analysis/resolve'
import { grammarDef, noteState, plans, stubTextMeasure, type PlanInput } from '../src/collage/planGrammars'
import analysisText from './v5-android-analysis-reference.txt?raw'
import resolveText from './v5-resolve-reference.txt?raw'

/**
 * The same differential Android runs (`AndroidMatrixFrozenDifferentialTest`
 * and the resolve BRANCH rows): real per-photo analyses from
 * `v5-android-analysis-reference.txt`, the frozen crop/cost matrix and the
 * complete-safe matching from `v5-resolve-reference.txt`. Every (tile,
 * photo) cell must agree on feasibility and cost within 1e-4, and every
 * branch on its matching.
 */

function analyses(): Map<string, CollageAnalysis> {
  const out = new Map<string, CollageAnalysis>()
  let current: { key: string; analysis: CollageAnalysis; maps: Map<string, Float64Array> } | null = null
  const flush = () => {
    if (current === null) return
    const m = current.maps
    out.set(current.key, {
      ...current.analysis,
      importance: m.get('importance')!,
      baselineField: m.get('baselineField')!,
      objectField: m.get('objectField')!,
      saliency: m.get('saliency')!,
      edgeEnergy: m.get('edgeEnergy')!,
      structureField: m.get('structureField')!,
    })
  }
  for (const raw of analysisText.split('\n')) {
    const line = raw.replace(/\r$/, '')
    if (line.trim() === '' || line.startsWith('#')) continue
    const f = line.split('\t')
    const box = (i: number) => ({ x: Number(f[i]), y: Number(f[i + 1]), width: Number(f[i + 2]), height: Number(f[i + 3]) })
    if (f[0] === 'A') {
      flush()
      const empty = new Float64Array(0)
      current = {
        key: `${f[1]}|${f[2]}`,
        maps: new Map(),
        analysis: {
          width: Number(f[3]), height: Number(f[4]), gridSize: Number(f[5]), faces: [], objects: [], protectedRegions: [],
          importance: empty, baselineField: empty, objectField: empty, saliency: empty, edgeEnergy: empty, structureField: empty,
        },
      }
    } else if (f[0] === 'F') current!.analysis.faces.push({ box: box(3), confidence: Number(f[7]) })
    else if (f[0] === 'O') current!.analysis.objects.push({ box: box(3), confidence: Number(f[7]), classId: Number(f[8]) })
    else if (f[0] === 'R') current!.analysis.protectedRegions.push({ kind: f[3]!, box: box(4), confidence: Number(f[8]), critical: f[9] === '1' })
    else if (f[0] === 'M') current!.maps.set(f[3]!, Float64Array.from(f[4]!.split(',').map(Number)))
  }
  flush()
  return out
}

interface Fixture {
  id: string
  note: boolean
  music: boolean
  photos: Array<{ id: string; width: number; height: number; face: boolean }>
  grammars: Map<number, { applicable: boolean; matrices: Map<number, Array<Array<number | null>>>; branches: Map<number, string[]> }>
}

function fixtures(): Fixture[] {
  const out: Fixture[] = []
  let fx: Fixture | null = null
  let gid = 0
  for (const raw of resolveText.split('\n')) {
    const line = raw.replace(/\r$/, '')
    if (line.trim() === '' || line.startsWith('#')) continue
    const f = line.split('\t')
    if (f[0] === 'FIXTURE') {
      fx = { id: f[1]!, note: f[3] === '1', music: f[4] === '1', photos: [], grammars: new Map() }
      out.push(fx)
    } else if (f[0] === 'PHOTO') fx!.photos.push({ id: f[1]!, width: Number(f[2]), height: Number(f[3]), face: f[4] === '1' })
    else if (f[0] === 'GRAMMAR') {
      gid = Number(f[1])
      fx!.grammars.set(gid, { applicable: f[2] === '1', matrices: new Map(), branches: new Map() })
    } else if (f[0] === 'MATRIX') {
      const n = fx!.photos.length
      fx!.grammars.get(gid)!.matrices.set(Number(f[1]), f[2]!.split(';').map((row) => {
        const cells: Array<number | null> = Array.from({ length: n }, () => null)
        if (row !== '') for (const entry of row.split('|')) {
          const p = entry.split(',')
          if (p[1] === '1') cells[Number(p[0])] = Number(p[2])
        }
        return cells
      }))
    } else if (f[0] === 'BRANCH' && f[1] === 'safe') {
      fx!.grammars.get(gid)!.branches.set(Number(f[2]), f)
    }
  }
  return out
}

describe('analysis: crop matrix and matching against the frozen reference', () => {
  it('matches every matrix cell and every complete-safe matching', () => {
    const all = analyses()
    let compared = 0
    let feasible = 0
    let branches = 0
    // The two song fixtures record the frozen 48 px band; the 9 November 2026 band
    // rules moved those tiles, and the shared plan reference covers that geometry.
    for (const fx of fixtures().filter((entry) => !entry.music)) {
      const photoAnalyses = fx.photos.map((photo) => all.get(`${fx.id}|${photo.id}`))
      expect(photoAnalyses.every((analysis) => analysis !== undefined), `analysis for ${fx.id}`).toBe(true)
      const anyObject = photoAnalyses.some((analysis) => isObjectPhoto(analysis!))
      const d: PlanInput = {
        id: fx.id,
        sender: 'T151',
        photos: fx.photos.map((photo) => ({ id: photo.id, width: photo.width, height: photo.height, hasProminentFace: photo.face })),
        note: fx.note ? 'note' : null,
        music: fx.music ? { artwork: false } : null,
      }
      for (const [gid, g] of fx.grammars) {
        if (!g.applicable) continue
        const def = grammarDef(gid)
        plans(d, def, noteState(d, def, stubTextMeasure), stubTextMeasure).forEach((plan, index) => {
          const expected = g.matrices.get(index)
          expect(expected, `${fx.id} g${gid} #${index}`).toBeDefined()
          const matrix = plan.tiles.map((tile, slot) => fx.photos.map((_photo, j) => {
            const cell = matrixCell(photoAnalyses[j]!, anyObject, {
              slotWidth: tile.rect.width, slotHeight: tile.rect.height, shape: tile.shape, zooms: tile.zooms, assignmentRole: tile.assignmentRole,
            })
            const want = expected![slot]![j]
            const label = `${fx.id} g${gid} #${index} ${plan.branch} slot${slot} photo${j}`
            if (cell.feasible !== (want !== null)) expect.fail(`feasibility ${label}`)
            if (want !== null && want !== undefined) {
              feasible++
              if (Math.abs(cell.cost! - want) > 1e-4) expect.fail(`cost ${label}: ${cell.cost} vs ${want}`)
            }
            compared++
            return cell.feasible ? cell.cost! : null
          }))
          const row = g.branches.get(index)
          if (row !== undefined) {
            const result = assign(plan, fx.photos.map((photo) => photo.id), matrix, false)
            const failure = row[4] === '' || row[4] === '-' ? null : row[4]
            if (failure === null && (row[5] === '' || row[5] === '-')) {
              expect(result.earlyReadability, `${fx.id} g${gid} ${plan.branch} unreadable`).toBe(true)
            } else if (failure === null) {
              expect(assignmentOk(result), `${fx.id} g${gid} ${plan.branch} solved`).toBe(true)
              expect(result.compositionOrder.join(','), `${fx.id} g${gid} ${plan.branch} order`).toBe(row[6])
              branches++
            } else if (failure !== null) {
              expect(assignmentOk(result), `${fx.id} g${gid} ${plan.branch} fails`).toBe(false)
            }
          }
        })
      }
    }
    expect(compared).toBeGreaterThan(1000)
    expect(feasible).toBeGreaterThan(100)
    expect(branches).toBeGreaterThan(40)
  }, 60_000)

  it('quantizes a crop like Android, clamping the right and bottom edges', () => {
    expect(toCropWindow({ x: 0.12345, y: 0, width: 0.87656, height: 1 })).toEqual({ x: 1235, y: 0, width: 8765, height: 10_000 })
    expect(GRID_SIZE).toBe(48)
  })
})
