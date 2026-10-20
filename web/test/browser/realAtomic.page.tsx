/** Actual production App/transports/crypto. TEST-only synthetic provider ports
 * obtain RSA Google / ES256 WebAuthn signatures from isolated fixture sidecar.
 * No authenticated user, bundle, account keys or API success are injected. */
import { createRoot } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { PresentationMemory } from '../../src/collage/memory'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { fetchTransport } from '../../src/auth/http'
import { atomicTransport, type AtomicPort, type FinalIntent } from '../../src/auth/atomicWire'
import type { PublicAssertion } from '../../src/auth/passkey'
import { parseHpkePublicKeyset } from '../../src/crypto/tink'
import { unb64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { DhkemX25519HkdfSha256 } from '@hpke/core'
import { arrayBuffer, utf8 } from '../../src/protocol/bytes'
import { sealTo } from '../recoveryFixture'
import '../../src/design/tokens.css'

const store = new SessionStore(), keys = new MemoryKeys(), memory = new PresentationMemory()
const recipient = new RecipientDraft(), note = new NoteDraft(), photos = new PhotoDraft(), music = new MusicDraft()
for (const owner of [keys, memory, recipient, note, photos, music]) owner.attach(store)
const api = fetchTransport(''), wire = atomicTransport('')
const secrets: Uint8Array[] = []
let challenge: number[] | null = null
let candidate: { accountId: string; bundleId: string } | null = null
async function fixture(path: 'google' | 'passkey', body: unknown): Promise<unknown> {
  const result = await fetch(`/__fixture/${path}`, { method: 'POST', body: JSON.stringify(body), headers: { 'content-type': 'application/json' },
    credentials: 'omit', cache: 'no-store', redirect: 'error' })
  if (!result.ok) throw new Error('Synthetic provider fixture failed')
  return result.json()
}
const setup: AtomicPort = { async request(input) {
  if (input.action === 'finalize' && challenge === null) {
    const intent = input.body as FinalIntent
    const pub = parseHpkePublicKeyset(unb64(intent.key_bundle.encryption_public_keyset))
    const key = await new DhkemX25519HkdfSha256().importKey('raw', arrayBuffer(pub.raw), true)
    // Ciphertext addressed to the candidate BEFORE its first commit. Only this
    // ciphertext/public tuple may cross into the browser driver's RAM.
    challenge = Array.from(await sealTo(key, pub.keyId, utf8('T404 prior ciphertext'), utf8('T404 original identity')))
    candidate = { accountId: intent.record.account_id, bundleId: intent.record.bundle_id }
  }
  return wire.request(input)
} }
async function passkey(options: unknown, create: boolean) {
  const response = await fixture('passkey', { options, create }) as PublicAssertion
  const prf = create ? null : new Uint8Array(32).fill(7)
  if (prf) secrets.push(prf)
  return { id: response.id, publicResponse: response, prf }
}
const host = document.createElement('div'); document.body.append(host)
createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
  transport={api} atomicRegistration={setup}
  google={{ async requestIdToken(clientId, nonce) { return (await fixture('google', { clientId, nonce }) as { token: string }).token } }}
  passkeys={{ canAuthenticate: () => true, create: (options) => passkey(options, true), get: (options) => passkey(options, false) }}
  preferences={{ get: () => null, set: () => undefined, remove: () => undefined }} />)
Object.assign(window, { realAtomic: {
  state: () => ({ ...store.getSnapshot(), keys: keys.capabilities(store.getSnapshot()) !== null,
    setup: document.querySelector('[data-atomic-phase]')?.getAttribute('data-atomic-phase'), wiped: secrets.every((b) => b.every((v) => v === 0)) }),
  challenge: () => ({ encrypted: challenge, candidate }),
  prove: async (encrypted: number[]) => {
    const plain = await keys.capabilities(store.getSnapshot())!.openEnvelope(utf8('T404 prior ciphertext'), new Uint8Array(encrypted))
    try { return new TextDecoder().decode(plain) === 'T404 original identity' } finally { plain.fill(0) }
  },
} })
