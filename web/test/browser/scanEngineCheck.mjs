#!/usr/bin/env node
// T328 connected scan-engine browser gate (Chromium-only): bundles the
// fixture page with the repo's pinned Vite, serves it plus a synthetic
// same-origin 127.0.0.1 Node harness for the verified incoming page, the
// directory signing key, and blob downloads. The REAL exported Scan flow +
// REAL crypto run in a real browser. The harness counts every blob download
// so any content/photo read fails the gate. TEST-ONLY keys/tokens/synthetic
// pixels; no real backend/account.
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
if (!webDir || !outDir) throw new Error('usage: scanEngineCheck.mjs <webDir> <outDir>')
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

const work = join(tmpdir(), `scan-engine-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), 'import ' + JSON.stringify(join(web, 'test', 'browser', 'scanEngine.page.tsx')) + '\n')
await writeFile(join(work, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><script type="module" src="./index.ts"></script></body></html>')
await writeFile(
  join(work, 'vite.config.mjs'),
  'import react from ' + JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] })) + '\n' +
    'export default { root: ' + JSON.stringify(work) + ', logLevel: "warn", plugins: [react()], esbuild: { jsx: "automatic" }, build: { outDir: ' + JSON.stringify(join(work, 'dist')) + ', emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n',
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], { cwd: web, env: process.env, stdio: 'pipe', encoding: 'utf8' })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the scan-engine fixture failed')
}

const dist = join(work, 'dist')
const vendor = join(web, 'public', 'opencv')
if (!existsSync(vendor)) throw new Error('vendored opencv assets missing at ' + vendor)
await cp(vendor, join(dist, 'opencv'), { recursive: true })
const fontsDir = join(web, 'public', 'fonts')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf', '.wasm': 'application/wasm' }

let catalogPages = null
let signingRecord = null
const blobs = new Map()
const downloads = { recognition: 0, content: 0, photo: 0, paths: [] }
const incomingRequests = []
let delayCursor = null
let delayMs = 0
// T388 labelled admission fixtures; this host is NOT FastAPI/PG evidence.
let admissionMode = 'ok', admissionHeld = false, releaseAdmission = null
const admitted = new Set(), admissions = []
let activePage = null
const json = { 'content-type': 'application/json', 'cache-control': 'no-store' }
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  const chunks = []
  req.on('data', (chunk) => chunks.push(chunk))
  req.on('end', async () => {
    try {
      const body = Buffer.concat(chunks)
      if (req.method === 'GET' && url.pathname === '/__capture') {
        res.writeHead(200, json).end(JSON.stringify({ ...downloads, incoming: incomingRequests, admissions }))
        return
      }
      if (req.method === 'POST' && url.pathname === '/__admission') {
        const config = JSON.parse(body.toString('utf8'))
        admissionMode = config.mode ?? 'ok'
        admissionHeld = config.hold === true
        if (!admissionHeld) { releaseAdmission?.(); releaseAdmission = null }
        res.writeHead(200, json).end('{}'); return
      }
      if (req.method === 'POST' && url.pathname === '/__shot') {
        const shot = JSON.parse(body.toString('utf8'))
        assert.match(shot.name, /^[a-z0-9-]+$/)
        assert.ok([320, 1280].includes(shot.width))
        await activePage.setViewportSize({ width: shot.width, height: 900 })
        await activePage.screenshot({ path: join(outDir, `${shot.name}.png`), fullPage: true })
        const overflow = await activePage.evaluate(() => document.documentElement.scrollWidth > innerWidth)
        res.writeHead(200, json).end(JSON.stringify({ overflow })); return
      }
      const opening = /^\/v1\/capsules\/([0-9a-f-]{36})\/first-open$/.exec(url.pathname)
      if (req.method === 'POST' && opening) {
        admissions.push({ capsule: opening[1], bearer: Boolean(req.headers.authorization), bodyLength: body.length,
          content: downloads.content, photo: downloads.photo })
        if (admissionHeld) await new Promise((resolve) => { releaseAdmission = resolve })
        if (admissionMode === 'lost') { admitted.add(opening[1]); res.destroy(); return }
        if (admissionMode === 'offline') { res.destroy(); return }
        if (admissionMode === 'deny') { res.writeHead(409, json).end(JSON.stringify({ status: 409, code: 'CAPSULE_STATE_INVALID' })); return }
        if (admissionMode === 'auth') { res.writeHead(401, json).end('{}'); return }
        if (admissionMode === 'unknown') { res.writeHead(503, json).end('{}'); return }
        const replay = admitted.has(opening[1])
        admitted.add(opening[1])
        res.writeHead(200, json).end(JSON.stringify({ capsule_id: opening[1], state: 'OPENED',
          first_opened_at: '2026-10-20T01:00:00Z', is_replay: replay })); return
      }
      if (req.method === 'POST' && url.pathname === '/__catalog') {
        const parsed = JSON.parse(body.toString('utf8'))
        if (Array.isArray(parsed.pages)) {
          catalogPages = new Map(parsed.pages.map((page) => [page.cursor ?? null, page]))
        } else {
          catalogPages = new Map([[null, parsed]])
        }
        res.writeHead(200, json).end(JSON.stringify({ ok: true }))
        return
      }
      if (req.method === 'POST' && url.pathname === '/__delay') {
        const parsed = JSON.parse(body.toString('utf8'))
        delayCursor = parsed.cursor ?? null
        delayMs = Number(parsed.ms) || 0
        res.writeHead(200, json).end(JSON.stringify({ ok: true }))
        return
      }
      if (req.method === 'POST' && url.pathname === '/__signing') {
        signingRecord = JSON.parse(body.toString('utf8'))
        res.writeHead(200, json).end(JSON.stringify({ ok: true }))
        return
      }
      if (req.method === 'POST' && url.pathname === '/__blobs') {
        for (const entry of JSON.parse(body.toString('utf8'))) blobs.set(entry.blobId, Buffer.from(entry.bodyHex, 'hex'))
        res.writeHead(200, json).end(JSON.stringify({ ok: true }))
        return
      }
      if (req.method === 'GET' && url.pathname === '/v1/capsules/incoming') {
        const cursor = url.searchParams.get('cursor')
        const limit = url.searchParams.get('limit')
        incomingRequests.push({ cursor, limit })
        if (delayCursor === cursor && delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs))
        const page = catalogPages ? catalogPages.get(cursor) : undefined
        if (!page) { res.writeHead(404, json).end(JSON.stringify({ code: 'missing' })); return }
        res.writeHead(200, json).end(JSON.stringify(page))
        return
      }
      const directory = /^\/v1\/directory\/key-bundles\/([0-9a-f-]{36})$/.exec(url.pathname)
      if (req.method === 'GET' && directory) {
        if (!signingRecord || signingRecord.key_bundle_id !== directory[1]) {
          res.writeHead(404, json).end(JSON.stringify({ code: 'missing' }))
          return
        }
        res.writeHead(200, json).end(JSON.stringify(signingRecord))
        return
      }
      const blobRoute = /^\/v1\/capsules\/([0-9a-f-]{36})\/blobs\/([0-9a-f-]{36})$/.exec(url.pathname)
      if (req.method === 'GET' && blobRoute) {
        const blobId = blobRoute[2]
        const bytes = blobs.get(blobId)
        downloads.paths.push(url.pathname)
        if (!catalogPages) { res.writeHead(404).end(); return }
        const meta = [...catalogPages.values()]
          .flatMap((page) => page.items)
          .map((item) => item.blobs.find((blob) => blob.blob_id === blobId))
          .find(Boolean)
        const kind = meta ? meta.kind : 'UNKNOWN'
        if (kind === 'RECOGNITION_MANIFEST') downloads.recognition += 1
        else if (kind === 'CONTENT_MANIFEST') downloads.content += 1
        else if (kind === 'PHOTO') downloads.photo += 1
        else downloads.content += 1
        if (!bytes) { res.writeHead(404).end(); return }
        res.writeHead(200, { 'content-type': 'application/octet-stream', 'cache-control': 'no-store' }).end(bytes)
        return
      }
      if (req.method === 'GET' && url.pathname.startsWith('/fonts/')) {
        const file = join(fontsDir, decodeURIComponent(url.pathname.slice('/fonts/'.length)))
        if (!file.startsWith(fontsDir + sep)) { res.writeHead(403).end(); return }
        res.writeHead(200, { 'content-type': 'font/otf', 'cache-control': 'no-store' }).end(await readFile(file))
        return
      }
      const path = resolve(dist, '.' + decodeURIComponent(url.pathname))
      if (!path.startsWith(dist + sep) && path !== dist) {
        if (!res.headersSent && !res.destroyed) res.writeHead(403).end()
        return
      }
      const file = path === dist ? join(dist, 'index.html') : path
      const bytes = await readFile(file)
      if (!res.headersSent && !res.destroyed) {
        res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(bytes)
      }
    } catch {
      if (!res.headersSent && !res.destroyed) res.writeHead(404).end('missing')
    }
  })
})
await new Promise((ok, fail) => {
  server.once('error', fail)
  server.listen(4198, '127.0.0.1', ok)
})

let failure = null
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    const page = await browser.newPage({ viewport: { width: 900, height: 900 } })
    activePage = page
    page.on('console', (msg) => report.console.push({ type: msg.type(), text: msg.text() }))
    page.on('pageerror', (error) => report.errors.push(String(error)))
    await page.route('**/*', async (route) => {
      const url = route.request().url()
      if (/^http:\/\/127\.0\.0\.1:4198\//.test(url)) await route.continue()
      else {
        report.errors.push('external request blocked: ' + url)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4198/index.html')
    await page.waitForFunction(() => {
      const status = document.querySelector('#scan-engine-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 900000 })
    const status = await page.textContent('#scan-engine-status')
    const result = await page.evaluate(() => window.__scanEngine)
    report.dom = await page.evaluate(() => document.body.outerHTML.slice(0, 200000))
    await page.screenshot({ path: join(outDir, 'scan-engine-final.png'), fullPage: true })
    report.pages['index'] = { status, assertions: result?.assertions ?? [], failures: result?.failures ?? [], report: result?.report ?? null }
    await page.close()

    try {
      assert.equal(status, 'done', 'page status "' + status + '"')
      assert.ok(result, 'window.__scanEngine missing')
      for (const item of result.assertions) assert.equal(item.ok, true, 'scan-engine :: ' + item.name + ' ' + (item.detail ?? ''))
      assert.ok(result.assertions.length >= 20, 'expected >=20 assertions, got ' + result.assertions.length)
      assert.deepEqual(result.failures, [])
      assert.equal(result.report?.pass, true, 'report.pass must be true')
      assert.equal(result.report?.positive?.phase, 'presentation-admitted', 'positive phase')
      assert.equal(result.report?.positive?.photos, 3, 'positive memory photos')
      assert.ok(downloads.recognition >= 1, 'recognition blob downloaded')
      assert.deepEqual(report.errors, [], 'no page/route/runtime errors')
      // Deliberate denied/offline/lost first-open responses emit browser network errors.
      assert.ok(report.console.filter((item) => item.type === 'error').every((item) => /Failed to load resource/.test(item.text)), 'only controlled network errors')
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'scan-engine-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log('scan-engine browser check PASS: ' + report.pages['index'].assertions.length + ' assertions')
