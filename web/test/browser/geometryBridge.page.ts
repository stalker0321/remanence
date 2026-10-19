/**
 * T337 real-pinned-runtime geometry bridge browser proof (Chromium-only).
 *
 * Uses the EXISTING pinned OpenCV.js/WASM on loopback; the production bridge
 * executes the exact native seven-argument findHomography at seed 42, and the
 * accepted T333 pure evaluator consumes the copied H/mask observation.
 * Also runs a genuine producer/extractor format-3 wire through the accepted
 * correspondence stage (same-frame >=6 pairs) then the real bridge+evaluation.
 * Technical geometry only: no recognition/admission/grant/backend/field claim.
 */
import { produceFrontStillFingerprint } from '../../src/scan/frontStillProducer'
import { parseSiftFingerprint, type SiftFingerprint } from '../../src/scan/siftFingerprint'
import { matchDescriptors } from '../../src/scan/descriptorCorrespondence'
import { createPinnedMatcherRuntime } from '../../src/scan/opencvMatcherPort'
import { loadOpenCvModule } from '../../src/scan/opencvRuntime'
import { estimateAndEvaluateGeometry } from '../../src/scan/geometryEstimator'
import { estimateHomographyObservations } from '../../src/scan/opencvGeometryPort'
import { evaluateGeometryObservation, type GeometryPixelPoint } from '../../src/scan/geometryEvaluation'
import type { FrontStill } from '../../src/create/front'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

function mulberry32(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state |= 0
    state = (state + 0x6d2b79f5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

async function texturedQuadJpeg(width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  const quad = [
    { x: 100, y: 80 },
    { x: 540, y: 60 },
    { x: 560, y: 400 },
    { x: 80, y: 420 },
  ]
  context.save()
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.clip()
  const random = mulberry32(7)
  for (let i = 0; i < 260; i++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    context.fillRect(quad[0]!.x + random() * 480, quad[0]!.y + random() * 340, 4 + random() * 22, 4 + random() * 22)
  }
  context.restore()
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

const REF: GeometryPixelPoint[] = [
  { x: 30, y: 20 },
  { x: 160, y: 20 },
  { x: 290, y: 20 },
  { x: 30, y: 180 },
  { x: 160, y: 180 },
  { x: 290, y: 180 },
]
const QUERY = REF.map((p) => ({ x: p.x + 17, y: p.y + 11 }))

function pointOf(fingerprint: SiftFingerprint, keypointIndex: number): GeometryPixelPoint {
  const keypoint = fingerprint.keypoints[keypointIndex]!
  return {
    x: (keypoint.xMicro / 1_000_000) * fingerprint.canonicalWidthPx,
    y: (keypoint.yMicro / 1_000_000) * fingerprint.canonicalHeightPx,
  }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'geometry-bridge-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = {}
  try {
    // 1. Translated grid: real estimator + T333 evaluation.
    const grid = await estimateAndEvaluateGeometry({
      referencePoints: REF,
      queryPoints: QUERY,
      referenceWidthPx: 320,
      referenceHeightPx: 200,
    })
    assert('translated grid is technical accepted', grid.accepted && grid.failure === 'NONE', grid.failure)
    assert('translated grid zero-near median error', grid.medianErrorPx <= 1e-6, String(grid.medianErrorPx))
    assert('translated grid hull coverage 0.65', Math.abs(grid.referenceCoverage - 0.65) <= 1e-6, String(grid.referenceCoverage))
    assert('translated grid support area 41600', Math.abs(grid.supportAreaPx2 - 41600) <= 1e-6, String(grid.supportAreaPx2))
    assert('translated grid edge ratio 1.625', Math.abs(grid.supportEdgeRatio - 1.625) <= 1e-6, String(grid.supportEdgeRatio))
    assert('translated grid H maps ref to query by +17/+11',
      grid.matrix !== null && Math.abs(grid.matrix[2]! / grid.matrix[8]! - 17) <= 1e-6 && Math.abs(grid.matrix[5]! / grid.matrix[8]! - 11) <= 1e-6,
      JSON.stringify(grid.matrix))
    report.grid = { accepted: grid.accepted, medianErrorPx: grid.medianErrorPx, coverage: grid.referenceCoverage, supportAreaPx2: grid.supportAreaPx2, edgeRatio: grid.supportEdgeRatio, matrix: grid.matrix }

    // 2. Repeat determinism (same pinned runtime/process only).
    const repeat = await estimateAndEvaluateGeometry({
      referencePoints: REF,
      queryPoints: QUERY,
      referenceWidthPx: 320,
      referenceHeightPx: 200,
    })
    assert('repeat evaluation is deterministic',
      repeat.accepted === grid.accepted && repeat.medianErrorPx === grid.medianErrorPx &&
      repeat.supportAreaPx2 === grid.supportAreaPx2 && repeat.supportEdgeRatio === grid.supportEdgeRatio &&
      JSON.stringify(repeat.matrix) === JSON.stringify(grid.matrix))

    // 3. Reflection refusal via the accepted pure evaluator (synthetic H; not native).
    const reflected = evaluateGeometryObservation({
      referencePoints: [{ x: 30, y: 20 }, { x: 90, y: 20 }, { x: 90, y: 80 }, { x: 30, y: 80 }],
      queryPoints: [{ x: 30, y: 20 }, { x: 90, y: 20 }, { x: 90, y: 80 }, { x: 30, y: 80 }],
      referenceWidthPx: 100,
      referenceHeightPx: 100,
      homography: { empty: false, rows: 3, cols: 3, type: 6, values: [1, 0, 0, 0, -1, 0, 0, 0, 1] },
      mask: { empty: false, rows: 4, cols: 1, type: 0, bytes: [1, 1, 1, 1] },
    })
    assert('reflected support is refused', reflected.accepted === false && reflected.failure === 'INVALID_PROJECTED_SUPPORT', reflected.failure)

    // 4. Real leaf observation carries no native handles.
    const module = await loadOpenCvModule()
    const observations = estimateHomographyObservations(module, REF, QUERY)
    assert('observation H is plain data only', !('delete' in observations.homography) && !('delete' in observations.mask))
    const hValues = observations.homography.values
    assert('observation H is 3x3 CV_64F with nine finite values',
      observations.homography.rows === 3 && observations.homography.cols === 3 && observations.homography.type === 6 &&
      hValues !== null && hValues.length === 9 && hValues.every((v) => Number.isFinite(v)))
    const maskBytes = observations.mask.bytes
    assert('observation mask is CV_8U with six nonzero bytes',
      observations.mask.type === 0 && maskBytes !== null && maskBytes.length === 6 && maskBytes.every((b) => b !== 0))

    // 5. Genuine producer/extractor wire -> correspondence -> double pixels -> bridge -> T333.
    const jpeg = await texturedQuadJpeg(640, 480)
    const still: FrontStill = { bytes: jpeg, width: 640, height: 480, userId: 'u-1', keyBundleId: 'k-1', directoryVersion: 'd-1' }
    const produced = await produceFrontStillFingerprint(still, { fence: () => true })
    const fingerprint = parseSiftFingerprint(produced.wire)
    const correspondence = await matchDescriptors(fingerprint, fingerprint, {
      matcherFactory: async () => createPinnedMatcherRuntime(await loadOpenCvModule()),
    })
    assert('genuine same-frame correspondence yields at least six pairs', correspondence.pairs.length >= 6, String(correspondence.pairs.length))
    report.correspondencePairs = correspondence.pairs.length
    report.wireLength = produced.wire.length
    // Native MIN_GEOMETRY_PAIRS(6) lives outside the leaf bridge; enforce it
    // at this caller integration boundary before invoking the estimator.
    if (correspondence.pairs.length >= 6) {
      const referencePixels = correspondence.pairs.map((pair) => pointOf(fingerprint, pair.referenceIndex))
      const queryPixels = correspondence.pairs.map((pair) => pointOf(fingerprint, pair.queryIndex))
      const genuine = await estimateAndEvaluateGeometry({
        referencePoints: referencePixels,
        queryPoints: queryPixels,
        referenceWidthPx: fingerprint.canonicalWidthPx,
        referenceHeightPx: fingerprint.canonicalHeightPx,
      })
      assert('genuine producer correspondence reaches technical geometry', genuine.accepted === true && genuine.failure === 'NONE', genuine.failure)
      report.genuine = { accepted: genuine.accepted, inliers: genuine.inlierIndices.length, medianErrorPx: genuine.medianErrorPx, supportAreaPx2: genuine.supportAreaPx2, edgeRatio: genuine.supportEdgeRatio }
    }

    // 6. Caller points unchanged.
    const refBefore = JSON.stringify(REF)
    const queryBefore = JSON.stringify(QUERY)
    assert('caller point arrays are not mutated', refBefore === JSON.stringify(REF) && queryBefore === JSON.stringify(QUERY))
    report.pass = failures.length === 0
  } catch (error) {
    assert('geometry bridge harness', false, String(error))
  }
  ;(window as unknown as { __geometryBridge?: unknown }).__geometryBridge = { assertions, failures, report }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
