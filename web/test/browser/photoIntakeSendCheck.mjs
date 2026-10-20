import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const modes = (process.env.T364_MODES || 'gated,same-turn,queued,read-error,normalize-error,queued-error,cancel-intake,picker-cancel,recipient,owner,unmount').split(',')
const checks = [], facts = [], errors = [], consoleErrors = []
let mode, directory, calls = []
const host = await fixtureHost(web, proof, 'photoIntakeSend.page.tsx', {
  async handle(req, res) {
    const path = new URL(req.url, 'http://fixture.invalid').pathname
    if (!path.startsWith('/v1/')) return false
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const bytes = Buffer.concat(chunks)
    const body = req.method === 'POST' ? JSON.parse(bytes.toString()) : null
    calls.push({ mode, method: req.method, path, bytes: bytes.length,
      photoBlobs: body?.blobs?.filter((blob) => blob.kind === 'PHOTO').length })
    const json = { 'content-type': 'application/json', 'cache-control': 'no-store' }
    if (path === '/v1/directory/handles/next_qa') res.writeHead(200, json).end(JSON.stringify(directory))
    else if (path === '/v1/capsules') res.writeHead(201, json).end(JSON.stringify({
      capsule_id: body.capsule_id, state: 'DRAFT', draft_expires_at: '2026-10-21T00:00:00Z',
      blobs: body.blobs.map((blob) => ({ blob_id: blob.blob_id, state: 'DECLARED' })),
    }))
    else if (req.method === 'PUT') res.writeHead(204).end()
    else if (path.endsWith('/finalize')) res.writeHead(201, json).end(JSON.stringify({
      capsule_id: path.split('/')[3], state: 'READY', ready_at: '2026-10-20T16:00:00Z',
    }))
    else res.writeHead(404, json).end('{}')
    return true
  },
})
async function check(name, work) {
  try { await work(); checks.push({ mode, name, ok: true }) }
  catch (error) { checks.push({ mode, name, ok: false, error: String(error) }) }
}
async function capture(page, label) {
  facts.push({ mode, label, calls: [...calls],
    state: await page.evaluate(() => window.photoIntakeFixture.state()),
    send: await page.evaluate(() => window.sendFeedbackFixture.state()),
    notice: await page.locator('main > .status[role=alert]').innerText(),
  })
  await page.screenshot({ path: join(proof, `${mode}-${label}.png`), fullPage: true })
  if (await page.locator('.send-block').count()) await page.locator('.send-block').screenshot({ path: join(proof, `${mode}-${label}-send.png`) })
  await writeFile(join(proof, `${mode}-${label}.html`), await page.content())
}
const publicationCalls = () => calls.filter((call) => call.path.startsWith('/v1/capsules'))
async function blocked(page, label) {
  await capture(page, `${label}-pending`)
  await check(`${label}:render-blocks-intake`, async () => {
    assert.equal(await page.locator('[data-testid=send_button]').isDisabled(), true)
    assert.equal(await page.locator('.photo-draft').getAttribute('aria-busy'), 'true')
  })
  if (await page.locator('[data-testid=send_button]').isEnabled()) await page.locator('[data-testid=send_button]').click()
  await page.evaluate(() => window.photoIntakeFixture.staleClick())
  await page.waitForTimeout(1200)
  await capture(page, `${label}-after-click`)
  await check(`${label}:no-begin-sign-publish`, async () => {
    assert.equal(await page.evaluate(() => window.photoIntakeFixture.state().begins.length), 0)
    assert.equal(await page.evaluate(() => window.sendFeedbackFixture.state().signs), 0)
    assert.equal(publicationCalls().length, 0)
  })
}
async function released(page, count) {
  await page.waitForFunction((expected) => window.photoIntakeFixture.state().photoIds.length === expected
    && document.querySelector('.photo-draft')?.getAttribute('aria-busy') !== 'true', count)
}
async function sendCurrent(page, count, preserve = false) {
  await check('one-send-only-current-photos-and-freeze', async () => {
    assert.equal(await page.evaluate(() => window.photoIntakeFixture.state().begins.length), 0, 'intake did not consume the attempt')
    if (!preserve) {
      await page.waitForFunction(() => document.querySelector('[data-testid=creator-frozen]') === null)
      assert.equal(await page.locator('[data-testid=send_button]').isDisabled(), true)
      await page.evaluate((expected) => window.photoIntakeFixture.freeze(expected), count)
    }
    await page.waitForFunction(() => !document.querySelector('[data-testid=send_button]').disabled)
    await page.locator('[data-testid=send_button]').click()
    await page.waitForFunction(() => document.querySelector('[data-testid=send_state]')?.textContent === window.photoIntakeFixture.messages().sendSent)
    const state = await page.evaluate(() => window.photoIntakeFixture.state())
    assert.equal(state.begins.length, 1)
    assert.equal(state.begins[0].admitted, true)
    assert.equal(state.begins[0].owner, state.owner)
    assert.equal(state.begins[0].recipient, state.recipient)
    assert.deepEqual(state.begins[0].ids, state.photoIds)
    assert.deepEqual([...state.begins[0].slots].sort(), [...state.photoIds].sort())
    assert.equal(await page.evaluate(() => window.sendFeedbackFixture.state().signs), 1)
    assert.equal(calls.filter((call) => call.path === '/v1/capsules').length, 1)
    assert.equal(calls.find((call) => call.path === '/v1/capsules').photoBlobs, count)
    assert.equal(calls.filter((call) => call.path.endsWith('/finalize')).length, 1)
    await page.locator('[data-testid=send_button]').evaluate((button) => button.click())
    assert.equal(await page.evaluate(() => window.photoIntakeFixture.state().begins.length), 1)
  })
}
async function chooseRecipient(page) {
  await page.locator('input[maxlength="32"]').fill('next_qa')
  const labels = await page.evaluate(() => window.photoIntakeFixture.messages())
  await page.getByRole('button', { name: labels.lookupRecipient, exact: true }).click()
  await page.getByRole('button', { name: labels.recipientConfirm, exact: true }).click()
  await page.waitForFunction(() => window.photoIntakeFixture.state().recipient === '55555555-6666-4777-8888-999999999999')
}
async function freshFront(page, count) {
  await page.evaluate((expected) => window.photoIntakeFixture.freeze(expected), count)
  await page.evaluate(() => window.photoIntakeFixture.front())
  await page.waitForFunction(() => !document.querySelector('[data-testid=send_button]').disabled)
  await page.evaluate(() => window.photoIntakeFixture.rememberHandler())
}
try {
  for (mode of modes) {
    calls = []
    const page = await host.browser.newPage({ viewport: { width: 390, height: 740 } })
    page.on('pageerror', (error) => errors.push({ mode, error: String(error) }))
    page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push({ mode, text: message.text() }) })
    await page.route('**/*', async (route) => {
      if (new URL(route.request().url()).origin === new URL(host.url).origin) await route.continue()
      else { errors.push({ mode, error: 'external request' }); await route.abort() }
    })
    try {
      await page.goto(`${host.url}#/create`)
      await page.waitForFunction(() => Boolean(window.photoIntakeFixture))
      await page.evaluate(() => window.photoIntakeFixture.ready())
      directory = await page.evaluate(() => window.photoIntakeFixture.directory())
      const initial = await page.evaluate(() => window.photoIntakeFixture.state())
      if (mode === 'picker-cancel') {
        await page.evaluate(() => {
          document.querySelector('.photo-draft input').dispatchEvent(new Event('cancel', { bubbles: true }))
          window.photoIntakeFixture.pick([])
        })
        await check('empty-picker-preserves-selection-and-attempt', async () => {
          const current = await page.evaluate(() => window.photoIntakeFixture.state())
          assert.deepEqual(current.photoIds, initial.photoIds)
          assert.equal(current.frozen, initial.frozen)
          assert.equal(current.disabled, false)
          assert.equal(current.begins.length, 0)
          assert.equal(publicationCalls().length, 0)
        })
        await sendCurrent(page, 3, true)
        await capture(page, 'final')
        await page.close()
        continue
      }
      await page.evaluate((scenario) => {
        window.photoIntakeFixture.gate('intake-D.jpg',
          ['read-error', 'queued-error', 'recipient'].includes(scenario),
          scenario === 'normalize-error')
        window.photoIntakeFixture.pick(['intake-D.jpg'], scenario === 'same-turn')
        if (scenario === 'queued' || scenario === 'queued-error') {
          window.photoIntakeFixture.gate('intake-E.jpg', false, scenario === 'queued-error')
          window.photoIntakeFixture.pick(['intake-E.jpg'])
        }
      }, mode)
      await page.waitForFunction(() => window.photoIntakeFixture.state().gates[0].started)
      await blocked(page, 'first')
      if (['recipient', 'owner', 'unmount'].includes(mode)) {
        if (mode === 'recipient') {
          const labels = await page.evaluate(() => window.photoIntakeFixture.messages())
          await page.getByRole('button', { name: labels.recipientChange, exact: true }).click()
          await chooseRecipient(page)
          await freshFront(page, 3)
        } else if (mode === 'unmount') {
          await page.locator('.card a[href="#/"]').last().click()
          await page.waitForFunction(() => document.querySelector('[data-testid=send_button]') === null)
          await capture(page, 'left')
          await page.locator('a[href="#/create"]').click()
          await freshFront(page, 3)
        } else {
          await page.evaluate(() => window.photoIntakeFixture.replaceOwner())
          await page.waitForFunction(() => window.photoIntakeFixture.state().photoIds.length === 0 && document.querySelector('.photo-draft') === null)
          await page.evaluate(() => window.photoIntakeFixture.unlockOwner())
          await chooseRecipient(page)
        }
        await page.evaluate((scenario) => {
          const name = scenario === 'owner' ? 'new-C.jpg' : 'intake-E.jpg'
          window.photoIntakeFixture.gate(name)
          window.photoIntakeFixture.pick(scenario === 'owner' ? ['new-A.jpg', 'new-B.jpg', 'new-C.jpg'] : [name])
        }, mode)
        await page.evaluate(() => window.photoIntakeFixture.release('intake-D.jpg'))
        const nextName = mode === 'owner' ? 'new-C.jpg' : 'intake-E.jpg'
        await page.waitForFunction((name) => window.photoIntakeFixture.state().gates.find((gate) => gate.name === name)?.started, nextName)
        await page.waitForFunction(() => window.photoIntakeFixture.state().settledIntakes.some((work) => work.names.includes('intake-D.jpg')))
        await check('old-completion-cannot-clear-new-owner-work-or-notice', async () => {
          const state = await page.evaluate(() => window.photoIntakeFixture.state())
          assert.equal(state.settledIntakes.find((work) => work.names.includes('intake-D.jpg')).added, 0)
          assert.equal(state.intakeBusy, 'true')
          assert.equal(await page.locator('main > .status[role=alert]').innerText(), await page.evaluate(() => window.photoIntakeFixture.messages().photoLoading))
          assert.equal(state.photoIds.length, mode === 'owner' ? 2 : 3)
          assert.equal(state.begins.length, 0)
          assert.equal(publicationCalls().length, 0)
          if (mode === 'owner') assert.notEqual(state.owner, initial.owner)
          if (mode === 'recipient') assert.notEqual(state.recipient, initial.recipient)
        })
        if (mode !== 'owner') await blocked(page, 'replacement')
        await page.evaluate((name) => window.photoIntakeFixture.release(name), nextName)
        await released(page, mode === 'owner' ? 3 : 4)
        if (mode === 'owner') {
          // Real owner change correctly wipes the previous owner's note too.
          await page.locator('textarea').fill('New owner synthetic postcard.')
          await freshFront(page, 3)
          await sendCurrent(page, 3, true)
        } else await sendCurrent(page, 4)
      } else if (mode === 'queued' || mode === 'queued-error') {
        await page.evaluate(() => window.photoIntakeFixture.release('intake-D.jpg'))
        await page.waitForFunction(() => window.photoIntakeFixture.state().gates[1].started)
        await check('second-pick-was-queued-and-still-owned', async () => {
          assert.equal(await page.locator('.photo-draft').getAttribute('aria-busy'), 'true')
          assert.equal(await page.evaluate(() => window.photoIntakeFixture.state().photoIds.length), mode === 'queued' ? 4 : 3)
        })
        if (mode === 'queued') await page.evaluate(() => window.photoIntakeFixture.freeze(4))
        await page.evaluate(() => window.photoIntakeFixture.rememberHandler())
        await blocked(page, 'second')
        await page.evaluate(() => window.photoIntakeFixture.release('intake-E.jpg'))
        await released(page, mode === 'queued' ? 5 : 3)
        await sendCurrent(page, mode === 'queued' ? 5 : 3, mode === 'queued-error')
      } else {
        if (mode === 'cancel-intake') await page.evaluate(() => window.photoIntakeFixture.cancelIntake())
        await page.evaluate(() => window.photoIntakeFixture.release('intake-D.jpg'))
        const unchanged = ['read-error', 'normalize-error', 'cancel-intake'].includes(mode)
        await released(page, unchanged ? 3 : 4)
        if (unchanged) await check('failure-or-cancel-preserves-valid-old-selection', async () => {
          const state = await page.evaluate(() => window.photoIntakeFixture.state())
          assert.deepEqual(state.hashes, initial.hashes)
          assert.equal(state.frozen, initial.frozen)
          assert.equal(state.disabled, false)
          if (mode !== 'cancel-intake') assert.equal(await page.locator('main > .status[role=alert]').innerText(),
            await page.evaluate(() => window.photoIntakeFixture.messages().photoRejected))
        })
        await sendCurrent(page, unchanged ? 3 : 4, unchanged)
      }
      await capture(page, 'final')
    } catch (error) {
      checks.push({ mode, name: 'fixture-run', ok: false, error: String(error), stack: error.stack })
      await capture(page, 'failure')
    }
    await page.close()
  }
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ synthetic: true, executableSha256: host.executableSha256, checks, facts, errors, consoleErrors }, null, 2))
  await host.close()
}
assert.deepEqual(errors, [])
assert.deepEqual(consoleErrors, [])
assert.equal(checks.every((check) => check.ok), true, JSON.stringify(checks))
console.log(`photo-intake/send PASS: ${checks.length} checks in ${modes.length} real App visits`)
