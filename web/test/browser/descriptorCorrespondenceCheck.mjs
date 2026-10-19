#!/usr/bin/env node
// T330 connected correspondence browser gate (Chromium-only): bundles the
// fixture page with the repo's pinned Vite and serves it plus the committed
// same-origin fonts/opencv vendor assets. The REAL correspondence stage runs
// with the REAL pinned BFMatcher (root-proven exports) over genuine accepted
// producer/extractor wires. TEST-ONLY synthetic pixels; no backend/account.
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
if (!webDir || !outDir) throw new Error('usage: descriptorCorrespondenceCheck.mjs <webDir> <outDir>')
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

const work = join(tmpdir(), `descriptor-correspondence-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), 'import ' + JSON.stringify(join(web, 'test', 'browser', 'descriptorCorrespondence.page.tsx')) + '\n')
await writeFile(join(work, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.ts"></script></body></html>')
await writeFile(
  join(work, 'vite.config.mjs'),
  'import react from ' + JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] })) + '\n' +
    'export default { root: ' + JSON.stringify(work) + ', logLevel: "warn", plugins: [react()], esbuild: { jsx: "automatic" }, build: { outDir: ' + JSON.stringify(join(work, 'dist')) + ', emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n',
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], { cwd: web, env: process.env, stdio: 'pipe', encoding: 'utf8' })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the descriptor-correspondence fixture failed')
}

const dist = join(work, 'dist')
const vendor = join(web, 'public', 'opencv')
if (!existsSync(vendor)) throw new Error('vendored opencv assets missing at ' + vendor)
await cp(vendor, join(dist, 'opencv'), { recursive: true })
const fontsDir = join(web, 'public', 'fonts')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf', '.wasm': 'application/wasm' }

const json = { 'content-type': 'application/json', 'cache-control': 'no-store' }
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  const chunks = []
  req.on('data', (chunk) => chunks.push(chunk))
  req.on('end', async () => {
    try {
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
  server.listen(4191, '127.0.0.1', ok)
})

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
      if (/^http:\/\/127\.0\.0\.1:4191\//.test(url)) await route.continue()
      else {
        report.errors.push('external request blocked: ' + url)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4191/index.html')
    await page.waitForFunction(() => {
      const status = document.querySelector('#descriptor-correspondence-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 600000 })
    const status = await page.textContent('#descriptor-correspondence-status')
    const result = await page.evaluate(() => window.__descriptorCorrespondence)
    report.pages['index'] = { status, assertions: result?.assertions ?? [], failures: result?.failures ?? [], report: result?.report ?? null }
    await page.close()

    try {
      assert.equal(status, 'done', 'page status "' + status + '"')
      assert.ok(result, 'window.__descriptorCorrespondence missing')
      for (const item of result.assertions) assert.equal(item.ok, true, 'descriptor-correspondence :: ' + item.name + ' ' + (item.detail ?? ''))
      assert.ok(result.assertions.length >= 14, 'expected >=14 assertions, got ' + result.assertions.length)
      assert.deepEqual(result.failures, [])
      assert.equal(result.report.vendorRuntime, 'pinned-opencv-BFMatcher')
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'descriptor-correspondence-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log('descriptor-correspondence browser check PASS: ' + report.pages['index'].assertions.length + ' assertions')
