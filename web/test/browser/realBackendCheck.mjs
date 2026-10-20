// Synthetic-account integration, NOT response fixtures: all API bytes are proxied
// unchanged through an SSH loopback forward to composed FastAPI + real PG.
import assert from 'node:assert/strict'
import { request } from 'node:http'
import { createInterface } from 'node:readline'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const lines = createInterface({ input: process.stdin })[Symbol.asyncIterator]()
async function command(phase) {
  const next = await lines.next()
  assert.equal(next.done, false, 'test controller must remain connected')
  const value = JSON.parse(next.value)
  assert.equal(value.phase, phase)
  return value
}
const initial = await command('fresh') // Credentials arrive via encrypted stdin, never argv/files/logs.
assert.ok(Number.isInteger(initial.apiPort) && initial.apiPort > 1024)
const checks = [], errors = [], responses = []
const check = (name, condition) => { assert.ok(condition, name); checks.push({ name, ok: true }) }
const acknowledge = (phase) => process.stdout.write(JSON.stringify({ phase, ok: true,
  ...(phase === 'fresh' ? { browserPort: Number(new URL(host.url).port), pid: process.pid } : {}) }) + '\n')
const host = await fixtureHost(web, proof, 'realBackend.page.tsx', { async handle(req, res) {
  if (!/^\/v[12]\//.test(req.url)) return false
  const allowed = (req.method === 'GET' && ['/v1/me/send-access', '/v1/capsules/outgoing', '/v1/recipients/recent'].includes(req.url))
    || (req.method === 'POST' && /^\/v[12]\/(capsules|pending-deliveries)\/[0-9a-f-]{36}\/(revoke|cancel)$/.test(req.url))
  assert.ok(allowed, 'unexpected API path in bounded integration')
  await new Promise((resolve, reject) => {
    const upstream = request({ hostname: '127.0.0.1', port: initial.apiPort, method: req.method, path: req.url,
      headers: { ...req.headers, host: `127.0.0.1:${initial.apiPort}` } }, (reply) => {
      const chunks = []
      reply.on('data', (chunk) => chunks.push(chunk))
      reply.on('end', () => {
        const bytes = Buffer.concat(chunks)
        // Keep real DTOs only in test memory for assertions, not raw private payload logs.
        responses.push({ path: req.url, method: req.method, status: reply.statusCode,
          authenticated: Boolean(req.headers.authorization), cache: reply.headers['cache-control'],
          etag: reply.headers.etag ?? null, body: JSON.parse(bytes.toString('utf8')) })
        res.writeHead(reply.statusCode, reply.headers).end(bytes)
        resolve()
      })
      reply.on('error', reject)
    })
    upstream.on('error', reject)
    req.pipe(upstream)
  })
  return true
} })
const page = await host.browser.newPage({ viewport: { width: 390, height: 850 } })
for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.once(signal, async () => {
  await host.close()
  process.exit(1)
})
page.setDefaultTimeout(12000)
page.on('pageerror', (error) => errors.push(String(error)))
await page.route('**/*', async (route) => {
  if (new URL(route.request().url()).origin === new URL(host.url).origin) await route.continue()
  else { errors.push('external request blocked'); await route.abort() }
})
await page.addInitScript((session) => { window.t384Session = session }, initial.session)
delete initial.session
const accessPath = '/v1/me/send-access', historyPath = '/v1/capsules/outgoing', recentPath = '/v1/recipients/recent'
const isPath = (path) => (reply) => new URL(reply.url()).pathname === path
const last = (path) => responses.filter((reply) => reply.path === path).at(-1)
async function capture(name) {
  check(`${name}: no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true })
  await writeFile(join(proof, `${name}.html`), await page.content())
}
async function button(key, root = page) {
  return root.getByRole('button', { name: await page.evaluate((name) => window.t384.messages[name], key), exact: true })
}
async function access({ phase, self, other, display = 'counts', mode = 'enforce', lifetime = false }, first = false) {
  const incoming = page.waitForResponse(isPath(accessPath))
  if (first) await page.goto(`${host.url}#/settings`)
  else if (new URL(page.url()).hash === '#/settings') await page.locator('[data-testid=access-refresh]').click()
  else await page.evaluate(() => { location.hash = '#/settings' })
  const response = await incoming
  check(`${phase}: response status`, response.status() === (display === 'off' ? 404 : 200))
  check(`${phase}: real private cache header`, response.headers()['cache-control'] === 'private, no-store')
  if (display === 'off') {
    await page.waitForFunction(() => document.querySelector('[data-testid=send-access]') === null)
    check('OFF hides whole access block', await page.locator('[data-testid=access-self], [data-testid=access-lifetime], [data-testid=access-record]').count() === 0)
  } else {
    const data = await response.json()
    assert.deepEqual(data, { policy_version: 1, mode, lifetime,
      free: { self: { limit: 2, used: 2 - self, remaining: self }, other: { limit: 2, used: 2 - other, remaining: other } },
      can_publish: { self: mode === 'record' || lifetime || self > 0, other: mode === 'record' || lifetime || other > 0 } })
    check(`${phase}: exact real DTO`, true)
    if (display === 'counts') await page.waitForFunction(([own, other]) =>
      document.querySelector('[data-testid=access-self]')?.textContent?.startsWith(`${own} / 2`)
      && document.querySelector('[data-testid=access-other]')?.textContent?.startsWith(`${other} / 2`), [self, other])
    else {
      await page.locator(`[data-testid=access-${display}]`).waitFor()
      check(`${phase}: no false exhausted counters`, await page.locator('[data-testid=send-access] dd').count() === 0)
    }
  }
  await capture(phase)
}
async function history() {
  const incoming = page.waitForResponse(isPath(historyPath))
  await page.evaluate(() => { location.hash = '#/history' })
  const reply = await incoming
  check('history real response200/private', reply.status() === 200 && reply.headers()['cache-control'] === 'private, no-store')
  await page.locator('[data-history-key]').nth(2).waitFor()
  return (await reply.json()).items
}
async function cancel(key, endpoint, expected) {
  const row = page.locator(`[data-history-key="${key}"]`)
  await row.locator('summary').click()
  const before = responses.filter((r) => r.method === 'POST').length
  await (await button('historyCancel', row)).click()
  check(`${expected}: explicit confirmation no early POST`, responses.filter((r) => r.method === 'POST').length === before)
  const mutation = page.waitForResponse(isPath(endpoint)), refreshed = page.waitForResponse(isPath(historyPath))
  await (await button('historyConfirmCancel', row)).click()
  check(`${expected}: real mutation200`, (await mutation).status() === 200)
  const body = await (await refreshed).json()
  const item = body.items.find((item) => `${item.protocol_version}:${item.capsule_id}` === key)
  check(`${expected}: authoritative refetch status`, item.status === expected && item.cancel_action === null && item.locator.code === null)
  check(`${expected}: exactly one POST`, responses.filter((r) => r.method === 'POST').length === before + 1)
  await page.locator('.history-list[aria-busy=false]').waitFor()
  await capture(expected.toLowerCase())
}
try {
  await access({ phase: 'fresh', self: 2, other: 2 }, true)
  for (const path of [accessPath, historyPath, recentPath]) {
    const denied = await page.evaluate(async (path) => {
      const response = await fetch(path, { cache: 'no-store' })
      return { status: response.status, cache: response.headers.get('cache-control'), code: (await response.json()).code }
    }, path)
    check(`real bearer required ${path}`, denied.status === 401 && denied.code === 'AUTH_INVALID' && denied.cache === 'private, no-store')
  }
  acknowledge('fresh')
  await access(await command('self')); acknowledge('self')
  await access(await command('other')); acknowledge('other')
  const pending = await command('pending')
  await access(pending); acknowledge('pending')
  await command('cancel')
  const rows = await history()
  check('real history exactly three publications', rows.length === 3)
  const v2 = rows.find((row) => row.protocol_version === 2)
  check('pending immutable history null semantics', v2.status === 'PENDING' && v2.published_at === null
    && v2.recipient.user_id === null && v2.recipient.handle === null && v2.recipient.is_self === null)
  check('both cancellation arms are eligible', v2.cancel_action === 'CANCEL_PENDING_V2'
    && rows.find((row) => row.capsule_id === pending.otherId).cancel_action === 'REVOKE_V1')
  await capture('history-before')
  await cancel(`1:${pending.otherId}`, `/v1/capsules/${pending.otherId}/revoke`, 'REVOKED')
  await cancel(`2:${pending.pendingCapsuleId}`, `/v2/pending-deliveries/${pending.pendingId}/cancel`, 'CANCELLED')
  await access({ phase: 'no-refund', self: 1, other: 0 })
  await page.evaluate(() => { location.hash = '#/create' })
  await page.locator('[data-testid=recent-recipients]').waitFor()
  const recentResponse = page.waitForResponse(isPath(recentPath))
  await (await button('recentTitle')).click()
  const recent = await (await recentResponse).json()
  check('recents derive only known non-self v1, revoked retained', recent.items.length === 1 && recent.items[0].handle === 't384_recipient')
  await page.locator('[data-testid=recent-recipients] li').waitFor()
  check('actual App renders real recent suggestion', await page.locator('[data-testid=recent-recipients] li').innerText() === '@t384_recipient')
  await capture('recent-after-cancel')
  acknowledge('cancel')
  await access(await command('record')); acknowledge('record')
  await access(await command('lifetime')); acknowledge('lifetime')
  await access(await command('off'))
  check('no durable private storage', await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await caches.keys()).length === 0))
  check('App private requests use no-store', await page.evaluate(() => window.t384.audit.every((item) => item.cache === 'no-store')))
  check('read/auth replies private, without ETag', responses.filter((r) => r.method === 'GET').every((r) => r.cache === 'private, no-store' && r.etag === null))
  check('no intercepted fixture API success', responses.filter((r) => r.status === 200).length >= 12)
  assert.deepEqual(errors, [])
  acknowledge('off')
} finally {
  const safeResponses = responses.map(({ body, ...metadata }) => ({ ...metadata,
    access: metadata.path === accessPath && metadata.status === 200 ? body : undefined,
    history: metadata.path === historyPath && metadata.status === 200 ? body.items.map((row) => ({ protocol: row.protocol_version, status: row.status,
      published_at_null: row.published_at === null, cancel_action: row.cancel_action })) : undefined,
    recent_count: metadata.path === recentPath && metadata.status === 200 ? body.items.length : undefined,
  }))
  await writeFile(join(proof, 'report.json'), JSON.stringify({ syntheticAccounts: true, realBackend: true,
    apiResponsesIntercepted: false, executableSha256: host.executableSha256, checks, errors, requests: safeResponses }, null, 2))
  await host.close()
}
