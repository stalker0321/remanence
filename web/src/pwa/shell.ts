interface InstallPrompt extends Event {
  prompt(): Promise<unknown>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export interface ShellState {
  readonly support: 'pending' | 'available' | 'unavailable' | 'unsupported'
  readonly offline: boolean
  readonly waiting: boolean
  readonly installable: boolean
  readonly installed: boolean
}

let state: ShellState = {
  support: 'pending', offline: typeof navigator !== 'undefined' && navigator.onLine === false, waiting: false,
  installable: false, installed: false,
}
const listeners = new Set<() => void>()
let prompt: InstallPrompt | null = null
let started = false
function change(patch: Partial<ShellState>) {
  state = { ...state, ...patch }
  for (const listener of listeners) listener()
}
export const shellStore = {
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  },
  getSnapshot: () => state,
}

export async function installShell(): Promise<void> {
  const event = prompt
  if (!event) return
  prompt = null
  change({ installable: false })
  try {
    await event.prompt()
    // Acceptance of the prompt alone is not proof of completed installation.
    await event.userChoice
  } catch {
    // A dismissed/expired/unsupported prompt does not affect the online app.
  }
}

/** Called only by the production entry. Fixture entrypoints do not register. */
export function startShell(base: string): void {
  if (started) return
  started = true
  const standalone = window.matchMedia('(display-mode: standalone)')
  const installed = () => standalone.matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  change({ installed: installed() })
  window.addEventListener('online', () => change({ offline: false }))
  window.addEventListener('offline', () => change({ offline: true }))
  window.addEventListener('beforeinstallprompt', (raw) => {
    const event = raw as InstallPrompt
    if (typeof event.prompt !== 'function' || !event.userChoice || installed()) return
    event.preventDefault()
    prompt = event
    change({ installable: true })
  })
  window.addEventListener('appinstalled', () => {
    prompt = null
    change({ installed: true, installable: false })
  })
  standalone.addEventListener('change', () => {
    change({ installed: installed() })
    if (installed()) {
      prompt = null
      change({ installable: false })
    }
  })
  if (!window.isSecureContext || !navigator.serviceWorker) {
    change({ support: 'unsupported' })
    return
  }
  const register = async () => {
    try {
      const registration = await navigator.serviceWorker.register(`${base}sw.js`, {
        scope: base, updateViaCache: 'none',
      })
      const refresh = () => change({
        support: registration.active?.state === 'activated' ? 'available' : 'pending',
        waiting: registration.waiting !== null && registration.active !== null,
      })
      const watch = (worker: ServiceWorker | null) => {
        if (!worker) return
        worker.addEventListener('statechange', () => {
          if (worker.state === 'redundant' && !registration.active) {
            change({ support: 'unavailable', waiting: false })
          } else refresh()
        })
      }
      watch(registration.installing)
      watch(registration.active)
      registration.addEventListener('updatefound', () => {
        watch(registration.installing)
        refresh()
      })
      refresh()
    } catch {
      change({ support: 'unavailable' })
    }
  }
  // Rendering/auth are never gated on service worker registration.
  if (document.readyState === 'complete') window.setTimeout(() => { void register() }, 0)
  else window.addEventListener('load', () => { void register() }, { once: true })
}
