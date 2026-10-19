#!/usr/bin/env node
// T316 sender front-still browser gate (Chromium-only): bundles the fixture
// page with the repo's pinned Vite, serves it (plus the vendored custom
// OpenCV WASM) on loopback only, and runs the REAL production
// `produceFrontStillFingerprint` from deterministic synthetic JPEGs through
// the real decode/contour/select/warp/SIFT pipeline in a pinned headless
// Chromium. Proves functional localization/warp ONLY: no real-postcard field
// accuracy, exact native numerics, scan-admission, receive or AppSend.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve, sep } from 'node:path'
import { tmpdir } from 'node:os'

const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')

const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: frontStillProducerCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const report = { synthetic: true, pages: {}, console: [], errors: [] }

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

const work = join(tmpdir(), `front-still-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), `import ${JSON.stringify(join(web, 'test', 'browser', 'frontStillProducer.page.ts'))}\n`)
await writeFile(join(work, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.ts"></script></body></html>')
await writeFile(
  join(work, 'vite.config.mjs'),
  `import react from ${JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] }))}\n` +
    `export default { root: ${JSON.stringify(work)}, logLevel: 'warn', plugins: [react()], esbuild: { jsx: 'automatic' }, build: { outDir: ${JSON.stringify(join(work, 'dist'))}, emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n`,
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], { cwd: web, env: process.env, stdio: 'pipe', encoding: 'utf8' })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the front-still fixture failed')
}

const dist = join(work, 'dist')
const vendor = join(web, 'public', 'opencv')
if (!existsSync(vendor)) throw new Error(`vendored opencv assets missing at ${vendor}`)
await cp(vendor, join(dist, 'opencv'), { recursive: true })

const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf', '.wasm': 'application/wasm' }
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1')
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
  server.listen(4186, '127.0.0.1', ok)
})

let failure = null
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    const page = await browser.newPage({ viewport: { width: 800, height: 600 } })
    page.on('console', (msg) => report.console.push({ type: msg.type(), text: msg.text() }))
    page.on('pageerror', (error) => report.errors.push(String(error)))
    await page.route('**/*', async (route) => {
      const url = route.request().url()
      if (/^http:\/\/127\.0\.0\.1:4186\//.test(url)) await route.continue()
      else {
        report.errors.push(`external request blocked: ${url}`)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4186/index.html')
    await page.waitForFunction(() => {
      const status = document.querySelector('#front-still-producer-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 180000 })
    const status = await page.textContent('#front-still-producer-status')
    const result = await page.evaluate(() => window.__frontStillProducer)
    report.pages['index'] = {
      status,
      assertions: result?.assertions ?? [],
      failures: result?.failures ?? [],
      wireHex: result?.report?.wireHex ?? '',
      distinctWireHex: result?.report?.distinctWireHex ?? '',
    }
    await page.close()
    try {
      assert.equal(status, 'done', `page status "${status}"`)
      assert.ok(result, 'window.__frontStillProducer missing')
      for (const item of result.assertions) assert.equal(item.ok, true, `front-still :: ${item.name} ${item.detail ?? ''}`)
      assert.ok(result.assertions.length >= 18, `expected >=18 assertions, got ${result.assertions.length}`)
      assert.deepEqual(result.failures, [])
      assert.ok(/^[0-9a-f]+$/.test(result.report?.wireHex ?? '') && (result.report?.wireHex ?? '').length > 0, 'missing produced wire hex')
      assert.ok(/^[0-9a-f]+$/.test(result.report?.distinctWireHex ?? '') && (result.report?.distinctWireHex ?? '').length > 0, 'missing distinct wire hex')
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'front-still-producer-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log(`front-still browser check PASS: ${report.pages['index'].assertions.length} assertions`)
