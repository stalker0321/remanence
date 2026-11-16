/**
 * Letter on the back of the card, connected sender proof (Chromium-only).
 *
 * Mounts the ACTUAL `CreatorComposer` with the production text measurer, the
 * real bundled fonts, every style it offers and synthetic photos, and gives it
 * a long text the way the app does (`splitLetter`: the front as the note, the
 * whole text as the letter). It walks every layout the sender is offered and
 * proves on the painted pixels that the turn mark is inside the note, on bare
 * paper and next to the last word (or, on a full last line, on a patch of the
 * paper the note is written on), and that the back is that paper in the
 * note's ink. Then: the note turns the preview to the
 * whole text, the pager and the crop controls show the front again, a short
 * text has no mark and no back, a text that got long again starts on the
 * front, and choosing a layout freezes the front only.
 */
import { flushSync } from 'react-dom'
import { createRoot, type Root } from 'react-dom/client'
import { CreatorComposer, type CreatorComposerPhoto } from '../../src/collage/CreatorComposer'
import { canvasTextMeasurer } from '../../src/collage/measure'
import type { CreatorFreeze } from '../../src/create/creatorFreeze'
import { splitLetter } from '../../src/create/letter'
import { SessionStore } from '../../src/session/session'
import '../../src/design/tokens.css'
import '../../src/collage/creatorComposer.css'

interface CheckResult { name: string; ok: boolean; detail?: string }
const assertions: CheckResult[] = []
const failures: string[] = []
function pass(name: string): void { assertions.push({ name, ok: true }) }
function fail(name: string, detail = ''): void { assertions.push({ name, ok: false, detail }); failures.push(`${name}: ${detail}`) }
function assert(name: string, cond: boolean, detail = ''): void { cond ? pass(name) : fail(name, detail) }

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const LETTER = [
  'Mum, we made it to the sea at last. The water is cold and the kids will not leave it.',
  'We took the long road through the hills, the one you always wanted to see. There is a small white church at the top and a bench beside it; we sat there for an hour and nobody said a word.',
  'I will tell you the rest when we are home. Keep the card on the fridge until then.',
].join('\n\n')
const SHORT = 'Back on Sunday.'
const TINTS = ['#b33b2e', '#2e8b57', '#245a9c']

// The composer orders its layouts by a per-draft random seed; one fixed draft
// makes every run walk the same layouts in the same order.
crypto.randomUUID = () => '6f1c2a34-9b7d-4e58-a1c3-5d2e8f907b16'

async function photo(index: number): Promise<CreatorComposerPhoto> {
  const canvas = document.createElement('canvas')
  canvas.width = 900
  canvas.height = index === 1 ? 1200 : 900
  const context = canvas.getContext('2d')
  if (!context) throw new Error('no 2d context')
  context.fillStyle = TINTS[index]!
  context.fillRect(0, 0, canvas.width, canvas.height)
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((result) => result ? resolve(result) : reject(new Error('no jpeg')), 'image/jpeg', 0.9)
  })
  const bytes = new Uint8Array(await blob.arrayBuffer())
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes.buffer as ArrayBuffer))
  return {
    contentId: `p${index}`,
    width: canvas.width,
    height: canvas.height,
    contentHash: [...digest].map((byte) => byte.toString(16).padStart(2, '0')).join(''),
    image: await createImageBitmap(blob),
  }
}

async function waitFor(condition: () => boolean, what: string, timeoutMs = 30000): Promise<void> {
  const deadline = performance.now() + timeoutMs
  while (performance.now() < deadline) {
    try {
      if (condition()) return
    } catch {
      // The queried control may be transiently absent during a React commit.
    }
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  throw new Error(`timeout: ${what}`)
}

const frames = async (): Promise<void> => {
  for (let index = 0; index < 2; index++) await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
}

const byTest = <T extends HTMLElement>(id: string): T | null => document.querySelector(`[data-testid="${id}"]`)
const sides = (): HTMLElement => document.querySelector('.creator-sides') as HTMLElement
const front = (): HTMLElement => document.querySelector('.creator-sides .sheet-front') as HTMLElement
const back = (): HTMLElement | null => document.querySelector('.creator-sides .sheet-back')
const mark = (): HTMLButtonElement | null => document.querySelector('.creator-sides button.letter-mark')
const sheet = (): HTMLCanvasElement => byTest<HTMLCanvasElement>('creator-canvas')!
const count = (): string => byTest('creator-count')?.textContent ?? ''
const painted = (): boolean => sheet().dataset['painted'] === 'ready'
const turned = (): boolean => sides().dataset['turned'] === 'true'

interface LayoutFact { layout: string; branch: string; size: number; backed: boolean; ground: string; contrast: number; ok: boolean; why: string }

type Rgb = [number, number, number]
const rgbOf = (value: string): Rgb => {
  const parts = value.match(/[\d.]+/g)?.slice(0, 3).map(Number) ?? []
  return [parts[0] ?? -1, parts[1] ?? -1, parts[2] ?? -1]
}
const near = (a: Rgb, b: Rgb, limit: number): boolean => a.every((channel, index) => Math.abs(channel - b[index]!) <= limit)
const luminance = (colour: Rgb): number => {
  const [r, g, b] = colour.map((channel) => {
    const unit = channel / 255
    return unit <= 0.03928 ? unit / 12.92 : ((unit + 0.055) / 1.055) ** 2.4
  }) as Rgb
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (a: Rgb, b: Rgb): number => {
  const [low, high] = [luminance(a), luminance(b)].sort((x, y) => x - y) as [number, number]
  return (high + 0.05) / (low + 0.05)
}

/** What the note is actually painted on: the commonest colour of its region on the canvas. */
function paintedGround(canvas: HTMLCanvasElement, x: number, y: number, width: number, height: number): Rgb {
  const data = canvas.getContext('2d')!.getImageData(x, y, width, height).data
  const seen = new Map<number, number>()
  for (let at = 0; at < data.length; at += 4) {
    const key = (data[at]! << 16) | (data[at + 1]! << 8) | data[at + 2]!
    seen.set(key, (seen.get(key) ?? 0) + 1)
  }
  const [key] = [...seen].sort((a, b) => b[1] - a[1])[0]!
  return [key >> 16, (key >> 8) & 255, key & 255]
}

/** The mark of the layout now showing, judged on the DOM boxes and the canvas pixels alone. */
function judgeMark(): LayoutFact {
  const fact: LayoutFact = { layout: count(), branch: byTest('creator-branch')?.textContent?.trim() ?? '', size: 0, backed: false, ground: '', contrast: 0, ok: false, why: '' }
  const button = mark()
  if (button === null) return { ...fact, why: 'no mark' }
  const card = document.querySelector('.creator-sides .card-back') as HTMLElement | null
  if (card === null) return { ...fact, why: 'no back' }
  const svg = button.querySelector('svg') as SVGSVGElement
  const canvas = sheet()
  const box = canvas.getBoundingClientRect()
  const scale = box.width / canvas.width
  const hit = button.getBoundingClientRect()
  const arrow = svg.getBoundingClientRect()
  fact.size = arrow.width / scale
  fact.backed = svg.style.background !== ''
  if (hit.left < box.left - 0.5 || hit.right > box.right + 0.5 || hit.top < box.top - 0.5 || hit.bottom > box.bottom + 0.5) return { ...fact, why: 'the note button leaves the sheet' }
  if (arrow.left < hit.left - 0.5 || arrow.right > hit.right + 0.5 || arrow.top < hit.top - 0.5 || arrow.bottom > hit.bottom + 0.5) {
    return { ...fact, why: `the arrow leaves the note: ${[arrow.left, arrow.top, arrow.right, arrow.bottom].map(Math.round)} in ${[hit.left, hit.top, hit.right, hit.bottom].map(Math.round)}` }
  }
  if (fact.size < 11.5 || fact.size > 18.5) return { ...fact, why: `size ${fact.size}` }
  // The back is the paper the note is written on, in the note's ink: as readable as the front.
  const ground = paintedGround(
    canvas,
    Math.round((hit.left - box.left) / scale), Math.round((hit.top - box.top) / scale),
    Math.round(hit.width / scale), Math.round(hit.height / scale),
  )
  const noteInk = rgbOf(getComputedStyle(button).color)
  const backPaper = rgbOf(getComputedStyle(card).backgroundColor)
  fact.ground = ground.join(',')
  fact.contrast = Math.round(contrast(rgbOf(getComputedStyle(card).color), backPaper) * 10) / 10
  if (!near(backPaper, ground, 2)) return { ...fact, why: `the back is ${backPaper} but the note is written on ${ground}` }
  if (!near(rgbOf(getComputedStyle(card).color), noteInk, 0)) return { ...fact, why: 'the back is not in the note\'s ink' }
  if (fact.contrast < 3) return { ...fact, why: `the back reads at ${fact.contrast}:1` }
  if (fact.backed) {
    // A full last line: the arrow takes its end, flush with the note's right edge, on a patch of the note's own paper.
    if (!near(rgbOf(getComputedStyle(svg).backgroundColor), ground, 2)) return { ...fact, why: `the patch is ${getComputedStyle(svg).backgroundColor} on ${ground}` }
    return Math.abs(arrow.right - hit.right) < 1 ? { ...fact, ok: true } : { ...fact, why: 'a backed arrow is not at the end of the line' }
  }
  const context = canvas.getContext('2d')!
  const x0 = Math.ceil((arrow.left - box.left) / scale)
  const y0 = Math.ceil((arrow.top - box.top) / scale)
  const side = Math.floor(fact.size) - 1
  const under = context.getImageData(x0, y0, side, side).data
  const far = (data: Uint8ClampedArray, at: number, limit: number): boolean =>
    Math.abs(data[at]! - under[0]!) > limit || Math.abs(data[at + 1]! - under[1]!) > limit || Math.abs(data[at + 2]! - under[2]!) > limit
  let inked = 0
  for (let at = 0; at < under.length; at += 4) if (far(under, at, 6)) inked++
  if (inked > 0) return { ...fact, why: `${inked} painted pixels under the arrow` }
  // The last word ends just left of it: ink within the rule's gap, on the arrow's own rows.
  const reach = Math.ceil(fact.size * 0.7) + 2
  const tall = Math.min(Math.ceil(fact.size * 1.5), Math.floor((hit.bottom - box.top) / scale) - y0)
  const before = context.getImageData(Math.max(0, x0 - reach), y0, Math.min(reach, x0), Math.max(1, tall)).data
  let ink = 0
  for (let at = 0; at < before.length; at += 4) if (far(before, at, 40)) ink++
  return ink > 0 ? { ...fact, ok: true } : { ...fact, why: 'no word next to the arrow' }
}

/** The page waits here so the runner can photograph the named side; alone it just goes on. */
async function pose(name: string, statusNode: HTMLElement): Promise<void> {
  const flags = window as unknown as { __creatorLetterShot?: string }
  statusNode.textContent = `pose:${name}`
  const deadline = performance.now() + 4000
  while (flags.__creatorLetterShot !== name && performance.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  statusNode.textContent = 'running'
}

async function main(): Promise<void> {
  const statusNode = document.createElement('p')
  statusNode.id = 'creator-letter-status'
  statusNode.textContent = 'starting'
  document.body.appendChild(statusNode)
  const report: Record<string, unknown> = { pass: false, synthetic: true }
  let step = 'photos'
  try {
    const photos = await Promise.all([0, 1, 2].map(photo))
    const session = new SessionStore('en')
    session.signIn({
      accountId: ACCOUNT,
      bundleId: BUNDLE,
      handle: 'sergio',
      accessToken: 'access-token-value',
      refreshToken: 'refresh-token-value',
      accessExpiresAt: '2026-10-17T00:00:00Z',
      refreshExpiresAt: '2026-11-17T00:00:00Z',
    })
    session.unlock()
    const measurer = canvasTextMeasurer(document)
    const frozenLog: Array<CreatorFreeze | null> = []
    const host = document.createElement('div')
    host.style.width = '390px'
    document.body.appendChild(host)
    const root: { current: Root | null } = { current: null }
    const render = (text: string): void => {
      // The app's own split: the front is the note, the whole text is the letter.
      const split = splitLetter(text)
      root.current ??= createRoot(host)
      flushSync(() => {
        root.current!.render(
          <CreatorComposer
            photos={photos}
            note={split.front}
            letter={split.letter}
            track={null}
            recipient={{ userId: ACCOUNT, keyBundleId: BUNDLE, directoryVersion: '0'.repeat(16), handle: 'ada' }}
            ownerId={ACCOUNT}
            session={session}
            lang="en"
            measurer={measurer}
            onFrozenChange={(frozen) => frozenLog.push(frozen)}
          />,
        )
      })
    }
    const long = splitLetter(LETTER)
    assert('a long text is the letter and its start is the front', long.letter === LETTER && LETTER.startsWith(long.front) && long.front.length < LETTER.length, long.front)

    step = 'short'
    render(SHORT)
    await waitFor(() => byTest('creator-canvas') !== null && painted(), 'short text painted')
    assert('a short text: no turn mark and no back', mark() === null && back() === null && !turned())

    step = 'layouts'
    render(LETTER)
    await waitFor(() => painted() && mark() !== null, 'long text painted')
    await frames()
    assert('the mark is named for assistive tech', mark()!.getAttribute('aria-label') === 'continued on the back' && mark()!.title === 'continued on the back')
    assert('the preview starts on its front', !turned() && !front().inert && back() !== null && back()!.inert)
    const facts: LayoutFact[] = []
    const next = (): HTMLButtonElement => byTest<HTMLButtonElement>('creator-next')!
    for (let guard = 0; guard < 200; guard++) {
      facts.push(judgeMark())
      if (guard === 0) await pose('front', statusNode)
      if (next().disabled) break
      const was = count()
      next().click()
      await waitFor(() => count() !== was && painted() && mark() !== null, `layout after ${was}`)
      await frames()
    }
    report.layouts = facts
    const bad = facts.filter((fact) => !fact.ok)
    assert('every offered layout was walked', facts.length >= 8 && next().disabled, `${facts.length} layouts`)
    assert('in every layout the arrow is in the note, on bare paper or its own patch of it, next to the last word; the back is the note\'s paper and ink', bad.length === 0, JSON.stringify(bad))
    assert('a full last line happened too', facts.some((fact) => fact.backed) && facts.some((fact) => !fact.backed), String(facts.filter((fact) => fact.backed).length))
    assert('layouts differ', new Set(facts.map((fact) => fact.branch)).size >= 8, String(new Set(facts.map((fact) => fact.branch)).size))

    step = 'turn'
    mark()!.click()
    await waitFor(turned, 'preview turned')
    assert('turned: the back holds the whole text', document.querySelector('.creator-sides .card-back-text')?.textContent === LETTER)
    assert('turned: only the back takes input', front().inert && !back()!.inert)
    await new Promise((resolve) => setTimeout(resolve, 750))
    const text = document.querySelector('.creator-sides .card-back-text') as HTMLElement
    assert('turned: the text keeps to the card width', text.scrollWidth <= text.clientWidth + 1, `${text.scrollWidth} > ${text.clientWidth}`)
    await pose('back', statusNode)

    step = 'controls-show-the-front'
    const was = count()
    byTest<HTMLButtonElement>('creator-prev')!.click()
    await waitFor(() => count() !== was && painted(), 'previous layout')
    await waitFor(() => !turned(), 'the pager shows the front')
    assert('another layout shows its front', !turned() && !front().inert && mark() !== null)
    mark()!.click()
    await waitFor(turned, 'preview turned again')
    byTest<HTMLButtonElement>('creator-zoom-in')!.click()
    await waitFor(() => !turned(), 'a crop control shows the front')
    assert('a crop control shows the front', !turned() && !front().inert)
    await waitFor(() => painted() && mark() !== null, 'repainted after the crop')
    mark()!.click()
    await waitFor(turned, 'preview turned a third time')
    ;(document.querySelector('.creator-sides button.card-back-turn') as HTMLButtonElement).click()
    await waitFor(() => !turned(), '"the front" turns it back')
    assert('"the front" turns the preview back', !turned() && mark() !== null)

    step = 'text-changes'
    mark()!.click()
    await waitFor(turned, 'preview turned a fourth time')
    render(SHORT)
    await waitFor(() => painted() && mark() === null, 'short text painted again')
    assert('the text got short: no mark, no back, the front shows', back() === null && !turned() && !front().inert)
    render(LETTER)
    await waitFor(() => painted() && mark() !== null, 'long text painted again')
    await frames()
    assert('the text got long again: it starts on the front', !turned() && !front().inert && back()!.inert)

    step = 'freeze'
    const choose = byTest<HTMLButtonElement>('creator-freeze')!
    await waitFor(() => !choose.disabled, 'choosing is offered')
    choose.click()
    await waitFor(() => frozenLog.length > 0 && frozenLog[frozenLog.length - 1] !== null, 'layout chosen')
    const frozen = frozenLog[frozenLog.length - 1]!
    assert('the chosen layout holds the front as its note', frozen.note === long.front, frozen.note ?? 'null')
    const sealed = JSON.stringify(frozen, (_key, value: unknown) => typeof value === 'bigint' ? value.toString() : value)
    assert('the chosen layout holds nothing of the rest of the letter', !sealed.includes('fridge') && !sealed.includes('church'))

    report.pass = failures.length === 0
  } catch (error) {
    fail('creator letter connected harness', String(error) + ' | step=' + step)
  }
  ;(window as unknown as { __creatorLetter?: unknown }).__creatorLetter = { assertions, failures, report }
  statusNode.textContent = failures.length === 0 ? 'done' : 'failed'
}

void main()
