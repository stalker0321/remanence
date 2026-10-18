/**
 * T255 frozen-expression browser fixture (Chromium-only gate).
 *
 * Mounts the REAL connected `CreatorComposer` with real bundled faces, a real
 * canvas measurer and synthetic generated photo sources, takes the actual
 * `onFrozenChange` snapshot, and runs the pure `buildFrozenExpression` adapter
 * with an explicit `17n` fixture epoch. It then decodes the adapter bytes and
 * runs the REAL production `prepare` -> `paintScene` on a SECONDARY receiver
 * canvas, proving the receiver's photo source/destination, every note
 * word/row/baseline and the provenance match the captured preview.
 *
 * No grant/admission/provider/publish path is fabricated; the only synthetic
 * inputs are the generated bitmaps and the fixture epoch.
 */
import { createRoot, type Root } from 'react-dom/client'
import { CreatorComposer, type CreatorComposerPhoto } from '../../src/collage/CreatorComposer'
import { decodeExpression } from '../../src/collage/codec'
import { buildFrozenExpression } from '../../src/collage/frozenExpression'
import { canvasTextMeasurer } from '../../src/collage/measure'
import { paintScene, type PaintImage } from '../../src/collage/paint'
import { prepare, type Scene } from '../../src/collage/prepare'
import { SessionStore } from '../../src/session/session'
import type { CreatorFreeze } from '../../src/create/creatorFreeze'
import { bytesToHex } from '../../src/protocol/bytes'
import './composerFonts.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function assert(name: string, cond: boolean, detail = ''): void {
  assertions.push({ name, ok: cond, detail: cond ? undefined : detail })
  if (!cond) failures.push(`${name}: ${detail}`)
}

/** BigInt-safe serialization for assertion details (the epoch is a bigint). */
function ser(value: unknown): string {
  return JSON.stringify(value, (_key, item) => (typeof item === 'bigint' ? `${item}n` : item))
}

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const SENDER_HANDLE = 'sergio'
const RECIPIENT_HANDLE = 'ada'
const RECIPIENT = { userId: ACCOUNT, keyBundleId: BUNDLE, directoryVersion: '0'.repeat(16), handle: RECIPIENT_HANDLE }
const EPOCH = 17n

const SHORT = 'Probe note text.'
const MULTILINE = 'A small note that wraps across a few rows so the receiver must reproduce every single word.'

function hexHash(index: number): string {
  return `${(index + 1).toString(16).padStart(2, '0')}`.repeat(32)
}

function twoTone(width: number, height: number, shift: number): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = '#c0392b'
  context.fillRect(0, 0, width / 2, height)
  context.fillStyle = shift ? '#1e8449' : '#2471a3'
  context.fillRect(width / 2, 0, width / 2, height)
  return canvas
}

function buildPhotos(): Array<{ photo: CreatorComposerPhoto; paint: PaintImage }> {
  return [0, 1, 2].map((index) => {
    const canvas = twoTone(800, 800, index)
    return {
      photo: { contentId: `p${index}`, width: 800, height: 800, contentHash: hexHash(index), image: canvas },
      paint: { source: canvas, width: 800, height: 800 },
    }
  })
}

interface FontEvidence {
  registered: Array<{ family: string; status: string }>
  studySansLoaded: number
  studySansWidth: number
  missingWidth: number
  fontRequests: Array<{ name: string; status: number }>
}

async function collectFontEvidence(): Promise<FontEvidence> {
  const sans = await document.fonts.load('normal 400 16px StudySans')
  await document.fonts.load('normal 400 27px StudySerif')
  await document.fonts.load('italic 400 20px StudySerif')
  await document.fonts.ready
  const probe = document.createElement('canvas').getContext('2d')
  const widthOf = (font: string): number => {
    if (!probe) return -1
    probe.font = font
    return probe.measureText('Wg').width
  }
  const fontRequests = performance
    .getEntriesByType('resource')
    .filter((entry) => entry.name.includes('/fonts/'))
    .map((entry) => {
      const resource = entry as PerformanceResourceTiming & { responseStatus?: number }
      return { name: resource.name.replace(location.origin, ''), status: resource.responseStatus ?? 0 }
    })
  return {
    registered: Array.from(document.fonts).map((face) => ({ family: face.family, status: face.status })),
    studySansLoaded: sans.length,
    studySansWidth: widthOf(`normal 400 16px StudySans`),
    missingWidth: widthOf(`normal 400 16px __remanence_missing_family__`),
    fontRequests,
  }
}

function installTraces(): { fillText: Array<{ text: string; x: number; y: number; font: string }>; drawImage: Array<{ dx: number; dy: number; dw: number; dh: number }> } {
  const fillText: Array<{ text: string; x: number; y: number; font: string }> = []
  const drawImage: Array<{ dx: number; dy: number; dw: number; dh: number }> = []
  const proto = CanvasRenderingContext2D.prototype as unknown as {
    fillText: (this: CanvasRenderingContext2D, text: string, x: number, y: number) => void
    drawImage: (...args: unknown[]) => void
  }
  const realFill = proto.fillText
  proto.fillText = function (this: CanvasRenderingContext2D, text: string, x: number, y: number): void {
    fillText.push({ text, x, y, font: this.font })
    realFill.call(this, text, x, y)
  }
  const realImage = proto.drawImage
  proto.drawImage = function (this: CanvasRenderingContext2D, ...args: unknown[]): void {
    if (args.length >= 9) drawImage.push({ dx: args[5] as number, dy: args[6] as number, dw: args[7] as number, dh: args[8] as number })
    ;(realImage as (...values: unknown[]) => void).apply(this, args)
  }
  return { fillText, drawImage }
}

function App(props: {
  note: string | null
  session: SessionStore
  measurer: ReturnType<typeof canvasTextMeasurer>
  photos: CreatorComposerPhoto[]
  onFrozenChange: (frozen: CreatorFreeze | null) => void
}): React.JSX.Element {
  return (
    <CreatorComposer
      photos={props.photos}
      note={props.note}
      musicSelected={false}
      styleId="press"
      recipient={RECIPIENT}
      ownerId={ACCOUNT}
      session={props.session}
      lang="en"
      measurer={props.measurer}
      onFrozenChange={props.onFrozenChange}
    />
  )
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'frozen-expression-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const sheet = document.createElement('canvas')
  sheet.width = 360
  sheet.height = 640
  sheet.id = 'frozen-expression-sheet'
  document.body.appendChild(sheet)
  const receiverContext = sheet.getContext('2d')
  if (!receiverContext) throw new Error('no receiver 2d context')
  const rootRef: { root: Root | null } = { root: null }
  let fonts: FontEvidence | null = null
  try {
    const built = buildPhotos()
    const photos = built.map((item) => item.photo)
    const paintImages = new Map<string, PaintImage>(built.map((item) => [item.photo.contentId, item.paint]))
    const session = new SessionStore('en')
    session.signIn({
      accountId: ACCOUNT,
      bundleId: BUNDLE,
      handle: SENDER_HANDLE,
      accessToken: 'access-token-value',
      refreshToken: 'refresh-token-value',
      accessExpiresAt: '2026-10-17T00:00:00Z',
      refreshExpiresAt: '2026-11-17T00:00:00Z',
    })
    session.unlock()
    const measurer = canvasTextMeasurer(document)
    const host = document.createElement('div')
    host.setAttribute('data-testid', 't255-host')
    document.body.appendChild(host)
    rootRef.root = createRoot(host)
    const frozenLog: Array<CreatorFreeze | null> = []
    const render = (note: string | null): void => {
      rootRef.root!.render(
        <App note={note} session={session} measurer={measurer} photos={photos} onFrozenChange={(frozen) => frozenLog.push(frozen)} />,
      )
    }
    await new Promise((resolve) => setTimeout(resolve, 0))
    fonts = await collectFontEvidence()
    assert('bundled faces registered', fonts.registered.length > 0, JSON.stringify(fonts.registered))
    assert('StudySans loaded', fonts.studySansLoaded >= 1 && fonts.registered.some((face) => face.family === 'StudySans' && face.status === 'loaded'), JSON.stringify(fonts))
    assert('bundled /fonts fetched over the wire', fonts.fontRequests.some((r) => r.status >= 200 && r.status < 400), JSON.stringify(fonts.fontRequests))
    assert('real face differs from a missing-family fallback', fonts.studySansWidth > 0 && fonts.studySansWidth !== fonts.missingWidth, `sans=${fonts.studySansWidth} missing=${fonts.missingWidth}`)

    const traces = installTraces()
    const waitFor = async (predicate: () => boolean, label: string): Promise<void> => {
      const deadline = Date.now() + 30000
      for (;;) {
        let done = false
        try { done = predicate() } catch { done = false }
        if (done) return
        if (Date.now() > deadline) throw new Error(`timeout waiting for ${label}`)
        await new Promise((resolve) => setTimeout(resolve, 50))
      }
    }
    const freezeEnabled = (): boolean => {
      const button = document.querySelector('[data-testid="creator-freeze"]') as HTMLButtonElement | null
      return button !== null && !button.disabled
    }
    const freezeButton = (): HTMLButtonElement | null => document.querySelector('[data-testid="creator-freeze"]')
    /** Change the note, wait out the invalidation + fresh paint, then freeze. */
    const armAndFreeze = async (label: string, note: string | null): Promise<CreatorFreeze | null> => {
      const start = frozenLog.length
      const heldBefore = frozenLog.length > 0 && frozenLog[frozenLog.length - 1] !== null
      render(note)
      // A held freeze is invalidated by the note change (a fresh null is pushed
      // before the new note re-arms), so the click is never a stale click on
      // the previous note's armed button.
      if (heldBefore) {
        await waitFor(() => frozenLog.length > start && frozenLog[frozenLog.length - 1] === null, `${label} invalidated`)
      }
      await waitFor(freezeEnabled, `${label} freeze armed`)
      freezeButton()!.click()
      await waitFor(() => frozenLog.length > start && frozenLog[frozenLog.length - 1] !== null, `${label} frozen`)
      return frozenLog[frozenLog.length - 1] ?? null
    }
    const runCase = async (label: string, note: string | null): Promise<void> => {
      const snapshot = await armAndFreeze(label, note)
      assert(`${label}: real mounted freeze snapshot`, snapshot !== null && snapshot.renderedKey.length > 0, String(snapshot))
      if (!snapshot) return

      const before = JSON.stringify(snapshot)
      const result = await buildFrozenExpression(snapshot, EPOCH)
      assert(`${label}: adapter produced bytes`, result.ok, ser(result))
      if (!result.ok) return
      assert(`${label}: snapshot unchanged after build`, JSON.stringify(snapshot) === before)

      const plan = snapshot.renderedPlan
      result.expression.placements.forEach((placement, index) => {
        const frozenSlot = plan.slots[index]!
        assert(
          `${label}: placement ${index} = frozen integer destination/shape`,
          placement.x === frozenSlot.destination.x &&
            placement.y === frozenSlot.destination.y &&
            placement.width === frozenSlot.destination.width &&
            placement.height === frozenSlot.destination.height &&
            placement.shape === frozenSlot.shape &&
            placement.crop!.x === snapshot.slots[index]!.crop.x,
          JSON.stringify(placement),
        )
      })

      // Decode the adapter bytes, reattach the manifest-carried note region
      // (GENEX04 expression bytes intentionally omit it), and reproduce the
      // receiver scene with the production prepare/paint path.
      const decoded = decodeExpression(result.bytes)
      assert(`${label}: decode round-trips the expression`, ser({ ...decoded, noteRegion: result.expression.noteRegion }) === ser(result.expression))
      const receiver = { ...decoded, noteRegion: result.expression.noteRegion }
      const sources = new Map(photos.map((photo) => [photo.contentId, { width: photo.width, height: photo.height }]))
      const prepared = await prepare(receiver, receiver.input.note, null, sources, measurer)
      assert(`${label}: receiver prepare ready`, prepared.kind === 'ready', JSON.stringify(prepared))
      if (prepared.kind !== 'ready') return
      const scene = prepared.scene as Scene
      scene.photos.forEach((photo, index) => {
        const frozenSlot = plan.slots[index]!
        assert(
          `${label}: receiver photo ${index} source/destination match the captured preview`,
          photo.destination.x === frozenSlot.destination.x &&
            photo.destination.y === frozenSlot.destination.y &&
            photo.destination.width === frozenSlot.destination.width &&
            photo.destination.height === frozenSlot.destination.height &&
            photo.source.x === frozenSlot.source.x &&
            photo.source.y === frozenSlot.source.y &&
            photo.source.width === frozenSlot.source.width &&
            photo.source.height === frozenSlot.source.height,
          JSON.stringify({ got: photo, want: frozenSlot }),
        )
      })

      traces.fillText.length = 0
      traces.drawImage.length = 0
      assert(`${label}: receiver paints`, paintScene(receiverContext, scene, paintImages, null))
      const paintedDestination = traces.drawImage.slice(-plan.slots.length).map((call) => [call.dx, call.dy, call.dw, call.dh])
      assert(
        `${label}: receiver drawImage destination = frozen slots`,
        JSON.stringify(paintedDestination) === JSON.stringify(plan.slots.map((slot) => [slot.destination.x, slot.destination.y, slot.destination.width, slot.destination.height])),
        JSON.stringify(paintedDestination),
      )

      const noteCalls = traces.fillText.filter((call) => call.font.includes('StudySans'))
      if (plan.note === null) {
        assert(`${label}: absent note paints no rows`, noteCalls.length === 0, JSON.stringify(noteCalls))
      } else {
        const paintedRows = noteCalls.map((call) => ({ text: call.text, x: call.x, baseline: call.y }))
        const expectedRows = plan.note.rows.map((row) => ({ text: row.text, x: row.x, baseline: row.baseline }))
        assert(
          `${label}: receiver paints every captured note row/word/baseline`,
          JSON.stringify(paintedRows) === JSON.stringify(expectedRows),
          JSON.stringify({ paintedRows, expectedRows }),
        )
        const words = noteCalls.map((call) => call.text).join(' ').split(/\s+/).filter((word) => word.length > 0)
        assert(`${label}: every note word present`, JSON.stringify(words) === JSON.stringify((snapshot.note ?? '').split(/\s+/).filter((word) => word.length > 0)), JSON.stringify(words))
      }
      const provenanceTexts = traces.fillText.filter((call) => call.font.includes('StudySerif')).map((call) => call.text)
      assert(
        `${label}: receiver paints the frozen provenance text`,
        plan.provenance.every((mark) => provenanceTexts.includes(mark.text)),
        JSON.stringify(provenanceTexts),
      )
    }

    await runCase('short note', SHORT)
    await runCase('multiline note', MULTILINE)
    await runCase('absent note', null)

    // Typed controls on a real frozen snapshot.
    const control = (await armAndFreeze('control', SHORT))!
    assert('control: real mounted freeze snapshot', control !== null, String(control))
    const negative = await buildFrozenExpression(control, -5n)
    assert('negative epoch refuses typed', !negative.ok && negative.reason === 'epoch-invalid', JSON.stringify(negative))
    const fractional = structuredClone(control)
    ;(fractional.renderedPlan.slots[0]!.destination as { width: number }).width += 0.5
    const fraction = await buildFrozenExpression(fractional, EPOCH)
    assert('fractional wire geometry refuses typed', !fraction.ok && fraction.reason === 'fraction', JSON.stringify(fraction))

    // Deterministic bytes on the real snapshot.
    const runA = await buildFrozenExpression(control, EPOCH)
    const runB = await buildFrozenExpression(control, EPOCH)
    assert('adapter bytes deterministic on the live snapshot', runA.ok && runB.ok && bytesToHex(runA.bytes) === bytesToHex(runB.bytes))
  } catch (error) {
    assert('frozen-expression harness', false, String(error))
  }
  ;(window as unknown as { __frozenExpression?: unknown }).__frozenExpression = { assertions, failures, fonts }
  status.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main().catch((error) => {
  assert('frozen-expression main', false, String(error))
  ;(window as unknown as { __frozenExpression?: unknown }).__frozenExpression = { assertions, failures, fonts: null }
  const status = document.querySelector('#frozen-expression-status')
  if (status) status.textContent = 'failed'
})
