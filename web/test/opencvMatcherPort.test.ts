/**
 * T330 R2 unit gate: the pinned matcher PORT itself (not the fake matcher
 * above it). A bounded fake `cv` module records Mat/matcher/nested-row handle
 * lifecycles so the R2 cleanup invariants are exercised directly:
 * - every DMatchVector row returned by parent.get(anchor) is released
 *   (success AND validation throw; parent deletion alone is NOT enough —
 *   root real-WASM proof);
 * - a Mat is owned from the constructor: any later shape/getter/view.set
 *   failure wipes already-copied data and releases it (no bare delete).
 * These are lifecycle/contract tests over the port's own logic, NOT actual
 * WASM acceptance (the connected browser proof runs the real runtime).
 */
import { afterEach, describe, expect, it } from 'vitest'
import {
  createPinnedMatcherRuntime,
  PinnedMatcherError,
} from '../src/scan/opencvMatcherPort'
import type { RawCv } from '../src/scan/opencvRuntime'

interface FakeRow {
  deleted: boolean
  size(): number
  get(index: number): { queryIdx: number; trainIdx: number; distance: number }
  delete(): void
}

interface FakeParent {
  deleted: boolean
  size(): number
  get(index: number): FakeRow
  delete(): void
}

interface FakeMat {
  deleted: boolean
  rows: number
  cols: number
  data32F: Float32Array
  type(): number
  delete(): void
}

interface FakeCvHarness {
  cv: RawCv
  mats: FakeMat[]
  parents: FakeParent[]
  rowsCreated: FakeRow[]
  copyCalls: number
}

const originalSet = Float32Array.prototype.set
let copyCalls = 0

afterEach(() => {
  Float32Array.prototype.set = originalSet
  copyCalls = 0
})

function makeFakeCv(options: {
  forwardRows: Array<Array<{ queryIdx: number; trainIdx: number; distance: number }>>
  reverseRows: Array<Array<{ queryIdx: number; trainIdx: number; distance: number }>>
}): FakeCvHarness {
  const harness: FakeCvHarness = {
    cv: undefined as unknown as RawCv,
    mats: [],
    parents: [],
    rowsCreated: [],
    copyCalls: 0,
  }
  let knnCalls = 0
  const makeParent = (rowsSpec: Array<Array<{ queryIdx: number; trainIdx: number; distance: number }>>): FakeParent => {
    // Rows materialize (and are registered) ONLY when the port calls
    // parent.get — the ownership invariant covers exactly those obtained
    // handles, mirroring embind's returned-child semantics.
    const rows: Array<FakeRow | null> = rowsSpec.map(() => null)
    const parent: FakeParent = {
      deleted: false,
      size: () => rowsSpec.length,
      get: (index: number) => {
        if (!rows[index]) {
          const spec = rowsSpec[index]!
          const row: FakeRow = {
            deleted: false,
            size: () => spec.length,
            get: (i: number) => spec[i]!,
            delete() { row.deleted = true },
          }
          rows[index] = row
          harness.rowsCreated.push(row)
        }
        return rows[index]!
      },
      delete() { parent.deleted = true },
    }
    harness.parents.push(parent)
    return parent
  }
  class FakeMat {
    deleted = false
    data32F: Float32Array
    constructor(public rows: number, public cols: number, _type: number) {
      this.data32F = new Float32Array(rows * cols)
      harness.mats.push(this)
    }
    type(): number { return 5 }
    delete(): void {
      this.deleted = true
    }
  }
  class FakeMatcher {
    deleted = false
    clear(): void { /* noop */ }
    delete(): void { this.deleted = true }
    knnMatch(_a: unknown, _b: unknown, _k: number): FakeParent {
      knnCalls += 1
      return makeParent(knnCalls === 1 ? options.forwardRows : options.reverseRows)
    }
  }
  harness.cv = {
    NORM_L2: 4,
    CV_32F: 5,
    CV_8UC1: 0,
    INTER_AREA: 3,
    Mat: FakeMat as unknown as RawCv['Mat'],
    BFMatcher: FakeMatcher as unknown as never,
  } as unknown as RawCv
  return harness
}

function floatRow128(): Float32Array {
  const row = new Float32Array(128)
  row[0] = 0.5
  return row
}

function rows(count: number): Float32Array[] {
  return Array.from({ length: count }, () => floatRow128())
}

function twoByTwoKnnSpecs(): { forward: Array<Array<{ queryIdx: number; trainIdx: number; distance: number }>>; reverse: Array<Array<{ queryIdx: number; trainIdx: number; distance: number }>> } {
  const forward = [
    [{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 1, distance: 0.9 }],
    [{ queryIdx: 1, trainIdx: 1, distance: 0.2 }, { queryIdx: 1, trainIdx: 0, distance: 0.8 }],
  ]
  const reverse = [
    [{ queryIdx: 0, trainIdx: 0, distance: 0.1 }, { queryIdx: 0, trainIdx: 1, distance: 0.8 }],
    [{ queryIdx: 1, trainIdx: 1, distance: 0.2 }, { queryIdx: 1, trainIdx: 0, distance: 0.9 }],
  ]
  return { forward, reverse }
}

describe('createPinnedMatcherRuntime handle lifecycle (fake cv)', () => {
  it('releases EVERY nested row handle returned by parent.get on the success path', async () => {
    Float32Array.prototype.set = function (this: Float32Array, source: ArrayLike<number>, offset?: number): void {
      copyCalls += 1
      originalSet.call(this, source as never, offset)
    }
    const specs = twoByTwoKnnSpecs()
    const harness = makeFakeCv({ forwardRows: specs.forward, reverseRows: specs.reverse })
    const runtime = createPinnedMatcherRuntime(harness.cv)
    const result = await runtime.knnMatch2(rows(2), rows(2))
    expect(result.forward.length).toBe(2)
    expect(result.reverse.length).toBe(2)
    // FOUR nested row handles (2 anchors × 2 directions) must be released.
    expect(harness.rowsCreated.length).toBe(4)
    expect(harness.rowsCreated.every((row) => row.deleted)).toBe(true)
    expect(harness.parents.every((parent) => parent.deleted)).toBe(true)
    expect(harness.mats.every((mat) => mat.deleted)).toBe(true)
    expect(harness.mats.every((mat) => mat.data32F.every((byte) => byte === 0))).toBe(true)
  })

  it('releases nested row handles when a LATER row fails validation', async () => {
    Float32Array.prototype.set = function (this: Float32Array, source: ArrayLike<number>, offset?: number): void {
      copyCalls += 1
      originalSet.call(this, source as never, offset)
    }
    const specs = twoByTwoKnnSpecs()
    // Second forward row has an out-of-range trainIdx: the first forward row
    // handle was already obtained and must still be released on the throw.
    specs.forward[1] = [{ queryIdx: 1, trainIdx: 9, distance: 0.2 }, { queryIdx: 1, trainIdx: 0, distance: 0.8 }]
    const harness = makeFakeCv({ forwardRows: specs.forward, reverseRows: specs.reverse })
    const runtime = createPinnedMatcherRuntime(harness.cv)
    await expect(runtime.knnMatch2(rows(2), rows(2))).rejects.toBeInstanceOf(PinnedMatcherError)
    expect(harness.rowsCreated.length).toBeGreaterThanOrEqual(1)
    expect(harness.rowsCreated.every((row) => row.deleted)).toBe(true)
    expect(harness.parents.every((parent) => parent.deleted)).toBe(true)
    expect(harness.mats.every((mat) => mat.deleted)).toBe(true)
  })

  it('owns a Mat from its constructor: a mid-copy failure wipes and releases the partial Mat', async () => {
    Float32Array.prototype.set = function (this: Float32Array, source: ArrayLike<number>, offset?: number): void {
      copyCalls += 1
      if (copyCalls === 3) throw new Error('injected copy failure')
      originalSet.call(this, source as never, offset)
    }
    const specs = twoByTwoKnnSpecs()
    const harness = makeFakeCv({ forwardRows: specs.forward, reverseRows: specs.reverse })
    const runtime = createPinnedMatcherRuntime(harness.cv)
    // Calls 1..2 fill the first Mat; call 3 (first row of the SECOND Mat)
    // throws: both Mats must be wiped/released, matcher never constructed.
    await expect(runtime.knnMatch2(rows(2), rows(2))).rejects.toBeInstanceOf(PinnedMatcherError)
    expect(harness.mats.length).toBe(2)
    expect(harness.mats.every((mat) => mat.deleted)).toBe(true)
    expect(harness.mats.every((mat) => mat.data32F.every((byte) => byte === 0))).toBe(true)
    expect(harness.parents.length).toBe(0)
  })

  it('a width-mismatched row refuses and wipes/releases the just-created Mat', async () => {
    Float32Array.prototype.set = function (this: Float32Array, source: ArrayLike<number>, offset?: number): void {
      copyCalls += 1
      originalSet.call(this, source as never, offset)
    }
    const specs = twoByTwoKnnSpecs()
    const harness = makeFakeCv({ forwardRows: specs.forward, reverseRows: specs.reverse })
    const runtime = createPinnedMatcherRuntime(harness.cv)
    const bad = [floatRow128(), new Float32Array(64)]
    await expect(runtime.knnMatch2(bad, rows(2))).rejects.toBeInstanceOf(PinnedMatcherError)
    expect(harness.mats.length).toBe(1)
    expect(harness.mats[0]!.deleted).toBe(true)
    expect(harness.mats[0]!.data32F.every((byte) => byte === 0)).toBe(true)
    expect(harness.parents.length).toBe(0)
  })
})
