#!/usr/bin/env node
// T228 browser normalization check (Chromium-only gate).
// Bundles web/test/browser/normalizeCheck.page.ts + the REAL production
// modules with the repo's pinned Vite, serves the bundle on loopback only,
// and asserts the in-page report in real Chromium (EXIF 1-8, ladder,
// refusal, metadata strip, draft intake). No provider, no uploads.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve, sep } from 'node:path'
import { tmpdir } from 'node:os'

const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')

const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: normalizeCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const report = { synthetic: true, assertions: [], failures: [], console: [], errors: [] }

const work = join(tmpdir(), `normcheck-${process.pid}`)
await mkdir(work, { recursive: true })
const page = join(web, 'test', 'browser', 'normalizeCheck.page.ts')
await writeFile(
  join(work, 'entry.ts'),
  `import ${JSON.stringify(page)}\n`,
)
await writeFile(
  join(work, 'index.html'),
  '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./entry.ts"></script></body></html>',
)
await writeFile(
  join(work, 'vite.config.mjs'),
  `export default { root: ${JSON.stringify(work)}, logLevel: 'warn', build: { outDir: ${JSON.stringify(join(work, 'dist'))}, emptyOutDir: true, minify: false, sourcemap: false, rollupOptions: { onwarn() {} } } }\n`,
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], {
  cwd: web,
  env: process.env,
  stdio: 'pipe',
  encoding: 'utf8',
})
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the normalization check failed')
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

let browser
try {
  browser = await chromium.launch({ headless: true })
  report.browser = browser.version()
  report.executable = chromium.executablePath()
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  page.on('console', (msg) => report.console.push({ type: msg.type(), text: msg.text() }))
  page.on('pageerror', (error) => report.errors.push(String(error)))
  await page.route('**/*', async (route) => {
    const url = route.request().url()
    if (/^http:\/\/127\.0\.0\.1:4175\//.test(url)) await route.continue()
    else {
      report.errors.push(`external request blocked: ${url}`)
      await route.abort()
    }
  })
  await page.goto('http://127.0.0.1:4175/')
  await page.waitForSelector('#norm-status', { timeout: 60000 })
  const result = await page.evaluate(() => window.__normCheck)
  report.assertions = result.assertions
  report.failures = result.failures
  for (const item of result.assertions) {
    assert.equal(item.ok, true, `${item.name} ${item.detail ?? ''}`)
  }
  assert.ok(result.assertions.length >= 30, `expected >=30 assertions, got ${result.assertions.length}`)
  assert.deepEqual(result.failures, [])
  await page.screenshot({ path: join(outDir, 'normalize-chromium.png') })
  report.shots = ['normalize-chromium.png']
} finally {
  await browser?.close()
  server.close()
}
await writeFile(join(outDir, 'normalize-report.json'), JSON.stringify(report, null, 2))
console.log(`normalize browser check PASS: ${report.assertions.length} assertions`)
