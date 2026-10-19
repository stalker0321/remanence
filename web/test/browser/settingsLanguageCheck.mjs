#!/usr/bin/env node
// T332 settings/language UI browser gate (Chromium-only): bundles the fixture
// page with the repo's pinned Vite, serves it on loopback only, and mounts the
// REAL production `App` to exercise the header `LanguageMenu` and the real
// `Settings` screen in a pinned headless Chromium. No backend/provider/camera;
// synthetic session/identities only. The launched executable is the verified
// installed headless shell (path + SHA-256 recorded).
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
if (!webDir || !outDir) throw new Error('usage: settingsLanguageCheck.mjs <webDir> <outDir>')
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

const work = join(tmpdir(), `settings-language-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.tsx'), `import ${JSON.stringify(join(web, 'test', 'browser', 'settingsLanguage.page.tsx'))}\n`)
await writeFile(join(work, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module" src="./index.tsx"></script></body></html>')
await writeFile(
  join(work, 'vite.config.mjs'),
  `import react from ${JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] }))}\n` +
    `export default { root: ${JSON.stringify(work)}, logLevel: 'warn', plugins: [react()], esbuild: { jsx: 'automatic' }, build: { outDir: ${JSON.stringify(join(work, 'dist'))}, emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n`,
)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'vite.config.mjs')], { cwd: web, env: process.env, stdio: 'pipe', encoding: 'utf8' })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? '') + (built.stderr ?? ''))
  throw new Error('vite build of the settings-language fixture failed')
}

const dist = join(work, 'dist')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf' }
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
  server.listen(4188, '127.0.0.1', ok)
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
      if (/^http:\/\/127\.0\.0\.1:4188\//.test(url)) await route.continue()
      else {
        report.errors.push(`external request blocked: ${url}`)
        await route.abort()
      }
    })
    await page.goto('http://127.0.0.1:4188/index.html')
    await page.waitForFunction(() => {
      const status = document.querySelector('#settings-language-status')?.textContent
      return status === 'done' || status === 'failed'
    }, undefined, { timeout: 60000 })
    const status = await page.textContent('#settings-language-status')
    const result = await page.evaluate(() => window.__settingsLanguage)
    report.pages['index'] = { status, assertions: result?.assertions ?? [], failures: result?.failures ?? [], prefWrites: result?.prefWrites ?? [], language: result?.language ?? null, docLang: result?.docLang ?? null }
    // Real UI screenshots (desktop + narrow 320px) of the settings screen for
    // independent visual review. Taken regardless of assertion outcome.
    try {
      await page.evaluate(() => { window.location.hash = '#/settings' })
      await page.waitForTimeout(400)
      await page.setViewportSize({ width: 800, height: 600 })
      await page.screenshot({ path: join(outDir, 'settings-desktop.png'), fullPage: true })
      await page.setViewportSize({ width: 320, height: 640 })
      await page.waitForTimeout(150)
      await page.screenshot({ path: join(outDir, 'settings-narrow.png'), fullPage: true })
      report.screenshots = ['settings-desktop.png', 'settings-narrow.png']
    } catch (error) {
      report.errors.push(`screenshot failed: ${String(error)}`)
    }
    await page.close()
    try {
      assert.equal(status, 'done', `page status "${status}"`)
      assert.ok(result, 'window.__settingsLanguage missing')
      for (const item of result.assertions) assert.equal(item.ok, true, `settings-language :: ${item.name} ${item.detail ?? ''}`)
      assert.ok(result.assertions.length >= 20, `expected >=20 assertions, got ${result.assertions.length}`)
      assert.deepEqual(result.failures, [])
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'settings-language-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log(`settings-language browser check PASS: ${report.pages['index'].assertions.length} assertions`)
