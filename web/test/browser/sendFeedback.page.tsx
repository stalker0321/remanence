/** Actual App → Create → seal/publish/fetch; golden test keys and synthetic pixels only. */
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
import { signPublishStatement } from '../../src/crypto/publish'
import { b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import { catalog, type Language, type MessageKey } from '../../src/i18n/catalog'
import hpkeVector from '../../../protocol/fixtures/recipient-envelope-v1.json'
import signatureVector from '../../../protocol/fixtures/publish-signature-v1.json'
import { keysetFromJson, type JsonKeyset } from '../keysetJson'
import '../../src/design/tokens.css'

const account = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const bundle = '22222222-2222-4222-8222-222222222222'
const target = '33333333-4444-4555-8666-777777777777'
const targetBundle = '44444444-5555-4666-8777-888888888888'
const mode = new URLSearchParams(location.search).get('mode')
// Observe application fetch invocations separately from HTTP requests observed
// by the server: Chromium can resend on a reused connection after an empty reply.
const realFetch = window.fetch.bind(window)
const fetches: { method: string; path: string }[] = []
window.fetch = (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href)
  if (url.pathname.startsWith('/v1/')) fetches.push({ method: init?.method ?? 'GET', path: url.pathname })
  return realFetch(input, init)
}
const store = new SessionStore()
store.signIn({ accountId: account, bundleId: bundle, handle: 'fixture_qa', accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh', accessExpiresAt: '2026-10-21T00:00:00Z', refreshExpiresAt: '2026-11-21T00:00:00Z' })
store.unlock()
const keys = new MemoryKeys()
keys.attach(store)
let signs = 0
keys.hold(store.getSnapshot(), {
  accountId: account, bundleId: bundle,
  openEnvelope: async () => { throw new Error('unused fixture operation') },
  decryptArtifact: async () => { throw new Error('unused fixture operation') },
  sign: (statement) => {
    signs++
    if (mode === 'local') throw new Error('SYNTHETIC_PRIVATE_ERROR_DO_NOT_RENDER')
    return signPublishStatement(keysetFromJson(signatureVector.fixed_private_keyset_json as JsonKeyset), statement)
  },
  lock: () => undefined,
})
const memory = new PresentationMemory()
memory.attach(store)
const recipient = new RecipientDraft()
recipient.attach(store)
const ticket = recipient.nextTicket(account)
recipient.offer(ticket, account, {
  userId: target, handle: 'recipient_qa', keyBundleId: targetBundle,
  suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocolVersion: 1,
  encryptionPublicKeyset: b64(keysetFromJson(hpkeVector.fixed_public_keyset_json as JsonKeyset)),
  signingPublicKeyset: b64(keysetFromJson(signatureVector.fixed_public_keyset_json as JsonKeyset)),
  directoryVersion: '0'.repeat(16), self: false,
})
recipient.confirm()
const note = new NoteDraft()
note.attach(store)
const photos = new PhotoDraft()
photos.attach(store)
const music = new MusicDraft()
music.attach(store)
let frame: Uint8Array = new Uint8Array()
const host = document.createElement('div')
document.body.append(host)
createRoot(host).render(<App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
  preferences={{ get: () => null, set: () => undefined, remove: () => undefined }}
  transport={fetchTransport(location.origin)}
  camera={{ open: async () => ({
    preview: document.createElement('video'), stop: () => undefined,
    capture: async () => ({ bytes: frame.slice(), width: 640, height: 480 }),
  }) }} />)

async function jpeg(width: number, height: number, index: number, front = false): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')!
  ctx.fillStyle = '#101418'
  ctx.fillRect(0, 0, width, height)
  if (front) {
    const points = [[100, 80], [540, 60], [560, 400], [80, 420]]
    ctx.save()
    ctx.beginPath()
    points.forEach(([x, y], i) => i === 0 ? ctx.moveTo(x!, y!) : ctx.lineTo(x!, y!))
    ctx.closePath()
    ctx.clip()
    // Same deterministic textured quad used by the established real-send proof.
    let seed = 7
    const random = () => {
      seed |= 0
      seed = (seed + 0x6d2b79f5) | 0
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296
    }
    for (let i = 0; i < 260; i++) {
      const gray = Math.floor(random() * 256)
      ctx.fillStyle = `rgb(${gray},${gray},${gray})`
      ctx.fillRect(100 + random() * 480, 80 + random() * 340, 4 + random() * 22, 4 + random() * 22)
    }
    ctx.restore()
    ctx.lineWidth = 6
    ctx.strokeStyle = '#ffffff'
    ctx.stroke()
  } else {
    const image = ctx.createImageData(width, height)
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
      const at = (y * width + x) * 4
      image.data.set([20 + x / width * 200, 40 + index * 50, 20 + y / height * 200, 255], at)
    }
    ctx.putImageData(image, 0, 0)
  }
  const blob = await new Promise<Blob>((resolve) => canvas.toBlob((value) => resolve(value!), 'image/jpeg', 0.92))
  return new Uint8Array(await blob.arrayBuffer())
}
async function waitFor(predicate: () => boolean, label: string, timeout = 180000) {
  const until = Date.now() + timeout
  while (!predicate()) {
    if (Date.now() > until) throw new Error(`fixture timeout: ${label}`)
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
}
function button(key: MessageKey): HTMLButtonElement {
  const found = [...document.querySelectorAll('button')].find((item) => item.textContent === catalog[store.getSnapshot().language][key])
  if (!found) throw new Error(`button missing: ${key}`)
  return found
}
async function ready() {
  await waitFor(() => document.querySelector('.photo-draft input') !== null, 'Create')
  const dimensions = [[800, 600], [600, 800], [1000, 1000]]
  const transfer = new DataTransfer()
  for (let i = 0; i < dimensions.length; i++) {
    const [width, height] = dimensions[i]!
    const bytes = await jpeg(width!, height!, i)
    transfer.items.add(new File([new Uint8Array(bytes)], `synthetic-${i}.jpg`, { type: 'image/jpeg' }))
  }
  const input = document.querySelector<HTMLInputElement>('.photo-draft input[type=file]')!
  input.files = transfer.files
  input.dispatchEvent(new Event('change', { bubbles: true }))
  note.edit('Synthetic send feedback — спогад, воспоминание.')
  store.setLanguage('ru')
  store.setLanguage('en')
  await waitFor(() => photos.accepted()?.length === 3 && document.querySelector('[data-testid=creator-canvas]')?.getAttribute('data-painted') === 'ready', 'composer')
  await waitFor(() => !document.querySelector<HTMLButtonElement>('[data-testid=creator-freeze]')?.disabled, 'freeze button')
  document.querySelector<HTMLButtonElement>('[data-testid=creator-freeze]')!.click()
  await waitFor(() => document.querySelector('[data-testid=creator-frozen]') !== null, 'frozen')
  frame = await jpeg(640, 480, 0, true)
  button('useCamera').click()
  await waitFor(() => [...document.querySelectorAll('button')].some((item) => item.textContent === catalog.en.takePicture && !item.disabled), 'camera')
  button('takePicture').click()
  await waitFor(() => document.querySelector<HTMLButtonElement>('[data-testid=send_button]')?.disabled === false, 'send ready')
}
Object.assign(window, { sendFeedbackFixture: {
  ready, language: (value: Language) => { store.setLanguage(value); document.documentElement.lang = value },
  messages: (language: Language) => catalog[language],
  state: () => ({ signs, fetches: fetches.slice(), phase: store.getSnapshot().phase, photoCount: photos.photos().length, confirmed: recipient.confirmed()?.handle }),
} })

// Reuse the exact real-App setup in further isolated browser regressions.
export { store, keys, recipient, photos, note, ready, jpeg, waitFor, button }
