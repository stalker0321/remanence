/** Test entry only: actual App, in-memory synthetic session, real HTTP backend. */
import { createRoot } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { SessionStore, type SignInInput } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { PresentationMemory } from '../../src/collage/memory'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { fetchTransport } from '../../src/auth/http'
import { catalog } from '../../src/i18n/catalog'
import '../../src/design/tokens.css'

declare global {
  interface Window { t384Session?: SignInInput }
}
const session = window.t384Session
delete window.t384Session
if (!session) throw new Error('synthetic session must be supplied in memory')
const store = new SessionStore()
store.signIn(session)
store.unlock()
const keys = new MemoryKeys(), memory = new PresentationMemory(), recipient = new RecipientDraft()
const note = new NoteDraft(), photos = new PhotoDraft(), music = new MusicDraft()
for (const item of [keys, memory, recipient, note, photos, music]) item.attach(store)
const audit: { method: string; path: string; cache?: RequestCache }[] = []
const fetchReal = window.fetch.bind(window)
window.fetch = (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href)
  if (/^\/v[12]\//.test(url.pathname)) audit.push({ method: init?.method ?? 'GET', path: url.pathname, cache: init?.cache })
  return fetchReal(input, init)
}
const host = document.createElement('div')
document.body.append(host)
createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient}
  note={note} photos={photos} music={music} transport={fetchTransport(location.origin)}
  preferences={{ get: () => null, set: () => undefined, remove: () => undefined }} />)
Object.assign(window, { t384: { audit, state: () => store.getSnapshot(), messages: catalog.en } })
