import { describe, expect, it } from 'vitest'
import limits from '../../protocol/fixtures/limits-v1.json'
import type { NormalizedPhotoInput } from '../src/create/normalize'
import {
  NORMALIZED_PHOTO_MAX_LONG_EDGE_PX,
  NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES,
  NORMALIZED_PHOTO_MEDIA_TYPE,
} from '../src/create/normalize'
import { PHOTO_COUNT_MAX, PHOTO_COUNT_MIN, PHOTO_MAX_PLAINTEXT_BYTES, PhotoDraft } from '../src/create/photos'
import { protocolLimitsMatchFixture } from '../src/protocol/limits'
import { SessionStore } from '../src/session/session'

function jpeg(size = 4): Uint8Array {
  const bytes = new Uint8Array(size)
  bytes[0] = 0xff
  bytes[1] = 0xd8
  bytes[2] = 1
  return bytes
}

function photo(size = 8, width = 4, height = 3, contentHash = 'ab'.repeat(32)): NormalizedPhotoInput {
  return { bytes: jpeg(size), width, height, contentHash }
}

describe('create photos', () => {
  it('keeps three to five normalized photos and refuses the rest', () => {
    expect(PHOTO_COUNT_MIN).toBe(limits.photo_count_min)
    expect(PHOTO_COUNT_MAX).toBe(limits.photo_count_max)
    expect(PHOTO_MAX_PLAINTEXT_BYTES).toBe(limits.normalized_photo_max_plaintext_bytes)
    expect(NORMALIZED_PHOTO_MEDIA_TYPE).toBe(limits.normalized_photo_media_type)
    expect(NORMALIZED_PHOTO_MAX_LONG_EDGE_PX).toBe(limits.normalized_photo_max_long_edge_px)
    expect(NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES).toBe(limits.normalized_photo_max_plaintext_bytes)
    expect(protocolLimitsMatchFixture()).toBe(true)
    const draft = new PhotoDraft()
    expect(draft.add({ ...photo(), bytes: new Uint8Array([1, 2, 3, 4]) })).toBe('type')
    expect(draft.add({ ...photo(), bytes: new Uint8Array([0xff, 0xd8]) })).toBe('size')
    expect(draft.add({ ...photo(), width: 0 })).toBe('decode')
    expect(draft.add({ ...photo(), height: -2 })).toBe('decode')
    expect(draft.add({ ...photo(), contentHash: 'zz' })).toBe('decode')
    const huge = photo(PHOTO_MAX_PLAINTEXT_BYTES + 1)
    expect(draft.add(huge)).toBe('size')
    expect(huge.bytes[0]).toBe(0xff)
    for (let index = 0; index < 5; index += 1) {
      expect(draft.add(photo(8 + index, 4 + index, 3, `${index}`.repeat(64).slice(0, 64)))).toBeNull()
    }
    expect(draft.add(photo())).toBe('full')
    expect(draft.accepted()?.map((item) => item.id)).toEqual(['1', '2', '3', '4', '5'])
    const removed = draft.photos()[1]
    if (!removed) throw new Error('missing photo')
    draft.remove(removed.id)
    expect(Array.from(removed.bytes)).toEqual([0, 0, 0, 0, 0, 0, 0, 0, 0])
    expect(draft.accepted()).toHaveLength(4)
    draft.add(photo())
    draft.move('1', -1)
    draft.move('1', 1)
    expect(draft.photos().map((item) => item.id)).toEqual(['3', '1', '4', '5', '6'])
    expect(draft.accepted()).not.toBeNull()
  })

  it('bounds normalized dimensions at the protocol long edge', () => {
    const draft = new PhotoDraft()
    expect(draft.add({ ...photo(), width: 2560, height: 2560 })).toBeNull()
    expect(draft.photos()[0]).toMatchObject({ width: 2560, height: 2560 })
    expect(draft.add({ ...photo(), width: 2561, height: 10 })).toBe('decode')
    expect(draft.add({ ...photo(), width: 10, height: 2561 })).toBe('decode')
    expect(draft.add({ ...photo(), width: 16384, height: 16384 })).toBe('decode')
    expect(draft.photos()).toHaveLength(1)
  })

  it('carries dimensions and content hash into the accepted draft', () => {
    const draft = new PhotoDraft()
    for (let index = 0; index < 3; index += 1) {
      expect(draft.add(photo(64 + index, 2560 - index * 10, 1280, `${index}a`.repeat(32)))).toBeNull()
    }
    const accepted = draft.accepted()
    expect(accepted).toHaveLength(3)
    expect(accepted?.[0]).toMatchObject({ width: 2560, height: 1280, contentHash: `${0}a`.repeat(32) })
    expect(accepted?.every((item) => /^[0-9a-f]{64}$/.test(item.contentHash))).toBe(true)
  })

  it('zeroes the files when the keys lock', () => {
    const session = new SessionStore()
    session.signIn({
      accountId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
      bundleId: '22222222-2222-4222-8222-222222222222',
      handle: 'ada',
      accessToken: 'access-token-value',
      refreshToken: 'refresh-token-value',
      accessExpiresAt: '2026-10-17T00:00:00Z',
      refreshExpiresAt: '2026-11-17T00:00:00Z',
    })
    session.unlock()
    const draft = new PhotoDraft()
    draft.attach(session)
    const bytes = jpeg()
    expect(draft.add({ bytes, width: 4, height: 4, contentHash: 'cd'.repeat(32) })).toBeNull()
    const held = draft.photos()[0]?.bytes
    session.lock()
    expect(draft.photos()).toEqual([])
    expect(Array.from(held ?? [])).toEqual([0, 0, 0, 0])
    expect(Array.from(bytes)).toEqual([255, 216, 1, 0])
  })
})
