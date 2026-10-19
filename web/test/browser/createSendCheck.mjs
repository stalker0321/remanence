#!/usr/bin/env node
// T326 connected Create Send browser gate (Chromium-only): bundles the fixture
// page with the repo's pinned Vite, serves it plus a synthetic same-origin
// 127.0.0.1 Node HTTP server implementing the EXACT current three-step wire
// (draft POST -> per-blob PUT -> finalize POST) with real receipts. The REAL
// production Create Send path (sealCapsule + publishCapsule + fetchTransport
// through the actual App wiring) runs in a real browser (no route.fulfill).
// The server supports three modes driven by the page: ok, finalize-500 (lost
// finalize), and hold-finalize (publication deferred until release, for the
// invalidation race). TEST-ONLY golden keys + synthetic JPEGs + fake tokens.
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
if (!webDir || !outDir) throw new Error('usage: createSendCheck.mjs <webDir> <outDir>')
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

const work = join(tmpdir(), `create-send-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), `import ${JSON.stringify(join(web, 'test', 'browser', 'createSend.page.tsx'))}\n`)
await writeFile(join(work, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.ts"></script></body></html>')
await writeFile(
  join(work, 'vite.config.mjs'),
  `import react from ${JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] }))}\n` +
    `export default { root: ${JSON.stringify(work)}, logLevel: 'warn', plugins: [react()], esbuild: { jsx: 'automatic' }, build: { outDir: ${JSON.stringify(join(work, 'dist'))}, emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n`,
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], { cwd: web, env: process.env, stdio: 'pipe', encoding: 'utf8' })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the create-send fixture failed')
}

const dist = join(work, 'dist')
const vendor = join(web, 'public', 'opencv')
if (!existsSync(vendor)) throw new Error(`vendored opencv assets missing at ${vendor}`)
await cp(vendor, join(dist, 'opencv'), { recursive: true })
const fontsDir = join(web, 'public', 'fonts')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf', '.wasm': 'application/wasm' }
const captured = { drafts: [], uploads: [], finalizes: [] }
let mode = 'ok'
let releaseHeld = false
let heldFinalize = null
let heldDraft = null
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
      if (req.method === 'POST' && url.pathname === '/__mode') {
        mode = String(JSON.parse(body.toString('utf8')).mode ?? 'ok')
        releaseHeld = false
        heldFinalize = null
        heldDraft = null
        res.writeHead(200, json).end(JSON.stringify({ mode }))
        return
      }
      if (req.method === 'POST' && url.pathname === '/__release') {
        releaseHeld = true
        if (heldDraft) {
          const { res: heldRes, receipt } = heldDraft
          heldDraft = null
          if (!heldRes.headersSent && !heldRes.destroyed) heldRes.writeHead(201, json).end(JSON.stringify(receipt))
        }
        if (heldFinalize) {
          const { res: heldRes, capsuleId } = heldFinalize
          heldFinalize = null
          if (!heldRes.headersSent && !heldRes.destroyed) {
            heldRes.writeHead(201, json).end(JSON.stringify({ capsule_id: capsuleId, state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z', locator_hint: 'a•••@•••' }))
          }
        }
        if (!res.headersSent && !res.destroyed) res.writeHead(200, json).end(JSON.stringify({ released: true }))
        return
      }
      if (req.method === 'POST' && url.pathname === '/v1/capsules') {
        captured.drafts.push({ method: req.method, url: req.url, rawHeaders: req.rawHeaders, bodyHex: body.toString('hex') })
        const parsed = JSON.parse(body.toString('utf8'))
        // All blobs DECLARED: the browser must upload every ciphertext.
        const blobs = parsed.blobs.map((blob) => ({ blob_id: blob.blob_id, state: 'DECLARED' }))
        const receipt = { capsule_id: parsed.capsule_id, state: 'DRAFT', draft_expires_at: '2026-10-17T00:00:00Z', blobs }
        if (mode === 'hold-draft' && !releaseHeld) {
          heldDraft = { res, receipt }
          return
        }
        res.writeHead(201, json).end(JSON.stringify(receipt))
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
        const call = { method: req.method, url: req.url, rawHeaders: req.rawHeaders, bodyHex: body.toString('hex') }
        captured.finalizes.push(call)
        if (mode === 'finalize-500') {
          res.writeHead(500, json).end(JSON.stringify({ code: 'internal', message: 'synthetic finalize failure' }))
          return
        }
        if (mode === 'hold-finalize' && !releaseHeld) {
          heldFinalize = { res, capsuleId: finalize[1] }
          return
        }
        if (!res.headersSent && !res.destroyed) res.writeHead(201, json).end(JSON.stringify({ capsule_id: finalize[1], state: 'READY', ready_at: '2026-10-17T00:00:10Z', locator_code: 'AB12Z', locator_hint: 'a•••@•••' }))
        return
      }
      if (req.method === 'GET' && url.pathname.startsWith('/fonts/')) {
        const file = join(fontsDir, decodeURIComponent(url.pathname.slice('/fonts/'.length)))
        if (!file.startsWith(fontsDir + sep)) {
          res.writeHead(403).end()
          return
        }
        if (!res.headersSent && !res.destroyed) res.writeHead(200, { 'content-type': 'font/otf', 'cache-control': 'no-store' }).end(await readFile(file))
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
  server.listen(4187, '127.0.0.1', ok)
})

function headerValues(rawHeaders, name) {
  const out = []
  for (let i = 0; i < rawHeaders.length; i += 2) if (String(rawHeaders[i]).toLowerCase() === name) out.push(rawHeaders[i + 1])
  return out
}
const header = (rawHeaders, name) => headerValues(rawHeaders, name)[0] ?? null

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
      if (/^http:\/\/127\.0\.0\.1:4187\//.test(url)) await route.continue()
      else {
        report.errors.push(`external request blocked: ${url}`)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4187/index.html')
    await page.waitForFunction(() => {
      const status = document.querySelector('#create-send-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 1200000 })
    const status = await page.textContent('#create-send-status')
    const result = await page.evaluate(() => window.__createSend)
    report.pages['index'] = { status, assertions: result?.assertions ?? [], failures: result?.failures ?? [], report: result?.report ?? null }
    await page.close()

    try {
      assert.equal(status, 'done', `page status "${status}"`)
      assert.ok(result, 'window.__createSend missing')
      for (const item of result.assertions) assert.equal(item.ok, true, `create-send :: ${item.name} ${item.detail ?? ''}`)
      assert.ok(result.assertions.length >= 24, `expected >=24 assertions, got ${result.assertions.length}`)
      assert.deepEqual(result.failures, [])

      // Independent Node-side checks of the recorded wire across scenarios.
      assert.equal(captured.drafts.length, 5, 'expected exactly five drafts (happy, lost-finalize, invalidation, draft-edit, alternate-order)')
      assert.equal(captured.finalizes.length, 4, 'expected exactly four finalize attempts (none for the draft-edit scenario)')
      assert.equal(captured.uploads.length, 20, 'expected 5 uploads per publication scenario except the draft-edit stop')
      const draftKey = header(captured.drafts[0].rawHeaders, 'idempotency-key')
      const finalizeKey = header(captured.finalizes[0].rawHeaders, 'idempotency-key')
      assert.ok(draftKey && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(draftKey), `draft idempotency key ${draftKey}`)
      // The accepted wire sends the ONE per-attempt idempotency key on the
      // draft route only; finalize carries the signed statement + envelope.
      assert.equal(finalizeKey, null, 'finalize does not carry an idempotency key header')
      const uploadKeys = captured.uploads.slice(0, 5).map((call) => header(call.rawHeaders, 'idempotency-key'))
      assert.equal(new Set(uploadKeys).size, 5, 'per-blob upload idempotency keys are distinct')
      assert.ok(!uploadKeys.includes(draftKey), 'upload keys differ from the draft key')
      for (const call of captured.uploads) {
        const body = Buffer.from(call.bodyHex, 'hex')
        assert.deepEqual(headerValues(call.rawHeaders, 'content-length'), [String(body.length)], `upload ${call.url} Content-Length`)
        assert.deepEqual(headerValues(call.rawHeaders, 'transfer-encoding'), [], `upload ${call.url} Transfer-Encoding must be absent`)
        const digest = createHash('sha256').update(body).digest('base64url')
        assert.equal(header(call.rawHeaders, 'x-remanence-ciphertext-sha256'), digest, `upload ${call.url} digest header`)
      }
      const finalizeBody = JSON.parse(Buffer.from(captured.finalizes[0].bodyHex, 'hex').toString('utf8'))
      const pageReport = result.report ?? {}
      assert.equal(finalizeBody.signed_publish_statement.statement, pageReport.statementB64, 'finalize statement matches the page-captured sealer output')
      const envelopeHex = Buffer.from(finalizeBody.recipient_envelope.ciphertext, 'base64').toString('hex')
      assert.equal(envelopeHex, pageReport.envelopeHex, 'finalize envelope ciphertext matches the page round trip')
      assert.equal(pageReport.happyUploads, 5, 'page saw five uploads on the happy path')
      assert.equal(pageReport.roundTrip?.note, true, 'receiver round trip recovered the note text')
      const noticeLog = pageReport.notices ?? []
      assert.ok(noticeLog.includes('sendSent'), 'sendSent notice present for the happy paths')
      assert.equal(noticeLog.filter((n) => n === 'sendSent').length, 2, 'exactly two sendSent (primary + alternate camera-before-freeze order)')
      assert.ok(!noticeLog.includes('sendNotConfirmed') || noticeLog.filter((n) => n === 'sendNotConfirmed').length >= 1, 'lost finalize produced a not-confirmed notice')
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'create-send-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log(`create-send browser check PASS: ${report.pages['index'].assertions.length} assertions`)
