import { Aes256Gcm, CipherSuite, DhkemX25519HkdfSha256, HkdfSha256 } from '@hpke/core'
import { paintScene, type PaintImage } from '../src/collage/paint'
import { prepare } from '../src/collage/prepare'
import '../src/design/tokens.css'
import './harness.css'
import { fitText, overflowText, pressSheet } from '../test/sheets'

const root = document.documentElement

function swatch(width: number, height: number, color: string): PaintImage {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('canvas')
  context.fillStyle = color
  context.fillRect(0, 0, width, height)
  return { source: canvas, width, height }
}

function sources(): Map<string, PaintImage> {
  return new Map([
    ['p1', swatch(64, 48, '#c4554a')],
    ['p2', swatch(64, 48, '#d6cce9')],
    ['p3', swatch(64, 48, '#f4e8c7')],
  ])
}

async function paint(id: string, expression: Awaited<ReturnType<typeof pressSheet>>, measurer: typeof fitText, artwork: PaintImage | null) {
  const canvas = document.getElementById(id)
  if (!(canvas instanceof HTMLCanvasElement)) throw new Error(id)
  const context = canvas.getContext('2d')
  if (!context) throw new Error(id)
  const ready = await prepare(
    expression,
    'hello',
    { title: 'Hi', artistDisplay: 'Ada' },
    new Map([['p1', { width: 64, height: 48 }], ['p2', { width: 64, height: 48 }], ['p3', { width: 64, height: 48 }]]),
    measurer,
    artwork ? { width: artwork.width, height: artwork.height } : null,
  )
  canvas.dataset.result = ready.kind
  if (ready.kind !== 'ready') return
  const painted = paintScene(context, ready.scene, sources(), artwork)
  canvas.dataset.painted = painted ? 'yes' : 'no'
  canvas.dataset.artwork = ready.scene.artwork ? 'cover' : 'text'
}

const press = await pressSheet()
const covered = {
  ...press,
  musicRegion: press.musicRegion ? {
    ...press.musicRegion,
    mode: 'thumbnail',
    artwork: {
      provider: 'catalog-sample',
      url: 'https://art.example.org/cover/ab12.webp',
      sha256: new Uint8Array(32).fill(4),
      mimeType: 'image/webp',
      width: 80,
      height: 80,
    },
  } : null,
}
await document.fonts.ready
await paint('plain', press, fitText, null)
await paint('cover', covered, fitText, swatch(80, 80, '#191b19'))
await paint('overflow', press, overflowText, null)

for (const language of ['ru', 'uk'] as const) {
  document.getElementById(language)?.addEventListener('click', () => {
    root.lang = language
    root.dataset.locale = language
    const locale = document.getElementById('locale')
    if (locale) locale.textContent = language
  })
}

const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Aes256Gcm(),
})
const pair = await suite.kem.generateKeyPair()
const info = new TextEncoder().encode('remanence-harness')
const sealed = await suite.seal({ recipientPublicKey: pair.publicKey, info }, new TextEncoder().encode('open'))
const opened = new Uint8Array(await suite.open({
  recipientKey: pair.privateKey,
  enc: new Uint8Array(sealed.enc),
  info,
}, new Uint8Array(sealed.ct)))
root.dataset.hpke = new TextDecoder().decode(opened) === 'open' ? 'open' : 'fail'
root.dataset.harness = 'ready'
