import { writeFileSync } from 'node:fs'
import { describe, it } from 'vitest'
import { ACTIVE_GRAMMAR_IDS, applicability, grammarDef, noteState, plans, stubTextMeasure, type PlanInput, type PlanRect } from '../src/collage/planGrammars'
import reference from './v5-plans-reference.txt?raw'

/**
 * Regenerates the shared plan reference from this planner. Android's
 * `FrozenGrammarPlansTest` and the site's `plan-grammars.test.ts` both read
 * it, so a layout change is made once here and checked on both platforms.
 * Rows without a song must come out byte-identical to the frozen runtime's.
 *
 *   GEN_REFERENCES=1 npx vitest run test/plan-reference.gen.test.ts
 */

function num(value: number): string {
  return String(Number(value.toFixed(6)))
}

function box(r: PlanRect): string {
  return [r.x, r.y, r.width, r.height].map(num).join(',')
}

describe('plan reference', () => {
  it.skipIf(!process.env['GEN_REFERENCES'])('writes v5-plans-reference.txt from the planner', () => {
    const out: string[] = [
      '# All-family plans reference (line format)',
      '# Rows without a song: the frozen V5 runtime (remanence_capsule_engine_v5_freeze_2026-09-25).',
      '# Rows with a song: web/src/collage/planGrammars.ts after the 9 November 2026 song band rules',
      '# (64 px plate, 56 px layout-6 spine), written by web/test/plan-reference.gen.test.ts.',
      '# header: CASE <id> <n> <music> <note> <faces> <aspects>',
      '#         APP <gid> <applicable> <reasons | ->',
      '#         PLAN <gid> <branch> TILES <...> NOTE <...> MUSIC <...> PROV <...> DECOR <...>',
      '# active families: 5,6,11,12,17,18,20,21,22,23,24,25,26,27',
    ]
    for (const line of reference.split('\n')) {
      const f = line.split('\t')
      if (f[0] !== 'CASE') continue
      out.push(line)
      const input: PlanInput = {
        id: f[1]!,
        sender: 'T151',
        photos: f[6]!.split(',').map((aspect, i) => ({ id: `p${i}`, width: Number(aspect) * 1000, height: 1000, hasProminentFace: f[5] === '1' })),
        note: f[4] === '1' ? 'Probe note text.' : null,
        music: f[3] === 'none' ? null : { artwork: f[3] === 'cover' },
      }
      for (const gid of ACTIVE_GRAMMAR_IDS) {
        const g = grammarDef(gid)
        const app = applicability(input, g)
        out.push(['APP', gid, app.ok ? 1 : 0, app.reasons.length === 0 ? '-' : app.reasons.join(' | ')].join('\t'))
        if (!app.ok) continue
        for (const plan of plans(input, g, noteState(input, g, stubTextMeasure), stubTextMeasure)) {
          out.push([
            'PLAN', gid, plan.branch,
            'TILES', plan.tiles.map((t) => `${t.photoId}:${t.shape}:${box(t.rect)}:${t.zooms.map(num).join('|')}`).join(';'),
            'NOTE', plan.note === null ? '-' : `${plan.note.state}:${box(plan.note.rect)}`,
            'MUSIC', plan.music === null ? '-' : `${plan.music.mode}:${box(plan.music.rect)}`,
            'PROV', plan.provenance.map((m) => `${m.role}:${box(m.rect)}:${num(m.size)}:${m.orientation}:${m.align}:${m.italic ? 1 : 0}:${m.weight}:${m.font}`).join(';'),
            'DECOR', plan.decor.map((d) => `${d.kind}:${box(d.rect)}`).join(';'),
          ].join('\t'))
        }
      }
    }
    writeFileSync(new URL('./v5-plans-reference.txt', import.meta.url), `${out.join('\n')}\n`)
  })
})
