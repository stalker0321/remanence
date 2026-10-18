#!/usr/bin/env node
// T243 receiver frozen-note browser gate (Chromium-only): bundles the fixture
// page with the repo's pinned Vite, serves it plus bundled fonts on loopback
// only, runs the REAL production prepare -> paintScene for a frozen multi-line
// note, traces fillText, and screenshots the painted sheet. The launched
// executable is explicitly the verified installed headless shell (path + SHA-256
// recorded). No provider/network.
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
if (!webDir || !outDir) throw new Error('usage: receiverNoteCheck.mjs <webDir> <outDir>')
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

const work = join(tmpdir(), `receiver-note-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), `import ${JSON.stringify(join(web, 'test', 'browser', 'receiverNote.page.tsx'))}\n`)
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
  throw new Error('vite build of the receiver-note fixture failed')
}

const dist = join(work, 'dist')
const fontsDir = join(web, 'public', 'fonts')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf' }
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1')
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
  server.listen(4177, '127.0.0.1', ok)
})

try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
    page.on('console', (msg) => report.console.push({ type: msg.type(), text: msg.text() }))
    page.on('pageerror', (error) => report.errors.push(String(error)))
    await page.route('**/*', async (route) => {
      const url = route.request().url()
      if (/^http:\/\/127\.0\.0\.1:4177\//.test(url)) await route.continue()
      else {
        report.errors.push(`external request blocked: ${url}`)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4177/index.html')
    await page.waitForFunction(() => document.querySelector('#receiver-note-status')?.textContent === 'done', undefined, { timeout: 60000 })
    const result = await page.evaluate(() => window.__receiverNote)
    report.pages['index'] = { assertions: result.assertions, failures: result.failures }
    for (const item of result.assertions) assert.equal(item.ok, true, `receiver-note :: ${item.name} ${item.detail ?? ''}`)
    assert.ok(result.assertions.length >= 12, `expected >=12 assertions, got ${result.assertions.length}`)
    assert.deepEqual(result.failures, [])
    await page.locator('#receiver-note-sheet').screenshot({ path: join(outDir, 'receiver-note-chromium.png') })
    await page.close()
  } finally {
    await browser.close()
  }
} finally {
  server.close()
}
report.shots = ['receiver-note-chromium.png']
await writeFile(join(outDir, 'receiver-note-report.json'), JSON.stringify(report, null, 2))
console.log(`receiver note browser check PASS: ${report.pages['index'].assertions.length} assertions`)
