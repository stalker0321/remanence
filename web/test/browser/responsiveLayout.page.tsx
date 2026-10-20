/** T406 frontend-only actual-App fixture, adapted from finished T402.
 * In-memory metadata and generated JPEGs only; no API/provider/camera actions. */
import { createRoot } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { PresentationMemory } from '../../src/collage/memory'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { catalog, type Language } from '../../src/i18n/catalog'
import type { ApiTransport, ByteTransport } from '../../src/auth/http'
import '../../src/design/tokens.css'

const params = new URL(location.href).searchParams
const stage = params.get('stage') || 'signedout'
const lang = (['en', 'ru', 'uk'].includes(params.get('lang') || '') ? params.get('lang') : 'en') as Language
document.documentElement.style.fontSize = `${18 * Number(params.get('scale') || 1)}px`
document.documentElement.lang = lang
const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const OTHER = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const DATE = '2026-10-21T00:00:00Z'
const store = new SessionStore(lang), keys = new MemoryKeys(), memory = new PresentationMemory()
const recipient = new RecipientDraft(), note = new NoteDraft(), photos = new PhotoDraft(), music = new MusicDraft()
for (const part of [keys, memory, recipient, note, photos, music]) part.attach(store)
const calls: string[] = [], forbidden: string[] = []
const deny = (name: string): never => { forbidden.push(name); throw new Error(`Outside layout fixture: ${name}`) }
const historyRows = [false, true].map((self, index) => ({
  capsule_id: index ? '33333333-3333-4333-8333-333333333333' : '11111111-1111-4111-8111-111111111111',
  protocol_version: 1, delivery_id: null, status: self ? 'READY' : 'OPENED', created_at: DATE,
  published_at: DATE, expires_at: null, first_opened_at: self ? null : DATE, revoked_at: null,
  recipient: { user_id: self ? ACCOUNT : OTHER, handle: self ? 'synthetic_qa' : 'friend_qa', hint: null, is_self: self },
  locator: { status: self ? 'ACTIVE' : 'RETIRED_OPEN', code: self ? 'QA123' : null }, cancel_action: null, cancel_deadline: null,
}))
const transport: ApiTransport & ByteTransport = {
  requestBytes: async () => deny('byte transport'),
  async request(input) {
    calls.push(`${input.method} ${input.path}`)
    if (input.method !== 'GET') return deny('mutation')
    if (input.path === '/v1/capsules/outgoing') return { status: 200, body: { items: historyRows, has_more: false, next_cursor: null } }
    if (input.path === '/v1/me/send-access') return { status: 200, body: { policy_version: 1, mode: 'record', lifetime: false,
      free: { self: { limit: 2, used: 1, remaining: 1 }, other: { limit: 2, used: 0, remaining: 2 } }, can_publish: { self: true, other: true } } }
    if (input.path === '/v1/recipients/recent') return { status: 200, body: { items: [] } }
    return deny(input.path)
  },
}
if (stage !== 'signedout') {
  store.signIn({ accountId: ACCOUNT, bundleId: BUNDLE, handle: 'synthetic_qa',
    accessToken: 'synthetic-unused', refreshToken: 'synthetic-unused', accessExpiresAt: DATE, refreshExpiresAt: DATE })
  store.unlock()
}
if (stage === 'design') {
  const ticket = recipient.nextTicket(ACCOUNT)
  recipient.offer(ticket, ACCOUNT, { userId: OTHER, handle: 'friend_qa', keyBundleId: BUNDLE,
    suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocolVersion: 1,
    encryptionPublicKeyset: 'SYNTHETIC_PUBLIC_ONLY', signingPublicKeyset: 'SYNTHETIC_PUBLIC_ONLY', directoryVersion: '0123456789abcdef', self: false })
  recipient.confirm()
  note.edit({ en: 'A synthetic afternoon by the sea.', ru: 'Синтетический день у моря.', uk: 'Синтетичний день біля моря.' }[lang])
}
const routes: Record<string, string> = { signedout: 'account', design: 'create', settings: 'settings', history: 'history' }
const route = routes[stage] || 'account'
history.replaceState(null, '', `?${params}#/${route}`)
const host = document.createElement('div'); document.body.append(host)
createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
  transport={transport} preferences={{ get: () => null, set: () => undefined, remove: () => undefined }}
  google={{ requestIdToken: async () => deny('Google') }}
  passkeys={{ canAuthenticate: () => true, create: async () => deny('passkey create'), get: async () => deny('passkey get') }}
  camera={{ open: async () => deny('camera') }} />)

async function pick() {
  const transfer = new DataTransfer()
  for (const [width, height, label, tone] of [[640, 400, 'LANDSCAPE', '#8b4545'], [400, 640, 'PORTRAIT', '#427866'], [500, 500, 'SQUARE', '#486a9f']] as const) {
    const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height
    const ctx = canvas.getContext('2d')!
    ctx.fillStyle = tone; ctx.fillRect(0, 0, width, height)
    ctx.strokeStyle = '#fff9ec'; ctx.lineWidth = 12; ctx.strokeRect(6, 6, width - 12, height - 12)
    ctx.fillStyle = '#fff9ec'; ctx.font = '36px sans-serif'; ctx.fillText(`SYNTHETIC ${label}`, 28, 70)
    ctx.fillRect(width * .55, height * .45, width * .28, height * .4)
    const blob = await new Promise<Blob>(ok => canvas.toBlob(value => ok(value!), 'image/jpeg', .9))
    transfer.items.add(new File([blob], `${label}.jpg`, { type: 'image/jpeg' }))
  }
  const input = document.querySelector<HTMLInputElement>('input[type=file]')!
  input.files = transfer.files; input.dispatchEvent(new Event('change', { bubbles: true }))
}
Object.assign(window, { layoutFixture: { pick, catalog,
  state: () => ({ language: store.getSnapshot().language, phase: store.getSnapshot().phase, calls, forbidden }) } })
