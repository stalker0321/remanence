#!/usr/bin/env node
// Letter on the back of the card, connected viewer gate (Chromium-only):
// bundles the actual CollageStage fixture with the pinned Vite, serves it with
// the real bundled fonts, and checks the turn mark inside the note, the turn
// to the whole text and back, which side takes input, and that "save picture"
// still saves the front. Photographs both sides. Synthetic photos only.
// BROWSER_SMOKE_PACKAGE / BROWSER_SMOKE_CHROMIUM point a developer machine at
// its own Playwright install.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve, sep } from 'node:path'
import { tmpdir } from 'node:os'

const requireTool = createRequire(process.env.BROWSER_SMOKE_PACKAGE ?? '/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')

const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: stageLetterCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const report = { synthetic: true, pages: {}, console: [], errors: [], hover: {} }

const candidates = [
  ...(process.env.BROWSER_SMOKE_CHROMIUM ? [process.env.BROWSER_SMOKE_CHROMIUM] : []),
  '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell',
  chromium.executablePath(),
]
const executable = candidates.find((path) => {
  try {
    return existsSync(path)
  } catch {
    return false
  }
})
if (!executable) throw new Error('no installed chromium executable found')
report.executable = executable
report.executableSha256 = createHash('sha256').update(await readFile(executable)).digest('hex')

const work = join(tmpdir(), `stage-letter-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), 'import ' + JSON.stringify(join(web, 'test', 'browser', 'stageLetter.page.tsx')) + '\n')
await writeFile(join(work, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.ts"></script></body></html>')
await writeFile(
  join(work, 'vite.config.mjs'),
  'import react from ' + JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] })) + '\n' +
    'export default { root: ' + JSON.stringify(work) + ', logLevel: "warn", plugins: [react()], esbuild: { jsx: "automatic" }, build: { outDir: ' + JSON.stringify(join(work, 'dist')) + ', emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n',
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], { cwd: web, env: process.env, stdio: 'pipe', encoding: 'utf8' })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the stage-letter fixture failed')
}

const dist = join(work, 'dist')
const fontsDir = join(web, 'public', 'fonts')
if (!existsSync(fontsDir)) throw new Error('bundled fonts missing at ' + fontsDir)
await cp(fontsDir, join(dist, 'fonts'), { recursive: true })
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf', '.wasm': 'application/wasm', '.png': 'image/png' }

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  if (url.pathname === '/favicon.ico') { res.writeHead(204).end(); return }
  const path = resolve(dist, '.' + decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname))
  if (!path.startsWith(dist + sep)) { res.writeHead(403).end(); return }
  try {
    const body = await readFile(path)
    res.writeHead(200, { 'content-type': types[extname(path)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(body)
  } catch {
    res.writeHead(404).end()
  }
})
await new Promise((ok) => server.listen(4202, '127.0.0.1', ok))

let failure = null
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    const page = await browser.newPage({ viewport: { width: 900, height: 900 } })
    page.on('console', (msg) => report.console.push({ type: msg.type(), text: msg.text() }))
    page.on('pageerror', (error) => report.errors.push(String(error)))
    await page.route('**/*', async (route) => {
      const url = route.request().url()
      if (/^http:\/\/127\.0\.0\.1:4202\//.test(url)) await route.continue()
      else {
        report.errors.push('external request blocked: ' + url)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4202/index.html')
    // The page holds each side still until it has been photographed.
    for (const side of ['front', 'back']) {
      await page.waitForFunction((name) => {
        const status = document.querySelector('#stage-letter-status')?.textContent
        return status === 'pose:' + name || status === 'done' || status === 'failed'
      }, side, { timeout: 120000 })
      if ((await page.textContent('#stage-letter-status')) !== 'pose:' + side) break
      await page.locator('.stage .card-sides').screenshot({ path: join(outDir, 'stage-letter-' + side + '.png') })
      // Under a real pointer the control keeps the card's own look: the site's button hover must not reach it.
      const control = '.stage .card-sides ' + (side === 'front' ? 'button.letter-mark' : 'button.card-back-turn')
      await page.hover(control)
      await page.waitForTimeout(250)
      report.hover[side] = await page.evaluate((selector) => {
        const style = getComputedStyle(document.querySelector(selector))
        return { matches: document.querySelector(selector).matches(':hover'), background: style.backgroundColor, border: style.borderTopWidth }
      }, control)
      if (side === 'front') {
        // The same for a photo: under the pointer it stays a photo.
        await page.hover('.stage .sheet-front button.hit')
        await page.waitForTimeout(250)
        report.hover.photo = await page.evaluate(() => {
          const hit = document.querySelector('.stage .sheet-front button.hit')
          return { matches: hit.matches(':hover'), background: getComputedStyle(hit).backgroundColor, border: getComputedStyle(hit).borderTopWidth }
        })
      }
      await page.mouse.move(1, 1)
      await page.evaluate((name) => { window.__stageLetterShot = name }, side)
    }
    await page.waitForFunction(() => {
      const status = document.querySelector('#stage-letter-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 120000 })
    const status = await page.textContent('#stage-letter-status')
    const result = await page.evaluate(() => window.__stageLetter)
    report.dom = await page.evaluate(() => document.body.outerHTML.slice(0, 200000))
    await page.screenshot({ path: join(outDir, 'stage-letter-final.png'), fullPage: true })
    report.pages['index'] = { status, assertions: result?.assertions ?? [], failures: result?.failures ?? [], report: result?.report ?? null }
    await page.close()

    try {
      assert.equal(status, 'done', 'page status "' + status + '"')
      assert.ok(result, 'window.__stageLetter missing')
      for (const item of result.assertions) assert.equal(item.ok, true, 'stage-letter :: ' + item.name + ' ' + (item.detail ?? ''))
      assert.ok(result.assertions.length >= 30, 'expected >=30 assertions, got ' + result.assertions.length)
      assert.deepEqual(result.failures, [])
      assert.equal(result.report?.pass, true, 'report.pass must be true')
      assert.equal(result.report?.downloads, 1, 'one saved picture')
      for (const side of ['front', 'back', 'photo']) {
        assert.deepEqual(report.hover[side], { matches: true, background: 'rgba(0, 0, 0, 0)', border: '0px' }, 'hovered ' + side + ' control keeps the card\'s look')
      }
      assert.deepEqual(report.errors, [], 'no page/route/runtime errors')
      assert.deepEqual(report.console.filter((item) => item.type === 'error'), [], 'no console errors')
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'stage-letter-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log('stage-letter browser check PASS: ' + report.pages['index'].assertions.length + ' assertions')
