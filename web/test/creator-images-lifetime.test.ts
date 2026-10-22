import { describe, expect, it, vi } from 'vitest'
import { CreatorImageBridge, type CreatorImageFence } from '../src/create/creatorImages'
import type { DraftPhoto } from '../src/create/photos'

/** Local decoded-preview ownership only; no encryption/publish/capsule harness. */
function bitmap() { return { width: 10, height: 8, close: vi.fn() } as unknown as ImageBitmap }
const photo = (id: string): DraftPhoto => ({ id, contentHash: id.repeat(64), width: 10, height: 8, bytes: new Uint8Array([id.charCodeAt(0)]) })
const owner: CreatorImageFence = { owner: 'synthetic-owner', generation: 1, confirmed: {}, mounted: true }
const fence = () => owner
function deferred<T>() {
  let resolve!: (value: T) => void; let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

describe('latest creator-image selection owns its cache', () => {
  it('returning to the cached selection retires an older pending decode', async () => {
    const a = bitmap(); const b = bitmap(); const gate = deferred<ImageBitmap>()
    const bridge = new CreatorImageBridge(async bytes => bytes[0] === 97 ? a : gate.promise)
    const first = await bridge.load([photo('a')], 'a', fence)
    const older = bridge.load([photo('a'), photo('b')], 'ab', fence)
    const latest = await bridge.load([photo('a')], 'a', fence)
    expect(latest).toEqual(first)
    gate.resolve(b)
    expect(await older).toEqual({ ok: false, stale: true })
    expect(b.close).toHaveBeenCalledOnce(); expect(a.close).not.toHaveBeenCalled()
    const again = await bridge.load([photo('a')], 'a', fence)
    if (!latest.ok || !again.ok) throw new Error('expected current preview')
    expect(again.photos).toBe(latest.photos)
    bridge.dispose(); expect(a.close).toHaveBeenCalledOnce()
  })

  it.each(['resolve', 'reject'] as const)('late old %s cannot clear a newer committed preview', async mode => {
    const old = bitmap(); const current = bitmap(); const gate = deferred<ImageBitmap>()
    const bridge = new CreatorImageBridge(async bytes => bytes[0] === 97 ? gate.promise : current)
    const older = bridge.load([photo('a')], 'a', fence)
    const latest = await bridge.load([photo('b')], 'b', fence)
    if (mode === 'resolve') gate.resolve(old); else gate.reject(new Error('synthetic old decode failed'))
    expect(await older).toEqual({ ok: false, stale: true })
    const again = await bridge.load([photo('b')], 'b', fence)
    if (!latest.ok || !again.ok) throw new Error('expected current preview')
    expect(again.photos).toBe(latest.photos)
    expect(current.close).not.toHaveBeenCalled()
    expect(old.close).toHaveBeenCalledTimes(mode === 'resolve' ? 1 : 0)
    bridge.dispose(); expect(current.close).toHaveBeenCalledOnce()
  })

  it('unmounted cache access is stale and does not return a decoded preview', async () => {
    const image = bitmap(); const bridge = new CreatorImageBridge(async () => image)
    await bridge.load([photo('a')], 'a', fence)
    expect(await bridge.load([photo('a')], 'a', () => ({ ...owner, mounted: false })))
      .toEqual({ ok: false, stale: true })
    bridge.dispose(); expect(image.close).toHaveBeenCalledOnce()
  })

  it('dispose while decoding closes only the late owned handle and does not revive the cache', async () => {
    const old = bitmap(); const fresh = bitmap(); const gate = deferred<ImageBitmap>()
    const decode = vi.fn().mockImplementationOnce(() => gate.promise).mockResolvedValue(fresh)
    const bridge = new CreatorImageBridge(decode)
    const pending = bridge.load([photo('a')], 'a', fence)
    bridge.dispose(); gate.resolve(old)
    expect(await pending).toEqual({ ok: false, stale: true })
    expect(old.close).toHaveBeenCalledOnce()
    expect((await bridge.load([photo('a')], 'a', fence)).ok).toBe(true)
    expect(decode).toHaveBeenCalledTimes(2); bridge.dispose()
    expect(fresh.close).toHaveBeenCalledOnce()
  })
})
