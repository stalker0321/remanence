#!/usr/bin/env node
// T348 requested-quality query gate (Chromium-only): bundles the fixture page
// with the repo's pinned Vite, serves the vendored custom OpenCV JS/WASM on
// loopback only, and runs the REAL production query with `quality:true`
// (shared cached RawCv -> wrapOpenCvRuntime + accepted native meter). The
// counting wrapper calls the real runtime/meter/decode; default/sender wires
// must stay byte-identical.
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
if (!webDir || !outDir) throw new Error('usage: queryQualityCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
const PORT = 4197
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
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex')
report.assets = {}
for (const name of ['opencv.js', 'opencv_js.wasm']) {
  report.assets[name] = sha(await readFile(join(web, 'public', 'opencv', name)))
}

const work = join(tmpdir(), `query-quality-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, 'index.ts'), `import ${JSON.stringify(join(web, 'test', 'browser', 'queryQuality.page.ts'))}\n`)
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
  throw new Error('vite build of the query-quality fixture failed')
}

const dist = join(work, 'dist')
const vendor = join(web, 'public', 'opencv')
if (!existsSync(vendor)) throw new Error(`vendored opencv assets missing at ${vendor}`)
await cp(vendor, join(dist, 'opencv'), { recursive: true })

const types = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.css': 'text/css',
  '.otf': 'font/otf',
  '.wasm': 'application/wasm',
}
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
    res
      .writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' })
      .end(await readFile(file))
  } catch {
    res.writeHead(404).end('missing')
  }
})
await new Promise((ok, fail) => {
  server.once('error', fail)
  server.listen(PORT, '127.0.0.1', ok)
})

let failure = null
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    const page = await browser.newPage({ viewport: { width: 800, height: 600 } })
    page.on('console', (msg) => {
      const text = msg.text().slice(0, 500)
      report.console.push({ type: msg.type(), text })
      if (msg.type() === 'error') report.errors.push(`console.error: ${text}`)
    })
    page.on('pageerror', (error) => report.errors.push(String(error)))
    await page.route('**/*', async (route) => {
      const url = route.request().url()
      if (new RegExp(`^http://127\\.0\\.0\\.1:${PORT}/`).test(url)) await route.continue()
      else {
        report.errors.push(`external request blocked: ${url}`)
        await route.abort()
      }
    })
    await page.goto(`http://127.0.0.1:${PORT}/index.html`)
    await page.waitForFunction(
      () => {
        const status = document.querySelector('#query-quality-status')?.textContent
        return status === 'done' || status === 'failed'
      },
      undefined,
      { timeout: 180000 },
    )
    const status = await page.textContent('#query-quality-status')
    const result = await page.evaluate(() => window.__queryQuality)
    report.pages['index'] = {
      status,
      assertions: result?.assertions ?? [],
      failures: result?.failures ?? [],
      report: result?.report ?? null,
    }
    await page.close()
    try {
      assert.deepEqual(report.errors, [], `page/route errors recorded: ${JSON.stringify(report.errors)}`)
      const consoleErrors = report.console.filter((entry) => entry.type === 'error')
      assert.deepEqual(consoleErrors, [], `console errors recorded: ${JSON.stringify(consoleErrors)}`)
      assert.equal(status, 'done', `page status "${status}"`)
      assert.ok(result, 'window.__queryQuality missing')
      assert.deepEqual(result.failures, [], `semantic failures: ${JSON.stringify(result.failures)}`)
      for (const item of result.assertions) assert.equal(item.ok, true, `query-quality :: ${item.name} ${item.detail ?? ''}`)
      assert.ok(result.assertions.length >= 12, `expected >=12 assertions, got ${result.assertions.length}`)
      assert.equal(result.report?.pass, true, 'report.pass must be true')
      assert.deepEqual(result.report?.counts, { decode: 1, warp: 1, meter: 1 })
      assert.equal(result.report?.wireEqual, true)
      assert.equal(result.report?.senderQualityAbsent, true)
      assert.ok(Number.isFinite(result.report?.quality?.laplacianVariance))
    } catch (error) {
      failure = error
    }
  } finally {
    await browser.close()
  }
} finally {
  server.close()
  await writeFile(join(outDir, 'query-quality-report.json'), JSON.stringify(report, null, 2))
}
if (failure) {
  const page = report.pages['index']
  if (page) console.error('PAGE-FAILURES', JSON.stringify(page.failures, null, 2))
  throw failure
}
console.log(
  `query-quality browser check PASS: ${report.pages['index'].assertions.length} assertions, ` +
    `reasons=${JSON.stringify(report.pages['index'].report?.reasons)}`,
)
