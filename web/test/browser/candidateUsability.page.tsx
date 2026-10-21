/** Task-owned ordinary-UI fixture. No capsule admission, crypto or publication.
 * Production App/drafts + synthetic read-only directory/music/camera ports. */
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

const lang = (new URL(location.href).searchParams.get('lang') || 'en') as Language
document.documentElement.lang = lang
const account = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', other = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const bundle = '22222222-2222-4222-8222-222222222222', date = '2026-10-21T00:00:00Z'
const store = new SessionStore(lang), keys = new MemoryKeys(), memory = new PresentationMemory()
const recipient = new RecipientDraft(), note = new NoteDraft(), photos = new PhotoDraft(), music = new MusicDraft()
for (const part of [keys, memory, recipient, note, photos, music]) part.attach(store)
const calls: { method: string; path: string; query?: unknown }[] = [], forbidden: string[] = []
const musicReplies: { route: string; aborted: boolean }[] = []
const preferences = new Map<string, string>()
let releaseMusic: (() => void) | null = null, cameraStarts = 0, cameraStops = 0
const deny = (name: string): never => { forbidden.push(name); throw new Error(`Forbidden fixture action: ${name}`) }
const hit = { id: '0198f0a0-0000-7000-8000-00000000c001', title: 'Synthetic shoreline', artists: ['Fixture artist'],
  version: null, release: null, year: null, durationMs: 123000, artworkAvailable: false, recordingMbid: null }
const transport: ApiTransport & ByteTransport = {
  requestBytes: async () => deny('byte transport'),
  async request(input) {
    calls.push({ method: input.method, path: input.path, query: input.query })
    if (input.method !== 'GET') return deny('mutation')
    if (input.path === '/v1/recipients/recent') return { status: 200, body: { items: [] } }
    if (input.path === '/v1/me/send-access') return { status: 200, body: { policy_version: 1, mode: 'record', lifetime: false,
      free: { self: { limit: 2, used: 0, remaining: 2 }, other: { limit: 2, used: 0, remaining: 2 } }, can_publish: { self: true, other: true } } }
    if (input.path.startsWith('/v1/directory/handles/')) {
      const handle = input.path.split('/').at(-1)!
      if (handle === 'missing_qa') return { status: 404, body: {} }
      const id = handle === 'self_qa' ? account : other
      return { status: 200, body: { user: { user_id: id, handle }, key_bundle: {
        key_bundle_id: bundle, user_id: id, suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocol_version: 1,
        encryption_public_keyset: 'CIabc123', signing_public_keyset: 'CJxyz789', status: 'ACTIVE', created_at: date }, directory_version: '0123456789abcdef' } }
    }
    if (input.path === '/music/v1/search') {
      const query = input.query?.q
      if (query === 'slow') await new Promise<void>(resolve => { releaseMusic = resolve })
      if (query === 'slow') musicReplies.push({ route: location.hash, aborted: input.signal?.aborted ?? false })
      if (query === 'down') return { status: 503, body: {} }
      return { status: 200, body: { results: query === 'empty' ? [] : [hit], total: query === 'empty' ? 0 : 1, offset: 0 } }
    }
    return deny(input.path)
  },
}
const host = document.createElement('div'); document.body.append(host)
createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
  transport={transport} preferences={{ get: key => preferences.get(key) ?? null, set: (key, value) => { preferences.set(key, value) }, remove: key => { preferences.delete(key) } }}
  google={{ requestIdToken: async () => deny('Google') }}
  passkeys={{ canAuthenticate: () => true, create: async () => deny('passkey create'), get: async () => deny('passkey get') }}
  camera={{ open: async () => { cameraStarts++; return { preview: document.createElement('video'),
    capture: async () => deny('camera capture/admission'), stop: () => { cameraStops++ } } } }} />)

async function pick() {
  const transfer = new DataTransfer()
  for (const [width, height, tone] of [[640, 400, '#8b4545'], [400, 640, '#427866'], [500, 500, '#486a9f']] as const) {
    const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height
    const ctx = canvas.getContext('2d')!; ctx.fillStyle = tone; ctx.fillRect(0, 0, width, height)
    ctx.fillStyle = '#fff9ec'; ctx.font = '36px sans-serif'; ctx.fillText('SYNTHETIC', 24, 60); ctx.fillRect(width * .55, height * .45, width * .28, height * .4)
    const blob = await new Promise<Blob>(ok => canvas.toBlob(value => ok(value!), 'image/jpeg', .9))
    transfer.items.add(new File([blob], `${width}-${height}.jpg`, { type: 'image/jpeg' }))
  }
  const input = document.querySelector<HTMLInputElement>('.photo-draft input[type=file]')!
  input.files = transfer.files; input.dispatchEvent(new Event('change', { bubbles: true }))
}
Object.assign(window, { candidateUI: { catalog, pick,
  unlock() { store.signIn({ accountId: account, bundleId: bundle, handle: 'self_qa', accessToken: 'synthetic-only', refreshToken: 'synthetic-only', accessExpiresAt: date, refreshExpiresAt: date }); store.unlock() },
  lock: () => store.lock(), releaseMusic: () => { releaseMusic?.(); releaseMusic = null },
  releaseOnNavigation(event: 'popstate' | 'hashchange') { window.addEventListener(event, () => { releaseMusic?.(); releaseMusic = null }, { once: true }) },
  state: () => ({ calls, forbidden, cameraStarts, cameraStops, pending: recipient.pending()?.handle ?? null,
    confirmed: recipient.confirmed()?.handle ?? null, photos: photos.photos().length, music: music.selected()?.id ?? null,
    hits: music.hits().length, phase: store.getSnapshot().phase, preferences: [...preferences], musicReplies }),
} })
