/**
 * T332 connected settings/language UI proof (Chromium-only).
 *
 * Mounts the REAL production `App` (App.tsx) with a real `SessionStore`, an
 * in-memory preference port, and the real drafts, then exercises the ACTUAL
 * header `LanguageMenu` and the real `Settings` screen: trigger + listbox,
 * EN/RU/UK selection, preference write + `store.setLanguage` + `document.lang`,
 * Escape/outside close and focus return, arrow-key navigation, signed-out and
 * keys-unlocked Settings, readonly handle and router nav/back. No real backend/
 * account/provider/camera; the account/key/grant state is synthetic and the
 * language selection must not change it.
 */
import { createRoot } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { PresentationMemory } from '../../src/collage/memory'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { catalog, type Language, type MessageKey } from '../../src/i18n/catalog'
import type { PreferencePort } from '../../src/preferences/language'
import type { CameraPort } from '../../src/scan/camera'
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const ACCOUNT = '11111111-2222-4333-8444-555555555555'
const BUNDLE = '22222222-3333-4444-8555-666666666666'
const SIGN_IN = {
  accountId: ACCOUNT,
  bundleId: BUNDLE,
  handle: 'sergio',
  accessToken: 'access-token-value',
  refreshToken: 'refresh-token-value',
  accessExpiresAt: '2026-10-17T00:00:00Z',
  refreshExpiresAt: '2026-11-17T00:00:00Z',
}

const prefStore = new Map<string, string>()
const prefWrites: Array<{ key: string; value: string }> = []
const preferences: PreferencePort = {
  get: (key) => prefStore.get(key) ?? null,
  set: (key, value) => { prefStore.set(key, value); prefWrites.push({ key, value }) },
  remove: (key) => { prefStore.delete(key); prefWrites.push({ key, value: '' }) },
}

const camera: CameraPort = {
  async open() {
    return { preview: document.createElement('video') as HTMLVideoElement, capture: async () => ({ bytes: new Uint8Array([1]), width: 1, height: 1 }), stop: () => undefined }
  },
}

const store = new SessionStore('en')
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

function t(key: MessageKey, language: Language = store.getSnapshot().language): string {
  return catalog[language][key]
}
function bodyText(): string { return document.body.textContent ?? '' }
// Scope-aware menu helpers: the header menu is `.bar`, the Settings menu is
// `.settings-group`. Using one global selector made the Settings test silently
// re-exercise the header menu (both triggers exist on the settings route).
function trigger(scope = '.bar'): HTMLButtonElement | null { return document.querySelector(`${scope} .language-trigger`) }
function options(scope = '.bar'): HTMLButtonElement[] { return Array.from(document.querySelectorAll(`${scope} .language-option`)) }
function list(scope = '.bar'): HTMLElement | null { return document.querySelector(`${scope} .language-list`) }
function click(el: Element): void { (el as HTMLElement).click() }
function navigate(route: string): void { window.location.hash = `#/${route}` }

function waitFor(cond: () => boolean, what: string, timeoutMs = 5000): Promise<void> {
  const start = Date.now()
  return new Promise((resolve, reject) => {
    const tick = (): void => {
      let ok = false
      try { ok = cond() } catch { ok = false }
      if (ok) { resolve(); return }
      if (Date.now() - start > timeoutMs) { reject(new Error(`timeout: ${what}`)); return }
      setTimeout(tick, 25)
    }
    tick()
  })
}

function main(): void {
  const status = document.createElement('p')
  status.id = 'settings-language-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const finish = (): void => {
    status.textContent = failures.length === 0 ? 'done' : 'failed'
    ;(window as unknown as { __settingsLanguage?: unknown }).__settingsLanguage = {
      assertions,
      failures,
      prefWrites,
      language: store.getSnapshot().language,
      docLang: document.documentElement.lang,
    }
  }
  try {
    const host = document.createElement('div')
    document.body.appendChild(host)
    createRoot(host).render(
      <App store={store} preferences={preferences} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music} camera={camera} />,
    )
    run().catch((error) => { assert('settings/language harness', false, String(error)) }).finally(finish)
  } catch (error) {
    assert('settings/language harness', false, String(error))
    finish()
  }
}

async function run(): Promise<void> {
  await waitFor(() => trigger() !== null, 'header language trigger mounts')

  // --- header: one translated trigger, no flat buttons --------------------
  const trg = trigger()!
  assert('header renders a single language trigger', trg.querySelectorAll('button').length === 0)
  assert('trigger is labelled by the translated language key', trg.getAttribute('aria-label') === t('language'))
  assert('three flat header language buttons are gone', document.querySelectorAll('.bar nav button').length === 0)
  assert('menu starts closed', list() === null && trg.getAttribute('aria-expanded') === 'false')

  // --- open + option content ---------------------------------------------
  click(trg)
  await waitFor(() => list() !== null, 'menu opens')
  assert('trigger opens a listbox', list()!.getAttribute('role') === 'listbox')
  assert('three language options are offered', options().length === 3)
  assert('options are named by their own endonyms', options().map((element) => element.textContent).join('|') === 'English|Русский|Українська')
  assert('current language option is marked selected', options().find((element) => element.textContent === 'English')!.getAttribute('aria-selected') === 'true')
  await waitFor(() => options().includes(document.activeElement as HTMLButtonElement), 'focus enters the list')

  const phaseBefore = store.getSnapshot().phase
  const accountBefore = store.getSnapshot().accountId

  // --- arrow-key navigation ----------------------------------------------
  options()[0]!.focus()
  options()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
  assert('ArrowDown moves focus to the next option', document.activeElement === options()[1])

  // --- select RU ----------------------------------------------------------
  click(options().find((element) => element.textContent === 'Русский')!)
  await waitFor(() => list() === null && document.activeElement === trigger(), 'menu closes and focuses trigger')
  assert('selecting RU writes the language preference', prefWrites.some((w) => w.key === 'remanence.language' && w.value === 'ru'))
  assert('selecting RU updates the session language', store.getSnapshot().language === 'ru')
  assert('selecting RU sets document.lang', document.documentElement.lang === 'ru')
  assert('trigger now shows the Russian copy', trigger()!.textContent === catalog.ru.language)
  assert('language selection did not change account/phase', store.getSnapshot().phase === phaseBefore && store.getSnapshot().accountId === accountBefore)

  // --- Escape closes + focus return --------------------------------------
  click(trigger()!)
  await waitFor(() => list() !== null, 'menu reopens')
  options()[0]!.focus()
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  await waitFor(() => list() === null, 'Escape closes the menu')
  assert('Escape returns focus to the trigger', document.activeElement === trigger())

  // --- outside pointer closes --------------------------------------------
  click(trigger()!)
  await waitFor(() => list() !== null, 'menu reopens for outside close')
  document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
  await waitFor(() => list() === null, 'outside pointer closes the menu')

  // --- select UK then EN --------------------------------------------------
  click(trigger()!)
  await waitFor(() => list() !== null, 'menu reopens for UK')
  click(options().find((element) => element.textContent === 'Українська')!)
  await waitFor(() => store.getSnapshot().language === 'uk', 'UK selected')
  assert('selecting UK updates language + document.lang', document.documentElement.lang === 'uk')
  click(trigger()!)
  await waitFor(() => list() !== null, 'menu reopens for EN')
  click(options().find((element) => element.textContent === 'English')!)
  await waitFor(() => store.getSnapshot().language === 'en', 'EN selected')
  assert('selecting EN restores language + document.lang', document.documentElement.lang === 'en')

  // --- Settings: signed-out honest state ---------------------------------
  navigate('settings')
  await waitFor(() => bodyText().includes(t('settingsTitle')) && bodyText().includes(t('settingsSignedOut')), 'signed-out settings renders')
  assert('settings shows the account group', bodyText().includes(t('account')))
  assert('settings shows the language group', bodyText().includes(t('language')))
  assert('settings account link points at the existing account route', document.querySelector('a[href="#/account"]') !== null)
  assert('settings back link points home', document.querySelector('a[href="#/"]') !== null)

  // --- Settings: language menu works here too (scoped to the settings group) --
  const settingsTrigger = trigger('.settings-group')
  assert('settings renders its own language menu, not the header', settingsTrigger !== null && settingsTrigger !== trigger())
  click(settingsTrigger!)
  await waitFor(() => list('.settings-group') !== null, 'settings language menu opens')
  assert('opening the settings menu does not open the header menu', list('.bar') === null)
  click(options('.settings-group').find((element) => element.textContent === 'Русский')!)
  await waitFor(() => store.getSnapshot().language === 'ru', 'RU selected from settings')
  assert('selecting from settings updates language + document.lang', document.documentElement.lang === 'ru')

  // --- keys-unlocked: readonly handle, state unchanged --------------------
  store.signIn(SIGN_IN)
  store.unlock()
  await waitFor(() => bodyText().includes('sergio'), 'unlocked settings shows the handle')
  assert('unlocked settings shows the permanent handle readonly', bodyText().includes('sergio'))
  assert('handle is rendered as read-only text, not an input', document.querySelector('.settings-handle input') === null)
  assert('unlocked settings shows a current session state', bodyText().includes(t('settingsSession')))

  // --- Create route unaffected by the header/global change ---------------
  const accountIdBeforeNav = store.getSnapshot().accountId
  navigate('create')
  await waitFor(() => window.location.hash === '#/create', 'create route hash')
  assert('create route still renders after the header change', document.querySelector('.shell') !== null && trigger() !== null)
  assert('header language menu persists across routes', trigger() !== null)
  assert('create route shows its action controls after a language switch', document.querySelectorAll('.shell button').length > 0 && list() === null)
  assert('no language <select> was introduced', document.querySelectorAll('select').length === 0)
  assert('navigating did not change the account', store.getSnapshot().accountId === accountIdBeforeNav)

  // --- back link navigates home ------------------------------------------
  navigate('settings')
  await waitFor(() => document.querySelector('a[href="#/"]') !== null, 'settings back link present')
  click(document.querySelector('a[href="#/"]')!)
  await waitFor(() => window.location.hash === '#/' || window.location.hash === '', 'back navigates home')
  assert('back link navigates to home', window.location.hash === '#/' || window.location.hash === '')
}

main()
