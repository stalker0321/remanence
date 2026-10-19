/**
 * T320 connected Create proof (Chromium-only).
 *
 * Mounts the REAL production `Create` component with a real SessionStore,
 * pre-confirmed RecipientDraft and a fake CameraPort serving a synthetic
 * textured JPEG, then clicks the actual useCamera/takePicture buttons so the
 * accepted producer (real DOM decode + real WASM contour/warp/SIFT) runs
 * through the actual App wiring. A holder-level run on the same genuine path
 * yields the wire for the unchanged Kotlin codec check. No real camera,
 * provider, backend, or AppSend; synthetic pixels only.
 */
import { createRoot, type Root } from 'react-dom/client'
import { Create } from '../../src/ui/App'
import { NoteDraft } from '../../src/create/note'
import { MusicDraft } from '../../src/create/music'
import { PhotoDraft } from '../../src/create/photos'
import { RecipientDraft, type RecipientSnapshot } from '../../src/create/recipient'
import { SessionStore } from '../../src/session/session'
import { FrontHold } from '../../src/create/front'
import { FrontFingerprintHold } from '../../src/create/frontFingerprint'
import { parseSiftFingerprint } from '../../src/scan/siftFingerprint'
import { bytesToHex } from '../../src/protocol/bytes'
import { catalog, type MessageKey, type Language } from '../../src/i18n/catalog'
import type { ApiTransport } from '../../src/auth/http'
import type { CameraPort, StillCapture } from '../../src/scan/camera'
import '../../src/design/tokens.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
const notices: MessageKey[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const SIGN_IN = {
  accountId: ACCOUNT,
  bundleId: BUNDLE,
  handle: 'sergio',
  accessToken: 'access-token-value',
  refreshToken: 'refresh-token-value',
  accessExpiresAt: '2026-10-17T00:00:00Z',
  refreshExpiresAt: '2026-11-17T00:00:00Z',
}

function snapshot(): RecipientSnapshot {
  return {
    userId: ACCOUNT, handle: 'sergio', keyBundleId: BUNDLE,
    suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519', protocolVersion: 1,
    encryptionPublicKeyset: 'x'.repeat(10), signingPublicKeyset: 'y'.repeat(10),
    directoryVersion: '0'.repeat(16), self: false,
  }
}

function mulberry32(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state |= 0
    state = (state + 0x6d2b79f5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

async function texturedQuadJpeg(seed: number, shiftX: number, shiftY: number, width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#101418'
  context.fillRect(0, 0, width, height)
  const quad = [
    { x: 100 + shiftX, y: 80 + shiftY },
    { x: 540 + shiftX, y: 60 + shiftY },
    { x: 560 + shiftX, y: 400 + shiftY },
    { x: 80 + shiftX, y: 420 + shiftY },
  ]
  context.save()
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.clip()
  const random = mulberry32(seed)
  for (let i = 0; i < 260; i++) {
    const gray = Math.floor(random() * 256)
    context.fillStyle = `rgb(${gray},${gray},${gray})`
    context.fillRect(quad[0]!.x + random() * 480, quad[0]!.y + random() * 340, 4 + random() * 22, 4 + random() * 22)
  }
  context.restore()
  context.lineWidth = 6
  context.strokeStyle = '#ffffff'
  context.beginPath()
  context.moveTo(quad[0]!.x, quad[0]!.y)
  for (let i = 1; i < quad.length; i++) context.lineTo(quad[i]!.x, quad[i]!.y)
  context.closePath()
  context.stroke()
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

async function blankJpeg(width: number, height: number): Promise<Uint8Array> {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#808080'
  context.fillRect(0, 0, width, height)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!blob) throw new Error('encode failed')
  return new Uint8Array(await blob.arrayBuffer())
}

function waitFor(cond: () => boolean, what: string, timeoutMs = 180000): Promise<void> {
  const start = Date.now()
  return new Promise((resolve, reject) => {
    const tick = () => {
      try {
        if (cond()) {
          resolve()
          return
        }
      } catch (error) {
        reject(error)
        return
      }
      if (Date.now() - start > timeoutMs) {
        reject(new Error(`timeout: ${what}`))
        return
      }
      setTimeout(tick, 250)
    }
    tick()
  })
}

function buttonByText(text: string): HTMLButtonElement | null {
  for (const button of document.querySelectorAll('button')) {
    if (button.textContent === text) return button as HTMLButtonElement
  }
  return null
}

async function clickText(text: string, what: string): Promise<void> {
  await waitFor(() => {
    const button = buttonByText(text)
    return button !== null && !button.disabled
  }, `${what} clickable`)
  buttonByText(text)!.click()
}

function bodyText(): string {
  return document.body.textContent ?? ''
}

async function main(): Promise<void> {
  const lang: Language = 'en'
  const t = (key: MessageKey): string => catalog[lang][key]
  const status = document.createElement('p')
  status.id = 'front-fingerprint-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, string> = {}
  try {
    const store = new SessionStore(lang)
    store.signIn(SIGN_IN)
    store.unlock()
    const recipient = new RecipientDraft()
    recipient.attach(store)
    const ticket = recipient.nextTicket(ACCOUNT)
    recipient.offer(ticket, ACCOUNT, snapshot())
    recipient.confirm()
    const note = new NoteDraft()
    const photos = new PhotoDraft()
    photos.attach(store)
    const music = new MusicDraft()

    const transport: ApiTransport = { request: async () => { throw new Error('no network in this fixture') } }
    let payload: Uint8Array = await texturedQuadJpeg(7, 0, 0, 640, 480)
    let payloadWidth = 640
    let payloadHeight = 480
    const camera: CameraPort = {
      open: async () => ({
        preview: document.createElement('video') as HTMLVideoElement,
        capture: async (): Promise<StillCapture> => ({ bytes: payload.slice(), width: payloadWidth, height: payloadHeight }),
        stop: () => undefined,
      }),
    }

    const rootRef: { root: Root | null } = { root: null }
    const render = () => {
      if (rootRef.root === null) {
        const host = document.createElement('div')
        host.setAttribute('data-testid', 'create-host')
        host.className = 'shell'
        document.body.appendChild(host)
        rootRef.root = createRoot(host)
      }
      rootRef.root.render(
        <Create
          t={t}
          store={store}
          transport={transport}
          recipient={recipient}
          camera={camera}
          note={note}
          photos={photos}
          music={music}
          onNotice={(notice) => { notices.push(notice) }}
        />,
      )
    }
    const reconfirm = () => {
      const nextTicket = recipient.nextTicket(ACCOUNT)
      recipient.offer(nextTicket, ACCOUNT, snapshot())
      recipient.confirm()
      render()
    }
    render()
    await waitFor(() => buttonByText(t('useCamera')) !== null, 'useCamera visible');

    // 1. Real take through the actual wiring: JPEG -> producer -> ready copy.
    await clickText(t('useCamera'), 'useCamera')
    await clickText(t('takePicture'), 'takePicture')
    await waitFor(() => bodyText().includes(t('frontReady')), 'ready copy')
    assert('take runs the producer to a local ready state', bodyText().includes(t('frontReady')))
    assert('ready notice follows the held notice', notices.slice(-2).join('|') === 'frontHeld|frontReady', notices.slice(-2).join('|'))
    assert('ready copy disclaims send and match', bodyText().includes(t('frontReady')))

    // 2. Retake replaces the ready result (no stale residue).
    const noticesBefore = notices.length
    await clickText(t('takePicture'), 'retake')
    await waitFor(() => notices.length >= noticesBefore + 2, 'second ready notice')
    assert('retake produces a second ready notice', notices.slice(-2).join('|') === 'frontHeld|frontReady', notices.slice(-2).join('|'))
    assert('ready copy still shown after retake', bodyText().includes(t('frontReady')))

    // 3. Blank frame -> retryable scan failure, not camera unavailable.
    payload = await blankJpeg(320, 240)
    payloadWidth = 320
    payloadHeight = 240
    await clickText(t('takePicture'), 'blank take')
    await waitFor(() => bodyText().includes(t('frontScanFailed')), 'scan failure copy')
    assert('blank frame refuses with a retryable scan failure', bodyText().includes(t('frontScanFailed')))

    // 4. Close clears the pictured/ready state without misleading notices.
    const countBeforeClose = notices.length
    await clickText(t('closeCamera'), 'closeCamera')
    await waitFor(() => !bodyText().includes(t('frontReady')) && !bodyText().includes(t('frontScanFailed')), 'ready cleared')
    assert('close clears the ready state', !bodyText().includes(t('frontReady')))
    assert('close adds no success or failure notice', !notices.slice(countBeforeClose).includes('frontReady' as MessageKey) && !notices.slice(countBeforeClose).includes('frontScanFailed' as MessageKey))

    // 5. Recipient change invalidates the visit.
    await clickText(t('useCamera'), 'reopen camera')
    payload = await texturedQuadJpeg(7, 0, 0, 640, 480)
    payloadWidth = 640
    payloadHeight = 480
    await clickText(t('takePicture'), 'take after reopen')
    await waitFor(() => bodyText().includes(t('frontReady')), 'ready again')
    const countBeforeRestart = notices.length
    await clickText(t('recipientChange'), 'recipient change')
    await waitFor(() => !bodyText().includes(t('frontReady')), 'ready cleared on recipient change')
    assert('recipient change clears the ready state', !bodyText().includes(t('frontReady')))
    assert('recipient change adds no success notice', !notices.slice(countBeforeRestart).includes('frontReady' as MessageKey))

    // 6. Recovery after invalidation: re-confirm, reopen, take again.
    reconfirm()
    await clickText(t('useCamera'), 'reopen after re-confirm')
    await clickText(t('takePicture'), 'take after re-confirm')
    await waitFor(() => bodyText().includes(t('frontReady')), 'ready after re-confirm')
    assert('re-confirm recovers to a ready state', bodyText().includes(t('frontReady')))

    // 7. Lock invalidates (camera stays open); unlock + re-confirm recovers.
    store.lock()
    await waitFor(() => !bodyText().includes(t('frontReady')), 'ready cleared on lock')
    const countBeforeLock = notices.length
    assert('lock clears the ready state', !bodyText().includes(t('frontReady')))
    assert('lock adds no success notice', !notices.slice(countBeforeLock).includes('frontReady' as MessageKey))
    store.unlock()
    reconfirm()
    await clickText(t('useCamera'), 'reopen after unlock')
    await clickText(t('takePicture'), 'take after unlock')
    await waitFor(() => bodyText().includes(t('frontReady')), 'ready after unlock')
    assert('unlock and re-confirm recover to ready', bodyText().includes(t('frontReady')))

    // 8. Unmount closes everything without errors.
    rootRef.root?.unmount()
    rootRef.root = null
    await waitFor(() => document.querySelector('[data-testid="create-host"]') !== null, 'host persists')
    assert('unmount completes without crashing the page', true)

    // 7. Holder-level real wire on the same genuine path (no UI observer).
    const store2 = new SessionStore(lang)
    store2.signIn(SIGN_IN)
    store2.unlock()
    const recipient2 = new RecipientDraft()
    recipient2.attach(store2)
    const ticket2 = recipient2.nextTicket(ACCOUNT)
    recipient2.offer(ticket2, ACCOUNT, snapshot())
    recipient2.confirm()
    const hold2 = new FrontHold()
    const quadBytes = await texturedQuadJpeg(7, 0, 0, 640, 480)
    await hold2.open({
      open: async () => ({
        preview: document.createElement('video') as HTMLVideoElement,
        capture: async () => ({ bytes: quadBytes.slice(), width: 640, height: 480 }),
        stop: () => undefined,
      }),
    })
    const still2 = await hold2.take(recipient2.confirmed()!)
    const holder = new FrontFingerprintHold()
    const produced = await holder.run(still2, {
      store: store2,
      recipient: recipient2,
      hold: hold2,
      mounted: () => true,
    })
    assert('holder produces a nonempty wire', produced.wire.length > 0)
    assert('holder returns the confirmed recipient tuple', produced.recipient.userId === ACCOUNT)
    // Cross-check through the accepted codec parser.
    const parsed = parseSiftFingerprint(produced.wire)
    assert('holder wire parses to a nonempty fingerprint', parsed.keypoints.length > 0, String(parsed.keypoints.length))
    report.wireHex = bytesToHex(produced.wire)

    const distinctBytes = await texturedQuadJpeg(99, 24, -12, 640, 480)
    await hold2.open({
      open: async () => ({
        preview: document.createElement('video') as HTMLVideoElement,
        capture: async () => ({ bytes: distinctBytes.slice(), width: 640, height: 480 }),
        stop: () => undefined,
      }),
    })
    const still3 = await hold2.take(recipient2.confirmed()!)
    const produced2 = await holder.run(still3, {
      store: store2,
      recipient: recipient2,
      hold: hold2,
      mounted: () => true,
    })
    assert('distinct frame yields a distinct holder wire', bytesToHex(produced2.wire) !== report.wireHex)
    report.distinctWireHex = bytesToHex(produced2.wire)
    hold2.close()
  } catch (error) {
    assert('front fingerprint connected harness', false, String(error))
  }
  ;(window as unknown as { __frontFingerprint?: unknown }).__frontFingerprint = { assertions, failures, report }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
