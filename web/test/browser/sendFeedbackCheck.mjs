import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const modes = (process.env.T363_MODES || 'local,draft422,draft503,malformed,lost-finalize,hold-draft,hold-finalize,delayed-draft').split(',')
const checks = []
const facts = []
const errors = []
const consoleErrors = []
let mode = ''
let calls = []
let releaseHeld = null
const json = { 'content-type': 'application/json', 'cache-control': 'no-store' }
const host = await fixtureHost(web, proof, 'sendFeedback.page.tsx', {
  async handle(req, res) {
    const path = new URL(req.url, 'http://fixture.invalid').pathname
    if (!path.startsWith('/v1/')) return false
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const bytes = Buffer.concat(chunks)
    const parsed = req.method === 'POST' ? JSON.parse(bytes.toString()) : null
    calls.push({ mode, method: req.method, path, bytes: bytes.length, digest: createHash('sha256').update(bytes).digest('hex'),
      idempotencyKey: req.headers['idempotency-key'] ?? null })
    if (path === '/v1/capsules') {
      const fail = (status = 422) => res.writeHead(status, json).end(JSON.stringify({ code: status === 422 ? 'RECIPIENT_KEY_STALE' : 'INTERNAL_UNAVAILABLE', detail: 'SYNTHETIC_PRIVATE_ERROR_DO_NOT_RENDER' }))
      if (mode === 'draft422') fail()
      else if (mode === 'draft503') fail(503)
      else if (mode === 'hold-draft' || mode === 'delayed-draft') releaseHeld = fail
      else if (mode === 'malformed') res.writeHead(201, json).end('{}')
      else res.writeHead(201, json).end(JSON.stringify({
        capsule_id: parsed.capsule_id, state: 'DRAFT', draft_expires_at: '2026-10-21T00:00:00Z',
        blobs: parsed.blobs.map((item) => ({ blob_id: item.blob_id, state: 'DECLARED' })),
      }))
    } else if (req.method === 'PUT') res.writeHead(204).end()
    else if (path.endsWith('/finalize')) {
      if (mode === 'lost-finalize') res.destroy()
      else if (mode === 'hold-finalize') releaseHeld = () => res.writeHead(201, json).end(JSON.stringify({
        capsule_id: path.split('/')[3], state: 'READY', ready_at: '2026-10-20T16:00:00Z',
      }))
      else res.writeHead(500, json).end('{}')
    } else res.writeHead(404, json).end('{}')
    return true
  },
})
async function capture(page, label) {
  const name = `${mode}-${label}`
  if (await page.locator('[data-testid=send_state]').count()) {
    await page.locator('[data-testid=send_state]').scrollIntoViewIfNeeded()
  }
  await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true })
  await writeFile(join(proof, `${name}.html`), await page.content())
  facts.push({ mode, label, calls: [...calls], ...(await page.evaluate(() => ({
    state: window.sendFeedbackFixture.state(), text: document.querySelector('.send-block')?.textContent,
    active: { tag: document.activeElement?.tagName, testid: document.activeElement?.getAttribute('data-testid') },
    hash: location.hash,
    viewport: { width: innerWidth, zoom: getComputedStyle(document.documentElement).zoom },
    sendBounds: [...document.querySelectorAll('.send-block > *')].map((element) => {
      const rect = element.getBoundingClientRect()
      return { testid: element.getAttribute('data-testid'), left: rect.left, right: rect.right, width: rect.width }
    }),
    overflow: document.documentElement.scrollWidth > innerWidth + 1,
  }))) })
}
async function check(name, work) {
  try { await work(); checks.push({ mode, name, ok: true }) }
  catch (error) { checks.push({ mode, name, ok: false, error: String(error) }) }
}
try {
  for (mode of modes) {
    calls = []
    releaseHeld = null
    const page = await host.browser.newPage({ viewport: { width: 320, height: 568 } })
    page.on('pageerror', (error) => errors.push({ mode, error: String(error) }))
    page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push({ mode, text: message.text() }) })
    await page.route('**/*', async (route) => {
      if (new URL(route.request().url()).origin === new URL(host.url).origin) await route.continue()
      else { errors.push({ mode, error: 'unexpected external request' }); await route.abort() }
    })
    try {
      await page.goto(`${host.url}?mode=${mode}#/create`)
      await page.waitForFunction(() => Boolean(window.sendFeedbackFixture))
      await page.evaluate(() => window.sendFeedbackFixture.ready())
      await page.locator('[data-testid=send_button]').click()
      // Second activation on the same mounted visit cannot admit another send.
      await page.locator('[data-testid=send_button]').evaluate((button) => button.click())
      if (mode.startsWith('hold-')) {
        const until = Date.now() + 60000
        while (!releaseHeld && Date.now() < until) await new Promise((resolve) => setTimeout(resolve, 100))
        assert.ok(releaseHeld, 'server reached the gated request')
        await check('pending-send-stays-disabled', async () => {
          assert.equal(await page.locator('[data-testid=send_button]').isDisabled(), true)
          assert.equal(calls.filter((call) => call.path === '/v1/capsules').length, 1)
        })
        await capture(page, 'waiting')
        await check('leaving-does-not-claim-cancellation-or-completion', async () => {
          await page.locator('.card a[href="#/"]').last().click()
          await page.waitForFunction(() => location.hash === '#/' && document.querySelector('[data-testid=send_button]') === null)
          releaseHeld()
          await page.waitForTimeout(250)
          assert.equal(calls.filter((call) => call.path === '/v1/capsules').length, 1)
          if (mode === 'hold-draft') assert.equal(calls.length, 1, 'no upload/finalize follows an abandoned draft')
          assert.equal(await page.locator('[data-testid=send_state]').count(), 0)
          const text = await page.locator('main.shell').innerText()
          assert.equal(text.includes('postcard sent.'), false)
          assert.equal(text.includes('nothing left this device'), false)
        })
      } else {
        let pendingFocus = null
        if (mode === 'delayed-draft') {
          const until = Date.now() + 60000
          while (!releaseHeld && Date.now() < until) await new Promise((resolve) => setTimeout(resolve, 100))
          assert.ok(releaseHeld, 'server reached delayed draft')
          await check('delayed-answer-keeps-one-pending-attempt', async () => {
            assert.equal(await page.locator('[data-testid=send_button]').isDisabled(), true)
            assert.equal(await page.locator('[data-testid=send_state]').innerText(), await page.evaluate(() => window.sendFeedbackFixture.messages('en').sendWorking))
            assert.equal(calls.length, 1)
            await page.locator('.card a[href="#/"]').last().focus()
            pendingFocus = await page.evaluateHandle(() => document.activeElement)
            await capture(page, 'waiting')
          })
          releaseHeld()
        }
        await page.waitForFunction(() => {
          const state = document.querySelector('[data-testid=send_state]')
          return state && state.textContent !== window.sendFeedbackFixture.messages('en').sendWorking
        }, undefined, { timeout: 60000 })
        await capture(page, 'result-en')
        await check('message-respects-observed-boundary', async () => {
          const text = await page.locator('[data-testid=send_state]').innerText()
          if (mode.startsWith('draft') || mode === 'delayed-draft') {
            assert.equal(calls.length, 1)
            assert.equal(text.includes('nothing left this device'), false, 'a request reached the server')
            assert.equal(text, await page.evaluate(() => window.sendFeedbackFixture.messages('en').sendDraftFailed))
          } else if (mode === 'local') {
            assert.equal(calls.length, 0)
            assert.equal(text, await page.evaluate(() => window.sendFeedbackFixture.messages('en').sendNotSent))
          } else {
            if (mode === 'malformed') assert.equal(calls.length, 1, 'malformed draft must not start uploads')
            if (mode === 'lost-finalize') {
              const finals = calls.filter((call) => call.path.endsWith('/finalize'))
              const fetches = await page.evaluate(() => window.sendFeedbackFixture.state().fetches)
              assert.equal(fetches.filter((call) => call.path.endsWith('/finalize')).length, 1, 'exactly one application fetch')
              assert.ok(finals.length >= 1 && finals.length <= 2, 'bounded Chromium empty-response resend')
              assert.equal(new Set(finals.map((call) => JSON.stringify(call))).size, 1, 'same capsule and exact body')
              assert.equal(finals[0].idempotencyKey, null, 'finalize keeps its existing header-free replay contract')
              assert.equal(fetches.filter((call) => call.path === '/v1/capsules').length, 1, 'no fresh application attempt')
            }
            assert.equal(text, await page.evaluate(() => window.sendFeedbackFixture.messages('en').sendNotConfirmed))
          }
          assert.equal(await page.locator('main > .status[role=alert]').innerText(), text)
          if (pendingFocus) assert.equal(await page.evaluate((previous) => document.activeElement === previous, pendingFocus), true, 'async error must not steal focus')
          assert.equal(await page.locator('body').innerText().then((value) => value.includes('SYNTHETIC_PRIVATE_ERROR_DO_NOT_RENDER')), false)
        })
        await check('terminal-does-not-rearm', async () => {
          const before = calls.length
          assert.equal(await page.locator('[data-testid=send_button]').isDisabled(), true)
          await page.locator('[data-testid=send_button]').evaluate((button) => button.click())
          await page.waitForTimeout(150)
          assert.equal(calls.length, before)
          assert.equal(await page.evaluate(() => window.sendFeedbackFixture.state().signs), 1)
        })
        await check('locale-and-scale-safe-exit', async () => {
          for (const language of ['en', 'ru', 'uk']) for (const width of [320, 1280]) {
            await page.setViewportSize({ width, height: width === 320 ? 568 : 720 })
            await page.evaluate((lang) => window.sendFeedbackFixture.language(lang), language)
            // Explicit CSS layout magnification, not a claim for native browser-toolbar zoom.
            await page.evaluate(() => { document.documentElement.style.zoom = '2' })
            await page.locator('[data-testid=send_exit]').scrollIntoViewIfNeeded()
            assert.equal(await page.locator('[data-testid=send_exit]').isVisible(), true)
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false)
            assert.equal(await page.evaluate(() => [...document.querySelectorAll('.send-block > *')].every((element) => {
              const rect = element.getBoundingClientRect()
              return rect.left >= 0 && rect.right <= innerWidth + 1
            })), true, 'send text and exit remain within the physical viewport')
            assert.equal(await page.locator('[data-testid=send_help]').innerText().then((value) => value.length > 10), true)
            const key = mode.startsWith('draft') || mode === 'delayed-draft' ? 'sendDraftFailed' : mode === 'local' ? 'sendNotSent' : 'sendNotConfirmed'
            assert.equal(await page.locator('[data-testid=send_state]').innerText(), await page.evaluate(([lang, name]) => window.sendFeedbackFixture.messages(lang)[name], [language, key]))
            await page.locator('[data-testid=send_exit]').focus()
            assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('data-testid')), 'send_exit')
            await capture(page, `${language}-${width}-magnified`)
          }
          await capture(page, 'result-uk-magnified')
          const before = calls.length
          await page.keyboard.press('Enter')
          await page.waitForFunction(() => location.hash === '#/' && document.querySelector('[data-testid=send_button]') === null)
          assert.equal(calls.length, before, 'exit does not retry or reset an attempt')
          assert.equal(await page.evaluate(() => window.sendFeedbackFixture.state().phase), 'keys-unlocked', 'Home exit is not logout')
          assert.equal(await page.evaluate(() => window.sendFeedbackFixture.state().photoCount), 3, 'existing in-memory draft policy is unchanged')
        })
      }
    } catch (error) { checks.push({ mode, name: 'fixture-run', ok: false, error: String(error) }) }
    await capture(page, 'final')
    await page.close()
  }
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ synthetic: true, executableSha256: host.executableSha256, checks, facts, errors, consoleErrors }, null, 2))
  await host.close()
}
assert.deepEqual(errors, [])
const expectedConsole = {
  draft422: 'Failed to load resource: the server responded with a status of 422 (Unprocessable Entity)',
  draft503: 'Failed to load resource: the server responded with a status of 503 (Service Unavailable)',
  'delayed-draft': 'Failed to load resource: the server responded with a status of 422 (Unprocessable Entity)',
  'lost-finalize': 'Failed to load resource: net::ERR_EMPTY_RESPONSE',
}
for (const error of consoleErrors) assert.equal(error.text, expectedConsole[error.mode], `unexpected console error: ${JSON.stringify(error)}`)
assert.equal(checks.every((check) => check.ok), true, JSON.stringify(checks))
console.log(`send-feedback PASS: ${checks.length} checks across ${modes.length} real Create visits`)
