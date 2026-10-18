/**
 * Connected Create photo test (T228, Chromium-only gate).
 *
 * Mounts the REAL production `Create` component with a real `SessionStore`,
 * `RecipientDraft`, `PhotoDraft` and the REAL `browserPhotoCodec` wrapped
 * in a decode gate — then suspends decode mid-flight and disrupts the visit
 * (unmount / lock / recipient restart). Asserts the fenced outcome: no
 * draft photos, no late object URLs, no late DOM, no success notice.
 * A positive control proves the same seam accepts photos when live.
 *
 * Uses only synthetic bytes and no network/provider/camera. Reports
 * `window.__createPhotos = { assertions, failures }` for the node runner.
 */
import { createRoot } from 'react-dom/client'
import type { MessageKey } from '../../src/i18n/catalog'
import { Create } from '../../src/ui/App'
import { NoteDraft } from '../../src/create/note'
import { MusicDraft } from '../../src/create/music'
import { PhotoDraft } from '../../src/create/photos'
import type { PhotoCodec } from '../../src/create/normalize'
import { browserPhotoCodec } from '../../src/create/browserPhoto'
import { RecipientDraft, type RecipientSnapshot } from '../../src/create/recipient'
import { SessionStore } from '../../src/session/session'
import type { ApiTransport } from '../../src/auth/http'
import type { CameraPort } from '../../src/scan/camera'

export interface CheckResult {
  name: string
  ok: boolean
  detail?: string
}

const assertions: CheckResult[] = []
const failures: string[] = []

function pass(name: string): void {
  assertions.push({ name, ok: true })
}

function fail(name: string, detail: string): void {
  assertions.push({ name, ok: false, detail })
  failures.push(`${name}: ${detail}`)
}

function assert(name: string, cond: boolean, detail = ''): void {
  if (cond) pass(name)
  else fail(name, detail)
}

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'

function snapshotFor(accountId: string): RecipientSnapshot {
  return {
    userId: accountId,
    handle: 'ada',
    keyBundleId: BUNDLE,
    suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519',
    protocolVersion: 1,
    encryptionPublicKeyset: 'x'.repeat(10),
    signingPublicKeyset: 'y'.repeat(10),
    directoryVersion: '0'.repeat(16),
    self: false,
  }
}

function makeFile(name: string, bytes: Uint8Array): File {
  return new File([bytes.buffer as ArrayBuffer], name, { type: 'image/jpeg' })
}

async function gradientJpeg(width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  const image = context.createImageData(width, height)
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const at = (y * width + x) * 4
      const tone = Math.floor((x / width) * 200) + 28
      image.data[at] = tone
      image.data[at + 1] = 255 - tone
      image.data[at + 2] = 128
      image.data[at + 3] = 255
    }
  }
  context.putImageData(image, 0, 0)
  const blob = await new Promise<Blob | null>((resolve) => {
    canvas.toBlob((result) => resolve(result), 'image/jpeg', 0.9)
  })
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

interface Harness {
  container: HTMLElement
  unmount: () => void
  store: SessionStore
  recipient: RecipientDraft
  draft: PhotoDraft
  notices: string[]
  createdUrls: string[]
  setGate: (gate: Promise<void>) => void
  decodeCalls: () => number
  pick: (file: File) => void
}

function mountHarness(): Harness {
  const store = new SessionStore('en')
  store.signIn({
    accountId: ACCOUNT,
    bundleId: BUNDLE,
    handle: 'ada',
    accessToken: 'access-token-value',
    refreshToken: 'refresh-token-value',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  store.unlock()
  const recipient = new RecipientDraft()
  recipient.attach(store)
  const ticket = recipient.nextTicket(ACCOUNT)
  recipient.offer(ticket, ACCOUNT, snapshotFor(ACCOUNT))
  recipient.confirm()
  const draft = new PhotoDraft()
  draft.attach(store)
  const notices: string[] = []
  const createdUrls: string[] = []
  const realCreateObjectURL = URL.createObjectURL.bind(URL)
  URL.createObjectURL = ((object: Blob | MediaSource) => {
    const url = realCreateObjectURL(object as Blob)
    createdUrls.push(url)
    return url
  }) as typeof URL.createObjectURL

  let gate: Promise<void> | null = null
  let decodeCalls = 0
  const real = browserPhotoCodec(document)
  const codec: PhotoCodec = {
    async decode(bytes) {
      decodeCalls += 1
      if (gate) await gate
      return real.decode(bytes)
    },
  }
  const transport: ApiTransport = {
    request: async () => {
      throw new Error('no network in photo test')
    },
  }
  const camera: CameraPort = {
    open: async () => {
      throw new Error('no camera in photo test')
    },
  }
  const container = document.createElement('div')
  document.body.append(container)
  const root = createRoot(container)
  root.render(
    <Create
      t={(key: MessageKey) => key}
      store={store}
      transport={transport}
      recipient={recipient}
      camera={camera}
      note={new NoteDraft()}
      photos={draft}
      music={new MusicDraft()}
      onNotice={(notice: MessageKey) => {
        notices.push(notice)
      }}
      photoCodec={codec}
    />,
  )
  return {
    container,
    unmount: () => {
      root.unmount()
      URL.createObjectURL = realCreateObjectURL
    },
    store,
    recipient,
    draft,
    notices,
    createdUrls,
    setGate: (next: Promise<void>) => {
      gate = next
    },
    decodeCalls: () => decodeCalls,
    pick: (file: File) => {
      const input = container.querySelector('.photo-draft input[type="file"]') as HTMLInputElement | null
      if (!input) throw new Error('photo input missing')
      const transfer = new DataTransfer()
      transfer.items.add(file)
      input.files = transfer.files
      input.dispatchEvent(new Event('change', { bubbles: true }))
    },
  }
}

async function untilStarted(calls: () => number): Promise<void> {
  const deadline = Date.now() + 5000
  while (calls() === 0) {
    if (Date.now() > deadline) throw new Error('decode never started')
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
}

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 300))
}

async function main(): Promise<void> {
  try {
    const bytes = await gradientJpeg(96, 96)

    // Positive control: no disruption — the pick lands, URL + DOM + notice.
    {
      const h = mountHarness()
      await new Promise((resolve) => setTimeout(resolve, 0))
      h.setGate(Promise.resolve())
      const urlsBefore = h.createdUrls.length
      h.pick(makeFile('ok.jpg', bytes))
      const deadline = Date.now() + 5000
      while (h.draft.photos().length === 0) {
        if (Date.now() > deadline) throw new Error('positive pick never landed')
        await new Promise((resolve) => setTimeout(resolve, 10))
      }
      assert('control pick lands in draft', h.draft.photos().length === 1)
      assert('control creates one object URL', h.createdUrls.length === urlsBefore + 1)
      assert(
        'control renders one thumbnail',
        h.container.querySelectorAll('.photo-draft ul li').length === 1,
      )
      assert('control clears loading notice', h.notices[h.notices.length - 1] === 'errorRegion')
      h.unmount()
    }

    // Unmount mid-decode: no draft photos, no late URLs/DOM/notice.
    {
      const h = mountHarness()
      await new Promise((resolve) => setTimeout(resolve, 0))
      let release!: () => void
      h.setGate(new Promise<void>((ok) => {
        release = ok
      }))
      h.pick(makeFile('a.jpg', bytes))
      await untilStarted(h.decodeCalls)
      const urlsBefore = h.createdUrls.length
      const noticesBefore = h.notices.length
      h.unmount()
      release()
      await settle()
      assert('unmount drops pending photo', h.draft.photos().length === 0)
      assert('unmount creates no late object URLs', h.createdUrls.length === urlsBefore)
      assert('unmount emits no late notice', h.notices.length === noticesBefore)
      assert('unmount leaves no photo DOM', h.container.querySelectorAll('.photo-draft ul li').length === 0)
    }

    // Lock mid-decode: session incarnation moves on; output is dropped.
    {
      const h = mountHarness()
      await new Promise((resolve) => setTimeout(resolve, 0))
      let release!: () => void
      h.setGate(new Promise<void>((ok) => {
        release = ok
      }))
      h.pick(makeFile('b.jpg', bytes))
      await untilStarted(h.decodeCalls)
      const urlsBefore = h.createdUrls.length
      const noticesBefore = h.notices.length
      h.store.lock()
      release()
      await settle()
      assert('lock drops pending photo', h.draft.photos().length === 0)
      assert('lock creates no late object URLs', h.createdUrls.length === urlsBefore)
      assert('lock emits no late notice', h.notices.length === noticesBefore)
      assert('lock leaves no late DOM', h.container.querySelectorAll('.photo-draft ul li').length === 0)
      h.unmount()
    }

    // Recipient replacement mid-decode: the confirmed identity the pick was
    // fenced to is gone; output is dropped.
    {
      const h = mountHarness()
      await new Promise((resolve) => setTimeout(resolve, 0))
      let release!: () => void
      h.setGate(new Promise<void>((ok) => {
        release = ok
      }))
      h.pick(makeFile('c.jpg', bytes))
      await untilStarted(h.decodeCalls)
      const urlsBefore = h.createdUrls.length
      const noticesBefore = h.notices.length
      h.recipient.restart()
      release()
      await settle()
      assert('recipient restart drops pending photo', h.draft.photos().length === 0)
      assert('recipient restart creates no late object URLs', h.createdUrls.length === urlsBefore)
      assert('recipient restart emits no late notice', h.notices.length === noticesBefore)
      h.unmount()
    }
  } catch (error) {
    fail('harness', String(error))
  }
  ;(window as unknown as { __createPhotos: unknown }).__createPhotos = { assertions, failures }
  const status = document.createElement('p')
  status.id = 'create-photos-status'
  status.textContent = failures.length === 0 ? `PASS ${assertions.length}` : `FAIL ${failures.length}/${assertions.length}`
  document.body.append(status)
}

void main()
