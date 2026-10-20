import { describe, expect, it } from 'vitest'
import {
  createRecognitionEngine,
  RecognitionEngineError,
  type RecognitionEngineErrorCode,
} from '../src/scan/recognitionEngine'
import type { RecognitionIndex } from '../src/scan/recognitionIndex'
import { RecognitionIndexError } from '../src/scan/recognitionIndex'
import type { IncomingCatalog, IncomingCapsule } from '../src/sync/incoming'
import type { StillCapture } from '../src/scan/camera'
import type { SiftFingerprint } from '../src/scan/siftFingerprint'
import { serializeSiftFingerprint } from '../src/scan/siftFingerprint'
import type { QueryFingerprint } from '../src/scan/queryFingerprint'
import {
  FrontStillError,
  type CaptureQualityDiagnostics,
  type ProduceFrontStillOptions,
} from '../src/scan/frontStillProducer'
import type { SideMatchResult } from '../src/scan/sideMatcher'
import type { SideScoreReport } from '../src/scan/sideScoring'

const CAPTURE: StillCapture = { bytes: new Uint8Array([1, 2, 3]), width: 4, height: 5 }

function domain(seed = 1): SiftFingerprint {
  return {
    profileId: 'postcard-sift-rootsift-v1',
    canonicalWidthPx: 1600,
    canonicalHeightPx: 1200,
    coarseHash64: 0n,
    keypoints: [
      { xMicro: 100000, yMicro: 100000, scaleMicro: 1000, angleCentiDegrees: 0, responseQuantized: 1, octave: 0 },
    ],
    quantizedSiftDescriptors: [new Uint8Array(128).fill(seed)],
  }
}

function queryOf(seed = 1): QueryFingerprint {
  return {
    wire: serializeSiftFingerprint(domain(seed)),
    profileId: 'postcard-sift-rootsift-v1',
    provenance: {
      source: 'LEGACY_CONTOUR',
      corners: [
        { x: 0, y: 0 },
        { x: 1, y: 0 },
        { x: 1, y: 1 },
        { x: 0, y: 1 },
      ],
      areaRatio: 0.5,
      rectangularity: 0.9,
      confidence: 0.9,
      workingWidth: 320,
      workingHeight: 240,
      warpedWidth: 1600,
      warpedHeight: 1200,
    },
  }
}

function report(sideScore: number, weakGatePassed: boolean, strongGatePassed: boolean): SideScoreReport {
  return {
    countScore: sideScore,
    ratioScore: sideScore,
    coverageScore: sideScore,
    reprojectionScore: sideScore,
    sideScore,
    weakGatePassed,
    strongGatePassed,
  }
}

function sideResult(reportValue: SideScoreReport): SideMatchResult {
  return { report: reportValue } as unknown as SideMatchResult
}

interface FakeIndex {
  disposed?: boolean
  entries: () => readonly { capsuleId: string; fingerprint: SiftFingerprint }[]
  hasMore: boolean
  nextCursor: string | null
}

function makeIndex(
  entries: Array<{ capsuleId: string; fingerprint: SiftFingerprint }>,
  options: { hasMore?: boolean; nextCursor?: string | null } = {},
): FakeIndex {
  const fake: FakeIndex = {
    entries: () => {
      if (fake.disposed === true) throw new RecognitionIndexError('stale')
      return entries
    },
    hasMore: options.hasMore ?? false,
    nextCursor: options.nextCursor ?? null,
  }
  return fake
}

function catalogOf(capsuleIds: string[], options: { hasMore?: boolean; nextCursor?: string | null } = {}): IncomingCatalog {
  return {
    items: capsuleIds.map((capsuleId) => ({ capsuleId }) as IncomingCapsule),
    hasMore: options.hasMore ?? false,
    nextCursor: options.nextCursor ?? null,
  }
}

function engineWith(
  options: {
    produceQuery?: unknown
    matchSide?: unknown
    signal?: AbortSignal
    fence?: () => boolean
  } = {},
) {
  return createRecognitionEngine({
    produceQuery: (options.produceQuery ?? (async () => queryOf())) as never,
    matchSide: (options.matchSide ?? (async () => sideResult(report(0.9, true, true)))) as never,
    signal: options.signal,
    fence: options.fence,
  })
}

async function codeOf(promise: Promise<unknown>): Promise<RecognitionEngineErrorCode | 'ok' | 'other'> {
  return promise.then(
    () => 'ok',
    (error) => (error instanceof RecognitionEngineError ? error.code : 'other'),
  )
}

describe('T346 connected receiving engine', () => {
  it('returns one for a unique strong reference', async () => {
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain(1) }])
    const result = await engineWith().match(CAPTURE, catalogOf(['c1']), { index: index as unknown as RecognitionIndex })
    expect(result).toEqual({ kind: 'one', capsuleId: 'c1' })
  })

  it('returns many for two distinct plausible capsule IDs (no shortcut winner)', async () => {
    const index = makeIndex([
      { capsuleId: 'c1', fingerprint: domain(1) },
      { capsuleId: 'c2', fingerprint: domain(2) },
    ])
    const engine = engineWith({ matchSide: async () => sideResult(report(0.8, true, true)) })
    const result = await engine.match(CAPTURE, catalogOf(['c1', 'c2']), { index: index as unknown as RecognitionIndex })
    expect(result).toEqual({ kind: 'many' })
  })

  it('returns many for a single plausible below auto (guided recapture maps to many)', async () => {
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain(1) }])
    const engine = engineWith({ matchSide: async () => sideResult(report(0.5, true, false)) })
    const result = await engine.match(CAPTURE, catalogOf(['c1']), { index: index as unknown as RecognitionIndex })
    expect(result).toEqual({ kind: 'many' })
  })

  it('returns none for a complete but empty candidate universe', async () => {
    const index = makeIndex([])
    const result = await engineWith().match(CAPTURE, catalogOf([]), { index: index as unknown as RecognitionIndex })
    expect(result).toEqual({ kind: 'none' })
  })

  it('refuses unavailable without an index context', async () => {
    await expect(codeOf(engineWith().match(CAPTURE, catalogOf(['c1'])))).resolves.toBe('unavailable')
  })

  it('fails closed as incomplete on hasMore or a cursor on EITHER side', async () => {
    const entry = [{ capsuleId: 'c1', fingerprint: domain() }]
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1'], { hasMore: true }), { index: makeIndex(entry) as unknown as RecognitionIndex })),
    ).resolves.toBe('incomplete')
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1'], { nextCursor: 'n' }), { index: makeIndex(entry) as unknown as RecognitionIndex })),
    ).resolves.toBe('incomplete')
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1']), { index: makeIndex(entry, { hasMore: true }) as unknown as RecognitionIndex })),
    ).resolves.toBe('incomplete')
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1']), { index: makeIndex(entry, { nextCursor: 'n' }) as unknown as RecognitionIndex })),
    ).resolves.toBe('incomplete')
  })

  it('refuses invalid on missing/extra/duplicate catalog-index IDs', async () => {
    const entry = [{ capsuleId: 'c1', fingerprint: domain() }]
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1', 'extra']), { index: makeIndex(entry) as unknown as RecognitionIndex })),
    ).resolves.toBe('invalid')
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1']), { index: makeIndex([...entry, { capsuleId: 'x', fingerprint: domain(2) }]) as unknown as RecognitionIndex })),
    ).resolves.toBe('invalid')
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1', 'c1']), { index: makeIndex(entry) as unknown as RecognitionIndex })),
    ).resolves.toBe('invalid')
    await expect(
      codeOf(engineWith().match(CAPTURE, catalogOf(['c1']), { index: makeIndex([...entry, { capsuleId: 'c1', fingerprint: domain(3) }]) as unknown as RecognitionIndex })),
    ).resolves.toBe('invalid')
  })

  it('captures the signal and refuses cancelled after a deferred query', async () => {
    const controller = new AbortController()
    let resolveQuery!: (value: QueryFingerprint) => void
    const deferred = new Promise<QueryFingerprint>((resolve) => {
      resolveQuery = resolve
    })
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain() }])
    const pending = engineWith({ produceQuery: () => deferred, signal: controller.signal }).match(
      CAPTURE,
      catalogOf(['c1']),
      { index: index as unknown as RecognitionIndex },
    )
    controller.abort()
    resolveQuery(queryOf())
    await expect(codeOf(pending)).resolves.toBe('cancelled')
  })

  it('refuses stale when the captured index is invalidated during the query await', async () => {
    let resolveQuery!: (value: QueryFingerprint) => void
    const deferred = new Promise<QueryFingerprint>((resolve) => {
      resolveQuery = resolve
    })
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain() }])
    const pending = engineWith({ produceQuery: () => deferred }).match(CAPTURE, catalogOf(['c1']), {
      index: index as unknown as RecognitionIndex,
    })
    index.disposed = true
    resolveQuery(queryOf())
    await expect(codeOf(pending)).resolves.toBe('stale')
  })

  it('uses the pre-await reference snapshot despite caller/index mutation', async () => {
    let resolveQuery!: (value: QueryFingerprint) => void
    const deferred = new Promise<QueryFingerprint>((resolve) => {
      resolveQuery = resolve
    })
    const reference = domain(7)
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: reference }])
    let sawSeed = 0
    const engine = engineWith({
      produceQuery: () => deferred,
      matchSide: async (_query: SiftFingerprint, snapshot: SiftFingerprint) => {
        sawSeed = snapshot.quantizedSiftDescriptors[0]![0]!
        return sideResult(report(0.9, true, true))
      },
    })
    const pending = engine.match(CAPTURE, catalogOf(['c1']), { index: index as unknown as RecognitionIndex })
    reference.quantizedSiftDescriptors[0]!.fill(0)
    resolveQuery(queryOf())
    await expect(pending).resolves.toEqual({ kind: 'one', capsuleId: 'c1' })
    expect(sawSeed).toBe(7)
  })

  it('maps dependency failures to redacted internal, never none', async () => {
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain() }])
    await expect(
      codeOf(
        engineWith({ produceQuery: async () => { throw new Error('boom') } }).match(CAPTURE, catalogOf(['c1']), {
          index: index as unknown as RecognitionIndex,
        }),
      ),
    ).resolves.toBe('internal')
    await expect(
      codeOf(
        engineWith({ matchSide: async () => { throw new Error('boom') } }).match(CAPTURE, catalogOf(['c1']), {
          index: index as unknown as RecognitionIndex,
        }),
      ),
    ).resolves.toBe('internal')
  })

  it('does not mutate the caller capture or index and wipes owned buffers', async () => {
    const captureBytes = new Uint8Array([1, 2, 3])
    const capture: StillCapture = { bytes: captureBytes, width: 4, height: 5 }
    const reference = domain(5)
    const referenceSnapshot = Array.from(reference.quantizedSiftDescriptors[0]!)
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: reference }])
    const records: Array<{ before: Uint8Array; after: Uint8Array }> = []
    const originalFill = Uint8Array.prototype.fill
    const installFill = originalFill as unknown as (value: number, start?: number, end?: number) => Uint8Array
    ;(Uint8Array.prototype as { fill: unknown }).fill = function (
      this: Uint8Array,
      value: number,
      start?: number,
      end?: number,
    ): Uint8Array {
      records.push({ before: this.slice(), after: this })
      return installFill.call(this, value, start, end)
    }
    try {
      const result = await engineWith().match(capture, catalogOf(['c1']), { index: index as unknown as RecognitionIndex })
      expect(result).toEqual({ kind: 'one', capsuleId: 'c1' })
    } finally {
      ;(Uint8Array.prototype as { fill: unknown }).fill = originalFill
    }
    expect(Array.from(capture.bytes)).toEqual(Array.from(captureBytes))
    expect(Array.from(reference.quantizedSiftDescriptors[0]!)).toEqual(referenceSnapshot)
    expect(records.some((record) => record.before.some((byte) => byte !== 0) && record.after.every((byte) => byte === 0))).toBe(true)
  })

  function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (reason: unknown) => void
    const promise = new Promise<T>((yes, no) => {
      resolve = yes
      reject = no
    })
    return { promise, resolve, reject }
  }

  it('keeps the original routing ID paired with the copied reference across await', async () => {
    const entries = [
      { capsuleId: 'c1', fingerprint: domain(1) },
      { capsuleId: 'c2', fingerprint: domain(2) },
    ]
    const index = makeIndex(entries)
    const ready = deferred<QueryFingerprint>()
    const engine = createRecognitionEngine({
      produceQuery: (() => ready.promise) as never,
      matchSide: (async (_query: SiftFingerprint, snapshot: SiftFingerprint) =>
        sideResult(report(snapshot.quantizedSiftDescriptors[0]![0] === 1 ? 0.9 : 0, true, true))) as never,
    })
    const pending = engine.match(CAPTURE, catalogOf(['c1', 'c2']), { index: index as unknown as RecognitionIndex })
    entries[0]!.capsuleId = 'c2'
    ready.resolve(queryOf())
    await expect(pending).resolves.toEqual({ kind: 'one', capsuleId: 'c1' })
  })

  it('wipes the transferred query wire when ownership dies at the post-query handover', async () => {
    let current = true
    const entries = [{ capsuleId: 'c1', fingerprint: domain(1) }]
    const index = {
      hasMore: false,
      nextCursor: null,
      entries: () => {
        if (!current) throw new RecognitionIndexError('stale')
        return entries
      },
    } as unknown as RecognitionIndex
    const ready = deferred<QueryFingerprint>()
    const produced = queryOf()
    const engine = createRecognitionEngine({
      produceQuery: (() => ready.promise) as never,
      matchSide: (async () => sideResult(report(0.9, true, true))) as never,
    })
    const pending = engine.match(CAPTURE, catalogOf(['c1']), { index })
    expect(produced.wire.some((byte) => byte !== 0)).toBe(true)
    current = false
    ready.resolve(produced)
    await expect(codeOf(pending)).resolves.toBe('stale')
    expect(produced.wire.every((byte) => byte === 0)).toBe(true)
  })

  it('gives captured cancellation precedence over a rejected query dependency', async () => {
    const controller = new AbortController()
    const ready = deferred<QueryFingerprint>()
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain() }])
    const engine = createRecognitionEngine({
      signal: controller.signal,
      produceQuery: (() => ready.promise) as never,
      matchSide: (async () => sideResult(report(0.9, true, true))) as never,
    })
    const pending = engine.match(CAPTURE, catalogOf(['c1']), { index: index as unknown as RecognitionIndex })
    controller.abort()
    ready.reject(new Error('synthetic dependency failure'))
    await expect(codeOf(pending)).resolves.toBe('cancelled')
  })

  it('gives captured cancellation precedence over a rejected side dependency', async () => {
    const controller = new AbortController()
    const queryReady = deferred<QueryFingerprint>()
    const sideReady = deferred<SideMatchResult>()
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain() }])
    const engine = createRecognitionEngine({
      signal: controller.signal,
      produceQuery: (() => queryReady.promise) as never,
      matchSide: (() => sideReady.promise) as never,
    })
    const pending = engine.match(CAPTURE, catalogOf(['c1']), { index: index as unknown as RecognitionIndex })
    queryReady.resolve(queryOf())
    // Let the engine reach the side await, then abort before rejecting it.
    await Promise.resolve()
    await Promise.resolve()
    controller.abort()
    sideReady.reject(new Error('synthetic side failure'))
    await expect(codeOf(pending)).resolves.toBe('cancelled')
  })
})

describe('T350 engine requests real post-warp quality without a hard gate', () => {
  it('passes quality:true with the captured original signal and fence', async () => {
    const controller = new AbortController()
    let captured: ProduceFrontStillOptions | null = null
    let calls = 0
    const engine = createRecognitionEngine({
      signal: controller.signal,
      produceQuery: async (_capture, options) => {
        calls += 1
        captured = options
        return queryOf()
      },
      matchSide: async () => sideResult(report(0.9, true, true)),
    })
    const index = makeIndex([{ capsuleId: 'c1', fingerprint: domain(1) }])
    await expect(engine.match(CAPTURE, catalogOf(['c1']), { index: index as unknown as RecognitionIndex })).resolves.toEqual({
      kind: 'one',
      capsuleId: 'c1',
    })
    expect(calls).toBe(1)
    expect(captured).not.toBeNull()
    expect(captured!.quality).toBe(true)
    expect(captured!.signal).toBe(controller.signal)
    expect(typeof captured!.fence).toBe('function')
    expect(captured!.fence()).toBe(true)
  })

  it('keeps nonempty advisory warnings out of one/many/none policy', async () => {
    const warnings: CaptureQualityDiagnostics = {
      signals: {
        laplacianVariance: 50,
        nearBlackFraction: 0.5,
        clippedWhiteFraction: 0.5,
        largestGlareFraction: 0.5,
      },
      reasons: ['TOO_BLURRY', 'TOO_DARK', 'GLARE_EXCESSIVE'],
    }
    const warned = (side: SideScoreReport) =>
      createRecognitionEngine({
        produceQuery: async () => ({ ...queryOf(), quality: warnings }),
        matchSide: async () => sideResult(side),
      })

    const one = warned(report(0.9, true, true))
    await expect(
      one.match(CAPTURE, catalogOf(['c1']), {
        index: makeIndex([{ capsuleId: 'c1', fingerprint: domain(1) }]) as unknown as RecognitionIndex,
      }),
    ).resolves.toEqual({ kind: 'one', capsuleId: 'c1' })

    const many = warned(report(0.8, true, true))
    await expect(
      many.match(CAPTURE, catalogOf(['c1', 'c2']), {
        index: makeIndex([
          { capsuleId: 'c1', fingerprint: domain(1) },
          { capsuleId: 'c2', fingerprint: domain(2) },
        ]) as unknown as RecognitionIndex,
      }),
    ).resolves.toEqual({ kind: 'many' })

    const none = warned(report(0.9, true, true))
    await expect(
      none.match(CAPTURE, catalogOf([]), { index: makeIndex([]) as unknown as RecognitionIndex }),
    ).resolves.toEqual({ kind: 'none' })
  })

  it('maps a typed measurement fault to unavailable/internal, never none or one', async () => {
    const entries = [{ capsuleId: 'c1', fingerprint: domain(1) }]
    const unavailable = createRecognitionEngine({
      produceQuery: async () => {
        throw new FrontStillError('unavailable')
      },
      matchSide: async () => sideResult(report(0.9, true, true)),
    })
    await expect(
      unavailable.match(CAPTURE, catalogOf(['c1']), {
        index: makeIndex(entries) as unknown as RecognitionIndex,
      }),
    ).rejects.toMatchObject({ code: 'unavailable' })

    const internal = createRecognitionEngine({
      produceQuery: async () => {
        throw new FrontStillError('internal')
      },
      matchSide: async () => sideResult(report(0.9, true, true)),
    })
    await expect(
      internal.match(CAPTURE, catalogOf(['c1']), {
        index: makeIndex(entries) as unknown as RecognitionIndex,
      }),
    ).rejects.toMatchObject({ code: 'internal' })
  })
})
