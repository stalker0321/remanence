import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { extname, join, normalize, sep } from 'node:path'

const web = process.argv[2]
const outDir = process.argv[3]
if (!web || !outDir) {
  console.error('usage: firefox-smoke.mjs <web-dir> <out-dir>')
  process.exit(2)
}

const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.otf': 'font/otf',
  '.txt': 'text/plain; charset=utf-8',
  '.png': 'image/png',
}

function serve(root, port) {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1')
    const relative = normalize(decodeURIComponent(url.pathname)).replace(/^[/\\]+/, '')
    if (relative.split(sep).includes('..')) {
      response.writeHead(403)
      response.end()
      return
    }
    const path = join(root, relative || 'index.html')
    if (!path.startsWith(`${root}${sep}`)) {
      response.writeHead(403)
      response.end()
      return
    }
    try {
      const body = await readFile(path)
      response.writeHead(200, {
        'content-type': types[extname(path)] ?? 'application/octet-stream',
        'cache-control': 'no-store',
        'referrer-policy': 'no-referrer',
      })
      response.end(body)
    } catch {
      response.writeHead(404, { 'cache-control': 'no-store' })
      response.end('missing')
    }
  })
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)))
}

async function waitFor(url) {
  const started = Date.now()
  while (Date.now() - started < 8000) {
    try {
      const response = await fetch(url)
      if (response.ok) return
    } catch { /* retry */ }
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  throw new Error(`server did not answer ${url}`)
}

const driver = spawn('/snap/bin/geckodriver', ['--host', '127.0.0.1', '--port', '4444'], {
  stdio: ['ignore', 'pipe', 'pipe'],
})
let driverLog = ''
driver.stdout.on('data', (chunk) => { driverLog += chunk })
driver.stderr.on('data', (chunk) => { driverLog += chunk })

const product = await serve(join(web, 'dist'), 4173)
const harness = await serve(join(web, 'harness-dist'), 4174)
await waitFor('http://127.0.0.1:4173/')
await waitFor('http://127.0.0.1:4174/')
await waitFor('http://127.0.0.1:4444/status')

async function wd(method, path, body) {
  const response = await fetch(`http://127.0.0.1:4444${path}`, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await response.text()
  const parsed = text ? JSON.parse(text) : {}
  if (!response.ok) throw new Error(`${method} ${path} ${response.status} ${text.slice(0, 800)}`)
  return parsed
}

const created = await wd('POST', '/session', {
  capabilities: {
    alwaysMatch: {
      browserName: 'firefox',
      'moz:firefoxOptions': {
        binary: '/snap/firefox/current/usr/lib/firefox/firefox',
        args: ['-headless'],
      },
    },
  },
})
const session = created.value?.sessionId ?? created.sessionId
if (!session) throw new Error(`no session ${JSON.stringify(created).slice(0, 400)}`)
const browser = created.value?.capabilities?.browserVersion ?? created.value?.browserVersion ?? 'unknown'

async function call(method, path, body) {
  return wd(method, `/session/${session}${path}`, body)
}

async function run(script) {
  const result = await call('POST', '/execute/sync', { script, args: [] })
  return result.value
}

async function resize(width, height) {
  await call('POST', '/window/rect', { x: 0, y: 0, width, height })
}

async function open(url) {
  await call('POST', '/url', { url })
}

async function until(script, label) {
  const started = Date.now()
  let last = null
  while (Date.now() - started < 15000) {
    last = await run(script)
    if (last) return last
    await new Promise((resolve) => setTimeout(resolve, 150))
  }
  throw new Error(`${label} timed out: ${JSON.stringify(last)}`)
}

async function shot(name) {
  const result = await call('GET', '/screenshot')
  const png = Buffer.from(result.value, 'base64')
  await writeFile(join(outDir, name), png)
  return png.length
}

await mkdir(outDir, { recursive: true })
const report = { browser, product: {}, harness: {}, shots: {} }

await resize(1280, 800)
await open('http://127.0.0.1:4173/')
await until('return document.documentElement.dataset.remanenceBoot === "ready"', 'product boot')
report.product.desktop = await run(`
  const resources = performance.getEntriesByType('resource').map((entry) => entry.name)
  return {
    lang: document.documentElement.lang,
    title: document.querySelector('h1')?.textContent ?? '',
    boot: window.__remanenceBoot,
    scripts: [...document.scripts].map((script) => script.src),
    resources,
    csp: document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.content ?? null,
  }
`)
report.shots.productDesktop = await shot('product-1280x800.png')
await run(`([...document.querySelectorAll('button')].find((button) => button.textContent.trim() === 'ru') || {}).click()`)
await until('return document.querySelector("h1")?.textContent === "вам пришла открытка?"', 'ru title')
report.shots.productRu = await shot('product-1280x800-ru.png')
await run(`([...document.querySelectorAll('button')].find((button) => button.textContent.trim() === 'uk') || {}).click()`)
await until('return document.querySelector("h1")?.textContent === "вам надійшла листівка?"', 'uk title')
report.shots.productUk = await shot('product-1280x800-uk.png')
await resize(390, 844)
report.shots.productMobile = await shot('product-390x844-uk.png')

await resize(1280, 900)
await open('http://127.0.0.1:4174/')
await until('return document.documentElement.dataset.harness === "ready"', 'harness ready')
const painted = await run(`
  const take = (id) => {
    const canvas = document.getElementById(id)
    return {
      result: canvas.dataset.result ?? '',
      painted: canvas.dataset.painted ?? '',
      artwork: canvas.dataset.artwork ?? '',
      png: canvas.toDataURL('image/png'),
    }
  }
  return {
    hpke: document.documentElement.dataset.hpke,
    journey: document.documentElement.dataset.journey,
    revoked: document.documentElement.dataset.revoked,
    identity: document.documentElement.dataset.identity,
    identityWiped: document.documentElement.dataset.identityWiped,
    locale: document.getElementById('locale')?.textContent,
    received: {
      note: document.getElementById('received')?.dataset.note ?? '',
      painted: document.getElementById('received')?.dataset.painted ?? '',
      style: document.getElementById('received')?.dataset.style ?? '',
    },
    plain: take('plain'),
    cover: take('cover'),
    overflow: take('overflow'),
    boot: window.__remanenceBoot ?? null,
    resources: performance.getEntriesByType('resource').map((entry) => entry.name),
    scripts: [...document.scripts].map((script) => script.src),
  }
`)
for (const id of ['plain', 'cover', 'overflow']) {
  const png = painted[id].png
  const bytes = Buffer.from(png.slice(png.indexOf(',') + 1), 'base64')
  await writeFile(join(outDir, `canvas-${id}.png`), bytes)
  painted[id].bytes = bytes.length
  delete painted[id].png
}
report.harness.desktop = painted
report.shots.harnessDesktop = await shot('harness-1280x900.png')
await run(`document.getElementById('ru').click()`)
await until('return document.documentElement.dataset.locale === "ru"', 'harness ru')
report.shots.harnessRu = await shot('harness-1280x900-ru.png')
await run(`document.getElementById('uk').click()`)
await until('return document.documentElement.dataset.locale === "uk"', 'harness uk')
report.shots.harnessUk = await shot('harness-1280x900-uk.png')
await resize(390, 844)
report.shots.harnessMobile = await shot('harness-390x844.png')
await resize(390, 2400)
report.shots.harnessMobileTall = await shot('harness-390x2400.png')

await writeFile(join(outDir, 'report.json'), JSON.stringify(report, null, 2))
await call('DELETE', '')
driver.kill()
product.close()
harness.close()
const summary = { browser, shots: report.shots, hpke: painted.hpke, journey: painted.journey, revoked: painted.revoked, identity: painted.identity, identityWiped: painted.identityWiped, received: painted.received, plain: painted.plain, cover: { result: painted.cover.result, painted: painted.cover.painted, artwork: painted.cover.artwork, bytes: painted.cover.bytes }, overflow: painted.overflow, productErrors: report.product.desktop.boot, harnessBoot: painted.boot, productResources: report.product.desktop.resources, harnessResources: painted.resources }
if (painted.hpke !== 'open' || painted.journey !== 'presentation-admitted' || painted.revoked !== 'yes' || painted.identity !== 'matched' || painted.identityWiped !== 'yes' || painted.received.note !== 'hello' || painted.received.style !== 'press' || painted.received.painted !== 'yes') {
  console.log(JSON.stringify(summary, null, 2))
  throw new Error('harness assertions failed')
}
if (report.product.desktop.boot.errors.length || report.product.desktop.boot.resources.length || painted.boot?.errors?.length || painted.boot?.resources?.length) {
  console.log(JSON.stringify(summary, null, 2))
  throw new Error('boot errors')
}
console.log(JSON.stringify(summary, null, 2))
