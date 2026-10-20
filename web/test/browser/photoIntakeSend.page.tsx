/** Real App/PhotoIntake/normalizer/controller/fetch. Only synthetic File timing is gated. */
import { store, keys, recipient, photos, ready, jpeg, waitFor, button } from './sendFeedback.page'
import { SendAttemptController } from '../../src/create/sendAttempt'
import { catalog } from '../../src/i18n/catalog'
import { PhotoIntake } from '../../src/create/normalize'

type Gate = { started: boolean; released: boolean; done: boolean; fail: boolean; malformed: boolean; promise: Promise<void>; release: () => void }
const gates = new Map<string, Gate>()
const settledIntakes: { names: string[]; added: number; completed: boolean }[] = []
let currentIntake: PhotoIntake | null = null
const originalAdd = PhotoIntake.prototype.addFiles
PhotoIntake.prototype.addFiles = function (files, guard) {
  currentIntake = this
  const result = originalAdd.call(this, files, guard)
  // Observe settlement without replacing the promise returned to the App.
  void result.then((outcome) => {
    settledIntakes.push({ names: Array.from(files).map((file) => (file as File).name), added: outcome.added, completed: outcome.completed })
  }, () => undefined)
  return result
}
const initialRecipient = { ...recipient.confirmed()! }
const nextRecipient = { ...initialRecipient, userId: '55555555-6666-4777-8888-999999999999', handle: 'next_qa' }
const initialCapabilities = keys.capabilities(store.getSnapshot())!
const originalRead = File.prototype.arrayBuffer
File.prototype.arrayBuffer = async function () {
  const gate = gates.get(this.name)
  if (gate) {
    gate.started = true
    await gate.promise
    gate.done = true
    if (gate.fail) throw new Error('SYNTHETIC_FILE_READ_FAILURE')
    if (gate.malformed) return new Uint8Array([255,216,255,192,0,11,8,0,10,0,10,1,1,17,0,255,217]).buffer
  }
  return originalRead.call(this)
}
const begins: { admitted: boolean; ids: string[]; slots: string[]; owner: string; recipient: string }[] = []
const originalBegin = SendAttemptController.prototype.begin
SendAttemptController.prototype.begin = function (visit) {
  const admitted = originalBegin.call(this, visit)
  begins.push({ admitted, ids: visit.photos.map((photo) => photo.id), slots: visit.freeze.slots.map((slot) => slot.contentId),
    owner: visit.freeze.ownerId, recipient: visit.freeze.recipient.userId })
  return admitted
}
let staleSend: (() => void) | null = null
function rememberHandler() {
  const send = document.querySelector('[data-testid=send_button]')!
  // Deliberately exercise the REAL previously-rendered callback before a new
  // render can protect it. This is a pinned-React test observation, not a stub.
  const key = Object.keys(send).find((value) => value.startsWith('__reactProps$'))!
  staleSend = (send as unknown as Record<string, { onClick: () => void }>)[key]!.onClick
}
const files = new Map<string, File>()
async function prepareFile(name: string, index: number) {
  const bytes = await jpeg(700 + index * 30, 620, index)
  files.set(name, new File([new Uint8Array(bytes)], name, { type: 'image/jpeg' }))
}
function pick(names: string[], race = false) {
  const transfer = new DataTransfer()
  for (const name of names) transfer.items.add(files.get(name)!)
  const input = document.querySelector<HTMLInputElement>('.photo-draft input[type=file]')!
  input.files = transfer.files
  input.dispatchEvent(new Event('change', { bubbles: true }))
  if (race) staleSend!()
}
function gate(name: string, fail = false, malformed = false) {
  let release!: () => void
  const promise = new Promise<void>((resolve) => { release = resolve })
  gates.set(name, { started: false, released: false, done: false, fail, malformed, promise, release })
}
function release(name: string) {
  const value = gates.get(name)!
  value.released = true
  value.release()
}
async function freeze(count: number) {
  await waitFor(() => photos.photos().length === count
    && document.querySelectorAll('[data-testid^=creator-slot-]').length === count
    && document.querySelector('[data-testid=creator-canvas]')?.getAttribute('data-painted') === 'ready', 'current composer')
  await waitFor(() => !document.querySelector<HTMLButtonElement>('[data-testid=creator-freeze]')?.disabled, 'freeze enabled')
  document.querySelector<HTMLButtonElement>('[data-testid=creator-freeze]')!.click()
  await waitFor(() => document.querySelector('[data-testid=creator-frozen]') !== null, 'explicit current freeze')
}
async function front() {
  button('useCamera').click()
  await waitFor(() => [...document.querySelectorAll('button')].some((item) => item.textContent === catalog.en.takePicture && !item.disabled), 'camera')
  button('takePicture').click()
  await waitFor(() => document.querySelector('main > .status[role=alert]')?.textContent === catalog.en.frontReady, 'front fingerprint')
}
Object.assign(window, { photoIntakeFixture: {
  async ready() {
    await ready()
    await prepareFile('intake-D.jpg', 3)
    await prepareFile('intake-E.jpg', 4)
    await prepareFile('new-A.jpg', 0)
    await prepareFile('new-B.jpg', 1)
    await prepareFile('new-C.jpg', 2)
    rememberHandler()
  },
  pick, gate, release, freeze, front, rememberHandler,
  cancelIntake: () => currentIntake!.cancel(),
  replaceOwner() {
    store.signIn({ accountId: 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff', bundleId: '22222222-2222-4222-8222-222222222222',
      handle: 'replacement_qa', accessToken: 'synthetic-next-access', refreshToken: 'synthetic-next-refresh',
      accessExpiresAt: '2026-10-21T00:00:00Z', refreshExpiresAt: '2026-11-21T00:00:00Z' })
  },
  unlockOwner() {
    store.unlock()
    const view = store.getSnapshot()
    keys.hold(view, { ...initialCapabilities, accountId: view.accountId!, bundleId: view.bundleId! })
  },
  directory: () => ({
    user: { user_id: nextRecipient.userId, handle: nextRecipient.handle },
    key_bundle: { key_bundle_id: nextRecipient.keyBundleId, user_id: nextRecipient.userId,
      suite: nextRecipient.suite, protocol_version: 1, encryption_public_keyset: nextRecipient.encryptionPublicKeyset,
      signing_public_keyset: nextRecipient.signingPublicKeyset, status: 'ACTIVE', created_at: '2026-10-20T00:00:00Z' },
    directory_version: nextRecipient.directoryVersion,
  }),
  staleClick: () => staleSend!(),
  state: () => ({
    begins: begins.slice(), photoIds: photos.photos().map((photo) => photo.id),
    hashes: photos.photos().map((photo) => photo.contentHash),
    owner: store.getSnapshot().accountId, generation: store.getSnapshot().generation,
    recipient: recipient.confirmed()?.userId,
    gates: [...gates].map(([name, value]) => ({ name, started: value.started, released: value.released, done: value.done })),
    settledIntakes: settledIntakes.slice(),
    disabled: document.querySelector<HTMLButtonElement>('[data-testid=send_button]')?.disabled,
    intakeBusy: document.querySelector('.photo-draft')?.getAttribute('aria-busy'),
    frozen: document.querySelector('[data-testid=creator-frozen]')?.textContent,
  }),
  messages: () => catalog.en,
} })

export { store, keys, recipient, photos, button, waitFor, freeze }
