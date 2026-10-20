/** Actual App + production crypto; synthetic server/provider contract fixtures.
 * This page never claims real WebAuthn, Google, database or server proof. */
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
import { AtomicFixture, RESERVATION } from '../atomicFixture'
import type { FinalIntent } from '../../src/auth/atomicWire'
import { parseHpkePublicKeyset } from '../../src/crypto/tink'
import { unb64 } from '../../../../server/src/remanence/recovery/web/crypto.mjs'
import { DhkemX25519HkdfSha256 } from '@hpke/core'
import { arrayBuffer, utf8 } from '../../src/protocol/bytes'
import { sealTo } from '../recoveryFixture'
import '../../src/design/tokens.css'

const fixture = new AtomicFixture()
const store = new SessionStore(), keys = new MemoryKeys(), memory = new PresentationMemory()
const recipient = new RecipientDraft(), note = new NoteDraft(), photos = new PhotoDraft(), music = new MusicDraft()
for (const owner of [keys, memory, recipient, note, photos, music]) owner.attach(store)
let hold = '', reached = '', release: (() => void) | null = null
fixture.pause = async (point) => { if (point === hold) { reached = point; await new Promise<void>((done) => { release = done }) } }
const host = document.createElement('div'); document.body.append(host)
createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
  transport={fixture.api} atomicRegistration={fixture.setup} google={fixture.google} passkeys={fixture.passkeys}
  preferences={{ get: () => null, set: () => undefined, remove: () => undefined }} />)
function resume() { hold = ''; release?.(); release = null }
Object.assign(window, { atomic: {
  messages: catalog, language: (language: Language) => { store.setLanguage(language); document.documentElement.lang = language },
  mode: (config: { finalMode?: AtomicFixture['finalMode']; readMode?: AtomicFixture['readMode']; noPrf?: boolean; cancelProvider?: boolean; terminal?: boolean }) => {
    if (config.finalMode) fixture.finalMode = config.finalMode
    if (config.readMode) fixture.readMode = config.readMode
    if (config.noPrf !== undefined) fixture.noPrf = config.noPrf
    if (config.cancelProvider !== undefined) fixture.cancelProvider = config.cancelProvider
    if (config.terminal) fixture.reservation.state = 'EXPIRED'
  },
  state: () => ({ ...store.getSnapshot(), keys: keys.capabilities(store.getSnapshot()) !== null, setup: document.querySelector('[data-atomic-phase]')?.getAttribute('data-atomic-phase'),
    creates: fixture.creates, assertions: fixture.assertions, commits: fixture.provisions, attempts: fixture.intents.length, calls: fixture.calls,
    wiped: fixture.secrets.every((bytes) => bytes.every((b) => b === 0)) }),
  hold: (point: string) => { hold = point; reached = '' }, reached: () => reached, release: resume,
  releaseOnBack: () => { window.addEventListener('popstate', resume, { once: true }) },
  replace: () => { store.signIn({ accountId: RESERVATION, bundleId: RESERVATION, handle: 'newer', accessToken: 'synthetic-newer', refreshToken: 'unused', accessExpiresAt: '', refreshExpiresAt: '' }) },
  saved: async () => {
    const saved = fixture.saved!
    const { final_authorization: _secret, ...publicIntent } = saved
    const pub = parseHpkePublicKeyset(unb64(saved.key_bundle.encryption_public_keyset))
    const key = await new DhkemX25519HkdfSha256().importKey('raw', arrayBuffer(pub.raw), true)
    return { publicIntent, encrypted: Array.from(await sealTo(key, pub.keyId, utf8('prior document'), utf8('original keys only'))) }
  },
  useSaved: (publicIntent: Omit<FinalIntent, 'final_authorization'>) => {
    fixture.saved = { ...publicIntent, final_authorization: 'unused-synthetic' }
    fixture.reservation.state = 'ACTIVE'; fixture.reservation.account_id = publicIntent.record.account_id
    fixture.reservation.credential_ids = [publicIntent.record.credential_id]
  },
  prove: async (encrypted: number[]) => {
    const plain = await keys.capabilities(store.getSnapshot())!.openEnvelope(utf8('prior document'), new Uint8Array(encrypted))
    try { return new TextDecoder().decode(plain) === 'original keys only' } finally { plain.fill(0) }
  },
} })
