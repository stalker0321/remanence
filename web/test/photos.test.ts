import { describe, expect, it } from 'vitest'
import limits from '../../protocol/fixtures/limits-v1.json'
import { PHOTO_COUNT_MAX, PHOTO_COUNT_MIN, PHOTO_MAX_PLAINTEXT_BYTES, PhotoDraft } from '../src/create/photos'
import { SessionStore } from '../src/session/session'

function jpeg(size = 4): Uint8Array {
  const bytes = new Uint8Array(size)
  bytes[0] = 0xff
  bytes[1] = 0xd8
  bytes[2] = 1
  return bytes
}

describe('create photos', () => {
  it('keeps three to five JPEG files and refuses the rest', () => {
    expect(PHOTO_COUNT_MIN).toBe(limits.photo_count_min)
    expect(PHOTO_COUNT_MAX).toBe(limits.photo_count_max)
    expect(PHOTO_MAX_PLAINTEXT_BYTES).toBe(limits.normalized_photo_max_plaintext_bytes)
    const draft = new PhotoDraft()
    expect(draft.add(new Uint8Array([1, 2, 3, 4]))).toBe('type')
    expect(draft.add(new Uint8Array([0xff, 0xd8]))).toBe('size')
    const huge = jpeg(PHOTO_MAX_PLAINTEXT_BYTES + 1)
    expect(draft.add(huge)).toBe('size')
    expect(huge[0]).toBe(0xff)
    for (let index = 0; index < 5; index += 1) expect(draft.add(jpeg(8 + index))).toBeNull()
    expect(draft.add(jpeg())).toBe('full')
    expect(draft.accepted()?.map((photo) => photo.id)).toEqual(['1', '2', '3', '4', '5'])
    const removed = draft.photos()[1]
    if (!removed) throw new Error('missing photo')
    draft.remove(removed.id)
    expect(Array.from(removed.bytes)).toEqual([0, 0, 0, 0, 0, 0, 0, 0, 0])
    expect(draft.accepted()).toHaveLength(4)
    draft.add(jpeg())
    draft.move('1', -1)
    draft.move('1', 1)
    expect(draft.photos().map((photo) => photo.id)).toEqual(['3', '1', '4', '5', '6'])
    expect(draft.accepted()).not.toBeNull()
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
    expect(draft.add(bytes)).toBeNull()
    const held = draft.photos()[0]?.bytes
    session.lock()
    expect(draft.photos()).toEqual([])
    expect(Array.from(held ?? [])).toEqual([0, 0, 0, 0])
    expect(Array.from(bytes)).toEqual([255, 216, 1, 0])
  })
})