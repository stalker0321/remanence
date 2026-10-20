/** Actual App + existing recovery crypto. Synthetic identity/passkey/HTTP ports,
 * NEVER real Google/WebAuthn/backend evidence. Private fixtures stay in memory. */
import { createRoot } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { PresentationMemory } from '../../src/collage/memory'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { AuthError } from '../../src/auth/errors'
import type { ApiTransport, ByteTransport } from '../../src/auth/http'
import type { RecoveryReply } from '../../src/crypto/keyOwner'
import { catalog, type Language } from '../../src/i18n/catalog'
import { syntheticRecovery, sealTo } from '../recoveryFixture'
import { recipientEnvelopeInfo } from '../../src/protocol/context'
import { utf8 } from '../../src/protocol/bytes'
import '../../src/design/tokens.css'

async function main() {
  const fixture = await syntheticRecovery()
  let reply = fixture.reply, mode = 'existing', hold = '', reached = '', release: (() => void) | null = null
  const secrets: Uint8Array[] = [], calls: string[] = []
  const store = new SessionStore(), keys = new MemoryKeys(), memory = new PresentationMemory()
  const recipient = new RecipientDraft(), note = new NoteDraft(), photos = new PhotoDraft(), music = new MusicDraft()
  for (const item of [keys, memory, recipient, note, photos, music]) item.attach(store)
  const pause = async (step: string) => { if (hold === step) { reached = step; await new Promise<void>((ok) => { release = ok }) } }
  const transport: ApiTransport & ByteTransport = {
    requestBytes: async () => { throw new Error('no ciphertext route in onboarding fixture') },
    async request(input) {
      calls.push(`${input.method} ${input.path}`)
      const part = input.path.split('/').at(-1)!
      await pause(input.path.includes('/google/') ? `google-${part}` : part)
      if (input.path === '/v1/auth/google/options') return { status: 200, body: { flow: '11111111-1111-4111-8111-111111111111', nonce: 'A'.repeat(43), client_id: 'synthetic' } }
      if (input.path === '/v1/auth/google/verify') {
        if (mode === 'new') return { status: 200, body: { status: 'NEEDS_HANDLE', flow: '11111111-1111-4111-8111-111111111111', registration_token: 'A'.repeat(43) } }
        return { status: 200, body: { status: 'SIGNED_IN', login: { user: { user_id: reply.record.account_id, handle: 'synthetic' },
          active_key_bundle: { key_bundle_id: reply.record.bundle_id }, access_token: 'synthetic-only', refresh_token: 'unused', access_expires_at: '', refresh_expires_at: '' } } }
      }
      if (input.path === '/v1/recovery/status') {
        if (mode === 'offline') throw new AuthError('TRANSPORT_FAILED')
        return { status: 200, body: { state: mode === 'missing' ? 'NOT_STARTED' : mode === 'pending' ? 'PENDING' : 'STORED',
          credential_id: mode === 'missing' ? null : reply.record.credential_id } }
      }
      if (input.path === '/v1/recovery/setup/options') return { status: 200, body: { flow: 'synthetic', options: {} } }
      if (input.path === '/v1/recovery/setup/verify') return { status: 200, body: { authentication_verified: true, credential_id: reply.record.credential_id } }
      if (input.path === '/v1/recovery/record' && input.method === 'GET') return { status: 200,
        body: mode === 'wrong-record' ? { ...reply, record: { ...reply.record, account_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' } } : reply }
      throw new Error('unexpected identity/account write')
    },
  }
  const host = document.createElement('div'); document.body.append(host)
  createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
    transport={transport} preferences={{ get: () => null, set: () => undefined, remove: () => undefined }}
    google={{ async requestIdToken() { calls.push('SYNTHETIC identity'); await pause('identity'); return 'synthetic' } }}
    passkeys={{ canAuthenticate: () => true, create: async () => { throw new Error('duplicate/new passkey forbidden') },
      async get() {
        calls.push('SYNTHETIC passkey'); await pause('passkey')
        if (mode === 'cancelled') throw new AuthError('PASSKEY_CANCELLED')
        const secret = fixture.prf.slice(); secrets.push(secret)
        const id = reply.record.credential_id
        return { id, prf: mode === 'no-prf' ? null : secret,
          publicResponse: { id, rawId: id, type: 'public-key', response: { clientDataJSON: 'e30' }, clientExtensionResults: {} } }
      } }} />)
  const info = () => recipientEnvelopeInfo({ capsuleId: '00010203-0405-0607-0809-0a0b0c0d0e0f',
    senderUserId: '10111213-1415-1617-1819-1a1b1c1d1e1f', recipientUserId: reply.record.account_id, recipientKeyBundleId: reply.record.bundle_id })
  Object.assign(window, { journey: {
    mode: (value: string) => { mode = value }, language: (value: Language) => store.setLanguage(value), messages: catalog,
    state: () => ({ ...store.getSnapshot(), keys: keys.capabilities(store.getSnapshot()) !== null }), calls,
    hold: (value: string) => { hold = value; reached = '' }, reached: () => reached,
    release: () => { hold = ''; release?.(); release = null },
    logout: () => store.signOut(), lock: () => store.lock(),
    replace: () => store.signIn({ accountId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', bundleId: reply.record.bundle_id,
      handle: 'replacement', accessToken: 'other', refreshToken: 'other', accessExpiresAt: '', refreshExpiresAt: '' }),
    wiped: () => secrets.every((secret) => secret.every((n) => n === 0)),
    // Encrypted record + encrypted challenge only; driver retains them in RAM
    // across page reload, never localStorage/file. No private identity exported.
    saved: async () => ({ reply, challenge: Array.from(await sealTo(fixture.publicKey, fixture.encryptionKeyId, info(), utf8('same-private-identity'))) }),
    useSaved: (saved: RecoveryReply) => { reply = saved },
    prove: async (bytes: number[]) => {
      const opened = await keys.capabilities(store.getSnapshot())!.openEnvelope(info(), new Uint8Array(bytes))
      try { return new TextDecoder().decode(opened) === 'same-private-identity' } finally { opened.fill(0) }
    },
  } })
}
void main()
