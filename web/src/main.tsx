import { StrictMode, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryKeys } from './auth/memoryKeys'
import { PresentationMemory } from './collage/memory'
import { MusicDraft } from './create/music'
import { NoteDraft } from './create/note'
import { PhotoDraft } from './create/photos'
import { RecipientDraft } from './create/recipient'
import { readLanguage, type PreferencePort } from './preferences/language'
import { SessionStore } from './session/session'
import { App } from './ui/App'
import { startShell } from './pwa/shell'
import { loadDeploymentConfig, type DeploymentConfig } from './deployment/config'
import { deploymentPorts } from './deployment/ports'
import { verifyDeploymentWorker } from './deployment/worker'
import './design/tokens.css'

const preferences: PreferencePort = {
  get: (key) => window.localStorage.getItem(key),
  set: (key, value) => window.localStorage.setItem(key, value),
  remove: (key) => window.localStorage.removeItem(key),
}

const language = readLanguage(preferences, navigator.languages ?? [navigator.language])
document.documentElement.lang = language
const store = new SessionStore(language)
const keys = new MemoryKeys()
keys.attach(store)
const memory = new PresentationMemory()
memory.attach(store)
const recipient = new RecipientDraft()
recipient.attach(store)
const note = new NoteDraft()
note.attach(store)
const photos = new PhotoDraft()
photos.attach(store)
const music = new MusicDraft()
music.attach(store)

const root = document.getElementById('root')
if (!root) throw new Error('ROOT_MISSING')
if (import.meta.env.PROD) startShell(import.meta.env.BASE_URL)

const reactRoot = createRoot(root)
function ConfiguredApplication({ config, controller }: { config: DeploymentConfig | null; controller: ServiceWorker | null }) {
  const [unavailable, setUnavailable] = useState(config === null)
  const [ports] = useState(() => deploymentPorts(config,
    () => navigator.onLine !== false && (navigator.serviceWorker?.controller ?? null) === controller,
    () => setUnavailable(true), document))
  return <App store={store} preferences={preferences} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music} deploymentUnavailable={unavailable} {...ports} />
}
async function start() {
  let config: DeploymentConfig | null = null
  const controller = navigator.serviceWorker?.controller ?? null
  try {
    // Verify a controlling worker before asking it for live deployment config.
    await verifyDeploymentWorker(import.meta.env.BASE_URL, location.origin, controller)
    config = await loadDeploymentConfig(new URL(`${import.meta.env.BASE_URL}app-config.json`, location.origin), location.origin, window.isSecureContext)
    if ((navigator.serviceWorker?.controller ?? null) !== controller) config = null
  } catch { /* Offline/unverified/mismatched: full static UI, no API/provider selection. */ }
  reactRoot.render(<StrictMode><ConfiguredApplication config={config} controller={controller} /></StrictMode>)
}
void start()
