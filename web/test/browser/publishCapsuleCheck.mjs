#!/usr/bin/env node
// T304 publisher browser gate (Chromium-only): bundles the fixture page with the
// repo's pinned Vite, serves it plus a synthetic same-origin 127.0.0.1 Node HTTP
// server implementing the EXACT current three-step wire (draft POST -> per-blob
// PUT -> finalize POST) with real receipts (one blob STORED, negotiated locator).
// The REAL production `sealCapsule`+`publishCapsule`+`fetchTransport` run in a
// real browser (no route.fulfill / scripted object for the wire). The fixture
// records the actual sequence/raw headers/bodies; the page and the runner assert
// them against the real sealer output. TEST-ONLY golden keys + synthetic
// header-JPEG photos + fake token; no real backend/account/admission claim.
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
if (!webDir || !outDir) throw new Error('usage: publishCapsuleCheck.mjs <webDir> <outDir>')
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

const work = join(tmpdir(), `publish-capsule-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), `import ${JSON.stringify(join(web, 'test', 'browser', 'publishCapsule.page.ts'))}\n`)
await writeFile(join(work, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.ts"></script></body></html>')
await writeFile(
  join(work, 'vite.config.mjs'),
  `import react from ${JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] }))}\n` +
    `export default { root: ${JSON.stringify(work)}, logLevel: 'warn', plugins: [react()], esbuild: { jsx: 'automatic' }, build: { outDir: ${JSON.stringify(join(work, 'dist'))}, emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n`,
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], { cwd: web, env: process.env, stdio: 'pipe', encoding: 'utf8' })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the publish-capsule fixture failed')
}

const dist = join(work, 'dist')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf' }
const captured = { draft: null, uploads: [], finalize: null }
const json = { 'content-type': 'application/json', 'cache-control': 'no-store' }
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  const chunks = []
  req.on('data', (chunk) => chunks.push(chunk))
  req.on('end', async () => {
    try {
      const body = Buffer.concat(chunks)
      if (req.method === 'GET' && url.pathname === '/__capture') {
        res.writeHead(200, json).end(JSON.stringify(captured))
        return
      }
      if (req.method === 'POST' && url.pathname === '/v1/capsules') {
        captured.draft = { method: req.method, url: req.url, rawHeaders: req.rawHeaders, bodyHex: body.toString('hex') }
        const parsed = JSON.parse(body.toString('utf8'))
        const blobs = parsed.blobs.map((blob, index) => ({ blob_id: blob.blob_id, state: index === 0 ? 'STORED' : 'DECLARED' }))
        res.writeHead(201, json).end(JSON.stringify({ capsule_id: parsed.capsule_id, state: 'DRAFT', draft_expires_at: '2026-10-17T00:00:00Z', blobs }))
        return
      }
      const upload = /^\/v1\/capsules\/([0-9a-f-]{36})\/blobs\/([0-9a-f-]{36})$/.exec(url.pathname)
      if (req.method === 'PUT' && upload) {
        captured.uploads.push({ method: req.method, url: req.url, rawHeaders: req.rawHeaders, bodyHex: body.toString('hex') })
        res.writeHead(204).end()
        return
      }
      const finalize = /^\/v1\/capsules\/([0-9a-f-]{36})\/finalize$/.exec(url.pathname)
      if (req.method === 'POST' && finalize) {
        captured.finalize = { method: req.method, url: req.url, rawHeaders: req.rawHeaders, bodyHex: body.toString('hex') }
        res.writeHead(201, json).end(JSON.stringify({ capsule_id: finalize[1], state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z', locator_hint: 'a•••@•••' }))
        return
      }
      const path = resolve(dist, '.' + decodeURIComponent(url.pathname))
      if (!path.startsWith(dist + sep) && path !== dist) {
        res.writeHead(403).end()
        return
      }
      const file = path === dist ? join(dist, 'index.html') : path
      res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(await readFile(file))
    } catch {
      res.writeHead(404).end('missing')
    }
  })
})
await new Promise((ok, fail) => {
  server.once('error', fail)
  server.listen(4182, '127.0.0.1', ok)
})

let failure = null
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    const page = await browser.newPage({ viewport: { width: 900, height: 700 } })
    page.on('console', (msg) => report.console.push({ type: msg.type(), text: msg.text() }))
    page.on('pageerror', (error) => report.errors.push(String(error)))
    await page.route('**/*', async (route) => {
      const url = route.request().url()
      if (/^http:\/\/127\.0\.0\.1:4182\//.test(url)) await route.continue()
      else {
        report.errors.push(`external request blocked: ${url}`)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4182/index.html')
    await page.waitForFunction(() => {
      const status = document.querySelector('#publish-capsule-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 60000 })
    const status = await page.textContent('#publish-capsule-status')
    const result = await page.evaluate(() => window.__publishCapsule)
    report.pages['index'] = { status, assertions: result?.assertions ?? [], failures: result?.failures ?? [], expected: result?.expected ?? null }
    await page.close()

    try {
      assert.equal(status, 'done', `page status "${status}"`)
      assert.ok(result, 'window.__publishCapsule missing')
      for (const item of result.assertions) assert.equal(item.ok, true, `publish-capsule :: ${item.name} ${item.detail ?? ''}`)
      assert.ok(result.assertions.length >= 24, `expected >=24 assertions, got ${result.assertions.length}`)
      assert.deepEqual(result.failures, [])

      // Independent Node-side checks of the recorded wire.
      assert.ok(captured.draft && captured.finalize, 'fixture did not record draft+finalize')
      assert.deepEqual(captured.uploads.map((call) => call.url), result.expected.uploadPaths, 'upload sequence differs')
      for (let index = 0; index < captured.uploads.length; index++) {
        const body = Buffer.from(captured.uploads[index].bodyHex, 'hex')
        assert.equal(body.toString('hex'), result.expected.uploadBodyHexes[index], `upload ${index} body bytes differ`)
        const raw = captured.uploads[index].rawHeaders
        const lengths = []
        const chunks = []
        for (let i = 0; i < raw.length; i += 2) {
          const name = String(raw[i]).toLowerCase()
          if (name === 'content-length') lengths.push(raw[i + 1])
          if (name === 'transfer-encoding') chunks.push(raw[i + 1])
        }
        assert.deepEqual(lengths, [String(body.length)], `upload ${index} Content-Length`)
        assert.deepEqual(chunks, [], `upload ${index} Transfer-Encoding must be absent`)
        const digest = createHash('sha256').update(body).digest('base64url')
        let digestHeader = null
        for (let i = 0; i < raw.length; i += 2) if (String(raw[i]).toLowerCase() === 'x-remanence-ciphertext-sha256') digestHeader = raw[i + 1]
        assert.equal(digestHeader, digest, `upload ${index} digest header`)
      }
      const finalizeBody = JSON.parse(Buffer.from(captured.finalize.bodyHex, 'hex').toString('utf8'))
      assert.equal(finalizeBody.signed_publish_statement.statement, result.expected.statementB64, 'finalize statement differs from real sealer output')
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'publish-capsule-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log(`publish-capsule browser check PASS: ${report.pages['index'].assertions.length} assertions`)
