// Actual built graphs; no auth/provider port. TEST public config is synthetic.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { join, extname } from 'node:path'
const [web, proof] = process.argv.slice(2)
await mkdir(proof, { recursive: true })
const base = '/test-entry/', checks = [], images = [], errors = [], requests = []
const check = (name, ok) => checks.push({ name, ok: Boolean(ok) })
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.otf': 'font/otf', '.svg': 'image/svg+xml', '.png': 'image/png', '.wasm': 'application/wasm', '.webmanifest': 'application/manifest+json' }
function host(combined) {
  const server = createServer(async (req, res) => {
    const url = req.url, origin = `http://127.0.0.1:${server.address().port}`
    requests.push({ combined, url, method: req.method })
    res.setHeader('cache-control', 'no-store'); res.setHeader('x-content-type-options', 'nosniff')
    if (req.method !== 'GET' || /[?%\\]/.test(url) || url.includes('..')) { res.writeHead(404).end(); return }
    if (url === `${base}atomic-test-config.json`) {
      res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ environment: 'TEST', appOrigin: origin, apiOrigin: origin, rpId: '127.0.0.1' })); return
    }
    const isTest = url.startsWith(base)
    if (!combined && !isTest) { res.writeHead(404).end(); return }
    const relative = (isTest ? url.slice(base.length) : url.slice(1)) || 'index.html'
    try {
      const bytes = await readFile(join(web, isTest ? 'atomic-test-prefix' : 'dist', relative))
      res.writeHead(200, { 'content-type': mime[extname(relative)] || 'application/octet-stream' }).end(bytes)
    } catch { res.writeHead(404).end() }
  })
  return server
}
const clean = host(false), combined = host(true)
for (const server of [clean, combined]) await new Promise(ok => server.listen(0, '127.0.0.1', ok))
const origin = server => `http://127.0.0.1:${server.address().port}`
const executable = '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const executableSha256 = createHash('sha256').update(await readFile(executable)).digest('hex')
assert.equal(executableSha256, '4fa61f0ba0b4f89b7bec242c32ca16d3bccf69dbfef0651924a9ba8fede5f9d6')
const { chromium } = createRequire('/srv/remanence/tools/browser-smoke/package.json')('playwright-core')
const browser = await chromium.launch({ executablePath: executable, headless: true })
const context = await browser.newContext({ viewport: { width: 320, height: 900 } })
context.on('page', page => page.on('pageerror', error => errors.push(String(error))))
await context.route('**/*', route => [origin(clean), origin(combined)].includes(new URL(route.request().url()).origin) ? route.continue() : route.abort())
const shot = async (page, name) => { await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true }); images.push(`${name}.png`) }
let complete = false
try {
  const page = await context.newPage()
  await page.goto(`${origin(clean)}${base}#/settings`)
  await page.getByRole('heading', { name: 'settings', exact: true }).waitFor()
  const preparing = 'Preparing the app shell for another visit.'
  check('online-only TEST never promises a worker installation in progress', !(await page.textContent('body')).includes(preparing))
  check('TEST reports explicitly disabled offline shell', (await page.textContent('body')).includes('This TEST entry is online-only.'))
  check('prefix loads styles and actually decodes a shipped public font', await page.evaluate(async base => {
    const font = new FontFace('T424-synthetic-font-probe', `url(${base}fonts/nimbus_roman_regular.otf)`)
    await font.load(); return document.styleSheets.length > 0 && font.status === 'loaded'
  }, base) && requests.some(x => x.url === `${base}fonts/nimbus_roman_regular.otf`))
  check('standalone TEST has no workers or caches', await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length === 0 && (await caches.keys()).length === 0))
  for (const [lang, name, offline] of [['en', 'English', 'This TEST entry needs a connection and is not saved for offline opening.'], ['ru', 'Русский', 'Для этого TEST-входа нужна сеть; он не сохранён для открытия без сети.'], ['uk', 'Українська', 'Для цього TEST-входу потрібна мережа; його не збережено для відкриття без мережі.']]) {
    await page.locator('.bar .language-trigger').click(); await page.getByRole('option', { name, exact: true }).click()
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
    await context.setOffline(true); await page.waitForFunction(() => !navigator.onLine)
    // A task turn after the offline event is enough; no worker/network operation awaited.
    await page.evaluate(() => new Promise(ok => requestAnimationFrame(() => requestAnimationFrame(ok))))
    check(`${lang}: online-only TEST tracks offline without suggesting cached success`, (await page.textContent('body')).includes(offline))
    check(`${lang}: narrow large text has no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await shot(page, `${lang}-test-offline`)
    await context.setOffline(false); await page.waitForFunction(() => navigator.onLine)
    await page.evaluate(() => new Promise(ok => requestAnimationFrame(() => requestAnimationFrame(ok))))
    check(`${lang}: online recovery clears offline notice`, !(await page.textContent('body')).includes(offline))
  }
  check('no API/provider dispatch from TEST settings/configuration', !requests.some(x => /^\/v[12]\//.test(x.url)))
  await page.close()
  const pwa = await context.newPage(); await pwa.goto(`${origin(combined)}/`)
  const deadline = Date.now() + 15000
  while (!(await pwa.evaluate(async () => (await navigator.serviceWorker.getRegistration('/'))?.active?.state === 'activated'))) {
    assert.ok(Date.now() < deadline); await new Promise(ok => setTimeout(ok, 50))
  }
  await pwa.reload(); await pwa.waitForFunction(() => Boolean(navigator.serviceWorker.controller))
  const oldCaches = await pwa.evaluate(() => caches.keys())
  const configCount = requests.filter(x => x.combined && x.url.endsWith('atomic-test-config.json')).length
  await pwa.goto(`${origin(combined)}${base}#/account`)
  await pwa.waitForFunction(() => document.querySelector('[data-test-configuration-error], [data-atomic-phase]'))
  check('existing controlling worker refuses TEST before App/config/API', await pwa.locator('[data-test-configuration-error]').count() === 1 && await pwa.locator('[data-atomic-phase]').count() === 0 && requests.filter(x => x.combined && x.url.endsWith('atomic-test-config.json')).length === configCount)
  check('refusal leaves unrelated worker and caches untouched', await pwa.evaluate(async names => Boolean(navigator.serviceWorker.controller) && JSON.stringify(await caches.keys()) === JSON.stringify(names), oldCaches))
  const cacheUrls = await pwa.evaluate(async () => (await Promise.all((await caches.keys()).map(async name => (await (await caches.open(name)).keys()).map(x => new URL(x.url).pathname)))).flat())
  check('ordinary PWA allowlist never caches TEST config/API', cacheUrls.every(path => !path.startsWith(base) && !/^\/v[12]\//.test(path)))
  await shot(pwa, 'worker-scope-refused')
  check('no uncaught exceptions', errors.length === 0)
  complete = true
} finally {
  await context.close(); await browser.close()
  for (const server of [clean, combined]) await new Promise(ok => server.close(ok))
  await writeFile(join(proof, 'report.json'), JSON.stringify({ complete, checks, errors, requests, images, executableSha256, syntheticPublicConfig: true, realProviders: false, serversClosed: true }, null, 2))
}
assert.ok(checks.every(x => x.ok), JSON.stringify(checks.filter(x => !x.ok)))
console.log(`atomicPackaging PASS ${checks.length} checks`)
