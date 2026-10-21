import { StrictMode, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { atomicTransport } from '../auth/atomicWire'
import { fetchTransport } from '../auth/http'
import { MemoryKeys } from '../auth/memoryKeys'
import { PresentationMemory } from '../collage/memory'
import { MusicDraft } from '../create/music'
import { NoteDraft } from '../create/note'
import { PhotoDraft } from '../create/photos'
import { RecipientDraft } from '../create/recipient'
import { readLanguage, type PreferencePort } from '../preferences/language'
import { SessionStore } from '../session/session'
import { startOnlineOnlyShell } from '../pwa/shell'
import { App } from '../ui/App'
import { browserAtomicTestAdapters } from './adapters'
import { assertUncontrolledTestContext, AtomicTestConfigurationError, loadAtomicTestConfig, type AtomicTestConfig } from './config'
import '../design/tokens.css'

function TestApplication({ config }: { config: AtomicTestConfig }) {
  const [notice, setNotice] = useState<string | null>(null)
  const [ports] = useState(() => {
    const preferences: PreferencePort = {
      get: key => window.localStorage.getItem(key),
      set: (key, value) => window.localStorage.setItem(key, value),
      remove: key => window.localStorage.removeItem(key),
    }
    const language = readLanguage(preferences)
    document.documentElement.lang = language
    const store = new SessionStore(language), keys = new MemoryKeys(), memory = new PresentationMemory()
    const recipient = new RecipientDraft(), note = new NoteDraft(), photos = new PhotoDraft(), music = new MusicDraft()
    for (const owner of [keys, memory, recipient, note, photos, music]) owner.attach(store)
    return { store, keys, memory, recipient, note, photos, music, preferences,
      transport: fetchTransport(config.apiOrigin), atomicRegistration: atomicTransport(config.apiOrigin),
      ...browserAtomicTestAdapters(config, document, setNotice) }
  })
  return <>
    <p role="status">TEST ONLY — atomic registration. Google sign-in and passkey storage-provider choice are separate. No public rollout.</p>
    {notice ? <p role="alert" data-test-configuration-error>{notice}</p> : null}
    <App {...ports} />
  </>
}

const host = document.getElementById('root')
if (!host) throw new Error('ROOT_MISSING')
const root = createRoot(host)
async function start() {
  try {
    if (import.meta.env.MODE !== 'atomic-test') throw new AtomicTestConfigurationError('Select the dedicated build:atomic-test configuration; normal/public builds cannot enable this entry.')
    assertUncontrolledTestContext(Boolean(navigator.serviceWorker?.controller))
    const base = new URL(import.meta.env.BASE_URL, window.location.origin)
    const config = await loadAtomicTestConfig(new URL('atomic-test-config.json', base), location.origin, window.isSecureContext)
    assertUncontrolledTestContext(Boolean(navigator.serviceWorker?.controller))
    startOnlineOnlyShell()
    root.render(<StrictMode><TestApplication config={config} /></StrictMode>)
  } catch (error) {
    const message = error instanceof AtomicTestConfigurationError ? error.message : 'TEST configuration could not be loaded. No registration started.'
    root.render(<section role="alert" data-test-configuration-error><h1>Atomic registration TEST — unavailable</h1><p>{message}</p></section>)
  }
}
void start()
// Deliberately no startShell/service-worker registration in this separate root.
