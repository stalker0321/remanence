/** Real App/session/presentation teardown. Synthetic admitted content, no crypto/provider claim. */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from '../../src/ui/App'
import { SessionStore } from '../../src/session/session'
import { MemoryKeys } from '../../src/auth/memoryKeys'
import { PresentationMemory, showGrantedPostcard } from '../../src/collage/memory'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { creatorScene, plannerFromTextMeasurer, previewCandidates } from '../../src/collage/creatorPreview'
import { styleById } from '../../src/collage/styles'
import '../../src/design/tokens.css'

const store = new SessionStore()
const keys = new MemoryKeys()
keys.attach(store)
const created: string[] = []
const revoked: string[] = []
const memory = new PresentationMemory({
  create(bytes, type) {
    const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type }))
    created.push(url)
    return url
  },
  revoke(url) { revoked.push(url); URL.revokeObjectURL(url) },
})
memory.attach(store)
const recipient = new RecipientDraft()
recipient.attach(store)
const note = new NoteDraft()
note.attach(store)
const photos = new PhotoDraft()
photos.attach(store)
const music = new MusicDraft()
music.attach(store)
function signIn(other = false) {
  store.signIn({ accountId: `${other ? 'bbbbbbbb' : 'aaaaaaaa'}-bbbb-4ccc-8ddd-eeeeeeeeeeee`, bundleId: '22222222-2222-4222-8222-222222222222',
    handle: 'fixture_qa', accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh', accessExpiresAt: '2026-10-21T00:00:00Z', refreshExpiresAt: '2026-11-21T00:00:00Z' })
  store.unlock()
}
signIn()
const rootElement = document.createElement('div')
document.body.append(rootElement)
const root = createRoot(rootElement)
root.render(<StrictMode><App store={store} keys={keys} memory={memory} recipient={recipient} note={note} photos={photos} music={music}
  preferences={{ get: () => null, set: () => undefined, remove: () => undefined }}
  transport={{ request: async () => { throw new Error('no API in navigation fixture') }, requestBytes: async () => { throw new Error('no blobs in navigation fixture') } }}
  camera={{ open: async () => ({
    preview: document.createElement('video'),
    capture: async () => { throw new Error('no capture in navigation fixture') },
    stop: () => undefined,
  }) }}
/></StrictMode>)

async function admit() {
  if (store.getSnapshot().phase === 'authenticated') store.unlock()
  const dimensions = [[600, 400], [400, 600], [500, 500]]
  const bytes = await Promise.all(dimensions.map(async ([width, height], index) => {
    const canvas = document.createElement('canvas')
    canvas.width = width!
    canvas.height = height!
    const ctx = canvas.getContext('2d')!
    ctx.fillStyle = ['#b76456', '#668c6c', '#697ca2'][index]!
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = '#f4f2ec'
    ctx.font = '60px sans-serif'
    ctx.fillText(`QA ${index + 1}`, 40, 100)
    const blob = await new Promise<Blob>((resolve) => canvas.toBlob((value) => resolve(value!), 'image/jpeg'))
    return new Uint8Array(await blob.arrayBuffer())
  }))
  const images = dimensions.map(([width, height], index) => ({ contentId: `qa${index}`, width: width!, height: height!, contentHash: `${index + 1}`.repeat(64) }))
  const style = styleById('press')!
  const text = 'Synthetic navigation fixture.'
  const view = previewCandidates({ id: 'creator', sender: 'fixture_qa', photos: images, note: text }, undefined,
    plannerFromTextMeasurer({ measure: (request) => ({ width: request.text.length * 8, height: request.line, hasVisualOverflow: false }) }, 640, 640, style.note))[0]!
  const scene = creatorScene({ view, styleId: 'press', senderHandle: 'fixture_qa', noteText: text,
    photos: new Map(images.map((image) => [image.contentId, { width: image.width, height: image.height }])) })
  store.matchPostcard()
  showGrantedPostcard(store, memory, { scene, photos: new Map(images.map((image, index) => [image.contentId, bytes[index]!])), artwork: null })
}
Object.assign(window, { presentationNavigation: {
  admit, lock: () => store.lock(), switchAccount: () => signIn(true),
  state: () => ({ phase: store.getSnapshot().phase, held: memory.getSnapshot() !== null, created, revoked, history: history.state }),
} })
