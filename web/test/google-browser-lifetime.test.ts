import { afterEach, describe, expect, it, vi } from 'vitest'
import { browserGoogleIdentity } from '../src/auth/googleBrowser'

/** Real browser adapter; synthetic GIS/script completion only, no provider/network. */
function fixture(loaded = false) {
  let callback: (response: { credential?: string }) => void = () => undefined
  let moment: (value: unknown) => void = () => undefined
  const sdk = {
    initialize: vi.fn((options: { callback: typeof callback }) => { callback = options.callback }),
    prompt: vi.fn((listener: typeof moment) => { moment = listener }),
    cancel: vi.fn(), disableAutoSelect: vi.fn(),
  }
  const scripts: HTMLScriptElement[] = []
  const script = { dataset: {}, onload: null, onerror: null, remove: vi.fn() } as unknown as HTMLScriptElement
  const doc = {
    createElement: vi.fn(() => script), head: { appendChild: vi.fn((item) => { scripts.push(item) }) },
  } as unknown as Document
  const install = () => vi.stubGlobal('google', { accounts: { id: sdk } })
  vi.stubGlobal('google', undefined)
  if (loaded) install()
  return { sdk, doc, script, scripts, install,
    complete: () => callback({ credential: 'synthetic.id.token' }),
    moment: (reason: string) => moment({ isNotDisplayed: () => false, isSkippedMoment: () => true,
      getSkippedReason: () => reason, getNotDisplayedReason: () => reason }),
    loaded: () => script.onload?.call(script, new Event('load')),
  }
}
afterEach(() => vi.unstubAllGlobals())

describe('GIS request lifetime at script/prompt boundary', () => {
  it('does not launch a prompt when aborted after script load but before its continuation', async () => {
    const f = fixture(); const controller = new AbortController()
    const result = browserGoogleIdentity(f.doc).requestIdToken('synthetic-client', 'synthetic-nonce', controller.signal)
    f.install(); f.loaded(); controller.abort()
    // If the broken adapter starts anyway, finish it so RED is an assertion, not a timeout.
    await Promise.resolve(); f.complete()
    await expect(result).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(f.sdk.initialize).not.toHaveBeenCalled(); expect(f.sdk.prompt).not.toHaveBeenCalled()
    expect(f.sdk.cancel).not.toHaveBeenCalled()
  })

  it('applies the same cancellation fence when GIS was already cached', async () => {
    const f = fixture(true); const controller = new AbortController()
    const result = browserGoogleIdentity(f.doc).requestIdToken('synthetic-client', 'synthetic-nonce', controller.signal)
    controller.abort(); await Promise.resolve(); f.complete()
    await expect(result).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(f.doc.createElement).not.toHaveBeenCalled(); expect(f.sdk.prompt).not.toHaveBeenCalled()
  })

  it('pre-aborted and aborted loading requests cannot be resurrected by a late load event', async () => {
    const f = fixture(); const pre = new AbortController(); pre.abort()
    await expect(browserGoogleIdentity(f.doc).requestIdToken('client', 'nonce', pre.signal)).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(f.scripts).toHaveLength(0)
    const controller = new AbortController()
    const result = browserGoogleIdentity(f.doc).requestIdToken('client', 'nonce', controller.signal)
    controller.abort(); f.install(); f.loaded()
    await expect(result).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(f.script.remove).toHaveBeenCalledOnce(); expect(f.sdk.prompt).not.toHaveBeenCalled()
  })

  it('cancels one live prompt and ignores its late credential callback', async () => {
    const f = fixture(true); const controller = new AbortController()
    const result = browserGoogleIdentity(f.doc).requestIdToken('client', 'nonce', controller.signal)
    await Promise.resolve(); controller.abort(); f.complete()
    await expect(result).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(f.sdk.prompt).toHaveBeenCalledOnce(); expect(f.sdk.cancel).toHaveBeenCalledOnce()
  })

  it('normal completion uses one prompt, explicit provider options and releases the abort listener', async () => {
    const f = fixture(true); const controller = new AbortController()
    const result = browserGoogleIdentity(f.doc).requestIdToken('client', 'nonce', controller.signal)
    await Promise.resolve(); f.complete()
    await expect(result).resolves.toBe('synthetic.id.token'); controller.abort()
    expect(f.sdk.initialize).toHaveBeenCalledWith(expect.objectContaining({
      client_id: 'client', nonce: 'nonce', auto_select: false, use_fedcm_for_prompt: true,
    }))
    expect(f.sdk.prompt).toHaveBeenCalledOnce(); expect(f.sdk.cancel).not.toHaveBeenCalled()
  })

  it.each(['user_cancel', 'tap_outside', 'browser_not_supported', 'unknown_private_reason'])(
    'does not infer dismissal from technical GIS reason %s', async (reason) => {
      const f = fixture(true)
      const result = browserGoogleIdentity(f.doc).requestIdToken('client', 'nonce')
      await Promise.resolve(); f.moment(reason)
      await expect(result).rejects.toMatchObject({ code: ['user_cancel', 'tap_outside'].includes(reason)
        ? 'GOOGLE_CANCELLED' : 'GOOGLE_CLIENT_UNAVAILABLE' })
      expect(f.sdk.prompt).toHaveBeenCalledOnce()
    })
})
