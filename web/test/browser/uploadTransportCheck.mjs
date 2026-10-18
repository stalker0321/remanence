#!/usr/bin/env node
// T300 upload-transport browser gate (Chromium-only): bundles the fixture page
// with the repo's pinned Vite, serves it on loopback and hosts a synthetic
// `/v1/capsules` draft POST + `/v1/capsules/{id}/blobs/{id}` PUT on the SAME
// 127.0.0.1 Node server, so the REAL production `fetchTransport` runs in a real
// browser against a real loopback HTTP peer (no route.fulfill/request mocks for
// the upload). The fixture records the actual raw headers/body and the runner
// asserts the browser-owned wire: exactly one automatic Content-Length, no
// Transfer-Encoding, fixed headers, raw byte body and matching SHA-256. The
// launched executable is the verified installed headless shell (path + SHA-256
// recorded). Synthetic ciphertext + a fake fixture token only.
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
if (!webDir || !outDir) throw new Error('usage: uploadTransportCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const report = { synthetic: true, pages: {}, console: [], errors: [] }

const CAPSULE = 'b0b1b2b3-b4b5-b6b7-b8b9-babbbcbdbebf'
const BLOB = '00000000-0000-0000-0000-0000000000e5'

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

const work = join(tmpdir(), `upload-transport-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), `import ${JSON.stringify(join(web, 'test', 'browser', 'uploadTransport.page.ts'))}\n`)
await writeFile(
  join(work, 'index.html'),
  '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.ts"></script></body></html>',
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
  throw new Error('vite build of the upload-transport fixture failed')
}

const dist = join(work, 'dist')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf' }
const captured = { draft: null, upload: null }
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  const chunks = []
  req.on('data', (chunk) => chunks.push(chunk))
  req.on('end', async () => {
    try {
      if (req.method === 'GET' && url.pathname === '/__capture') {
        res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify(captured))
        return
      }
      if (req.method === 'POST' && url.pathname === '/v1/capsules') {
        captured.draft = { method: req.method, url: req.url, rawHeaders: req.rawHeaders, bodyBase64: Buffer.concat(chunks).toString('base64') }
        res.writeHead(201, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify({ capsule_id: CAPSULE, state: 'DRAFT' }))
        return
      }
      if (req.method === 'PUT' && url.pathname === `/v1/capsules/${CAPSULE}/blobs/${BLOB}`) {
        captured.upload = { method: req.method, url: req.url, rawHeaders: req.rawHeaders, bodyBase64: Buffer.concat(chunks).toString('base64') }
        res.writeHead(204).end()
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
  server.listen(4181, '127.0.0.1', ok)
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
      if (/^http:\/\/127\.0\.0\.1:4181\//.test(url)) await route.continue()
      else {
        report.errors.push(`external request blocked: ${url}`)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4181/index.html')
    await page.waitForFunction(() => {
      const status = document.querySelector('#upload-transport-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 60000 })
    const status = await page.textContent('#upload-transport-status')
    const result = await page.evaluate(() => window.__uploadTransport)
    report.pages['index'] = {
      status,
      assertions: result?.assertions ?? [],
      failures: result?.failures ?? [],
      expected: result?.expected ?? null,
    }
    await page.close()

    try {
      assert.equal(status, 'done', `page status "${status}"`)
      assert.ok(result, 'window.__uploadTransport missing')
      for (const item of result.assertions) assert.equal(item.ok, true, `upload-transport :: ${item.name} ${item.detail ?? ''}`)
      assert.ok(result.assertions.length >= 18, `expected >=18 assertions, got ${result.assertions.length}`)
      assert.deepEqual(result.failures, [])

      // Independent Node-side wire re-verification of the fixture-recorded upload.
      assert.ok(captured.upload, 'fixture did not record the upload request')
      const body = Buffer.from(captured.upload.bodyBase64, 'base64')
      assert.equal(body.toString('hex'), result.expected.bodyHex, 'recorded body bytes differ from the page-declared ciphertext')
      const digestB64Url = createHash('sha256').update(body).digest('base64url')
      assert.equal(digestB64Url, result.expected.digestB64Url, 'recorded body SHA-256 differs from the declared digest')
      const raw = captured.upload.rawHeaders
      const contentLengths = []
      const transferEncodings = []
      for (let i = 0; i < raw.length; i += 2) {
        const name = String(raw[i]).toLowerCase()
        if (name === 'content-length') contentLengths.push(raw[i + 1])
        if (name === 'transfer-encoding') transferEncodings.push(raw[i + 1])
      }
      assert.deepEqual(contentLengths, [String(body.length)], `expected exactly one Content-Length == ${body.length}`)
      assert.deepEqual(transferEncodings, [], 'Transfer-Encoding must be absent')
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'upload-transport-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log(`upload-transport browser check PASS: ${report.pages['index'].assertions.length} assertions`)
