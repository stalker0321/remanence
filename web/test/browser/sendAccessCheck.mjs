import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'
const [web, proof] = process.argv.slice(2)
function dto(mode = 'enforce', self = 2, other = 2, lifetime = false) {
  return { policy_version: 1, mode, lifetime,
    free: { self: { limit: 2, used: 2 - self, remaining: self }, other: { limit: 2, used: 2 - other, remaining: other } },
    can_publish: { self: mode === 'record' || lifetime || self > 0, other: mode === 'record' || lifetime || other > 0 } }
}
let mode = 'enforce', value = dto(), held = [], requests = []
const json = (res, body, status = 200) => res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'private, no-store' }).end(JSON.stringify(body))
const host = await fixtureHost(web, proof, 'sendAccess.page.tsx', { async handle(req, res) {
  if (!req.url.startsWith('/v1/')) return false
  requests.push({ method: req.method, path: req.url, token: req.headers.authorization })
  assert.equal(req.method, 'GET')
  assert.equal(req.url, '/v1/me/send-access')
  assert.equal(req.headers['x-remanence-profile-version'], '2')
  if (req.headers.authorization === 'Bearer synthetic-other') { json(res, dto('enforce', 1, 0)); return true }
  assert.equal(req.headers.authorization, 'Bearer synthetic-access')
  if (mode === 'hold' || mode === 'hold-error') {
    const snapshot = structuredClone(value), status = mode === 'hold-error' ? 503 : 200
    held.push(() => json(res, snapshot, status))
  } else if (mode === 'off') res.writeHead(404, { 'content-type': 'text/html', 'cache-control': 'private, no-store' }).end('<h1>PRIVATE_NOT_SUPPORTED</h1>')
  else if (mode === '503') json(res, { code: 'PRIVATE_UNAVAILABLE' }, 503)
  else if (mode === 'network') res.destroy()
  else json(res, value)
  return true
} })
const checks = [], errors = [], facts = []
function check(name, value) { assert.ok(value, name); checks.push({ name, ok: true }) }
const section = (page) => page.locator('[data-testid=send-access]')
const refresh = (page) => page.locator('[data-testid=access-refresh]')
async function settled(page) { await page.waitForFunction(() => window.sendAccessFixture.audit.length > 0 && window.sendAccessFixture.audit.every((item) => item.settled)) }
async function heldRequest() {
  const until = Date.now() + 15000
  while (!held.length && Date.now() < until) await new Promise((resolve) => setTimeout(resolve, 25))
  assert.ok(held.length, 'loopback server actually reached the delayed request')
}
async function counts(page, self, other) {
  await page.waitForFunction(([self, other]) => document.querySelector('[data-testid=access-self]')?.textContent?.startsWith(`${self} / 2`)
    && document.querySelector('[data-testid=access-other]')?.textContent?.startsWith(`${other} / 2`), [self, other])
}
async function capture(page, name) {
  await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true })
  await writeFile(join(proof, `${name}.html`), await page.content())
}
async function scenario(name, initial, run) {
  mode = initial; value = dto(); held = []; requests = []
  const page = await host.browser.newPage({ viewport: { width: 320, height: 740 } })
  page.on('pageerror', (error) => errors.push({ name, error: String(error) }))
  await page.route('**/*', async (route) => {
    if (new URL(route.request().url()).origin === new URL(host.url).origin) await route.continue()
    else { errors.push({ name, error: 'external request' }); await route.abort() }
  })
  try {
    await page.goto(`${host.url}#/settings`)
    await page.waitForFunction(() => Boolean(window.sendAccessFixture))
    if (initial.startsWith('hold')) await heldRequest()
    await run(page)
    check(`${name}: no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    check(`${name}: never mutates`, requests.every((r) => r.method === 'GET' && r.path === '/v1/me/send-access'))
    check(`${name}: private fetch no-store`, await page.evaluate(() => window.sendAccessFixture.audit.every((r) => r.cache === 'no-store')))
    check(`${name}: no durable metadata`, await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await caches.keys()).length === 0))
    check(`${name}: no error/token leak`, !(await page.locator('body').innerText()).match(/PRIVATE|synthetic-access|synthetic-other/))
  } catch (error) { checks.push({ name, ok: false, error: String(error) }) }
  await capture(page, name)
  facts.push({ name, requests: structuredClone(requests), audit: await page.evaluate(() => window.sendAccessFixture.audit), text: await page.locator('main').innerText() })
  held.splice(0).forEach((release) => release())
  await page.close()
}
try {
  await scenario('off404-hidden', 'off', async (page) => {
    await settled(page)
    await page.waitForFunction(() => document.querySelector('[data-testid=send-access]') === null)
    check('OFF has neither section nor numbers', await section(page).count() === 0)
    await page.locator('a[href="#/"]').last().click()
    await page.locator('a[href="#/create"]').waitFor()
    check('OFF retains ordinary beta navigation', await page.locator('a[href="#/create"]').count() === 1)
  })
  await scenario('all-counts-record-lifetime-locales', 'enforce', async (page) => {
    await counts(page, 2, 2)
    for (const self of [0, 1, 2]) for (const other of [0, 1, 2]) {
      value = dto('enforce', self, other)
      await refresh(page).click(); await counts(page, self, other)
      check(`independent counts ${self}/${other}`, await section(page).locator('dd').count() === 2)
    }
    for (const display of ['enforce', 'record', 'lifetime']) {
      value = display === 'enforce' ? dto('enforce', 0, 2) : display === 'record' ? dto('record', 0, 1) : dto('enforce', 0, 0, true)
      await refresh(page).click()
      if (display === 'enforce') await counts(page, 0, 2)
      else await page.locator(`[data-testid=access-${display}]`).waitFor()
      if (display !== 'enforce') check(`${display}: no false exhausted counter`, await section(page).locator('dd').count() === 0)
      for (const language of ['en', 'ru', 'uk']) for (const width of [320, 1280]) {
        await page.setViewportSize({ width, height: 900 })
        await page.evaluate((lang) => window.sendFeedbackFixture.language(lang), language)
        await capture(page, `${display}-${language}-${width}`)
        check(`${display}/${language}/${width} layout`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      }
    }
    await refresh(page).focus()
    await page.keyboard.press('Enter')
    await page.locator('[data-testid=access-lifetime]').waitFor()
    check('keyboard refresh does not steal focus', await refresh(page).evaluate((el) => document.activeElement === el))
    check('no purchase/grant controls', await section(page).locator('button, a').count() === 1)
  })
  await scenario('errors-retry-clears-old-counts', 'enforce', async (page) => {
    await counts(page, 2, 2)
    const failures = [
      ['503', dto()], ['network', dto()], ['bad-policy', { ...dto(), policy_version: 2 }],
      ['bad-bucket', { ...dto(), free: { self: { limit: 2, used: 0, remaining: 0 }, other: dto().free.other } }],
      ['bad-record', { ...dto('record', 0, 0), can_publish: { self: false, other: true } }],
      ['bad-lifetime', { ...dto('enforce', 0, 0, true), can_publish: { self: false, other: false } }],
    ]
    for (const [failure, payload] of failures) {
      mode = failure; value = payload; await refresh(page).click()
      await section(page).getByRole('alert').waitFor()
      check(`${failure}: unavailable has no inferred numbers`, await section(page).locator('dd').count() === 0)
      check(`${failure}: unavailable no exhausted/lifetime claim`, await page.locator('[data-testid=access-record], [data-testid=access-lifetime]').count() === 0)
      mode = 'enforce'; value = dto('enforce', 1, 2)
      await refresh(page).focus(); await page.keyboard.press('Enter'); await counts(page, 1, 2)
    }
    mode = 'hold'; await refresh(page).click()
    await section(page).getByText('Checking sending access…', { exact: true }).waitFor()
    check('refresh removes old numbers before new response', await section(page).locator('dd').count() === 0)
    await heldRequest()
    const reads = requests.length
    await refresh(page).focus(); await page.keyboard.press('Enter')
    await refresh(page).evaluate((button) => button.click())
    check('busy keyboard/callback cannot duplicate read', requests.length === reads && await page.evaluate(() => window.sendAccessFixture.audit.filter((r) => !r.settled).length === 1))
    held.splice(0).forEach((release) => release()); await counts(page, 1, 2)
  })
  for (const failure of [false, true]) await scenario(`late-owner-${failure ? 'error' : 'success'}`, failure ? 'hold-error' : 'hold', async (page) => {
    await section(page).getByRole('status').waitFor()
    await page.waitForFunction(() => window.sendAccessFixture.audit.length === 1)
    await page.evaluate(() => window.sendAccessFixture.switchOwner())
    await counts(page, 1, 0)
    held.splice(0).forEach((release) => release()); await settled(page)
    check('replacement response survives late old completion', (await page.locator('[data-testid=access-self]').innerText()).startsWith('1 / 2'))
    check('old owner request is aborted', await page.evaluate(() => window.sendAccessFixture.audit[0].aborted))
    check('replacement has its own bearer', requests.some((r) => r.token === 'Bearer synthetic-other'))
  })
  await scenario('logout-late-and-signedout-hidden', 'hold', async (page) => {
    await section(page).getByRole('status').waitFor()
    await page.evaluate(() => window.sendAccessFixture.store.signOut())
    held.splice(0).forEach((release) => release()); await settled(page)
    check('logout clears block without late revival', await section(page).count() === 0)
    check('logout adds no read', requests.length === 1)
  })
  await scenario('generation-lock-fresh-read', 'hold', async (page) => {
    await section(page).getByRole('status').waitFor()
    mode = 'enforce'; value = dto('enforce', 0, 2)
    await page.evaluate(() => window.sendAccessFixture.store.lock())
    await counts(page, 0, 2)
    held.splice(0).forEach((release) => release()); await settled(page)
    check('lease generation clears old response', await page.evaluate(() => window.sendAccessFixture.audit[0].aborted))
    check('fresh locked session can read owner metadata', await page.evaluate(() => window.sendAccessFixture.store.getSnapshot().phase === 'authenticated'))
  })
  await scenario('unmount-back-fresh-owner-read', 'hold', async (page) => {
    await section(page).getByRole('status').waitFor()
    await page.locator('a[href="#/"]').last().click()
    await page.locator('a[href="#/settings"]').waitFor()
    held.splice(0).forEach((release) => release()); await settled(page)
    check('leaving retires request and section', await section(page).count() === 0 && await page.evaluate(() => window.sendAccessFixture.audit[0].aborted))
    mode = 'enforce'; value = dto('enforce', 1, 1)
    await page.goBack(); await counts(page, 1, 1)
    check('Back gets fresh metadata, not cached allowance', requests.length === 2)
  })
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ clientOnly: true, synthetic: true, executableSha256: host.executableSha256, checks, errors, facts }, null, 2))
  await host.close()
}
assert.deepEqual(errors, [])
assert.ok(checks.every((check) => check.ok), JSON.stringify(checks.filter((c) => !c.ok)))
