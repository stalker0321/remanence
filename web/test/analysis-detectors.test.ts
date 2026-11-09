import { describe, expect, it } from 'vitest'
import { aggregateFields, round4 } from '../src/collage/analysis/fieldAggregator'
import { decodeNanoDet, halfEvenRound, letterbox, type NanoDetHead } from '../src/collage/analysis/nanodet'
import { decodeYuNet, mergeYuNetPasses, normalizeYuNetPass, paddedSize, yunetSize } from '../src/collage/analysis/yunet'

/**
 * The pure halves of the photo analysis: the frozen letterbox and NanoDet
 * decode, the `FaceDetectorYN` decode and pass merge, and the field fold.
 * The OpenCV and onnxruntime halves are exercised in the browser.
 */

function nanodetHeads(hit: { side: number; anchor: number; classId: number; score: number; distances: number[] } | null): NanoDetHead[] {
  const heads: NanoDetHead[] = []
  for (const side of [52, 26, 13]) {
    const spatial = side * side
    const cls = new Float32Array(spatial * 80)
    const reg = new Float32Array(spatial * 32)
    if (hit && hit.side === side) {
      cls[hit.anchor * 80 + hit.classId] = hit.score
      // One-hot DFL logits: a large logit on bin `d` decodes to distance `d`.
      hit.distances.forEach((d, edge) => {
        for (let bin = 0; bin < 8; bin++) reg[hit.anchor * 32 + edge * 8 + bin] = bin === d ? 30 : 0
      })
    }
    heads.push({ name: `cls${side}`, shape: [1, spatial, 80], values: cls }, { name: `reg${side}`, shape: [1, spatial, 32], values: reg })
  }
  return heads
}

describe('NanoDet decode', () => {
  it('uses the frozen half-even letterbox', () => {
    expect(halfEvenRound(2.5)).toBe(2)
    expect(halfEvenRound(3.5)).toBe(4)
    expect(letterbox(1800, 1200)).toMatchObject({ rw: 416, rh: 277, padX: 0, padY: 69 })
  })

  it('decodes one confident object back into the source frame', () => {
    const box = letterbox(416, 416)
    // Stride 32, anchor (6, 6): centre 6*32 + 15.5 = 207.5; distances 2 bins = 64 px each way.
    const objects = decodeNanoDet(nanodetHeads({ side: 13, anchor: 6 * 13 + 6, classId: 5, score: 0.9, distances: [2, 2, 2, 2] }), box)!
    expect(objects).toHaveLength(1)
    expect(objects[0]!.classId).toBe(5)
    expect(objects[0]!.confidence).toBeCloseTo(0.9, 5)
    expect(objects[0]!.box.x).toBeCloseTo((207.5 - 64) / 416, 3)
    expect(objects[0]!.box.width).toBeCloseTo(128 / 416, 3)
  })

  it('drops scores at the threshold and refuses malformed heads', () => {
    expect(decodeNanoDet(nanodetHeads({ side: 13, anchor: 0, classId: 1, score: 0.35, distances: [1, 1, 1, 1] }), letterbox(416, 416))).toEqual([])
    expect(decodeNanoDet([{ name: 'x', shape: [1, 10, 7], values: new Float32Array(70) }], letterbox(416, 416))).toBeNull()
  })
})

describe('YuNet decode', () => {
  it('pads to 32 and resizes half-even', () => {
    expect(paddedSize(640, 427)).toEqual([640, 448])
    expect(yunetSize(1800, 1200, 960)).toEqual([960, 640])
  })

  it('decodes a face, suppresses its overlapping twin and normalizes', () => {
    const [padW, padH] = paddedSize(64, 64)
    const outputs = { cls: [] as Float32Array[], obj: [] as Float32Array[], bbox: [] as Float32Array[], kps: [] as Float32Array[] }
    for (const stride of [8, 16, 32]) {
      const n = (padW / stride) * (padH / stride)
      outputs.cls.push(new Float32Array(n))
      outputs.obj.push(new Float32Array(n))
      outputs.bbox.push(new Float32Array(n * 4))
      outputs.kps.push(new Float32Array(n * 10))
    }
    // Stride 8, cells (3,3) and (3,4): two strong, overlapping boxes of exp(1)*8 px.
    for (const [index, score] of [[3 * 8 + 3, 0.95], [3 * 8 + 4, 0.8]] as const) {
      outputs.cls[0]![index] = score
      outputs.obj[0]![index] = 1
      outputs.bbox[0]![index * 4 + 2] = 1
      outputs.bbox[0]![index * 4 + 3] = 1
    }
    const rows = decodeYuNet(outputs, padW, padH)
    expect(rows).toHaveLength(1)
    expect(rows[0]![14]).toBeCloseTo(Math.sqrt(0.95), 5)
    const faces = normalizeYuNetPass(rows, 64, 64)
    expect(faces[0]!.box.width).toBeCloseTo((Math.exp(1) * 8) / 64, 4)
    expect(mergeYuNetPasses([faces, faces])).toHaveLength(1)
  })
})

describe('field aggregation', () => {
  it('folds maps, objects and faces like the Android aggregator', () => {
    const cells = 48 * 48
    const zero = () => new Float64Array(cells)
    const analysis = aggregateFields({
      sourceWidth: 1800,
      sourceHeight: 1200,
      faces: [{ box: { x: 0.4, y: 0.2, width: 0.1, height: 0.15 }, confidence: 0.9 }],
      objects: [{ box: { x: 0.1, y: 0.5, width: 0.3, height: 0.3 }, confidence: 0.7, classId: 0 }],
      saliency: zero(),
      edgeEnergy: zero(),
      contrast: zero(),
      structureField: zero(),
      tipBoxes: [{ x: 0.5, y: 0.3, width: 0.03, height: 0.04 }, { x: 0.05, y: 0.1, width: 0.03, height: 0.04 }],
    })
    expect(analysis.protectedRegions.map((region) => region.kind)).toEqual(['structure-tip', 'structure-tip', 'object', 'face', 'face-context'])
    // The face is large (0.015 > 0.005): person focus, so no tip is critical.
    expect(analysis.protectedRegions.some((region) => region.critical)).toBe(false)
    expect(Math.max(...analysis.importance)).toBeGreaterThan(1)
    expect(round4(0.00005)).toBe(0)
    // numpy: 0.00015 * 1e4 is 1.4999999999999998, which rounds to 1.
    expect(round4(0.00015)).toBe(0.0001)
    expect(round4(0.123456)).toBe(0.1235)
  })
})
