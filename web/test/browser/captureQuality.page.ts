import {
  createCaptureQualityMeter,
  evaluateCaptureQualityGate,
  measureCaptureQuality,
  type CaptureQualitySignals,
} from '../../src/scan/captureQuality'
import type { QualityRawCv, QualityRawMat } from '../../src/scan/opencvQualityPort'
import { loadOpenCvModule } from '../../src/scan/opencvRuntime'

interface Assertion {
  readonly name: string
  readonly ok: boolean
  readonly detail?: string
}

interface CleanupRecord {
  readonly label: string
  deleted: boolean
  zeroBeforeDelete: boolean | null
  isDeletedAfter: boolean | null
}

interface Fixture {
  readonly label: string
  readonly width: number
  readonly height: number
  readonly values: readonly number[]
  readonly verify: (signals: CaptureQualitySignals) => boolean
}

function rgbaFromGray(values: readonly number[]): Uint8Array {
  const out = new Uint8Array(values.length * 4)
  for (let index = 0; index < values.length; index++) {
    const value = values[index]!
    out[index * 4] = value
    out[index * 4 + 1] = value
    out[index * 4 + 2] = value
    out[index * 4 + 3] = 255
  }
  return out
}

function brightL(): number[] {
  const values = new Array<number>(25).fill(128)
  values[0] = 250
  values[1] = 250
  values[5] = 250
  values[24] = 250
  return values
}

function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  for (let index = 0; index < a.length; index++) if (a[index] !== b[index]) return false
  return true
}

function viewsZero(mat: QualityRawMat): boolean {
  const views: Array<ArrayLike<number>> = []
  for (const key of ['data', 'data64F', 'data32S', 'data32F'] as const) {
    try {
      const view = mat[key]
      if (view && typeof view.length === 'number') views.push(view)
    } catch {
      /* unavailable view; ignore in diagnostic */
    }
  }
  return (
    views.length > 0 &&
    views.every((view) => {
      for (let index = 0; index < view.length; index++) if (view[index] !== 0) return false
      return true
    })
  )
}

/**
 * Wraps the REAL pinned cv so every Mat (constructor + `mul` return) records
 * whether its actual raw buffer is zero immediately before `delete`. The real
 * Mat instances stay real, so the production ops still run natively.
 */
function trackedRuntime(realCv: QualityRawCv, records: CleanupRecord[]): QualityRawCv {
  let counter = 0
  const track = (mat: QualityRawMat, label?: string): QualityRawMat => {
    const record: CleanupRecord = {
      label: label ?? `mat${++counter}`,
      deleted: false,
      zeroBeforeDelete: null,
      isDeletedAfter: null,
    }
    records.push(record)
    const originalDelete = mat.delete.bind(mat)
    try {
      mat.delete = (): void => {
        try {
          record.zeroBeforeDelete = viewsZero(mat)
        } catch {
          record.zeroBeforeDelete = false
        }
        originalDelete()
        record.deleted = true
        try {
          const probe = mat as unknown as { isDeleted?: () => boolean }
          record.isDeletedAfter = typeof probe.isDeleted === 'function' ? probe.isDeleted() : null
        } catch {
          record.isDeletedAfter = null
        }
      }
    } catch {
      /* the harness will surface a missing tracking proof */
    }
    if (typeof mat.mul === 'function') {
      const originalMul = mat.mul.bind(mat)
      try {
        mat.mul = (other: QualityRawMat, scale: number): QualityRawMat => {
          const result = originalMul(other, scale)
          return result ? track(result, `${record.label}.squared`) : result
        }
      } catch {
        /* handled by the tracking assertion */
      }
    }
    return mat
  }

  const RealMat = realCv.Mat
  const TrackedMat = function (rows?: number, cols?: number, type?: number) {
    return track(new RealMat(rows, cols, type))
  } as unknown as QualityRawCv['Mat']
  const tracked = Object.create(realCv) as QualityRawCv
  for (const key of ['cvtColor', 'Laplacian', 'mean', 'threshold', 'connectedComponentsWithStats'] as const) {
    const fn = (realCv as unknown as Record<string, unknown>)[key]
    if (typeof fn === 'function') {
      ;(tracked as unknown as Record<string, unknown>)[key] = fn.bind(realCv)
    }
  }
  tracked.Mat = TrackedMat
  return tracked
}

async function run(): Promise<void> {
  const assertions: Assertion[] = []
  const failures: string[] = []
  const cleanup: CleanupRecord[] = []
  const report: Record<string, unknown> = {}
  const check = (name: string, ok: boolean, detail?: string): void => {
    assertions.push(detail === undefined ? { name, ok } : { name, ok, detail })
    if (!ok) failures.push(name)
  }

  try {
    const realCv = (await loadOpenCvModule()) as unknown as QualityRawCv
    const cv = trackedRuntime(realCv, cleanup)
    const meter = createCaptureQualityMeter(cv)

    const measured = new Map<string, CaptureQualitySignals>()
    const fixtures: Fixture[] = [
      {
        label: 'uniform128',
        width: 4,
        height: 4,
        values: new Array<number>(16).fill(128),
        verify: (s) =>
          Math.abs(s.laplacianVariance) <= 1e-9 &&
          s.nearBlackFraction === 0 &&
          s.clippedWhiteFraction === 0 &&
          s.largestGlareFraction === 0,
      },
      {
        label: 'brightL3+isolated1',
        width: 5,
        height: 5,
        values: brightL(),
        verify: (s) =>
          Number.isFinite(s.laplacianVariance) &&
          s.laplacianVariance > 0 &&
          s.nearBlackFraction === 0 &&
          s.clippedWhiteFraction === 0 &&
          Math.abs(s.largestGlareFraction - 3 / 25) <= 1e-12,
      },
      {
        label: 'glare-threshold-245',
        width: 1,
        height: 1,
        values: [245],
        verify: (s) => s.largestGlareFraction === 0,
      },
      {
        label: 'glare-threshold-246',
        width: 1,
        height: 1,
        values: [246],
        verify: (s) => s.largestGlareFraction === 1,
      },
      {
        label: 'nearblack-40-vs-41',
        width: 2,
        height: 1,
        values: [40, 41],
        verify: (s) => s.nearBlackFraction === 0.5 && s.clippedWhiteFraction === 0,
      },
      {
        label: 'clipped-251',
        width: 1,
        height: 1,
        values: [251],
        verify: (s) => s.clippedWhiteFraction === 0 && s.largestGlareFraction === 1,
      },
      {
        label: 'clipped-252',
        width: 1,
        height: 1,
        values: [252],
        verify: (s) => s.clippedWhiteFraction === 1 && s.largestGlareFraction === 1,
      },
    ]

    for (const fixture of fixtures) {
      const rgba = rgbaFromGray(fixture.values)
      const before = rgba.slice()
      const signals = meter.measure(rgba, fixture.width, fixture.height)
      measured.set(fixture.label, signals)
      report[fixture.label] = { width: fixture.width, height: fixture.height, values: fixture.values, signals }
      check(`${fixture.label} native semantics`, fixture.verify(signals), JSON.stringify(signals))
      check(`${fixture.label} caller RGBA unchanged`, equalBytes(rgba, before))
      check(
        `${fixture.label} returns plain scalars`,
        Object.values(signals).every((value) => typeof value === 'number' && Number.isFinite(value)),
      )
    }

    check('tracked handle count', cleanup.length === fixtures.length * 8, `got ${cleanup.length}`)
    check(
      'every handle deleted',
      cleanup.every((record) => record.deleted === true),
    )
    check(
      'every actual raw buffer zero before delete',
      cleanup.every((record) => record.zeroBeforeDelete === true),
    )
    check(
      'every handle reports isDeleted after',
      cleanup.every((record) => record.isDeletedAfter !== false),
    )
    report.cleanup = cleanup

    // Default async wrapper over the shared accepted loader (real pinned module).
    const uniform = fixtures[0]!
    const wrapped = await measureCaptureQuality(rgbaFromGray(uniform.values), uniform.width, uniform.height)
    report.asyncWrapper = wrapped
    check('async default loader uniform variance 0', Math.abs(wrapped.laplacianVariance) <= 1e-9)
    check('async default loader uniform glare 0', wrapped.largestGlareFraction === 0)

    // Pure advisory gate smoke: a uniform frame is legitimately TOO_BLURRY
    // (variance 0 < active 110); a sharp measured frame passes the same gates.
    const blurredReasons = evaluateCaptureQualityGate({
      signals: wrapped,
      detectedAreaRatio: 0.5,
      rectangularity: 1,
    })
    report.advisoryReasons = { blurred: blurredReasons }
    check('pure gate advisory flags the uniform/blurred measurement', blurredReasons.includes('TOO_BLURRY'))
    const sharpReasons = evaluateCaptureQualityGate({
      signals: measured.get('brightL3+isolated1')!,
      detectedAreaRatio: 0.5,
      rectangularity: 1,
    })
    report.advisoryReasons = { ...(report.advisoryReasons as object), sharp: sharpReasons }
    check('pure gate advisory passes a sharp measurement', sharpReasons.length === 0)
  } catch (error) {
    failures.push(String(error))
  } finally {
    ;(window as unknown as { __captureQuality: unknown }).__captureQuality = { assertions, failures, report }
    let status = document.querySelector('#capture-quality-status')
    if (!status) {
      status = document.createElement('div')
      status.id = 'capture-quality-status'
      document.body.appendChild(status)
    }
    status.textContent = failures.length === 0 ? 'done' : 'failed'
  }
}

void run()
