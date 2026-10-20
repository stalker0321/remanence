// T398: encrypted stdin credentials, loopback-only unchanged FastAPI proxy.
import assert from 'node:assert/strict'
import { request } from 'node:http'
import { createInterface } from 'node:readline'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const commands = createInterface({ input: process.stdin })[Symbol.asyncIterator]()
async function command(phase) {
  const line = await commands.next()
  assert.equal(line.done, false, 'controller connected')
  const value = JSON.parse(line.value); assert.equal(value.phase, phase); return value
}
const initial = await command('start')
assert.ok(Number.isInteger(initial.apiPort) && initial.apiPort > 1024)
const requests = [], errors = [], checks = [], phases = []
const allowed = (method, path) =>
  (method === 'POST' && (path === '/v1/capsules' || /^\/v1\/capsules\/[0-9a-f-]{36}\/(finalize|revoke|first-open)$/.test(path)))
  || (method === 'GET' && (/^\/v1\/capsules\/incoming$/.test(path) || /^\/v1\/directory\/key-bundles\/[0-9a-f-]{36}$/.test(path)))
  || (['GET', 'PUT'].includes(method) && /^\/v1\/capsules\/[0-9a-f-]{36}\/blobs\/[0-9a-f-]{36}$/.test(path))
const host = await fixtureHost(web, proof, 'realAdmission.page.tsx', { async handle(req, res) {
  if (!req.url.startsWith('/v1/')) return false
  assert.ok(allowed(req.method, new URL(req.url, 'http://fixture.invalid').pathname), 'unexpected API route')
  await new Promise((resolve, reject) => {
    const upstream = request({ hostname: '127.0.0.1', port: initial.apiPort, method: req.method, path: req.url,
      headers: { ...req.headers, host: `127.0.0.1:${initial.apiPort}` } }, (reply) => {
      const chunks = []
      reply.on('data', (chunk) => chunks.push(chunk))
      reply.on('end', () => {
        const bytes = Buffer.concat(chunks)
        // Never persist tokens, private payloads, images or key material.
        const body = req.url.endsWith('/first-open') ? JSON.parse(bytes.toString('utf8')) : null
        requests.push({ method: req.method, path: req.url, status: reply.statusCode,
          authenticated: Boolean(req.headers.authorization), cache: reply.headers['cache-control'],
          firstOpen: body && reply.statusCode === 200 ? body : undefined })
        res.writeHead(reply.statusCode, reply.headers).end(bytes); resolve()
      })
      reply.on('error', reject)
    })
    upstream.on('error', reject); req.pipe(upstream)
  })
  return true
} })
for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.once(signal, async () => { await host.close(); process.exit(1) })
const page = await host.browser.newPage({ viewport: { width: 390, height: 850 } })
page.setDefaultTimeout(120000)
page.on('pageerror', (error) => errors.push(String(error)))
await page.route('**/*', async (route) => {
  if (new URL(route.request().url()).origin === new URL(host.url).origin) await route.continue()
  else { errors.push('external request blocked'); await route.abort() }
})
const acknowledge = (phase, data = {}) => process.stdout.write(JSON.stringify({ phase, ok: true, ...data }) + '\n')
try {
  await page.addInitScript((credentials) => { window.t398Credentials = credentials }, initial.credentials)
  delete initial.credentials
  await page.goto(host.url)
  await page.waitForFunction(() => !!window.t398)
  acknowledge('start', { browserPort: Number(new URL(host.url).port), pid: process.pid })
  for (const phase of ['revoked', 'back', 'replay', 'unknown', 'finish']) {
    await command(phase)
    const result = await page.evaluate((phase) => window.t398.run(phase), phase)
    phases.push({ phase, ...result })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `${phase} overflow`)
    if (phase !== 'finish') await page.screenshot({ path: join(proof, `${phase}.png`), fullPage: true })
    if (phase === 'finish') {
      const admitted = requests.filter((r) => r.firstOpen)
      assert.ok(admitted.length >= 4, 'commit and explicit replay responses reached actual API')
      assert.ok(admitted.every((r) => r.firstOpen.capsule_id === admitted[0].firstOpen.capsule_id
        && r.firstOpen.first_opened_at === admitted[0].firstOpen.first_opened_at), 'replays retain original first-open timestamp')
      assert.equal(admitted[0].firstOpen.is_replay, false)
      assert.ok(admitted.slice(1).every((r) => r.firstOpen.is_replay === true))
      assert.ok(requests.filter((r) => r.method === 'PUT').length === 10, 'both five-blob ciphertext publications used HTTP upload')
      assert.ok(requests.filter((r) => r.method === 'GET').every((r) => r.cache === 'private, no-store'), 'real private read cache headers')
      assert.deepEqual(errors, [])
    }
    acknowledge(phase, result)
  }
} finally {
  checks.push(...await page.evaluate(() => window.t398?.checks ?? []).catch(() => []))
  await writeFile(join(proof, 'report.json'), JSON.stringify({ syntheticAccountCustodyAndCamera: true,
    actualPublicationCryptoDefaultRecognitionApiAndPg: true, authOverrides: false, fabricatedApiResponses: false,
    executableSha256: host.executableSha256, phases, checks, errors, requests }, null, 2))
  await host.close()
}
