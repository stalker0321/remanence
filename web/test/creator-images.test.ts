import { describe, expect, it } from 'vitest'
import { CreatorImageBridge, type CreatorImageFence } from '../src/create/creatorImages'
import type { DraftPhoto } from '../src/create/photos'

interface FakeBitmap {
  width: number
  height: number
  closed: boolean
  close(): void
}

function bitmap(width: number, height: number): FakeBitmap {
  return {
    width,
    height,
    closed: false,
    close() {
      this.closed = true
    },
  }
}

function photo(id: string, width = 100, height = 80, contentHash = 'a'.repeat(64)): DraftPhoto {
  return { id, bytes: new Uint8Array([1, 2, 3]), width, height, contentHash }
}

const mounted: CreatorImageFence = { owner: 'owner-1', generation: 1, confirmed: 'recipient-1', mounted: true }
const fence = (value: CreatorImageFence) => () => value

describe('creator image bridge', () => {
  it('decodes accepted photos and publishes their metadata', async () => {
    const bridge = new CreatorImageBridge(async (bytes) => bitmap(bytes[0] === 1 ? 100 : 100, 80) as unknown as ImageBitmap)
    const result = await bridge.load([photo('a'), photo('b')], 'k1', fence(mounted))
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.photos.map((item) => item.contentId)).toEqual(['a', 'b'])
    expect(result.photos.every((item) => item.width === 100 && item.height === 80)).toBe(true)
  })

  it('closes decoded bitmaps and reports stale when the fence changes mid-load', async () => {
    let live: CreatorImageFence = mounted
    const decoded: FakeBitmap[] = []
    const bridge = new CreatorImageBridge(async () => {
      const image = bitmap(100, 80)
      decoded.push(image)
      live = { ...mounted, generation: 2 }
      return image as unknown as ImageBitmap
    })
    const result = await bridge.load([photo('a')], 'k1', () => live)
    expect(result).toEqual({ ok: false, stale: true })
    expect(decoded.every((image) => image.closed)).toBe(true)
  })

  it('refuses decode and dimension mismatches without publishing', async () => {
    const failing = new CreatorImageBridge(async () => {
      throw new Error('decode failed')
    })
    expect(await failing.load([photo('a')], 'k1', fence(mounted))).toEqual({ ok: false, stale: false })
    const mismatched = new CreatorImageBridge(async () => bitmap(50, 50) as unknown as ImageBitmap)
    expect(await mismatched.load([photo('a', 100, 80)], 'k1', fence(mounted))).toEqual({ ok: false, stale: false })
  })

  it('reuses the published set for the same key and closes it on dispose', async () => {
    const published: FakeBitmap[] = []
    const bridge = new CreatorImageBridge(async () => {
      const image = bitmap(100, 80)
      published.push(image)
      return image as unknown as ImageBitmap
    })
    await bridge.load([photo('a')], 'k1', fence(mounted))
    const again = await bridge.load([photo('a')], 'k1', fence(mounted))
    expect(again.ok).toBe(true)
    expect(published.length).toBe(1)
    bridge.dispose()
    expect(published.every((image) => image.closed)).toBe(true)
  })

  it('decodes a repeated content hash once and owns that one handle', async () => {
    const decoded: FakeBitmap[] = []
    const bridge = new CreatorImageBridge(async () => {
      const image = bitmap(100, 80)
      decoded.push(image)
      return image as unknown as ImageBitmap
    })
    const result = await bridge.load([photo('a'), photo('b')], 'k1', fence(mounted))
    expect(result.ok).toBe(true)
    if (!result.ok) return
    // Same hash twice -> one decode, one shared owned handle for both slots.
    expect(decoded.length).toBe(1)
    expect(result.photos[0]?.image).toBe(result.photos[1]?.image)
    bridge.dispose()
    expect(decoded.every((image) => image.closed)).toBe(true)
  })

  it('owns every distinct decoded handle for the whole published set', async () => {
    const decoded: FakeBitmap[] = []
    const bridge = new CreatorImageBridge(async () => {
      const image = bitmap(100, 80)
      decoded.push(image)
      return image as unknown as ImageBitmap
    })
    // Two distinct hashes + one duplicate: 2 decodes, all handles owned.
    const result = await bridge.load(
      [photo('a', 100, 80, 'a'.repeat(64)), photo('b', 100, 80, 'a'.repeat(64)), photo('c', 100, 80, 'b'.repeat(64))],
      'k1',
      fence(mounted),
    )
    expect(result.ok).toBe(true)
    expect(decoded.length).toBe(2)
    bridge.dispose()
    expect(decoded.every((image) => image.closed)).toBe(true)
  })

  it('releases the published cache on dispose so a later load re-decodes', async () => {
    let decodes = 0
    const bridge = new CreatorImageBridge(async () => {
      decodes += 1
      return bitmap(100, 80) as unknown as ImageBitmap
    })
    await bridge.load([photo('a')], 'k1', fence(mounted))
    expect(decodes).toBe(1)
    // Teardown (lock / recipient / account boundary) then a fresh load.
    bridge.dispose()
    await bridge.load([photo('a')], 'k1', fence(mounted))
    expect(decodes).toBe(2)
  })
})
