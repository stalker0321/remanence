import { afterEach, expect, it, vi } from 'vitest'

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

it('TEST reports disabled shell, tracks connection, and attaches no install/worker listeners', async () => {
  const events = new EventTarget(), add = vi.spyOn(events, 'addEventListener')
  const register = vi.fn()
  vi.stubGlobal('window', events)
  vi.stubGlobal('navigator', { onLine: true, serviceWorker: { register } })
  const { shellStore, startOnlineOnlyShell } = await import('../src/pwa/shell')
  const subscriber = vi.fn(), unsubscribe = shellStore.subscribe(subscriber)
  startOnlineOnlyShell(); startOnlineOnlyShell()
  expect(shellStore.getSnapshot()).toEqual({ support: 'disabled', offline: false, waiting: false, installable: false, installed: false })
  events.dispatchEvent(new Event('offline')); expect(shellStore.getSnapshot().offline).toBe(true)
  events.dispatchEvent(new Event('online')); expect(shellStore.getSnapshot().offline).toBe(false)
  expect(add.mock.calls.map(call => call[0])).toEqual(['online', 'offline'])
  expect(register).not.toHaveBeenCalled(); expect(subscriber).toHaveBeenCalledTimes(4)
  unsubscribe()
})

it('TEST starts with the actual offline state, without attempting installation', async () => {
  vi.stubGlobal('window', new EventTarget()); vi.stubGlobal('navigator', { onLine: false })
  const { shellStore, startOnlineOnlyShell } = await import('../src/pwa/shell')
  startOnlineOnlyShell()
  expect(shellStore.getSnapshot().offline).toBe(true)
  expect(shellStore.getSnapshot().support).toBe('disabled')
})
