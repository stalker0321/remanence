/**
 * Frozen matcher (S3) browser gate: real dataset JPEGs through the production
 * web path (DOM decode, OpenCV.js SIFT/GrabCut, OpenCV.js dnn localizer,
 * S3 codec, frozen decision) in headless Chromium.
 */
import { produceFrontStillFingerprint, produceLocalizedFingerprint } from '../../src/scan/frontStillProducer'
import { parseSiftFingerprint, S3_FINGERPRINT_PROFILE_ID } from '../../src/scan/siftFingerprint'
import * as S3 from '../../src/scan/s3/frozenMatcher'
import { loadFrozenMatcher } from '../../src/scan/s3/s3Assets'
import { queryFrame, toFeatures } from '../../src/scan/s3/s3Fingerprint'

const status = document.createElement('div')
status.id = 's3-status'
status.textContent = 'running'
document.body.append(status)

async function jpeg(name: string): Promise<Uint8Array> {
  return new Uint8Array(await (await fetch(`/fixtures/${name}.jpg`)).arrayBuffer())
}

const report: Record<string, unknown> = {}
;(async () => {
  const fence = () => true
  const t0 = performance.now()
  const sender = async (name: string) => {
    const bytes = await jpeg(name)
    const produced = await produceFrontStillFingerprint({ bytes, width: 1, height: 1, userId: 'u', keyBundleId: 'k', directoryVersion: 'v' }, { fence })
    return parseSiftFingerprint(produced.wire)
  }
  const a = await sender('sender-a')
  const b = await sender('sender-b')
  report.senderMs = Math.round(performance.now() - t0)
  const t1 = performance.now()
  const queryBytes = await jpeg('query-a')
  const produced = await produceLocalizedFingerprint({ bytes: queryBytes, width: 1, height: 1 }, { fence })
  const query = parseSiftFingerprint(produced.wire)
  report.queryMs = Math.round(performance.now() - t1)
  const matcher = await loadFrozenMatcher()
  const t2 = performance.now()
  const refs = [S3.reference('A', toFeatures(a), matcher.vocabulary), S3.reference('B', toFeatures(b), matcher.vocabulary)]
  const decision = S3.match(matcher.cv, toFeatures(query), refs, matcher.vocabulary, queryFrame(query)!.components, true)
  report.matchMs = Math.round(performance.now() - t2)
  report.profiles = [a.profileId, b.profileId, query.profileId]
  report.senderKeypoints = [a.keypoints.length, b.keypoints.length]
  report.queryKeypoints = query.keypoints.length
  report.queryFrame = queryFrame(query)
  report.decision = { accepted: decision.accepted, id: decision.id, score: decision.best?.finalScore, inliers: decision.best?.inliers, centre: decision.best?.centreDistance, ambiguous: decision.ambiguousIds }
  report.ok = report.profiles instanceof Array && (report.profiles as string[]).every((p) => p === S3_FINGERPRINT_PROFILE_ID) && decision.id === 'A'
  ;(window as unknown as { __s3: unknown }).__s3 = report
  status.textContent = report.ok ? 'done' : 'failed'
})().catch((error) => {
  report.error = String(error?.stack ?? error)
  ;(window as unknown as { __s3: unknown }).__s3 = report
  status.textContent = 'failed'
})
