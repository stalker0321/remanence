/**
 * Design review only: the actual App on a synthetic session with canned
 * metadata, so every screen can be looked at on a phone and a desk without a
 * live account. ?path=/create picks the page, ?out=1 stays signed out,
 * ?lang=ru|uk picks the language. Nothing here is sealed or sent.
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
import type { ApiRequest, ApiResult, ApiTransport, ByteTransport } from '../../src/auth/http'
import type { Language } from '../../src/i18n/catalog'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import hpkeVector from '../../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../../protocol/fixtures/publish-signature-v1.json'
import { keysetFromJson, type JsonKeyset } from '../keysetJson'
import '../../src/design/tokens.css'

const query = new URLSearchParams(location.search)
const account = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const bundle = '22222222-2222-4222-8222-222222222222'
const store = new SessionStore()
const language = query.get('lang') as Language | null
if (language) store.setLanguage(language)
if (!query.get('out')) {
  store.signIn({ accountId: account, bundleId: bundle, handle: 'vodkolyan', accessToken: 'preview', refreshToken: 'preview-refresh',
    accessExpiresAt: '2099-01-01T00:00:00Z', refreshExpiresAt: '2099-01-01T00:00:00Z' })
  store.unlock()
}
const keys = new MemoryKeys()
keys.attach(store)
if (!query.get('out')) keys.hold(store.getSnapshot(), {
  accountId: account, bundleId: bundle,
  openEnvelope: async () => { throw new Error('preview') },
  decryptArtifact: async () => { throw new Error('preview') },
  sign: () => { throw new Error('preview') },
  lock: () => undefined,
})
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

const people: Record<string, string> = { 'iren.77': '33333333-4444-4555-8666-777777777777', vodkolyan: account, anna_k: '55555555-6666-4777-8888-999999999999' }
const day = (n: number) => new Date(Date.UTC(2026, 10, 9 - n, 18, 30)).toISOString().replace('.000Z', 'Z')
function row(n: number, status: string, handle: string | null) {
  const v2 = status === 'PENDING' || status === 'CLAIMED' || status === 'CANCELLED' || status === 'EXPIRED'
  return {
    capsule_id: `0000000${n}-aaaa-4bbb-8ccc-dddddddddddd`, protocol_version: v2 ? 2 : 1,
    delivery_id: v2 ? `1000000${n}-aaaa-4bbb-8ccc-dddddddddddd` : null, status,
    created_at: day(n), published_at: v2 ? null : day(n), expires_at: v2 ? day(n - 30) : null,
    first_opened_at: status === 'OPENED' ? day(n - 1) : null, revoked_at: null,
    recipient: v2 ? { user_id: null, handle: null, hint: '+31 6•• ••• 678', is_self: null }
      : { user_id: people[handle!], handle, hint: null, is_self: handle === 'vodkolyan' },
    locator: { status: 'ACTIVE', code: ['K7P2Q', 'M4XR9', 'T2HV8', 'W9C3N'][n % 4] },
    cancel_action: status === 'PENDING' ? 'CANCEL_PENDING_V2' : status === 'READY' ? 'REVOKE_V1' : null,
    cancel_deadline: status === 'READY' ? day(n - 7) : null,
  }
}
const outgoingRows = [row(1, 'PENDING', null), row(2, 'OPENED', 'iren.77'), row(3, 'READY', 'anna_k'), row(4, 'OPENED', 'vodkolyan')]

const ok = (body: unknown): ApiResult => ({ status: 200, body })
const transport: ApiTransport & ByteTransport = {
  async request(input: ApiRequest): Promise<ApiResult> {
    await new Promise((resolve) => setTimeout(resolve, 120))
    const path = input.path
    if (path === '/v1/capsules/outgoing') return ok({ items: query.get("empty") ? [] : outgoingRows, has_more: false, next_cursor: null })
    if (path === '/v1/recipients/recent') return ok({ items: [
      { user_id: people['iren.77'], handle: 'iren.77', last_sent_at: day(2) },
      { user_id: people.anna_k, handle: 'anna_k', last_sent_at: day(3) },
    ] })
    if (path === '/v1/me/send-access') return ok({ policy_version: 1, mode: 'enforce', lifetime: false, free: { self: { limit: 2, used: 1, remaining: 1 }, other: { limit: 2, used: 0, remaining: 2 } }, can_publish: { self: true, other: true } })
    const handle = /^\/v1\/directory\/handles\/(.+)$/.exec(path)?.[1]
    if (handle) {
      const id = people[handle]
      if (!id) return { status: 404, body: { code: 'NOT_FOUND' } }
      return ok({
        user: { user_id: id, handle },
        key_bundle: {
          key_bundle_id: '44444444-5555-4666-8777-888888888888', user_id: id,
          suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocol_version: 1,
          encryption_public_keyset: b64(keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)),
          signing_public_keyset: b64(keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)),
          status: 'ACTIVE', created_at: '2026-10-01T00:00:00Z',
        },
        directory_version: '0'.repeat(16),
      })
    }
    return { status: 503, body: { code: 'PREVIEW' } }
  },
  async requestBytes() { return { status: 503, bytes: new Uint8Array() } as never },
}

const target = query.get('path') ?? '/'
history.replaceState(null, '', target)
const host = document.createElement('div')
document.body.append(host)
createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
  preferences={{ get: () => language, set: () => undefined, remove: () => undefined }}
  transport={transport}
  atomicRegistration={{ request: async () => ({ status: 503, body: { code: 'PREVIEW' } }) }}
  pendingServicePin={{ keyId: 'preview', publicKeysetB64: 'AA' }}
  camera={{ open: async () => { throw new DOMException('preview', 'NotAllowedError') } }} />)
