import { createRoot } from 'react-dom/client'
import { Scan } from '../../src/ui/App'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { PresentationMemory } from '../../src/collage/memory'
import { translate } from '../../src/i18n/translate'
import type { CameraPort, CameraSession, StillCapture } from '../../src/scan/camera'
import type { ApiTransport, ByteTransport } from '../../src/auth/http'
import '../../src/design/tokens.css'
import { receivePostcard } from '../../src/scan/receive'
import { jsPressCapture, jsPressIds, jsPressMaterial } from '../sealedFixture'
import { fitText } from '../sheets'
import { CollageStage } from '../../src/collage/stage'

const checks: Array<{ name: string; ok: boolean }> = []
const notices: string[] = []
const check = (name: string, ok: boolean) => { checks.push({ name, ok }) }
const wait = () => new Promise((ok) => setTimeout(ok, 30))
async function until(predicate: () => boolean) {
  const deadline = Date.now() + 3000
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('fixture timeout')
    await wait()
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
const t = (key: Parameters<typeof translate>[1]) => translate('en', key)
const button = (key: Parameters<typeof translate>[1]) =>
  [...document.querySelectorAll('button')].find((node) => node.textContent === t(key))
const press = (key: Parameters<typeof translate>[1]) => {
  const node = button(key)
  if (!node) throw new Error(`missing ${key}`)
  node.click()
}
const store = new SessionStore()
const keys = new MemoryKeys()
const memory = new PresentationMemory()
const transport = {
  request: async () => { throw new Error('unexpected request') },
  bytes: async () => { throw new Error('unexpected bytes') },
} as unknown as ApiTransport & ByteTransport
const host = document.createElement('main')
host.className = 'shell'
document.body.appendChild(host)
const root = createRoot(host)
let opens = 0
let stops = 0
let openGate: ReturnType<typeof deferred<CameraSession>> | null = null
let captureGate = deferred<StillCapture>()
const cameraSession = (): CameraSession => ({
  preview: document.createElement('video'),
  capture: () => captureGate.promise,
  stop: () => { stops++ },
})
const camera: CameraPort = { open: async () => {
  opens++
  return openGate ? openGate.promise : cameraSession()
} }
function signIn(id: string) {
  store.signIn({
    accountId: id, bundleId: null, handle: 'fixture',
    accessToken: 'isolated-fixture', refreshToken: 'isolated-fixture',
    accessExpiresAt: '', refreshExpiresAt: '',
  })
}
async function main() {
  try {
    root.render(<Scan t={t} camera={camera} onNotice={(key) => notices.push(key)} store={store} keys={keys} memory={memory} transport={transport} />)
    await until(() => Boolean(button('useCamera')))
    press('useCamera')
    await until(() => Boolean(button('takePicture')))
    press('takePicture')
    await wait()
    press('closeCamera')
    await wait()
    const count = opens
    press('useCamera')
    await wait()
    check('close releases busy before old capture settles', opens === count + 1)
    const stale = { bytes: new Uint8Array([255, 216, 1]), width: 1, height: 1 }
    captureGate.resolve(stale)
    await wait()
    check('late capture does not publish a held shot', !button('matchPicture'))
    check('late capture bytes are cleared', stale.bytes.every((n) => n === 0))
    check('late capture does not replace cleared notice', notices.at(-1) === 'errorRegion')
    // Continue independent cases even when the original bug is reproduced.
    if (button('closeCamera')) press('closeCamera')
    await wait()
    captureGate = deferred<StillCapture>()
    press('useCamera')
    await until(() => Boolean(button('takePicture')))
    press('takePicture')
    await wait()
    press('closeCamera')
    await wait()
    const noticeCount = notices.length
    captureGate.reject(new Error('late device failure'))
    await wait()
    check('late capture failure cannot overwrite next screen notice', notices.length === noticeCount)
    // An outstanding permission/camera-open result belongs to the old account.
    signIn('11111111-1111-4111-8111-111111111111')
    await wait()
    openGate = deferred<CameraSession>()
    press('useCamera')
    await wait()
    const stoppedBefore = stops
    signIn('22222222-2222-4222-8222-222222222222')
    openGate.resolve(cameraSession())
    await wait()
    check('late old-account camera is stopped', stops > stoppedBefore)
    check('account switch does not attach old camera preview', !host.querySelector('video'))
    openGate = null
    // Closing is idempotent and the same mounted screen can recover.
    if (button('closeCamera')) press('closeCamera')
    await wait()
    press('useCamera')
    await until(() => Boolean(button('takePicture')))
    check('same mounted screen can open again', Boolean(button('takePicture')))
    const unmountStops = stops
    root.unmount()
    check('unmount stops its live camera', stops > unmountStops)
    // Real browser crypto/decode on the existing JS-produced fixture. The
    // scripted match is deliberately isolated from the default-engine proof.
    const material = await jsPressMaterial()
    const receiveSession = new SessionStore()
    receiveSession.signIn({
      accountId: jsPressIds.account, bundleId: jsPressIds.bundle, handle: 'fixture',
      accessToken: 'fixture', refreshToken: 'fixture', accessExpiresAt: '', refreshExpiresAt: '',
    })
    receiveSession.unlock()
    const receiveKeys = new MemoryKeys()
    receiveKeys.attach(receiveSession)
    const receiveMemory = new PresentationMemory()
    receiveMemory.attach(receiveSession)
    const caps = material.capabilities()
    const original = caps.openEnvelope
    let failOnce = true
    caps.openEnvelope = async (...args) => {
      if (failOnce) { failOnce = false; throw new Error('isolated first-open crypto failure') }
      return original(...args)
    }
    receiveKeys.hold(receiveSession.getSnapshot(), caps)
    const receive = () => receivePostcard(receiveSession, receiveKeys, receiveMemory,
      { match: async () => ({ kind: 'one', capsuleId: jsPressIds.capsule }) },
      jsPressCapture, { items: [material.capsule], hasMore: false, nextCursor: null },
      { read: async (_item, id) => material.ciphertext(id).slice() }, fitText, new AbortController().signal)
    let refused = false
    try { await receive() } catch { refused = true }
    check('receive crypto failure refuses presentation and permits retry',
      refused && receiveMemory.getSnapshot() === null && receiveSession.getSnapshot().phase === 'keys-unlocked')
    await receive()
    const sheet = receiveMemory.getSnapshot()
    check('same owned receive retries into a decrypted three-photo sheet', sheet?.photos.size === 3)
    if (sheet) {
      createRoot(host).render(<CollageStage held={sheet} t={t} onClose={() => receiveSession.lock()} />)
      await until(() => Boolean(host.querySelector('.stage')))
      check('retried sheet is displayed in the actual stage', Boolean(host.querySelector('canvas')))
    }
  } catch (error) {
    check(String(error), false)
  }
  ;(window as unknown as { scanLifecycle: unknown }).scanLifecycle = { checks, notices, opens, stops, pass: checks.every((c) => c.ok) }
}
void main()
