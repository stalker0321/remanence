/** T398: actual App/default recognition/crypto and unchanged API; synthetic custody/camera only. */
import { createRoot, type Root } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { SessionStore, type SignInInput } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { fetchTransport, type ApiRequest } from '../../src/auth/http'
import { AuthError } from '../../src/auth/errors'
import { PresentationMemory, browserUrls } from '../../src/collage/memory'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { FrontHold } from '../../src/create/front'
import { FrontFingerprintHold } from '../../src/create/frontFingerprint'
import { sealCapsule } from '../../src/create/sealCapsule'
import { publishCapsule } from '../../src/sync/publishCapsule'
import { openRecipientEnvelope } from '../../src/crypto/hpke'
import { decryptArtifact } from '../../src/crypto/artifact'
import { signPublishStatement } from '../../src/crypto/publish'
import { catalog } from '../../src/i18n/catalog'
import type { MessageKey } from '../../src/i18n/catalog'
import { HPKE_PRIVATE, SIGNING_PRIVATE, SENDER_ID, SENDER_BUNDLE, RECIPIENT_ID, RECIPIENT_BUNDLE,
  NOTE_TEXT, recipientSnapshot, senderSnapshot, jpeg, buildFreeze, texturedQuadJpeg } from './admissionMaterial'

const fixture = window as unknown as {
  t398Credentials?: { sender: SignInInput; recipient: SignInInput }
  t398: { run(phase: string): Promise<Record<string, unknown>>; checks: { name: string; ok: boolean }[] }
}
const credentials = fixture.t398Credentials!
delete fixture.t398Credentials
const checks: { name: string; ok: boolean; detail?: string }[] = []
function check(name: string, value: boolean): void {
  checks.push({ name, ok: value })
  if (!value) throw new Error(name)
}
async function waitFor(condition: () => boolean, name: string): Promise<void> {
  const deadline = Date.now() + 90000
  while (!condition()) {
    if (Date.now() > deadline) throw new Error('timeout: ' + name)
    await new Promise((ok) => setTimeout(ok, 20))
  }
}
async function click(key: MessageKey): Promise<void> {
  const button = () => [...document.querySelectorAll('button')].find((b) => b.textContent === catalog.en[key] && !b.disabled)
  await waitFor(() => !!button(), key)
  button()!.click()
}
const stage = () => document.querySelector('.stage') !== null
const zero = (arrays: Uint8Array[]) => arrays.every((bytes) => bytes.every((byte) => byte === 0))
const api = fetchTransport(location.origin)
const sender = new SessionStore('en')
sender.signIn(credentials.sender); sender.unlock()
const senderKeys = new MemoryKeys()
senderKeys.hold(sender.getSnapshot(), { accountId: SENDER_ID, bundleId: SENDER_BUNDLE,
  sign: (bytes) => signPublishStatement(SIGNING_PRIVATE, bytes), lock() {},
  openEnvelope: async () => { throw new Error('unused') }, decryptArtifact: async () => { throw new Error('unused') } })
const preparation = (async () => {
  const quad = await texturedQuadJpeg(7, 640, 480)
  const recipient = new RecipientDraft(); recipient.attach(sender)
  recipient.offer(recipient.nextTicket(SENDER_ID), SENDER_ID, senderSnapshot()); recipient.confirm()
  const hold = new FrontHold()
  await hold.open({ open: async () => ({ preview: document.createElement('video'),
    capture: async () => ({ bytes: quad.slice(), width: 640, height: 480 }), stop() {} }) })
  const still = await hold.take(recipient.confirmed()!)
  const fingerprints = new FrontFingerprintHold()
  const produced = await fingerprints.run(still, { store: sender, recipient, hold, mounted: () => true })
  const photos = await Promise.all([jpeg('p0', 300, 400), jpeg('p1', 300, 400), jpeg('p2', 300, 400)])
  const frozen = await buildFreeze(photos, NOTE_TEXT, sender.getSnapshot().generation)
  return { quad, wire: produced.wire, photos: frozen.slots.map((slot) => ({ ...photos.find((photo) => photo.id === slot.contentId)!, contentHash: slot.contentHash })), frozen }
})()
async function publish(): Promise<string> {
  const material = await preparation
  const capsule = await sealCapsule({ session: sender, keys: senderKeys, freeze: material.frozen,
    recipient: recipientSnapshot(), photos: material.photos, capsuleId: crypto.randomUUID(),
    candidateId: crypto.randomUUID(), createdAtEpochSeconds: BigInt(Math.floor(Date.now() / 1000)),
    epoch: 1n, frontFingerprint: material.wire, placeLabel: null })
  await publishCapsule({ capsule, session: sender, idempotencyKey: crypto.randomUUID(), transport: api })
  check('real signed draft/upload/finalize completed', true)
  return capsule.capsuleId
}
function makeRecipient() {
  const store = new SessionStore('en'); store.signIn(credentials.recipient); store.unlock()
  const keys = new MemoryKeys(), plain: Uint8Array[] = [], held: Uint8Array[] = [], urls = new Set<string>()
  keys.attach(store)
  keys.hold(store.getSnapshot(), { accountId: RECIPIENT_ID, bundleId: RECIPIENT_BUNDLE,
    openEnvelope: (info, bytes) => openRecipientEnvelope(HPKE_PRIVATE, info, bytes),
    decryptArtifact: async (...args) => { const bytes = await decryptArtifact(...args); plain.push(bytes); return bytes },
    sign: async () => { throw new Error('unused') }, lock() {} })
  const memory = new PresentationMemory({
    create(bytes, type) { held.push(bytes); const url = browserUrls.create(bytes, type); urls.add(url); return url },
    revoke(url) { urls.delete(url); browserUrls.revoke(url) },
  })
  memory.attach(store)
  return { store, keys, memory, plain, held, urls }
}
let rec: ReturnType<typeof makeRecipient>, root: Root | undefined, host: HTMLDivElement | undefined
let admissionGate: { at: 'request' | 'response'; entered(): void; wait: Promise<void> } | null = null
let loseReply = false
const transport = { ...api, async request(input: ApiRequest) {
  const isAdmission = input.path.endsWith('/first-open')
  const gate = isAdmission ? admissionGate : null
  if (gate?.at === 'request') { gate.entered(); await gate.wait }
  const result = await api.request(input)
  if (gate?.at === 'response') { gate.entered(); await gate.wait }
  if (isAdmission && loseReply) throw new AuthError('TRANSPORT_FAILED')
  return result
} }
async function mount(): Promise<void> {
  root?.unmount(); host?.remove()
  if (rec) { rec.store.lock(); check('previous owner resources cleared', zero(rec.plain) && zero(rec.held) && rec.urls.size === 0) }
  rec = makeRecipient()
  history.replaceState(null, '', '#/'); location.hash = '#/scan'
  host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  const material = await preparation
  root.render(<App store={rec.store} keys={rec.keys} memory={rec.memory} transport={transport}
    preferences={{ get: () => null, set() {}, remove() {} }} recipient={new RecipientDraft()}
    note={new NoteDraft()} photos={new PhotoDraft()} music={new MusicDraft()}
    camera={{ open: async () => ({ preview: document.createElement('video'),
      capture: async () => ({ bytes: material.quad.slice(), width: 640, height: 480 }), stop() {} }) }} />)
}
async function scan(): Promise<void> { await click('useCamera'); await click('takePicture'); await click('matchPicture') }
function gate(at: 'request' | 'response') {
  let reached!: () => void, release!: () => void
  const entered = new Promise<void>((ok) => { reached = ok })
  admissionGate = { at, entered: reached, wait: new Promise<void>((ok) => { release = ok }) }
  return { entered, release }
}
async function post(id: string, operation: 'first-open' | 'revoke', token?: string) {
  return api.request({ method: 'POST', path: `/v1/capsules/${id}/${operation}`, token })
}
let openedId = ''
fixture.t398 = { checks, async run(phase) {
  if (phase === 'revoked') {
    const id = await publish(); await mount()
    const pending = gate('request'); await scan(); await pending.entered
    check('full local preparation before admission', rec.plain.length >= 5 && !stage() && rec.memory.getSnapshot() === null)
    check('real sender revoke wins', (await post(id, 'revoke', credentials.sender.accessToken)).status === 200)
    admissionGate = null; pending.release()
    await waitFor(() => document.body.textContent?.includes(catalog.en.openDenied) === true, 'real revoke denial')
    check('cached ciphertext cannot bypass real revoke409', !stage() && rec.memory.getSnapshot() === null
      && rec.store.getSnapshot().phase === 'keys-unlocked' && zero(rec.plain) && rec.urls.size === 0)
    return { capsuleId: id }
  }
  if (phase === 'back') {
    openedId = await publish(); await mount()
    const pending = gate('response'); await scan(); await pending.entered
    check('real committed response held before local presentation', !stage() && rec.memory.getSnapshot() === null)
    let offRoute = false
    const detach = rec.store.subscribe(() => { if (location.hash !== '#/scan' && rec.store.getSnapshot().phase === 'presentation-admitted') offRoute = true })
    const departed = new Promise<void>((ok) => window.addEventListener('popstate', () => { pending.release(); ok() }, { once: true }))
    history.back(); await departed
    await waitFor(() => zero(rec.plain), 'native Back plaintext cleanup')
    checks.push({ name: 'native Back event checkpoint (observation, not verdict)', ok: true,
      detail: JSON.stringify({ hash: location.hash, phase: rec.store.getSnapshot().phase,
        offRoute, stage: stage(), sheet: rec.memory.getSnapshot() !== null, urls: rec.urls.size }) })
    // Decrypt outputs may already be zero before receive's admission catch has
    // settled. Observe the terminal phase, retaining the monotonic subscriber
    // above so even a transient off-route admit cannot disappear from evidence.
    await waitFor(() => rec.store.getSnapshot().phase !== 'postcard-matched', 'native receive settled')
    check('native Back cannot admit late real committed response', !offRoute && location.hash === '#/' && !stage()
      && rec.memory.getSnapshot() === null && rec.store.getSnapshot().phase === 'keys-unlocked' && rec.urls.size === 0)
    detach(); admissionGate = null
    return { capsuleId: openedId }
  }
  if (phase === 'replay') {
    await mount(); await scan(); await waitFor(stage, 'real replay presentation')
    check('actual App real crypto renders three photos and sealed note', rec.store.getSnapshot().phase === 'presentation-admitted'
      && rec.memory.getSnapshot()?.photos.size === 3 && rec.memory.getSnapshot()?.scene.note?.text === NOTE_TEXT && rec.urls.size === 3)
    check('real revoke after first-open denied', (await post(openedId, 'revoke', credentials.sender.accessToken)).status === 409)
    check('actual auth rejects missing bearer', (await post(openedId, 'first-open')).status === 401)
    check('actual ownership rejects sender first-open', (await post(openedId, 'first-open', credentials.sender.accessToken)).status === 404)
    return { capsuleId: openedId }
  }
  if (phase === 'unknown') {
    await mount(); loseReply = true; await scan()
    await waitFor(() => document.body.textContent?.includes(catalog.en.openUnconfirmed) === true, 'lost real reply')
    check('lost real reply is unconfirmed, no local presentation', !stage() && rec.memory.getSnapshot() === null
      && rec.store.getSnapshot().phase === 'keys-unlocked' && zero(rec.plain))
    loseReply = false; await click('matchPicture'); await waitFor(stage, 'explicit real replay after loss')
    check('explicit retry after lost response admits', rec.memory.getSnapshot()?.photos.size === 3)
    return { capsuleId: openedId }
  }
  if (phase === 'finish') {
    rec.store.lock(); root?.unmount()
    check('final private copies and URLs cleared', zero(rec.plain) && zero(rec.held) && rec.urls.size === 0)
    check('no durable private browser storage', localStorage.length === 0 && sessionStorage.length === 0 && (await caches.keys()).length === 0)
    return {}
  }
  throw new Error('unknown test phase')
} }
