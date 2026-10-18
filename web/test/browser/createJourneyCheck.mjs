#!/usr/bin/env node
// T241 connected actual-Create journey checks (Chromium-only gate): bundles
// the fixture page with the repo's pinned Vite, serves it plus bundled fonts
// on loopback only, and asserts in real Chromium across EN/RU/UK and
// narrow+wide viewports. The launched executable is the verified installed
// headless shell; its path + SHA-256 are recorded.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { extname, join, resolve, sep } from 'node:path'
import { tmpdir } from 'node:os'

const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')

const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: createJourneyCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const report = { synthetic: true, pages: {}, console: [], errors: [] }
const PORT = 4177

const candidates = [
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

const work = join(tmpdir(), `createjourney-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), `import ${JSON.stringify(join(web, 'test', 'browser', 'createJourney.page.tsx'))}\n`)
await writeFile(
  join(work, 'index.html'),
  '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><script type="module" src="./index.ts"></script></body></html>',
)
await writeFile(
  join(work, 'vite.config.mjs'),
  `import react from ${JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] }))}\n` +
    `export default { root: ${JSON.stringify(work)}, logLevel: 'warn', plugins: [react()], esbuild: { jsx: 'automatic' }, build: { outDir: ${JSON.stringify(join(work, 'dist'))}, emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n`,
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], {
  cwd: web,
  env: process.env,
  stdio: 'pipe',
  encoding: 'utf8',
})
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the create journey fixture failed')
}

const dist = join(work, 'dist')
const fontsDir = join(web, 'public', 'fonts')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf' }

// CSS/font byte provenance: the fixture imports the REAL production
// `src/design/tokens.css`; Vite emits it as a built stylesheet, and the faces
// it registers resolve to the real `web/public/fonts/*.otf` bytes served here.
const assetsDir = join(dist, 'assets')
let cssFiles = []
try {
  cssFiles = (await readdir(assetsDir)).filter((name) => name.endsWith('.css'))
} catch {
  cssFiles = []
}
report.cssProvenance = []
for (const name of cssFiles) {
  report.cssProvenance.push({ file: `assets/${name}`, sha256: createHash('sha256').update(await readFile(join(assetsDir, name))).digest('hex') })
}
const studySansPath = join(fontsDir, 'nimbus_sans_regular.otf')
report.fontProvenance = existsSync(studySansPath)
  ? { file: 'web/public/fonts/nimbus_sans_regular.otf', sha256: createHash('sha256').update(await readFile(studySansPath)).digest('hex') }
  : null
const fontResponses = []
const styleResponses = []
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
    if (url.pathname.startsWith('/fonts/')) {
      const file = join(fontsDir, decodeURIComponent(url.pathname.slice('/fonts/'.length)))
      if (!file.startsWith(fontsDir + sep)) {
        res.writeHead(403)
        res.end()
        return
      }
      res.writeHead(200, { 'content-type': 'font/otf', 'cache-control': 'no-store' }).end(await readFile(file))
      return
    }
    const path = resolve(dist, '.' + decodeURIComponent(url.pathname))
    if (!path.startsWith(dist + sep) && path !== dist) {
      res.writeHead(403)
      res.end()
      return
    }
    const file = path === dist ? join(dist, 'index.html') : path
    res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(await readFile(file))
  } catch {
    res.writeHead(404).end('missing')
  }
})
await new Promise((ok, fail) => {
  server.once('error', fail)
  server.listen(PORT, '127.0.0.1', ok)
})

const runs = []
for (const lang of ['en', 'ru', 'uk']) {
  for (const [viewport, width, height] of [
    ['narrow', 390, 844],
    ['wide', 1280, 800],
  ]) {
    runs.push({ lang, viewport, width, height })
  }
}
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    for (const run of runs) {
      const route = `index.html?lang=${run.lang}`
      const page = await browser.newPage({ viewport: { width: run.width, height: run.height } })
      page.on('console', (msg) => {
        report.console.push({ page: route, viewport: run.viewport, type: msg.type(), text: msg.text() })
        if (msg.type() === 'error') console.log(`CONSOLE ${run.lang}/${run.viewport}: ${msg.text().slice(0, 300)}`)
      })
      page.on('pageerror', (error) => {
        report.errors.push(`${route}/${run.viewport}: ${String(error)}`)
        console.log(`PAGEERROR ${run.lang}/${run.viewport}: ${String(error).slice(0, 300)}`)
      })
      const runKey = `${run.lang}-${run.viewport}`
      page.on('response', (response) => {
        const url = response.url()
        if (url.includes('/fonts/')) fontResponses.push({ run: runKey, file: url.split('/').pop(), status: response.status() })
        else if (url.split('?')[0].endsWith('.css')) styleResponses.push({ run: runKey, file: url.split('/').pop(), status: response.status() })
      })
      await page.route('**/*', async (r) => {
        const url = r.request().url()
        if (new RegExp(`^http://127\\.0\\.0\\.1:${PORT}/`).test(url)) await r.continue()
        else {
          report.errors.push(`external request blocked: ${url}`)
          await r.abort()
        }
      })
      await page.goto(`http://127.0.0.1:${PORT}/${route}`)
      try {
        await page.waitForFunction(
          () => {
            const status = document.querySelector('#journey-status')?.textContent
            return status === 'capture-ready' || status === 'failed'
          },
          undefined,
          { timeout: 120000 },
        )
      } catch (timeout) {
        const diag = await page.evaluate(() => ({
          status: document.querySelector('#journey-status')?.textContent ?? null,
          journey: window.__createJourney,
          testids: [...document.querySelectorAll('[data-testid]')].map((node) => node.getAttribute('data-testid')),
          bodyLen: document.body.textContent.length,
          bitmapCloses: window.__bitmapCloses ?? null,
          t241: window.__t241 ?? null,
        }))
        report.errors.push(`journey timeout ${route}/${run.viewport}: ${JSON.stringify(diag)}`)
        throw timeout
      }
      const statusText = await page.evaluate(() => document.querySelector('#journey-status')?.textContent)
      if (statusText === 'failed') {
        const diag = await page.evaluate(() => ({
          journey: window.__createJourney,
          testids: [...document.querySelectorAll('[data-testid]')].map((node) => node.getAttribute('data-testid')),
          canvas: (() => { const c = document.querySelector('[data-testid="creator-canvas"]'); return c instanceof HTMLCanvasElement ? c.dataset.painted ?? null : null })(),
          bodyLen: document.body.textContent.length,
          t241: window.__t241 ?? null,
        }))
        report.failures = report.failures ?? []
        report.failures.push(`${route}/${run.viewport}: ${JSON.stringify(diag)}`)
        throw new Error(`create journey reported failed status for ${route}/${run.viewport} :: ${JSON.stringify(diag)}`)
      }
      // Viewport shot of the real styled connected Create flow (picker + note +
      // composer + controls) — production CSS constrains the photo controls, so
      // this is not the earlier giant unconstrained full-page dump.
      await page.locator('[data-testid="create-host"]').screenshot({
        path: join(outDir, `create-journey-${run.lang}-${run.viewport}-chromium.png`),
      })
      await page.evaluate(() => { window.__createRelease = true })
      await page.waitForFunction(() => document.querySelector('#journey-status')?.textContent === 'done', undefined, { timeout: 60000 })
      const result = await page.evaluate(() => window.__createJourney)
      report.pages[runKey] = { assertions: result.assertions, failures: result.failures, fonts: result.fonts ?? [], layout: result.layout ?? [] }
      if (result.failures.length > 0) console.log(`PAGE-FAILURES ${run.lang}/${run.viewport}: ${JSON.stringify(result.failures)}`)
      for (const item of result.assertions) {
        assert.equal(item.ok, true, `${run.lang}/${run.viewport} :: ${item.name} ${item.detail ?? ''}`)
      }
      assert.ok(result.assertions.length >= 15, `${run.lang}/${run.viewport}: expected >=15 assertions, got ${result.assertions.length}`)
      assert.deepEqual(result.failures, [])
      assert.ok(
        (result.fonts ?? []).length >= 2 && result.fonts.every((fact) => fact.faces > 0 && fact.status === 'loaded'),
        `${run.lang}/${run.viewport}: real bundle faces not loaded: ${JSON.stringify(result.fonts)}`,
      )
      assert.ok(
        (result.layout ?? []).length >= 1 && result.layout.every((fact) => fact.scrollWidth <= fact.innerWidth + 1),
        `${run.lang}/${run.viewport}: horizontal overflow: ${JSON.stringify(result.layout)}`,
      )
      await page.close()
    }
    // Actual font/style byte requests served on loopback for every run: prove
    // the real StudySans `.otf` and the built production stylesheet were
    // fetched (not descriptor-only font checks).
    for (const run of runs) {
      const key = `${run.lang}-${run.viewport}`
      const fonts = fontResponses.filter((entry) => entry.run === key)
      const styles = styleResponses.filter((entry) => entry.run === key)
      assert.ok(
        fonts.some((entry) => entry.file === 'nimbus_sans_regular.otf' && entry.status === 200),
        `${key}: real StudySans font bytes not served (${JSON.stringify(fonts)})`,
      )
      assert.ok(styles.some((entry) => entry.status === 200), `${key}: production stylesheet not served (${JSON.stringify(styles)})`)
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  // Persist the structured result (assertions, fonts, layout, raw font/style
  // byte responses, provenance) even when an assertion throws, so a failing
  // run still leaves complete evidence.
  report.fontResponses = fontResponses
  report.styleResponses = styleResponses
  report.shots = runs.map((run) => `create-journey-${run.lang}-${run.viewport}-chromium.png`)
  await writeFile(join(outDir, 'create-journey-report.json'), JSON.stringify(report, null, 2))
}
const total = Object.values(report.pages).reduce((sum, page) => sum + page.assertions.length, 0)
console.log(`create journey checks PASS: ${total} assertions across ${Object.keys(report.pages).length} runs`)
