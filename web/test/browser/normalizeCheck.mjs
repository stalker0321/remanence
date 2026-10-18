#!/usr/bin/env node
// T228 browser checks (Chromium-only gates): bundles the in-page checks with
// the repo's pinned Vite, serves the bundle on loopback only, and asserts in
// real Chromium. Pages:
// - index.html: normalizeCheck.page.ts (EXIF 1-8, ladder, strip, hash, draft).
// - create.html: createPhotos.page.tsx (connected Create fencing).
// The launched executable is explicitly the verified installed headless
// shell; its path + SHA-256 are recorded (T232 provenance correction).
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve, sep } from 'node:path'
import { tmpdir } from 'node:os'

const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')

const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: normalizeCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const report = { synthetic: true, pages: {}, console: [], errors: [] }

// Explicit headless-shell selection: playwright-core 1.64 launches the
// headless shell for headless runs; chromium.executablePath() points at the
// (absent) full-chromium default, so resolve and pin the installed shell.
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

const work = join(tmpdir(), `normcheck-${process.pid}`)
await mkdir(work, { recursive: true })
for (const [html, entry] of [
  ['index.html', join(web, 'test', 'browser', 'normalizeCheck.page.ts')],
  ['create.html', join(web, 'test', 'browser', 'createPhotos.page.tsx')],
]) {
  await writeFile(join(work, html.replace('.html', '.ts')), `import ${JSON.stringify(entry)}\n`)
}
await writeFile(
  join(work, 'index.html'),
  '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.ts"></script></body></html>',
)
await writeFile(
  join(work, 'create.html'),
  '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./create.ts"></script></body></html>',
)
await writeFile(
  join(work, 'vite.config.mjs'),
  `export default { root: ${JSON.stringify(work)}, logLevel: 'warn', esbuild: { jsx: 'automatic' }, build: { outDir: ${JSON.stringify(join(work, 'dist'))}, emptyOutDir: true, minify: false, sourcemap: false, rollupOptions: { input: { index: ${JSON.stringify(join(work, 'index.html'))}, create: ${JSON.stringify(join(work, 'create.html'))} }, onwarn() {} } } }\n`,
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], {
  cwd: web,
  env: process.env,
  stdio: 'pipe',
  encoding: 'utf8',
})
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the browser checks failed')
}

const dist = join(work, 'dist')
const types = { '.html': 'text/html', '.js': 'text/javascript' }
const server = createServer(async (req, res) => {
  try {
    const path = resolve(dist, '.' + decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname))
    if (!path.startsWith(dist + sep) && path !== dist) {
      res.writeHead(403)
      res.end()
      return
    }
    const file = path === dist ? join(dist, 'index.html') : path
    const bytes = await readFile(file)
    res.writeHead(200, {
      'content-type': types[extname(file)] ?? 'application/octet-stream',
      'cache-control': 'no-store',
    }).end(bytes)
  } catch {
    res.writeHead(404).end('missing')
  }
})
await new Promise((ok, fail) => {
  server.once('error', fail)
  server.listen(4175, '127.0.0.1', ok)
})

const expectedCounts = { index: 30, create: 10 }
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    for (const [route, key, statusId, minCount] of [
      ['index.html', '__normCheck', 'norm-status', expectedCounts.index],
      ['create.html', '__createPhotos', 'create-photos-status', expectedCounts.create],
    ]) {
      const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
      page.on('console', (msg) => report.console.push({ page: route, type: msg.type(), text: msg.text() }))
      page.on('pageerror', (error) => report.errors.push(`${route}: ${String(error)}`))
      await page.route('**/*', async (r) => {
        const url = r.request().url()
        if (/^http:\/\/127\.0\.0\.1:4175\//.test(url)) await r.continue()
        else {
          report.errors.push(`external request blocked: ${url}`)
          await r.abort()
        }
      })
      await page.goto(`http://127.0.0.1:4175/${route}`)
      await page.waitForSelector(`#${statusId}`, { timeout: 60000 })
      const result = await page.evaluate((k) => window[k], key)
      report.pages[route] = { assertions: result.assertions, failures: result.failures }
      for (const item of result.assertions) {
        assert.equal(item.ok, true, `${route} :: ${item.name} ${item.detail ?? ''}`)
      }
      assert.ok(result.assertions.length >= minCount, `${route}: expected >=${minCount} assertions, got ${result.assertions.length}`)
      assert.deepEqual(result.failures, [])
      await page.screenshot({ path: join(outDir, `${route === 'index.html' ? 'normalize' : 'create'}-chromium.png`) })
      await page.close()
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
}
report.shots = ['normalize-chromium.png', 'create-chromium.png']
await writeFile(join(outDir, 'normalize-report.json'), JSON.stringify(report, null, 2))
const total = Object.values(report.pages).reduce((sum, page) => sum + page.assertions.length, 0)
console.log(`browser checks PASS: ${total} assertions across ${Object.keys(report.pages).length} pages`)
