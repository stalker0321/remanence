import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'
const [web, proof] = process.argv.slice(2)
const modes = ['limit-v1', 'limit-pending', 'unavailable-v1', 'unavailable-pending', 'malformed', 'wrong-status', 'lost', 'success']
let mode, calls
const problem = (status, shape) => {
  const code = status === 409 ? 'SEND_LIMIT_REACHED' : 'SEND_ACCESS_UNAVAILABLE'
  return shape === 'pending' ? { type: 'about:blank', title: code, status, code }
    : { type: 'https://remanence.invalid/problems/send-access', title: code, status, code,
      detail: 'PRIVATE_SERVER_DETAIL', request_id: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', retryable: status === 503 }
}
const json = (res, body, status = 200) => res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'private, no-store' }).end(JSON.stringify(body))
const host = await fixtureHost(web, proof, 'sendAccess.page.tsx', { async handle(req, res) {
  const path = new URL(req.url, 'http://fixture.invalid').pathname
  if (!path.startsWith('/v1/')) return false
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  const bytes = Buffer.concat(chunks)
  calls.push({ method: req.method, path, digest: createHash('sha256').update(bytes).digest('hex'), idempotencyKey: req.headers['idempotency-key'] ?? null })
  assert.equal(req.headers.authorization, 'Bearer synthetic-access')
  if (path === '/v1/me/send-access') json(res, { policy_version: 1, mode: 'enforce', lifetime: false,
    free: { self: { limit: 2, used: 2, remaining: 0 }, other: { limit: 2, used: 2, remaining: 0 } }, can_publish: { self: false, other: false } })
  else if (path === '/v1/capsules') {
    const body = JSON.parse(bytes)
    json(res, { capsule_id: body.capsule_id, state: 'DRAFT', draft_expires_at: '2026-10-21T00:00:00Z',
      blobs: body.blobs.map((blob) => ({ blob_id: blob.blob_id, state: 'DECLARED' })) }, 201)
  } else if (req.method === 'PUT') res.writeHead(204).end()
  else if (path.endsWith('/finalize')) {
    if (mode === 'lost') res.destroy()
    else if (mode === 'success') json(res, { capsule_id: path.split('/')[3], state: 'READY', ready_at: '2026-10-20T18:00:00Z' }, 201)
    else if (mode === 'malformed') json(res, { code: 'SEND_LIMIT_REACHED' }, 409)
    else if (mode === 'wrong-status') json(res, problem(409, 'v1'), 503)
    else { const [kind, shape] = mode.split('-'); const status = kind === 'limit' ? 409 : 503; json(res, problem(status, shape), status) }
  } else json(res, {}, 404)
  return true
} })
const checks = [], errors = [], facts = []
const check = (name, value) => { assert.ok(value, name); checks.push({ mode, name, ok: true }) }
async function capture(page, label) {
  const send = page.locator('.send-block')
  await send.scrollIntoViewIfNeeded()
  await send.screenshot({ path: join(proof, `${mode}-${label}-send.png`) })
  await page.screenshot({ path: join(proof, `${mode}-${label}.png`), fullPage: true })
  await writeFile(join(proof, `${mode}-${label}.html`), await page.content())
}
try {
  for (mode of modes) {
    calls = []
    const page = await host.browser.newPage({ viewport: { width: 320, height: 740 } })
    page.on('pageerror', (error) => errors.push({ mode, error: String(error) }))
    await page.route('**/*', async (route) => {
      if (new URL(route.request().url()).origin === new URL(host.url).origin) await route.continue()
      else { errors.push({ mode, error: 'external request' }); await route.abort() }
    })
    try {
      // A stale/exhausted metadata view never overrides actual finalizer admission.
      await page.goto(`${host.url}?mode=${mode}#/settings`)
      await page.locator('[data-testid=access-self]').waitFor()
      check('zero read is advisory, no client spend', (await page.locator('[data-testid=access-other]').innerText()).startsWith('0 / 2'))
      await page.locator('a[href="#/"]').last().click()
      await page.locator('a[href="#/create"]').click()
      await page.waitForFunction(() => Boolean(window.sendFeedbackFixture))
      await page.evaluate(() => window.sendFeedbackFixture.ready())
      const authored = await page.locator('textarea').inputValue()
      await page.locator('[data-testid=send_button]').click()
      await page.locator('[data-testid=send_button]').evaluate((button) => button.click())
      const key = mode.startsWith('limit-') ? 'sendLimitReached' : mode.startsWith('unavailable-') ? 'sendAccessUnavailable' : mode === 'success' ? 'sendSent' : 'sendNotConfirmed'
      await page.waitForFunction((key) => document.querySelector('[data-testid=send_state]')?.textContent === window.sendFeedbackFixture.messages('en')[key], key, { timeout: 60000 })
      check('actual handler presents correct result', await page.locator('[data-testid=send_state]').innerText() === await page.evaluate((key) => window.sendFeedbackFixture.messages('en')[key], key))
      check('draft and photos remain authored', await page.locator('textarea').inputValue() === authored && await page.evaluate(() => window.sendFeedbackFixture.state().photoCount === 3))
      check('one signature and terminal button', await page.evaluate(() => window.sendFeedbackFixture.state().signs === 1) && await page.locator('[data-testid=send_button]').isDisabled())
      const before = await page.evaluate(() => window.sendAccessFixture.audit.length)
      await page.locator('[data-testid=send_button]').evaluate((button) => button.click())
      await page.locator('textarea').fill('Retained synthetic draft after a terminal result.')
      await page.waitForTimeout(150)
      check('edits/clicks cannot rearm', await page.locator('[data-testid=send_button]').isDisabled()
        && await page.evaluate((before) => window.sendAccessFixture.audit.length === before && window.sendFeedbackFixture.state().signs === 1, before))
      const fetches = await page.evaluate(() => window.sendAccessFixture.audit)
      check('one application draft and finalize', fetches.filter((r) => r.path.endsWith('/v1/capsules')).length === 1
        && fetches.filter((r) => r.path.endsWith('/finalize')).length === 1)
      const finals = calls.filter((r) => r.path.endsWith('/finalize'))
      check('no alternate capsule or signed request', finals.length >= 1 && finals.length <= (mode === 'lost' ? 2 : 1)
        && new Set(finals.map((call) => JSON.stringify(call))).size === 1 && finals[0].idempotencyKey === null)
      check('no private details or code echoed', !(await page.locator('body').innerText()).match(/PRIVATE|SEND_LIMIT_REACHED|SEND_ACCESS_UNAVAILABLE/))
      if (mode === 'limit-v1' || mode === 'unavailable-pending') {
        for (const language of ['en', 'ru', 'uk']) for (const width of [320, 1280]) {
          await page.setViewportSize({ width, height: 900 })
          await page.evaluate((lang) => window.sendFeedbackFixture.language(lang), language)
          await capture(page, `${language}-${width}`)
          check(`localized result ${language}/${width}`, await page.locator('[data-testid=send_state]').innerText() === await page.evaluate(([lang, key]) => window.sendFeedbackFixture.messages(lang)[key], [language, key]))
          check(`layout ${language}/${width}`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        }
      }
      if (mode !== 'success') {
        await page.locator('[data-testid=send_exit]').focus()
        check('safe exit keyboard focus', await page.locator('[data-testid=send_exit]').evaluate((el) => document.activeElement === el))
      }
      await capture(page, 'final')
      facts.push({ mode, calls: structuredClone(calls), audit: await page.evaluate(() => window.sendAccessFixture.audit),
        state: await page.evaluate(() => window.sendFeedbackFixture.state()) })
    } catch (error) { checks.push({ mode, name: 'fixture', ok: false, error: String(error) }) }
    await page.close()
  }
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ clientOnly: true, synthetic: true, realBackend: false, executableSha256: host.executableSha256, checks, errors, facts }, null, 2))
  await host.close()
}
assert.deepEqual(errors, [])
assert.ok(checks.every((check) => check.ok), JSON.stringify(checks.filter((c) => !c.ok)))
