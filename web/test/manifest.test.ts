import { describe, expect, it } from 'vitest'
import { layoutCreatorNote } from '../src/collage/creatorNote'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../src/collage/creatorPreview'
import { projectionBytes, projectionHash } from '../src/collage/codec'
import { captureFrozenRenderedPlan } from '../src/collage/frozenRenderedPlan'
import { buildFrozenExpression } from '../src/collage/frozenExpression'
import { encodeContentManifest, type ContentManifestRefusal } from '../src/collage/manifest'
import { prepare } from '../src/collage/prepare'
import { parseContentManifest } from '../src/collage/wire'
import { styleById } from '../src/collage/styles'
import { freezeSelection } from '../src/create/creatorFreeze'
import { validateResolved, type ResolvedExpression } from '../src/collage/model'
import type { TextMeasurer } from '../src/collage/prepare'

const SENDER = 'sergio'
const RECIPIENT = { userId: 'u-1', keyBundleId: 'b-1', directoryVersion: 'd-1' }
const CANDIDATE = 'cand-1'
const CAPSULE = Uint8Array.from({ length: 16 }, (_value, index) => 0xa0 + index)

/** Deterministic 10px/glyph measurer: the shared softWrap:false wrap primitive. */
const measurer: TextMeasurer = {
  measure(request) {
    return { width: request.text.length * 10, height: request.line, hasVisualOverflow: false }
  },
}

function hexHash(index: number): string {
  return `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32)
}

function photos(count = 3) {
  return Array.from({ length: count }, (_, index) => ({
    contentId: `p${index}`,
    width: 1000,
    height: 1000,
    contentHash: hexHash(index),
  }))
}

function frozenFor(note: string | null, count = 3) {
  const source = photos(count)
  const style = styleById('press')!
  const view = previewCandidates(
    { id: 'creator', sender: SENDER, photos: source, note },
    undefined,
    plannerFromTextMeasurer(measurer, 640, 640, style.note),
  )[0]!
  const scene = creatorScene({
    view,
    styleId: 'press',
    senderHandle: SENDER,
    noteText: note,
    photos: new Map(source.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }])),
  })
  const noteLayout = scene.note
    ? layoutCreatorNote(
        scene.note.text,
        { font: scene.note.family, weight: scene.note.weight, italic: scene.note.italic, size: scene.note.size, line: scene.note.line },
        scene.note.region,
        (line) =>
          measurer.measure({
            text: line,
            family: scene.note!.family,
            size: scene.note!.size,
            weight: scene.note!.weight,
            italic: scene.note!.italic,
            line: scene.note!.line,
            maxWidth: Number.POSITIVE_INFINITY,
            maxHeight: Number.POSITIVE_INFINITY,
            softWrap: false,
          }).width,
      )
    : null
  const renderedPlan = captureFrozenRenderedPlan({ scene, noteLayout, branchId: view.branch, styleId: 'press', styleVersion: style.version })
  const result = freezeSelection({
    branchId: view.branch,
    styleId: 'press',
    styleVersion: style.version,
    senderHandle: SENDER,
    slots: view.slots.map((slot, index) => ({
      contentId: slot.photoId,
      ordinal: index,
      contentHash: hexHash(index),
      widthPx: source[index]!.width,
      heightPx: source[index]!.height,
      crop: { ...slot.crop },
    })),
    note,
    noteRegion: scene.note ? { ...scene.note.region } : null,
    noteFits: true,
    musicSelected: false,
    ownerId: 'owner-1',
    sessionGeneration: 7,
    recipient: RECIPIENT,
    renderedKey: 'rk-1',
    renderedPlan,
    createdAtMs: 1,
  })
  if (!result.ok) throw new Error('freeze failed')
  return result.frozen
}

async function expressionFor(note: string | null, count = 3): Promise<ResolvedExpression> {
  const built = await buildFrozenExpression(frozenFor(note, count), 17n)
  if (!built.ok) throw new Error(`adapter failed: ${built.reason}`)
  return built.expression
}

function blobsFor(expression: ResolvedExpression): Map<string, Uint8Array> {
  return new Map(expression.input.photos.map((photo, index) => [photo.contentId, Uint8Array.from({ length: 16 }, () => index + 1)]))
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

/** BigInt-safe serialization for mutation assertions (the epoch is a bigint). */
function ser(value: unknown): string {
  return JSON.stringify(value, (_key, item) => (typeof item === 'bigint' ? `${item}n` : item))
}

function findSubsequence(haystack: Uint8Array, needle: Uint8Array): number {
  for (let start = 0; start <= haystack.length - needle.length; start++) {
    let match = true
    for (let offset = 0; offset < needle.length; offset++) {
      if (haystack[start + offset] !== needle[offset]) {
        match = false
        break
      }
    }
    if (match) return start
  }
  return -1
}

async function refusalReason(expression: ResolvedExpression, capsuleId: Uint8Array, candidateId: string, blobs: ReadonlyMap<string, Uint8Array>): Promise<ContentManifestRefusal | 'ok'> {
  const result = await encodeContentManifest({ expression, capsuleId, candidateId, blobIdByContentId: blobs })
  return result.ok ? 'ok' : result.reason
}

describe('content manifest encoder', () => {
  it('emits a plaintext v2 manifest the existing parser + prepare accept with no sidecar', async () => {
    const expression = await expressionFor('Probe note text.')
    const blobs = blobsFor(expression)
    const before = ser(expression)
    const result = await encodeContentManifest({ expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: blobs })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(ser(expression)).toBe(before)

    const parsed = await parseContentManifest(result.bytes)
    expect(parsed.capsuleId).toEqual(CAPSULE)
    expect(parsed.candidateId).toBe(CANDIDATE)
    expect(parsed.expressionHash).toBe(await projectionHash(expression, CANDIDATE, blobs))
    expect(parsed.note).toBe('Probe note text.')
    expect(parsed.track).toBeNull()

    // Root photo entries derive from the SAME expression input photos.
    expect(parsed.photos.map((photo) => ({ ordinal: photo.ordinal, mediaType: photo.mediaType, width: photo.width, height: photo.height }))).toEqual(
      expression.input.photos.map((photo, index) => ({ ordinal: index, mediaType: 'image/jpeg', width: photo.widthPx, height: photo.heightPx })),
    )
    expect(parsed.photos.map((photo) => Array.from(photo.blobId))).toEqual(blobs.size === 0 ? [] : expression.input.photos.map((photo) => Array.from(blobs.get(photo.contentId)!)))

    // No sidecar: the note region is carried by the manifest itself.
    expect(parsed.expression.noteRegion).toEqual(expression.noteRegion)
    expect(parsed.expression.noteRegion).not.toBeNull()
    expect(parsed.expression.placements).toEqual(expression.placements)
    expect(parsed.expression.noteType).toEqual(expression.noteType)
    expect(parsed.expression.style).toEqual(expression.style)
    expect(parsed.expression.provenance).toEqual(expression.provenance)
    expect(parsed.expression.input.ownerId).toBe('receiver') // existing receiver placeholder
    expect(parsed.expression.input.epoch).toBe(0n) // existing receiver placeholder, not a sender policy

    const sources = new Map(expression.input.photos.map((photo) => [photo.contentId, { width: photo.widthPx, height: photo.heightPx }]))
    const prepared = await prepare(parsed.expression, parsed.note, null, sources, measurer)
    expect(prepared.kind).toBe('ready')
    if (prepared.kind === 'ready') {
      expect(prepared.scene.note?.region).toEqual(expression.noteRegion)
      expect(prepared.scene.photos.map((photo) => photo.contentId)).toEqual(expression.placements.map((placement) => placement.contentId))
    }
  })

  it('supports 3..5 photos and note present/absent, deterministically', async () => {
    for (const count of [3, 4, 5]) {
      for (const note of ['Probe note text.', null]) {
        const expression = await expressionFor(note, count)
        const blobs = blobsFor(expression)
        const first = await encodeContentManifest({ expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: blobs })
        expect(first.ok).toBe(true)
        if (!first.ok) continue
        for (let run = 0; run < 10; run++) {
          const again = await encodeContentManifest({ expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: blobs })
          expect(again.ok && JSON.stringify(Array.from(again.bytes)) === JSON.stringify(Array.from(first.bytes))).toBe(true)
        }
        const parsed = await parseContentManifest(first.bytes)
        expect(parsed.photos.length).toBe(count)
        expect(parsed.note).toBe(note)
        expect(parsed.expression.noteRegion === null).toBe(note === null)
        const sources = new Map(expression.input.photos.map((photo) => [photo.contentId, { width: photo.widthPx, height: photo.heightPx }]))
        const prepared = await prepare(parsed.expression, parsed.note, null, sources, measurer)
        expect(prepared.kind).toBe('ready')
      }
    }
  })

  it('refuses bad capsule/candidate IDs and photo counts', async () => {
    const expression = await expressionFor('Probe note text.')
    const blobs = blobsFor(expression)
    expect(await refusalReason(expression, new Uint8Array(15), CANDIDATE, blobs)).toBe('capsule-id')
    expect(await refusalReason(expression, CAPSULE, '   ', blobs)).toBe('candidate-id')
    expect(await refusalReason(expression, CAPSULE, 'c'.repeat(257), blobs)).toBe('candidate-id')
  })

  it('refuses missing/extra/duplicate/short blob map entries', async () => {
    const expression = await expressionFor('Probe note text.')
    const good = blobsFor(expression)
    const missing = new Map(good)
    missing.delete('p1')
    expect(await refusalReason(expression, CAPSULE, CANDIDATE, missing)).toBe('blob-map')
    const extra = new Map(good)
    extra.set('ghost', Uint8Array.from({ length: 16 }, () => 9))
    expect(await refusalReason(expression, CAPSULE, CANDIDATE, extra)).toBe('blob-map')
    const duplicate = new Map(good)
    duplicate.set('p1', good.get('p0')!.slice())
    expect(await refusalReason(expression, CAPSULE, CANDIDATE, duplicate)).toBe('blob-map')
    const short = new Map(good)
    short.set('p2', new Uint8Array(15))
    expect(await refusalReason(expression, CAPSULE, CANDIDATE, short)).toBe('blob-map')
  })

  it('refuses out-of-contract dimensions, note size, and fractional wire geometry', async () => {
    const expression = await expressionFor('Probe note text.')
    const blobs = blobsFor(expression)
    const zeroDim = clone(expression)
    zeroDim.input.photos[0]!.widthPx = 0
    expect(await refusalReason(zeroDim, CAPSULE, CANDIDATE, blobs)).toBe('photo-dimensions')
    const hugeDim = clone(expression)
    hugeDim.input.photos[1]!.heightPx = 10001
    expect(await refusalReason(hugeDim, CAPSULE, CANDIDATE, blobs)).toBe('photo-dimensions')
    const longNote = clone(expression)
    longNote.input.note = 'x'.repeat(1001)
    expect(await refusalReason(longNote, CAPSULE, CANDIDATE, blobs)).toBe('note-size')
    const fractional = clone(expression)
    fractional.placements[0]!.x = 1.5
    expect(await refusalReason(fractional, CAPSULE, CANDIDATE, blobs)).toBe('fraction')
  })

  it('refuses a non-V4 / music expression', async () => {
    const expression = await expressionFor('Probe note text.')
    const blobs = blobsFor(expression)
    const v3 = clone(expression)
    ;(v3 as { expressionContractVersion: number }).expressionContractVersion = 3
    expect(await refusalReason(v3, CAPSULE, CANDIDATE, blobs)).toBe('unsupported')
    const music = clone(expression)
    music.musicRegion = { x: 16, y: 480, width: 328, height: 64, mode: 'text', type: music.noteType!, artwork: null }
    expect(await refusalReason(music, CAPSULE, CANDIDATE, blobs)).toBe('unsupported')
  })

  it('snapshots caller inputs before the async digest (mutation during await cannot desync)', async () => {
    const expression = await expressionFor('Probe note text.')
    const blobs = blobsFor(expression)
    const originalX = expression.placements[0]!.x
    const originalBlob = Array.from(blobs.get('p0')!)
    const pending = encodeContentManifest({ expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: blobs })
    // Mutate the caller's inputs immediately after the synchronous prologue,
    // while the SHA-256 digest is still awaited.
    expression.placements[0]!.x = originalX + 999
    blobs.get('p0')![0] = 0xff
    const result = await pending
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const parsed = await parseContentManifest(result.bytes)
    expect(parsed.expression.placements[0]!.x).toBe(originalX)
    expect(Array.from(parsed.photos[0]!.blobId)).toEqual(originalBlob)
    expect(parsed.expressionHash).toBe(await projectionHash(parsed.expression, CANDIDATE, new Map(parsed.photos.map((photo, index) => [`p${index}`, photo.blobId]))))
  })

  it('rejects a tampered expression hash and a tampered note region as INTEGRITY', async () => {
    const expression = await expressionFor('Probe note text.')
    const blobs = blobsFor(expression)
    const result = await encodeContentManifest({ expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: blobs })
    if (!result.ok) throw new Error('encode failed')
    const hashAscii = new TextEncoder().encode(await projectionHash(expression, CANDIDATE, blobs))
    const hashAt = findSubsequence(result.bytes, hashAscii)
    expect(hashAt).toBeGreaterThan(-1)
    const badHash = result.bytes.slice()
    badHash[hashAt] = badHash[hashAt] === 0x30 ? 0x31 : 0x30
    await expect(parseContentManifest(badHash)).rejects.toMatchObject({ code: 'INTEGRITY' })

    // noteRegion x is field 12 (tag 0x60) = zigzag32(16) = 0x20, right after has_note_region.
    const noteAt = findSubsequence(result.bytes, Uint8Array.of(0x58, 0x01, 0x60, 0x20))
    expect(noteAt).toBeGreaterThan(-1)
    const badRegion = result.bytes.slice()
    badRegion[noteAt + 3] = 0x22 // zigzag32(17) -> region x changed, hash no longer matches
    await expect(parseContentManifest(badRegion)).rejects.toMatchObject({ code: 'INTEGRITY' })
  })

  it('rejects a tampered root photo blob mapping and an over-bounded frame', async () => {
    const expression = await expressionFor('Probe note text.')
    const blobs = blobsFor(expression)
    const result = await encodeContentManifest({ expression, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: blobs })
    if (!result.ok) throw new Error('encode failed')
    // The first root PhotoEntry blobId is `0a 10` + 16x0x01; flip one byte so it
    // no longer matches the expression source blob id.
    const blobAt = findSubsequence(result.bytes, Uint8Array.of(0x0a, 0x10, ...new Array(16).fill(0x01)))
    expect(blobAt).toBeGreaterThan(-1)
    const badBlob = result.bytes.slice()
    badBlob[blobAt + 2] = 0x02
    await expect(parseContentManifest(badBlob)).rejects.toMatchObject({ code: 'INVALID_ENCODING' })

    // Existing parser frame limit: a >65536-byte buffer refuses OVERSIZED.
    await expect(parseContentManifest(new Uint8Array(65537))).rejects.toMatchObject({ code: 'OVERSIZED' })
  })

  it('refuses a contract-valid manifest whose final plaintext exceeds 65536', async () => {
    const varintLength = (value: number): number => {
      let rest = value
      let length = 1
      while (rest > 127) {
        rest = Math.floor(rest / 128)
        length += 1
      }
      return length
    }
    const withLongId = (expression: ResolvedExpression, index: number, id: string): ResolvedExpression => {
      const next = clone(expression)
      const previous = next.input.photos[index]!.contentId
      next.input.photos[index]!.contentId = id
      const placement = next.placements.find((item) => item.contentId === previous)
      if (!placement) throw new Error('missing placement for long-id counterexample')
      placement.contentId = id
      return next
    }
    const blobsOf = (expression: ResolvedExpression): Map<string, Uint8Array> =>
      new Map(expression.input.photos.map((photo, index) => [photo.contentId, Uint8Array.from({ length: 16 }, () => index + 1)]))

    const base = await expressionFor(null)
    // One probe (long opaque contentId, no note) derives the over-cap case from
    // real bytes: both BEXPR03 and the manifest grow by exactly 2 bytes per id
    // character (the contentId appears in source + placement), so a single
    // fixed-width measurement is enough — no fuzz, no tuning loop.
    const probeLength = 20000
    const probe = withLongId(base, 0, 'a'.repeat(probeLength))
    const probeBlobs = blobsOf(probe)
    expect(validateResolved(probe).ok).toBe(true)
    const probeProjection = projectionBytes(probe, CANDIDATE, probeBlobs).length
    expect(probeProjection).toBeLessThanOrEqual(65536)
    const probeEncode = await encodeContentManifest({ expression: probe, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: probeBlobs })
    expect(probeEncode.ok).toBe(true)
    if (!probeEncode.ok) return
    const probeManifest = probeEncode.bytes.length
    expect(probeManifest).toBeLessThanOrEqual(65536)

    // Put the projection just under the parser cap (same parity, same varint
    // bucket) and add a near-limit note (the manifest carries the note text;
    // BEXPR03 never does). Exactly 65536 is inside the plaintext cap.
    const target = probeProjection % 2 === 0 ? 65536 : 65535
    const step = (target - probeProjection) / 2
    const over = withLongId(base, 0, 'a'.repeat(probeLength + step))
    const note = 'n'.repeat(1000)
    over.input.note = note
    const overBlobs = blobsOf(over)
    const noteField = 1 + varintLength(note.length) + note.length
    const derivedManifest = probeManifest + 2 * step + noteField
    expect(validateResolved(over).ok).toBe(true)
    expect(projectionBytes(over, CANDIDATE, overBlobs).length).toBe(target)
    expect(projectionBytes(over, CANDIDATE, overBlobs).length).toBeLessThanOrEqual(65536)
    expect(derivedManifest).toBeGreaterThan(65536)

    const refused = await encodeContentManifest({ expression: over, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: overBlobs })
    expect(refused.ok).toBe(false)
    if (refused.ok) return
    expect(refused.reason).toBe('manifest-size')
    expect(Number(refused.detail?.split(' ')[0])).toBeGreaterThan(65536)

    // Near-edge WITHIN-bound variant is not bogusly rejected.
    const okStep = Math.floor((65536 - 8 - probeManifest) / 2)
    const within = withLongId(base, 0, 'a'.repeat(probeLength + okStep))
    const withinBlobs = blobsOf(within)
    expect(projectionBytes(within, CANDIDATE, withinBlobs).length).toBeLessThanOrEqual(65536)
    const withinResult = await encodeContentManifest({ expression: within, capsuleId: CAPSULE, candidateId: CANDIDATE, blobIdByContentId: withinBlobs })
    expect(withinResult.ok).toBe(true)
    if (withinResult.ok) expect(withinResult.bytes.length).toBeLessThanOrEqual(65536)
  })
})
