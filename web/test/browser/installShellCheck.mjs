#!/usr/bin/env node
// T356 real production build at ROOT and at --base=/test-prefix/. Serves ONLY
// the selected base, records every request, and hard-fails on any root/external
// fallback, page/console error, hash drift, icon/manifest failure or unsafe
// maskable radius. No install/offline/physical/provider claim.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { extname, join, resolve, sep } from 'node:path'
import { tmpdir } from 'node:os'

const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')
const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: installShellCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const work = join(tmpdir(), `install-shell-${process.pid}`)
await mkdir(work, { recursive: true })
const rootOut = join(work, 'root')
const prefixOut = join(work, 'prefix')
const PREFIX = '/test-prefix/'

const report = { modes: {}, builds: {}, errors: [], console: [], requests: {}, rootRequests: [] }
const rootBuild = spawnSync('npx', ['vite', 'build', '--outDir', rootOut], {
  cwd: web,
  env: { ...process.env, REMANENCE_STATIC_BASE: '/' },
  encoding: 'utf8',
})
const prefixBuild = spawnSync('npx', ['vite', 'build', '--base=/test-prefix/', '--outDir', prefixOut], {
  cwd: web,
  // The CLI base must override an independently configured default.
  env: { ...process.env, REMANENCE_STATIC_BASE: '/' },
  encoding: 'utf8',
})
report.builds.root = { status: rootBuild.status, log: (rootBuild.stdout ?? '') + (rootBuild.stderr ?? '') }
report.builds.prefix = { status: prefixBuild.status, log: (prefixBuild.stdout ?? '') + (prefixBuild.stderr ?? '') }
assert.equal(rootBuild.status, 0, report.builds.root.log)
assert.equal(prefixBuild.status, 0, report.builds.prefix.log)

// Build the assertion entry as a separate ES module into each production dist,
// then inject it into the REAL built index.html. The app's production document
// (manifest/theme/favicon/boot/app bundle) remains the page under test.
const testEntry = join(web, 'test/browser/installShell.page.tsx')
async function buildTest(base, out, name) {
  const config = join(work, `${name}.config.mjs`)
  await writeFile(
    config,
    `export default { root: ${JSON.stringify(web)}, base: ${JSON.stringify(base)}, logLevel: 'warn', build: { lib: { entry: ${JSON.stringify(testEntry)}, formats: ['es'], fileName: () => 'install-shell.js' }, outDir: ${JSON.stringify(out)}, emptyOutDir: false, minify: false } }\n`,
  )
  const built = spawnSync('npx', ['vite', 'build', '--config', config], { cwd: web, env: process.env, encoding: 'utf8' })
  report.builds[name] = { status: built.status, log: (built.stdout ?? '') + (built.stderr ?? '') }
  assert.equal(built.status, 0, report.builds[name].log)
}
await buildTest('/', rootOut, 'test-root')
await buildTest('/test-prefix/', prefixOut, 'test-prefix')
const rootHtml = (await readFile(join(rootOut, 'index.html'), 'utf8')).replace(
  '</body>',
  '<script type="module" src="/install-shell.js"></script></body>',
)
const prefixHtml = (await readFile(join(prefixOut, 'index.html'), 'utf8')).replace(
  '</body>',
  '<script type="module" src="/test-prefix/install-shell.js"></script></body>',
)
report.injected = { root: rootHtml.includes('src="/install-shell.js"'), prefix: prefixHtml.includes('src="/test-prefix/install-shell.js"') }
assert.equal(report.injected.root, true)
assert.equal(report.injected.prefix, true)

const executable = '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
assert.ok(existsSync(executable), 'pinned chromium missing')
report.executableSha256 = createHash('sha256').update(await readFile(executable)).digest('hex')
assert.equal(report.executableSha256, '4fa61f0ba0b4f89b7bec242c32ca16d3bccf69dbfef0651924a9ba8fede5f9d6')

const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json',
  '.otf': 'font/otf', '.wasm': 'application/wasm', '.json': 'application/json', '.txt': 'text/plain',
}
function fileFor(baseDir, pathname) {
  const rel = pathname.endsWith('/') ? `${pathname}index.html` : pathname
  const file = resolve(baseDir, '.' + rel)
  return file.startsWith(baseDir + sep) || file === baseDir ? file : null
}
async function serve(res, baseDir, pathname) {
  if (pathname === '/' || pathname === '/index.html') {
    const html = baseDir === rootOut ? rootHtml : prefixHtml
    res.writeHead(200, { 'content-type': 'text/html', 'cache-control': 'no-store' }).end(html)
    return
  }
  try {
    const file = fileFor(baseDir, pathname)
    if (!file) { res.writeHead(403).end(); return }
    const body = await readFile(file)
    res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' }).end(body)
  } catch {
    res.writeHead(404).end('missing')
  }
}
const rootServer = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  serve(res, rootOut, url.pathname)
})
const prefixServer = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  if (!url.pathname.startsWith(PREFIX)) {
    report.rootRequests.push(url.pathname)
    res.writeHead(404).end('outside prefix')
    return
  }
  serve(res, prefixOut, url.pathname.slice(PREFIX.length - 1))
})
await new Promise((ok, fail) => { rootServer.once('error', fail); rootServer.listen(4200, '127.0.0.1', ok) })
await new Promise((ok, fail) => { prefixServer.once('error', fail); prefixServer.listen(4201, '127.0.0.1', ok) })

const sourceFontHashes = new Set(
  await Promise.all((await readdir(join(web, 'public/fonts'))).filter((name) => name.endsWith('.otf')).map(async (name) => {
    return createHash('sha256').update(await readFile(join(web, 'public/fonts', name))).digest('hex')
  })),
)
const OPENCV_JS = '44f5c78f56d4a760a6dab81ddf5efd2c9fce17f273866a3b2204a95ad7530366'
const OPENCV_WASM = 'e569091b890a6464aa77c1e3945ff0012352fd47067c491774f9c149388fc924'

const browser = await chromium.launch({ executablePath: executable, headless: true })
try {
  for (const mode of ['root', 'prefix']) {
    for (const width of [900, 320]) {
      const port = mode === 'root' ? 4200 : 4201
      const path = mode === 'root' ? '/' : PREFIX
      const url = `http://127.0.0.1:${port}${path}?mode=${mode}`
      const page = await browser.newPage({ viewport: { width, height: width === 320 ? 568 : 900 } })
      const requests = []
      const errors = []
      page.on('request', (request) => requests.push({ url: request.url(), type: request.resourceType() }))
      page.on('pageerror', (error) => errors.push(String(error)))
      page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()) })
      try {
        await page.goto(url)
        await page.waitForFunction(() => {
          const status = document.querySelector('#install-shell-status')?.textContent
          return status === 'done' || status === 'failed'
        }, undefined, { timeout: 120000 })
        const status = await page.textContent('#install-shell-status')
        const result = await page.evaluate(() => window.__installShell)
        await page.screenshot({ path: join(outDir, `${mode}-${width}.png`), fullPage: false })
        assert.equal(status, 'done', `${mode}/${width} status`)
        assert.ok(result, 'window.__installShell missing')
        assert.deepEqual(result.failures, [], `${mode}/${width} failures`)
        for (const item of result.checks) assert.equal(item.ok, true, `${mode}/${width} :: ${item.name} ${item.detail ?? ''}`)
        assert.deepEqual(errors, [], `${mode}/${width} page/console errors`)
        const external = requests.filter((entry) => !entry.url.startsWith(`http://127.0.0.1:${port}/`))
        assert.deepEqual(external, [], `${mode}/${width} external requests`)
        if (mode === 'prefix') {
          const outside = requests.filter((entry) => new URL(entry.url).pathname.startsWith('/') && !new URL(entry.url).pathname.startsWith(PREFIX))
          assert.deepEqual(outside, [], 'prefix requested an asset outside the prefix')
          assert.deepEqual(report.rootRequests, [], 'prefix server received a root request')
        } else {
          const prefixed = requests.filter((entry) => new URL(entry.url).pathname.startsWith(PREFIX))
          assert.deepEqual(prefixed, [], 'root requested a prefix asset')
        }
        const fontHashes = result.report.fonts.map((font) => font.hash).sort()
        assert.equal(fontHashes.length, 9, 'nine font responses')
        assert.deepEqual(fontHashes, [...sourceFontHashes].sort(), 'font bytes changed')
        const js = result.report.openCv.find((entry) => entry.path.endsWith('/opencv.js'))
        const wasm = result.report.openCv.find((entry) => entry.path.endsWith('/opencv_js.wasm'))
        assert.equal(js?.hash, OPENCV_JS, 'OpenCV JS hash changed')
        assert.equal(wasm?.hash, OPENCV_WASM, 'OpenCV WASM hash changed')
        assert.ok(result.report.maskableMaxForegroundRadius <= 512 * 0.4, 'maskable safe radius')
        assert.ok(result.report.geometry.overflow <= 1, 'horizontal overflow')
        report.modes[`${mode}-${width}`] = { status, checks: result.checks.length, requests, report: result.report }
        report.requests[`${mode}-${width}`] = requests
      } catch (error) {
        report.errors.push({ mode, width, error: String(error) })
        throw error
      } finally {
        await page.close()
      }
    }
  }
} finally {
  await browser.close()
  rootServer.close()
  prefixServer.close()
  await writeFile(join(outDir, 'install-shell-report.json'), JSON.stringify(report, null, 2))
}
console.log(`install-shell PASS: ${Object.keys(report.modes).join(', ')}; rootRequests=${report.rootRequests.length}`)
