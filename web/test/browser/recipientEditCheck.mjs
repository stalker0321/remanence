import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const host = await fixtureHost(web, proof, 'recipientEdit.page.tsx')
const checks = []
const errors = []
async function scenario(name, run) {
  const page = await host.browser.newPage({ viewport: { width: 320, height: 568 } })
  page.on('pageerror', (error) => errors.push({ name, error: String(error) }))
  try {
    await page.goto(host.url)
    await page.locator('.handle-form input').waitFor()
    await run(page)
    checks.push({ name, ok: true })
  } catch (error) { checks.push({ name, ok: false, error: String(error) }) }
  await page.screenshot({ path: join(proof, `${name}.png`) })
  await writeFile(join(proof, `${name}.html`), await page.content())
  await page.close()
}
const input = (page) => page.locator('.handle-form input')
const submit = (page) => page.locator('.handle-form button[type=submit]')
const state = (page) => page.evaluate(() => window.recipientEdit.state())
const confirm = (page) => page.getByRole('button', { name: 'this is the person', exact: true })
async function find(page, handle) {
  await input(page).fill(handle)
  await submit(page).click()
}
async function result(page, index, status = 200) {
  await page.evaluate(([i, s]) => window.recipientEdit.result(i, s), [index, status])
  // Drain the promise and React commit; follow-up predicates assert actual state.
  await page.waitForTimeout(100)
}
try {
  await scenario('found-then-change-and-edit', async (page) => {
    await find(page, 'alice_qa')
    await result(page, 0)
    assert.equal((await state(page)).pending, 'alice_qa')
    // The confirmation is deliberately a separate screen. Return through the
    // real Change action before editing; no direct controller reset in a test.
    await page.getByRole('button', { name: 'change recipient', exact: true }).click()
    assert.equal((await state(page)).pending, null)
    await input(page).fill('bob_qa')
    assert.equal((await state(page)).pending, null)
    assert.equal(await confirm(page).count(), 0)
  })
  await scenario('edit-then-late-success', async (page) => {
    await find(page, 'alice_qa')
    await input(page).fill('bob_qa')
    await result(page, 0)
    assert.equal((await state(page)).pending, null)
    assert.equal(await submit(page).isEnabled(), true)
    assert.equal((await page.evaluate(() => window.recipientEdit.requests()))[0].aborted, true)
  })
  await scenario('old-completion-keeps-new-busy', async (page) => {
    await find(page, 'alice_qa')
    await input(page).fill('bob_qa')
    assert.equal(await submit(page).isEnabled(), true)
    await submit(page).click()
    await result(page, 0, 404)
    assert.equal(await submit(page).isDisabled(), true)
    assert.equal((await state(page)).notices.includes('recipientNotFound'), false)
    await result(page, 1)
    assert.equal((await state(page)).pending, 'bob_qa')
  })
  await scenario('self-then-change-and-edit', async (page) => {
    await page.getByRole('button', { name: 'for myself', exact: true }).click()
    await result(page, 0)
    assert.equal((await state(page)).pending, 'self_qa')
    await page.getByRole('button', { name: 'change recipient', exact: true }).click()
    await input(page).fill('bob_qa')
    assert.equal((await state(page)).pending, null)
  })
  await scenario('missing-then-normal-confirm', async (page) => {
    await find(page, 'missing_qa')
    await result(page, 0, 404)
    assert.equal((await state(page)).notices.at(-1), 'recipientNotFound')
    await find(page, 'bob_qa')
    await result(page, 1)
    // The actual localized confirmation control, not a direct draft mutation.
    await confirm(page).click()
    assert.equal((await state(page)).confirmed, 'bob_qa')
    await page.evaluate(() => window.recipientEdit.render())
    assert.equal((await state(page)).confirmed, 'bob_qa')
  })
  await scenario('owner-switch-rejects-late', async (page) => {
    await find(page, 'alice_qa')
    await page.evaluate(() => window.recipientEdit.switchAccount())
    await result(page, 0)
    assert.equal((await state(page)).pending, null)
    assert.equal((await state(page)).confirmed, null)
  })
  await scenario('unmount-rejects-late', async (page) => {
    await find(page, 'alice_qa')
    await page.evaluate(() => window.recipientEdit.unmount())
    await result(page, 0)
    assert.equal((await state(page)).pending, null)
    assert.equal((await page.evaluate(() => window.recipientEdit.requests()))[0].aborted, true)
  })
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ synthetic: true, executableSha256: host.executableSha256, checks, errors }, null, 2))
  await host.close()
}
assert.deepEqual(errors, [])
assert.equal(checks.every((check) => check.ok), true, JSON.stringify(checks))
console.log(`recipient-edit PASS: ${checks.length} scenarios`)
