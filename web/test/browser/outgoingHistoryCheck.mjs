import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const target = '33333333-4444-4555-8666-777777777777'
const id = (i) => `${String(i).padStart(8, '0')}-bbbb-4ccc-8ddd-eeeeeeeeeeee`
const time = '2026-10-20T12:00:00+00:00'
function row(i, status, version = 1) {
  const published = version === 1 && ['READY', 'OPENED', 'REVOKED'].includes(status)
  return { capsule_id: id(i), protocol_version: version, delivery_id: version === 2 ? id(i + 100) : null, status,
    created_at: time, published_at: published ? time : null, expires_at: published ? null : time,
    first_opened_at: status === 'OPENED' ? time : null, revoked_at: status === 'REVOKED' ? time : null,
    recipient: version === 1 ? { user_id: target, handle: 'recipient_qa', hint: null, is_self: false }
      : { user_id: null, handle: null, hint: 'r•••@•••', is_self: null },
    locator: ['READY', 'PENDING', 'CLAIMED'].includes(status) ? { status: 'ACTIVE', code: 'AB12Z' }
      : { status: status === 'OPENED' ? 'RETIRED_OPEN' : ['REVOKED', 'CANCELLED', 'EXPIRED'].includes(status) ? 'RETIRED_TERMINAL' : 'UNAVAILABLE', code: null },
    cancel_action: status === 'READY' ? 'REVOKE_V1' : version === 2 && ['DRAFT', 'PENDING'].includes(status) ? 'CANCEL_PENDING_V2' : null,
    cancel_deadline: published ? time : null }
}
const fresh = () => [row(1, 'READY'), row(2, 'OPENED'), row(3, 'REVOKED'), row(4, 'DRAFT'), row(5, 'ABORTED'),
  row(6, 'PENDING', 2), row(7, 'CLAIMED', 2), row(8, 'CANCELLED', 2), row(9, 'EXPIRED', 2), row(10, 'DRAFT', 2),
  { ...row(11, 'READY'), status: 'FUTURE', cancel_action: 'FUTURE' }]
let mode = 'normal', rows = fresh(), requests = [], held = [], directory = null
function json(res, body, status = 200) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'private, no-store' }).end(JSON.stringify(body))
}
const host = await fixtureHost(web, proof, 'outgoingHistory.page.tsx', { handle: async (req, res) => {
  const url = new URL(req.url, 'http://fixture.invalid')
  if (!url.pathname.startsWith('/v1/') && !url.pathname.startsWith('/v2/')) return false
  requests.push({ method: req.method, path: req.url, token: req.headers.authorization })
  if (req.headers.authorization === 'Bearer synthetic-other') { json(res, url.pathname.endsWith('/recent') ? { items: [] } : { items: [], has_more: false, next_cursor: null }); return true }
  assert.equal(req.headers.authorization, 'Bearer synthetic-access')
  if (url.pathname === '/v1/capsules/outgoing') {
    const body = mode === 'empty' ? { items: [], has_more: false, next_cursor: null }
      : url.searchParams.has('cursor') ? { items: rows.slice(5), has_more: false, next_cursor: null }
      : { items: rows.slice(0, 5), has_more: true, next_cursor: 'next_synthetic' }
    if (mode === 'hold-history') held.push(() => json(res, body))
    else if (mode === 'old-server') json(res, { code: 'NOT_FOUND' }, 404)
    else if (mode === 'malformed') json(res, { items: [] })
    else json(res, body)
  } else if (url.pathname === '/v1/recipients/recent') {
    const body = { items: [{ user_id: target, handle: 'recipient_qa', last_sent_at: time }] }
    if (mode === 'hold-recent') held.push(() => json(res, body))
    else if (mode === 'old-server') json(res, {}, 405)
    else if (mode === 'empty') json(res, { items: [] })
    else json(res, body)
  } else if (url.pathname.startsWith('/v1/directory/handles/')) {
    const result = structuredClone(directory)
    if (mode === 'rebound') { result.user.user_id = id(999); result.key_bundle.user_id = id(999) }
    json(res, result)
  } else if (req.method === 'POST' && (url.pathname.endsWith('/revoke') || url.pathname.endsWith('/cancel'))) {
    const found = rows.find((row) => row.capsule_id === url.pathname.split('/')[3] || row.delivery_id === url.pathname.split('/')[3])
    assert.ok(found)
    const response = () => {
      if (mode === 'conflict') { found.status = 'OPENED'; found.first_opened_at = time; found.cancel_action = null; found.locator = { status: 'RETIRED_OPEN', code: null }; json(res, {}, 409) }
      else if (mode === 'unknown') json(res, {}, 503)
      else {
        found.status = found.protocol_version === 1 ? 'REVOKED' : 'CANCELLED'
        found.cancel_action = null
        found.locator = { status: 'RETIRED_TERMINAL', code: null }
        json(res, found.protocol_version === 1 ? { capsule_id: found.capsule_id, state: 'REVOKED', is_replay: false }
          : { delivery_id: found.delivery_id, state: 'CANCELLED' })
      }
    }
    if (mode === 'hold-cancel') held.push(response)
    else response()
  } else json(res, {}, 404)
  return true
} })
const checks = [], errors = [], visits = []
const check = (name, value) => { assert.ok(value, name); checks.push({ name, ok: true }) }
const posts = () => requests.filter((req) => req.method === 'POST')
const button = async (page, key) => page.getByRole('button', { name: await page.evaluate((k) => window.sendFeedbackFixture.messages(window.historyFixture.store.getSnapshot().language)[k], key), exact: true })
async function click(page, key) { await (await button(page, key)).click() }
async function scenario(name, run, route = 'history') {
  mode = 'normal'; rows = fresh(); requests = []; held = []
  const page = await host.browser.newPage({ viewport: { width: 320, height: 740 } })
  page.setDefaultTimeout(8000)
  page.on('pageerror', (error) => errors.push({ name, error: String(error) }))
  try {
    if (name.startsWith('hold-')) mode = name
    await page.goto(`${host.url}#/${route}`)
    await page.waitForFunction(() => Boolean(window.historyFixture))
    const snapshot = await page.evaluate(() => window.historyFixture.recipient.confirmed())
    directory = { user: { user_id: snapshot.userId, handle: snapshot.handle }, key_bundle: {
      user_id: snapshot.userId, key_bundle_id: snapshot.keyBundleId, suite: snapshot.suite, protocol_version: 1,
      encryption_public_keyset: snapshot.encryptionPublicKeyset, signing_public_keyset: snapshot.signingPublicKeyset,
      status: 'ACTIVE', created_at: time }, directory_version: snapshot.directoryVersion }
    await run(page)
    check(`${name}: no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    check(`${name}: all private fetches no-store`, await page.evaluate(() => window.historyFixture.audit.filter((r) => /\/v[12]\//.test(r.path)).every((r) => r.cache === 'no-store')))
    check(`${name}: no durable private storage`, await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await caches.keys()).length === 0))
  } catch (error) { checks.push({ name, ok: false, error: String(error) }) }
  await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true })
  await writeFile(join(proof, `${name}.html`), await page.content())
  visits.push({ name, requests: structuredClone(requests) })
  held.splice(0).forEach((release) => release())
  await page.close()
}
async function loaded(page) { await page.locator('[data-history-key]').first().waitFor() }
async function openRow(page, key) {
  const detail = page.locator(`[data-history-key="${key}"] > details`)
  await detail.locator(':scope > summary').click()
  return detail
}
async function recents(page) {
  await page.evaluate(() => { window.historyFixture.recipient.restart(); location.hash = '#/create' })
  await page.locator('.handle-form input').waitFor()
  await click(page, 'recentTitle')
}
try {
  await scenario('states-locales-keyboard-pagination-back', async (page) => {
    await loaded(page)
    check('history entry focuses heading', await page.locator('h1').evaluate((el) => document.activeElement === el))
    const first = page.locator('summary').first()
    await first.focus(); await page.keyboard.press('Enter')
    check('keyboard opens native details', await first.evaluate((el) => el.parentElement.open))
    await click(page, 'historyMore')
    await page.locator('[data-history-key]').nth(10).waitFor()
    check('all 11 rows, no duplicate', await page.locator('[data-history-key]').count() === 11)
    await page.locator('details').evaluateAll((els) => els.forEach((el) => { el.open = true }))
    check('three eligible actions only', await (await button(page, 'historyCancel')).count() === 3)
    check('no row opens private content', await page.locator('.history-list a, .history-list img, canvas').count() === 0)
    for (const language of ['en', 'ru', 'uk']) for (const width of [320, 1280]) {
      await page.setViewportSize({ width, height: 900 })
      await page.evaluate((language) => window.sendFeedbackFixture.language(language), language)
      await page.screenshot({ path: join(proof, `states-${language}-${width}.png`), fullPage: true })
      check(`layout ${language}/${width}`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    }
    await page.locator('.mark').click()
    await page.locator('a[href="#/history"]').waitFor()
    await page.goBack()
    await loaded(page)
    check('browser back returns to real history', await page.locator('[data-testid=history-page]').count() === 1)
  })
  await scenario('copy-keep-cancel-v1', async (page) => {
    await loaded(page)
    await openRow(page, `1:${id(1)}`)
    await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (text) => { window.copiedCode = text } } }))
    await click(page, 'historyCopy')
    await page.getByText('Code copied.', { exact: true }).waitFor()
    check('copies only active public code', await page.evaluate(() => window.copiedCode === 'AB12Z'))
    await click(page, 'historyCancel')
    check('confirmation starts on non-destructive choice', await (await button(page, 'historyKeep')).evaluate((el) => document.activeElement === el))
    check('confirmation itself does not POST', posts().length === 0)
    await click(page, 'historyKeep')
    check('keep restores cancellation focus', await (await button(page, 'historyCancel')).evaluate((el) => document.activeElement === el))
    await click(page, 'historyCancel'); await click(page, 'historyConfirmCancel')
    await page.getByText('Cancellation confirmed. Refreshing the history.', { exact: true }).waitFor()
    await loaded(page)
    check('single existing revoke POST', posts().length === 1 && posts()[0].path.endsWith('/revoke'))
    const detail = await openRow(page, `1:${id(1)}`)
    check('confirmed revoke refetch retires code and action', await detail.locator('code, button').count() === 0)
  })
  await scenario('pending-cancel', async (page) => {
    await loaded(page); await click(page, 'historyMore')
    await page.locator(`[data-history-key="2:${id(6)}"]`).waitFor()
    await openRow(page, `2:${id(6)}`)
    await click(page, 'historyCancel'); await click(page, 'historyConfirmCancel')
    await page.getByText('Cancellation confirmed. Refreshing the history.', { exact: true }).waitFor()
    await loaded(page); await click(page, 'historyMore')
    const detail = await openRow(page, `2:${id(6)}`)
    check('pending uses actual v2 transport route', posts().length === 1 && posts()[0].path === `/v2/pending-deliveries/${id(106)}/cancel`)
    check('pending cancellation retired', await detail.locator('code, button').count() === 0)
  })
  for (const state of ['conflict', 'unknown']) await scenario(`cancel-${state}`, async (page) => {
    await loaded(page); await openRow(page, `1:${id(1)}`)
    mode = state
    await click(page, 'historyCancel'); await click(page, 'historyConfirmCancel')
    await page.locator('[data-testid=history-result]').filter({ hasText: state === 'conflict' ? 'no longer' : 'not confirmed' }).waitFor()
    await loaded(page)
    check(`${state}: exactly one POST then refetch`, posts().length === 1 && requests.filter((r) => r.path === '/v1/capsules/outgoing').length === 2)
  })
  await scenario('copy-denied-and-unavailable-retry-empty', async (page) => {
    await loaded(page); await openRow(page, `1:${id(1)}`)
    await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('synthetic refusal') } } }))
    await click(page, 'historyCopy')
    await page.getByText('Could not copy. You can select the code instead.', { exact: true }).waitFor()
    for (const failure of ['old-server', 'malformed']) {
      mode = failure; await click(page, 'historyRefresh')
      await page.getByText(await page.evaluate(() => window.sendFeedbackFixture.messages('en').historyUnavailable), { exact: true }).waitFor()
      check(`${failure}: not fabricated empty`, await page.locator('[data-testid=history-empty]').count() === 0)
      mode = 'normal'; await click(page, 'historyRetry'); await loaded(page)
    }
    mode = 'empty'; await click(page, 'historyRefresh')
    await page.locator('[data-testid=history-empty]').waitFor()
    check('real empty response displayed', await page.locator('[data-history-key]').count() === 0)
  })
  await scenario('hold-history', async (page) => {
    await page.getByText('Looking up your sends…', { exact: true }).waitFor()
    await page.evaluate(() => window.historyFixture.store.signOut())
    held.splice(0).forEach((release) => release())
    await page.getByText('Sign in to see your sending history.', { exact: true }).waitFor()
    check('late logout response cannot reveal rows', await page.locator('[data-history-key]').count() === 0)
    await page.evaluate(() => window.historyFixture.switchOwner())
    await page.locator('[data-testid=history-empty]').waitFor()
    check('new owner uses new bearer', requests.some((r) => r.token === 'Bearer synthetic-other'))
  })
  await scenario('owner-clears-loaded-and-late-cancel', async (page) => {
    await loaded(page); await openRow(page, `1:${id(1)}`)
    mode = 'hold-cancel'
    await click(page, 'historyCancel'); await click(page, 'historyConfirmCancel')
    await page.getByText('Requesting cancellation…', { exact: true }).waitFor()
    await page.evaluate(() => window.historyFixture.switchOwner())
    held.splice(0).forEach((release) => release())
    await page.locator('[data-testid=history-empty]').waitFor()
    check('owner retires rows, code and late result', await page.locator('[data-history-key], [data-testid=history-result], .history code').count() === 0)
  })
  await scenario('recent-fresh-lookup-and-confirm', async (page) => {
    await recents(page)
    await page.getByRole('button', { name: '@recipient_qa', exact: true }).click()
    await page.getByRole('button', { name: 'this is the person', exact: true }).waitFor()
    check('recent performs fresh directory GET', requests.some((r) => r.path === '/v1/directory/handles/recipient_qa'))
    check('recent not automatically confirmed', await page.evaluate(() => window.historyFixture.recipient.confirmed() === null))
    await page.getByRole('button', { name: 'this is the person', exact: true }).click()
    await page.locator('.photo-draft input').waitFor()
    check('normal composition remains reachable', await page.evaluate(() => window.historyFixture.recipient.confirmed()?.userId === '33333333-4444-4555-8666-777777777777'))
    check('selecting recent never sends', posts().length === 0)
  }, '')
  await scenario('recent-rebound-unavailable-empty', async (page) => {
    mode = 'old-server'; await recents(page)
    await page.getByText('Recent recipients are unavailable.', { exact: false }).waitFor()
    mode = 'empty'; await click(page, 'historyRetry')
    await page.getByText('No recent recipients available.', { exact: true }).waitFor()
    await page.evaluate(() => { location.hash = '#/' })
    await page.locator('a[href="#/history"]').waitFor()
    mode = 'rebound'; await recents(page)
    await page.getByRole('button', { name: '@recipient_qa', exact: true }).click()
    await page.getByText('This handle no longer matches that recipient.', { exact: false }).waitFor()
    check('rebound identity cannot be confirmed', await page.evaluate(() => window.historyFixture.recipient.pending() === null && window.historyFixture.recipient.confirmed() === null))
  }, '')
  await scenario('hold-recent', async (page) => {
    await recents(page)
    await page.getByText('Looking up recent recipients…', { exact: true }).waitFor()
    await page.evaluate(() => window.historyFixture.switchOwner())
    held.splice(0).forEach((release) => release())
    check('recent response cannot cross owner', await page.getByRole('button', { name: '@recipient_qa', exact: true }).count() === 0)
    check('recipient still unconfirmed', await page.evaluate(() => window.historyFixture.recipient.confirmed() === null))
  }, '')
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ synthetic: true, realBackend: false, executableSha256: host.executableSha256, checks, errors, visits }, null, 2))
  await host.close()
}
assert.deepEqual(errors, [])
assert.equal(checks.every((check) => check.ok), true, JSON.stringify(checks.filter((c) => !c.ok)))
