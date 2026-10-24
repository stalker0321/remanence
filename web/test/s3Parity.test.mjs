// Opt-in dataset run of the web frozen matcher, for an image-by-image
// comparison with the Android/Kotlin port. Needs S3_PARITY_DIR (manifest.json,
// bgr960/*.bgr + sizes.json, s3_vocabulary.bin, model.onnx) and
// S3_OPENCV_DIR (a CommonJS copy of the vendored opencv.js + wasm).
import { createRequire } from 'node:module'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, it } from 'vitest'
import * as S3 from '../src/scan/s3/frozenMatcher'

const dir = process.env.S3_PARITY_DIR
const cvDir = process.env.S3_OPENCV_DIR

describe.runIf(dir && cvDir)('frozen matcher parity run', () => {
  it('matches every query against the whole gallery', async () => {
    const require = createRequire(import.meta.url)
    ;(globalThis).Module = { locateFile: (f) => join(cvDir, f) }
    let cv = require(join(cvDir, 'opencv.js'))
    if (typeof cv.then === 'function') cv = await cv
    else if (typeof cv.Mat !== 'function') await new Promise((resolve) => { cv.onRuntimeInitialized = resolve })
    const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'))
    const sizes = JSON.parse(readFileSync(join(dir, 'bgr960/sizes.json'), 'utf8'))
    const vocabBytes = readFileSync(join(dir, 's3_vocabulary.bin'))
    const vocabulary = S3.createVocabulary(cv, new Float32Array(vocabBytes.buffer, vocabBytes.byteOffset, vocabBytes.byteLength / 4))
    const localizer = S3.createLocalizer(cv, new Uint8Array(readFileSync(join(dir, 'model.onnx'))))
    const load = (id) => {
      const [w, h] = sizes[id]
      const mat = new cv.Mat(h, w, cv.CV_8UC3)
      mat.data.set(readFileSync(join(dir, 'bgr960', `${id}.bgr`)))
      return mat
    }
    const refs = manifest.gallery.map((g) => {
      const image = load(g.id)
      const features = S3.senderFeatures(cv, image)
      image.delete()
      return S3.reference(g.card_id, features, vocabulary)
    })
    const limit = Number(process.env.S3_PARITY_LIMIT ?? '100000')
    const rows = ['id,expected,status,card_id,best,score,inliers,coverage,inlier_ratio,components,kept,centre_distance,ambiguous']
    for (const q of manifest.queries.slice(0, limit)) {
      const image = load(q.id)
      const [x0, y0, x1, y1] = S3.frameBounds(image.cols, image.rows)
      const frame = image.roi(new cv.Rect(x0, y0, x1 - x0, y1 - y0)).clone()
      image.delete()
      const { features, components } = S3.recipientFeatures(cv, frame, localizer.mask(frame))
      frame.delete()
      const d = S3.match(cv, features, refs, vocabulary, components)
      const b = d.best
      rows.push([q.id, q.expected ?? '', d.accepted ? 'matched' : 'unknown', d.id ?? '', b?.id ?? '', (b?.finalScore ?? 0).toFixed(4),
        b?.inliers ?? 0, (b?.coverage ?? 0).toFixed(4), (b?.inlierRatio ?? 0).toFixed(4), components, S3.featureCount(features),
        (b?.centreDistance ?? 9).toFixed(4), d.ambiguousIds.join('|')].join(','))
    }
    writeFileSync(join(dir, process.env.S3_PARITY_OUT ?? 's3-web.csv'), rows.join('\n') + '\n')
  }, 24 * 3600 * 1000)
})
