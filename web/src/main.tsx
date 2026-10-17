import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryKeys } from './auth/memoryKeys'
import { PresentationMemory } from './collage/memory'
import { readLanguage, type PreferencePort } from './preferences/language'
import { SessionStore } from './session/session'
import { App } from './ui/App'
import './design/tokens.css'

const preferences: PreferencePort = {
  get: (key) => window.localStorage.getItem(key),
  set: (key, value) => window.localStorage.setItem(key, value),
  remove: (key) => window.localStorage.removeItem(key),
}

const language = readLanguage(preferences)
document.documentElement.lang = language
const store = new SessionStore(language)
const keys = new MemoryKeys()
keys.attach(store)
const memory = new PresentationMemory()
memory.attach(store)

const root = document.getElementById('root')
if (!root) throw new Error('ROOT_MISSING')

createRoot(root).render(
  <StrictMode>
    <App store={store} preferences={preferences} keys={keys} memory={memory} />
  </StrictMode>,
)
