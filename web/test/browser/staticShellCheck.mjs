#!/usr/bin/env node
// Real production documents/workers in pinned Chromium. Faults are confined to
// this loopback fixture server. No provider, native install or account claim.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { mkdir, mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises'
import { extname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { pathToFileURL } from 'node:url'

const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: staticShellCheck.mjs webDir outDir')
const web = resolve(webDir)
await mkdir(outDir, { recursive: true })
const work = await mkdtemp(join(tmpdir(), 't359-shell-'))
const { shellArtifacts } = await import(pathToFileURL(join(web, 'tools/static-shell.mjs')))
const { chromium } = createRequire('/srv/remanence/tools/browser-smoke/package.json')('playwright-core')
const executable = '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex')
const report = { checks: [], requests: [], pageErrors: [], console: [], builds: [], cacheSnapshots: [], pass: false }
report.executableSha256 = digest(await readFile(executable))
assert.equal(report.executableSha256, '4fa61f0ba0b4f89b7bec242c32ca16d3bccf69dbfef0651924a9ba8fede5f9d6')
function check(name, actual, expected = true) {
  assert.deepEqual(actual, expected, name)
  report.checks.push(name)
}
const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.otf': 'font/otf',
  '.webmanifest': 'application/manifest+json', '.json': 'application/json', '.wasm': 'application/wasm',
}
async function build(base, revision, tag) {
  const out = join(work, tag)
  const config = join(work, `${tag}.config.mjs`)
  // A build-only revision marker makes an actual second hashed JS graph.
  await writeFile(config, `import original from ${JSON.stringify(join(web, 'vite.config.ts'))};
export default {...original, plugins: [...original.plugins, {
  name: 'fixture-revision', transform(code, id) {
    if (id === ${JSON.stringify(join(web, 'src/main.tsx'))}) return code + ${JSON.stringify(`\ndocument.documentElement.dataset.shellRevision = '${revision}';\n`)}
  }
}]};`)
  const result = spawnSync('npx', ['vite', 'build', '--config', config, '--base', base, '--outDir', out], {
    cwd: web, encoding: 'utf8', env: { ...process.env, REMANENCE_STATIC_BASE: '/' },
  })
  await writeFile(join(outDir, `${tag}.build.log`), (result.stdout || '') + (result.stderr || ''))
  report.builds.push({ tag, status: result.status })
  assert.equal(result.status, 0, `build ${tag}`)
  const manifest = JSON.parse(await readFile(join(out, 'shell-manifest.json'), 'utf8'))
  const files = new Map()
  for (const entry of manifest.entries) {
    const name = entry.path.slice(base.length)
    const bytes = await readFile(join(out, name))
    assert.equal(digest(bytes), entry.sha256)
    files.set(name, bytes)
  }
  files.set('sw.js', await readFile(join(out, 'sw.js')))
  files.set('shell-manifest.json', Buffer.from(JSON.stringify(manifest)))
  const chunks = (await readdir(join(out, 'assets'))).filter((name) => name.endsWith('.js'))
  check(`${tag}: includes ALL emitted chunks, including lazy browser-external`, chunks.every((name) => files.has(`assets/${name}`)))
  check(`${tag}: nine original fonts`, manifest.entries.filter((e) => e.path.endsWith('.otf')).length, 9)
  check(`${tag}: excludes OpenCV and runtime data`, manifest.entries.some((e) => /\/(?:opencv|v1|music|recovery)\//.test(e.path)), false)
  return { base, manifest, files, revision }
}

const root1 = await build('/', 'v1', 'root-v1')
const prefix1 = await build('/test-prefix/', 'v1', 'prefix-v1')
const root2 = await build('/', 'v2', 'root-v2')
const prefix2 = await build('/test-prefix/', 'v2', 'prefix-v2')
const repeat = await build('/', 'v1', 'root-repeat')
check('identical builds have identical manifest and worker', [repeat.manifest, String(repeat.files.get('sw.js'))], [root1.manifest, String(root1.files.get('sw.js'))])
check('root and prefix cache namespaces differ', root1.manifest.namespace !== prefix1.manifest.namespace)
check('new build really changes hashed JS graph', root1.manifest.entries.filter((e) => e.path.endsWith('.js')).some((e) => !root2.manifest.entries.find((next) => next.path === e.path)))

const servers = []
async function serverFor(versions, label) {
  const state = { versions, fault: null }
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://fixture.invalid')
    report.requests.push({ server: label, path: url.pathname, search: url.search, method: req.method })
    const selected = [...state.versions].sort((a, b) => b.base.length - a.base.length).find((v) => url.pathname.startsWith(v.base))
    if (!selected) { res.writeHead(404).end('outside base'); return }
    let name = url.pathname.slice(selected.base.length)
    if (!name) name = 'index.html'
    const fault = state.fault
    if (fault && (name === fault.path || (fault.kind === 'registration' && name === 'sw.js'))) {
      if (fault.kind === 'redirect') { res.writeHead(302, { location: `${selected.base}favicon.svg` }).end(); return }
      if (fault.kind === '404' || fault.kind === 'registration') { res.writeHead(404).end('fixture failure'); return }
      if (fault.kind === 'mime') { res.writeHead(200, { 'content-type': 'application/json' }).end(selected.files.get(name)); return }
      if (fault.kind === 'bytes') { res.writeHead(200, { 'content-type': types[extname(name)] }).end('fixture wrong bytes'); return }
    }
    const bytes = selected.files.get(name)
    res.writeHead(200, {
      'content-type': bytes ? types[extname(name)] : 'application/json',
      'cache-control': 'no-store', 'access-control-allow-origin': '*',
      // Ordinary compression variance is safe; content hashes still match.
      'vary': 'Accept-Encoding',
    }).end(bytes || '{"fixture":"network-only"}')
  })
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok))
  servers.push(server)
  return { state, origin: `http://127.0.0.1:${server.address().port}` }
}
const rootHost = await serverFor([root1], 'root')
const prefixHost = await serverFor([prefix1], 'prefix-only')
const sharedHost = await serverFor([root1, prefix1], 'shared')
const foreign = await serverFor([root1], 'cross-origin')
const browser = await chromium.launch({ executablePath: executable, headless: true })
let activePage
function watch(page, label) {
  activePage = page
  page.on('pageerror', (error) => report.pageErrors.push({ label, error: String(error) }))
  page.on('console', (msg) => { if (msg.type() === 'error') report.console.push({ label, error: msg.text() }) })
}
async function waitFor(page, predicate, arg) {
  const deadline = Date.now() + 30000
  while (!(await page.evaluate(predicate, arg))) {
    if (Date.now() >= deadline) throw new Error(`browser predicate timed out: ${predicate}`)
    await page.waitForTimeout(50)
  }
}
async function ready(page, origin, version) {
  await page.goto(`${origin}${version.base}#/settings`)
  await waitFor(page, async (base) => {
    const r = await navigator.serviceWorker.getRegistration(base)
    return r?.scope === new URL(base, location.origin).href && r.active?.state === 'activated'
  }, version.base)
  // Natural subsequent navigation; production deliberately does not claim().
  await page.reload()
  await page.waitForFunction((base) => navigator.serviceWorker.controller?.scriptURL === new URL(`${base}sw.js`, location.origin).href, version.base)
}
async function cacheProof(page, version, label) {
  const actual = await page.evaluate(async (cacheName) => {
    const result = []
    const cache = await caches.open(cacheName)
    for (const key of await cache.keys()) {
      const response = await cache.match(key)
      const hash = await crypto.subtle.digest('SHA-256', await response.arrayBuffer())
      result.push({ path: new URL(key.url).pathname, sha256: Array.from(new Uint8Array(hash), (n) => n.toString(16).padStart(2, '0')).join('') })
    }
    return result.sort((a, b) => a.path.localeCompare(b.path))
  }, version.manifest.cacheName)
  const expected = version.manifest.entries.map(({ path, sha256 }) => ({ path, sha256 })).sort((a, b) => a.path.localeCompare(b.path))
  check(`${label}: CacheStorage exactly equals allowlist with original bytes`, actual, expected)
  report.cacheSnapshots.push({ label, cacheName: version.manifest.cacheName, actual })
}
async function update(page, base) {
  return page.evaluate(async (base) => {
    const reg = await navigator.serviceWorker.getRegistration(base)
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('worker update timeout')), 20000)
      reg.addEventListener('updatefound', () => {
        const worker = reg.installing
        worker.addEventListener('statechange', () => {
          if (worker.state === 'installed' || worker.state === 'redundant') {
            clearTimeout(timeout)
            resolve(worker.state)
          }
        })
      }, { once: true })
      reg.update().catch((error) => { clearTimeout(timeout); reject(error) })
    })
  }, base)
}
try {
  for (const [host, version, label] of [[rootHost, root1, 'root'], [prefixHost, prefix1, 'prefix']]) {
    const context = await browser.newContext({ viewport: { width: 320, height: 568 }, reducedMotion: 'reduce' })
    const page = await context.newPage()
    watch(page, label)
    await ready(page, host.origin, version)
    await cacheProof(page, version, `${label} installed`)
    const negatives = [
      ['/v1/auth/google/options', 'GET'], ['/v1/recovery/record', 'GET'], ['/music/v1/search', 'GET'],
      ['/v1/capsules/fixture/blobs/fixture', 'GET'], ['/photo/fixture', 'GET'], ['/artwork/fixture', 'GET'],
      [`${version.base}unknown`, 'GET'], [`${version.base}boot.js?secret=fixture`, 'GET'],
      [`${version.base}boot.js`, 'POST'], [`${version.base}opencv/opencv.js`, 'GET'],
      [`${version.base}boot.js`, 'GET', { Authorization: 'Bearer synthetic-fixture' }],
    ]
    const before = report.requests.length
    const results = await page.evaluate(async ({ negatives, foreign }) => {
      const result = []
      for (const [path, method, headers] of negatives) {
        const response = await fetch(path, { method, headers, cache: 'no-store' })
        result.push(response.status)
      }
      await fetch(`${foreign}/cross`, { cache: 'no-store' })
      await fetch(`${foreign}/opaque`, { mode: 'no-cors', cache: 'no-store' })
      const blob = URL.createObjectURL(new Blob(['synthetic photo']))
      await fetch(blob)
      URL.revokeObjectURL(blob)
      return result
    }, { negatives, foreign: foreign.origin })
    check(`${label}: every negative request reaches network`, report.requests.slice(before).filter((r) => r.server !== 'cross-origin').length, negatives.length)
    check(`${label}: prefix host rejects root API fallbacks`, results.slice(0, 6), Array(6).fill(label === 'prefix' ? 404 : 200))
    await cacheProof(page, version, `${label} after negatives`)
    // Real offline context and a new document load, no route/network mocks.
    await context.setOffline(true)
    await page.reload()
    await page.waitForSelector('.shell')
    check(`${label}: offline shell booted`, await page.getAttribute('html', 'data-remanence-boot'), 'ready')
    check(`${label}: offline copy explains limits`, (await page.textContent('body')).includes('signing in, unlocking, scanning and sending need a connection'))
    check(`${label}: offline API fails honestly`, await page.evaluate(() => fetch('/v1/recovery/record', { cache: 'no-store' }).then(() => false, () => true)))
    check(`${label}: narrow no overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    await page.screenshot({ path: join(outDir, `${label}-offline-320.png`), fullPage: true })
    await writeFile(join(outDir, `${label}-offline.html`), await page.content())
    await cacheProof(page, version, `${label} offline`)
    await context.setOffline(false)
    await context.close()
  }
  // Same-origin root and prefix workers; unrelated persistence must survive.
  const context = await browser.newContext({ viewport: { width: 900, height: 900 } })
  const rootPage = await context.newPage()
  const prefixPage = await context.newPage()
  watch(rootPage, 'shared-root')
  watch(prefixPage, 'shared-prefix')
  await ready(rootPage, sharedHost.origin, root1)
  await ready(prefixPage, sharedHost.origin, prefix1)
  await rootPage.evaluate(async () => {
    const cache = await caches.open('unrelated-app')
    await cache.put('/unrelated-marker', new Response('keep'))
    localStorage.setItem('unrelated-key', 'keep')
    await new Promise((ok, fail) => {
      const request = indexedDB.open('unrelated-db', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('sentinel')
      request.onerror = fail
      request.onsuccess = () => {
        const db = request.result
        const tx = db.transaction('sentinel', 'readwrite')
        tx.objectStore('sentinel').put('keep', 'marker')
        tx.oncomplete = () => { db.close(); ok() }
      }
    })
  })
  await cacheProof(rootPage, root1, 'coexisting root')
  await cacheProof(prefixPage, prefix1, 'coexisting prefix')
  let rootLoads = 0
  rootPage.on('load', () => rootLoads++)
  await rootPage.evaluate(() => { window.fixtureDraft = 'unsent synthetic draft' })
  // New worker installs while an old page holds transient state.
  sharedHost.state.versions = [root2, prefix1]
  check('root update installs and waits', await update(rootPage, '/'), 'installed')
  await waitFor(rootPage, async () => Boolean((await navigator.serviceWorker.getRegistration('/')).waiting))
  await rootPage.waitForFunction(() => document.body.textContent.includes('An update is ready'))
  check('update never reloads old tab or loses transient state', [rootLoads, await rootPage.evaluate(() => window.fixtureDraft)], [0, 'unsent synthetic draft'])
  check('old document keeps old revision', await rootPage.getAttribute('html', 'data-shell-revision'), 'v1')
  await cacheProof(rootPage, root1, 'old graph while waiting')
  await cacheProof(rootPage, root2, 'new graph waiting')
  await cacheProof(prefixPage, prefix1, 'prefix survives root installation')
  // Explicit requests of every OLD graph URL must still return old bytes,
  // even though those old hashed files are absent from the v2 server.
  const oldGraph = await rootPage.evaluate(async (entries) => {
    const result = []
    for (const e of entries) {
      const bytes = await (await fetch(e.path)).arrayBuffer()
      const hash = await crypto.subtle.digest('SHA-256', bytes)
      result.push(Array.from(new Uint8Array(hash), (n) => n.toString(16).padStart(2, '0')).join(''))
    }
    return result
  }, root1.manifest.entries)
  check('old tab still fetches its entire compatible graph', oldGraph, root1.manifest.entries.map((e) => e.sha256))
  await rootPage.screenshot({ path: join(outDir, 'update-waiting-900.png'), fullPage: true })
  await rootPage.close()
  await waitFor(prefixPage, async (name) => !(await caches.keys()).includes(name), root1.manifest.cacheName)
  const nextPage = await context.newPage()
  watch(nextPage, 'activated-root')
  await ready(nextPage, sharedHost.origin, root2)
  check('new root document starts v2 after old clients close', await nextPage.getAttribute('html', 'data-shell-revision'), 'v2')
  await cacheProof(prefixPage, prefix1, 'prefix survives root activation')
  sharedHost.state.versions = [root2, prefix2]
  check('prefix update waits', await update(prefixPage, prefix1.base), 'installed')
  await prefixPage.close()
  await waitFor(nextPage, async (name) => !(await caches.keys()).includes(name), prefix1.manifest.cacheName)
  await cacheProof(nextPage, root2, 'root survives prefix activation')
  const nextPrefix = await context.newPage()
  watch(nextPrefix, 'activated-prefix')
  await ready(nextPrefix, sharedHost.origin, prefix2)
  await cacheProof(nextPrefix, prefix2, 'prefix activated v2')
  check('unrelated cache and storage preserved', await nextPage.evaluate(async () => {
    const cacheValue = await (await (await caches.open('unrelated-app')).match('/unrelated-marker')).text()
    const idbValue = await new Promise((ok) => {
      const request = indexedDB.open('unrelated-db', 1)
      request.onsuccess = () => {
        const db = request.result
        const read = db.transaction('sentinel').objectStore('sentinel').get('marker')
        read.onsuccess = () => { db.close(); ok(read.result) }
      }
    })
    return [cacheValue, localStorage.getItem('unrelated-key'), idbValue]
  }), ['keep', 'keep', 'keep'])
  // Invalid new installations must preserve both active versions and leave
  // no partial new cache. Each fault has a unique candidate worker version.
  for (const kind of ['404', 'mime', 'bytes', 'redirect']) {
    const files = new Map(root2.manifest.entries.map((e) => [e.path.slice(1), root2.files.get(e.path.slice(1))]))
    files.set('index.html', Buffer.from(String(files.get('index.html')) + `<!-- invalid-install-${kind} -->`))
    const artifact = shellArtifacts('/', files)
    files.set('sw.js', Buffer.from(artifact.worker))
    const broken = { base: '/', manifest: artifact.manifest, files }
    sharedHost.state.versions = [broken, prefix2]
    sharedHost.state.fault = { kind, path: 'icons/icon-512.png' }
    check(`${kind}: worker install rejected`, await update(nextPage, '/'), 'redundant')
    check(`${kind}: no partial version cache`, await nextPage.evaluate((name) => caches.has(name), broken.manifest.cacheName), false)
    await cacheProof(nextPage, root2, `${kind}: active root preserved`)
    await cacheProof(nextPrefix, prefix2, `${kind}: sibling preserved`)
  }
  sharedHost.state.fault = null
  sharedHost.state.versions = [root2, prefix2]
  await context.setOffline(true)
  await nextPage.reload()
  check('old active shell still opens offline after failed updates', await nextPage.getAttribute('html', 'data-shell-revision'), 'v2')
  await context.close()

  // Registration failure/unsupported environments are online-app fallbacks.
  for (const mode of ['registration', 'unsupported']) {
    const ctx = await browser.newContext()
    if (mode === 'unsupported') await ctx.addInitScript(() => { Object.defineProperty(navigator, 'serviceWorker', { value: undefined }); delete Navigator.prototype.serviceWorker })
    rootHost.state.fault = mode === 'registration' ? { kind: mode } : null
    const page = await ctx.newPage()
    watch(page, mode)
    await page.goto(`${rootHost.origin}/#/settings`)
    await page.waitForFunction((mode) => document.body.textContent.includes(mode === 'registration' ? 'could not be prepared' : 'cannot prepare an offline shell'), mode)
    check(`${mode}: normal app remains usable`, await page.locator('.shell').count(), 1)
    check(`${mode}: no dead install button`, await page.getByRole('button', { name: 'install Remanence', exact: true }).count(), 0)
    await ctx.close()
  }
  rootHost.state.fault = null
  // Explicitly synthetic install-capability events exercise UI behavior only;
  // they do not claim native browser/OS installation.
  for (const [lang, label, unsupported] of [
    ['en', 'install Remanence', 'cannot prepare an offline shell'],
    ['ru', 'установить Remanence', 'не поддерживает автономную оболочку'],
    ['uk', 'встановити Remanence', 'не підтримує автономну оболонку'],
  ]) {
    const ctx = await browser.newContext({ viewport: { width: 320, height: 568 }, reducedMotion: 'reduce' })
    await ctx.addInitScript((language) => {
      localStorage.setItem('remanence.language', language)
      Object.defineProperty(navigator, 'serviceWorker', { value: undefined })
    }, lang)
    const page = await ctx.newPage()
    watch(page, `install-ui-${lang}`)
    await page.goto(`${rootHost.origin}/#/settings`)
    await page.waitForFunction((text) => document.body.textContent.includes(text), unsupported)
    await page.evaluate(() => {
      window.promptCalls = 0
      const event = new Event('beforeinstallprompt', { cancelable: true })
      event.prompt = async () => { window.promptCalls++ }
      event.userChoice = Promise.resolve({ outcome: 'dismissed' })
      window.dispatchEvent(event)
    })
    const button = page.getByRole('button', { name: label, exact: true })
    await button.focus()
    await page.keyboard.press('Enter')
    await page.waitForFunction(() => window.promptCalls === 1)
    check(`${lang}: one-shot capability consumed`, await button.count(), 0)
    check(`${lang}: keyboard focus returns to install explanation`, await page.evaluate(() => document.activeElement?.tagName), 'P')
    check(`${lang}: install text fits 320px`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    await page.screenshot({ path: join(outDir, `install-${lang}-320.png`), fullPage: true })
    await ctx.close()
  }
  check('no uncaught JavaScript exceptions', report.pageErrors, [])
  report.pass = true
} catch (error) {
  report.failure = String(error.stack || error)
  if (activePage && !activePage.isClosed()) {
    await activePage.screenshot({ path: join(outDir, 'failure.png'), fullPage: true }).catch(() => {})
    await writeFile(join(outDir, 'failure.html'), await activePage.content()).catch(() => {})
  }
  throw error
} finally {
  await writeFile(join(outDir, 'static-shell-report.json'), JSON.stringify(report, null, 2))
  await browser.close()
  for (const server of servers) await new Promise((ok) => server.close(ok))
}
console.log(`static-shell PASS: ${report.checks.length} browser assertions`)
