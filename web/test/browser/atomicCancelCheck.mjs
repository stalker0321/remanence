// T410 actual App, unchanged production controller/UI/crypto; synthetic v1 HTTP/provider model.
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'
const [web, proof] = process.argv.slice(2)
const host = await fixtureHost(web, proof, 'atomicJourney.page.tsx')
const checks = [], errors = [], images = []
const check = (name, ok) => { assert.ok(ok, name); checks.push({ name, ok: true }) }
const page = await host.browser.newPage({ viewport: { width: 320, height: 900 } })
page.on('pageerror', error => errors.push(String(error)))
await page.route('**/*', route => new URL(route.request().url()).origin === new URL(host.url).origin ? route.continue() : route.abort())
const call = (name, value) => page.evaluate(([name, value]) => window.atomic[name](value), [name, value])
const state = () => call('state')
const phase = value => page.waitForFunction(value => window.atomic.state().setup === value && !document.querySelector('[aria-disabled=true]'), value)
const button = async (key, lang = 'en') => page.getByRole('button', { name: await page.evaluate(([key, lang]) => window.atomic.messages[lang][key], [key, lang]), exact: true })
async function fresh() { await page.goto(`${host.url}#/account`); await page.reload(); await page.waitForFunction(() => window.atomic) }
async function candidate(lang = 'en') {
  await call('mode', { finalMode: 'handle' }); await (await button('continueGoogle', lang)).click(); await phase('handle')
  await page.locator('#atomic-handle').fill('taken'); await (await button('atomicContinue', lang)).click(); await phase('passkey')
  await (await button('atomicCreate', lang)).click(); await phase('verify')
  await (await button('atomicVerify', lang)).click(); await phase('handle-conflict')
  await page.locator('.account-footer summary').click()
}
async function shot(name) {
  await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true }); images.push(name)
  check(`${name}: no horizontal clipping`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
}
let complete = false
try {
  for (const lang of ['en', 'ru', 'uk']) {
    await fresh(); await call('language', lang); await candidate(lang)
    const old = (await state()).reservationId
    await call('mode', { terminal: true }); await (await button('cancelRegistration', lang)).click(); await phase('reauthenticate')
    const after = await state()
    check(`${lang}: expired cancel410 wipes candidate, no READY or implicit restart`, after.wiped && !after.keys && after.phase === 'signed-out' && after.commits === 0 && after.creates === 1 && !after.calls.includes('restart'))
    check(`${lang}: no regeneration/restart action before fresh ownership`, await page.locator('#atomic-handle').count() === 0 && await (await button('atomicRestart', lang)).count() === 0)
    check(`${lang}: honest existing fresh-proof copy and heading focus`, await page.getByText(await page.evaluate(lang => window.atomic.messages[lang].atomicReauthenticate, lang), { exact: true }).count() === 1 && await page.locator('.atomic-account h1').evaluate(el => el === document.activeElement))
    await shot(`expired-cancel-${lang}-320`)
    await page.addStyleTag({ content: 'html { font-size: 200% !important; }' }); await shot(`expired-cancel-${lang}-large`)
    await (await button('continueGoogle', lang)).click(); await phase('terminal')
    check(`${lang}: fresh proof resolves SAME terminal setup, not replacement`, (await state()).reservationId === old && !(await state()).calls.includes('restart'))
    await (await button('atomicRestart', lang)).click(); await phase('handle')
    check(`${lang}: only explicit restart creates successor, no new provider/key operation`, (await state()).reservationId !== old && (await state()).creates === 1 && (await state()).attempts === 1)
  }
  for (const expired of [false, true]) {
    await fresh(); await page.evaluate(() => { history.replaceState(null, '', '#/'); history.pushState(null, '', '#/account') })
    await candidate(); if (expired) await call('mode', { terminal: true })
    await call('hold', 'cancel:after'); await (await button('cancelRegistration')).click()
    await page.waitForFunction(() => window.atomic.reached() === 'cancel:after')
    await call('releaseOnBack'); await page.evaluate(() => history.back()); await page.waitForURL('**/#/'); await page.waitForTimeout(60)
    check(`cancel${expired ? 410 : 200}: native Back fences late reply`, !(await state()).keys && (await state()).phase === 'signed-out' && (await state()).wiped && (await state()).commits === 0)
    await page.evaluate(() => history.forward()); await page.waitForURL('**/#/account'); await phase('idle')
    check(`cancel${expired ? 410 : 200}: Forward does not resume/restart abandoned setup`, !(await state()).calls.includes('restart') && (await state()).creates === 1)
  }
  check('no durable private/setup persistence', await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await indexedDB.databases()).length === 0 && (await caches.keys()).length === 0))
  assert.deepEqual(errors, []); complete = true
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ complete, source: process.env.T410_TARGET ?? 'unspecified', syntheticContract: true,
    realApp: true, realCrypto: true, realApi: false, realProviders: false, executableSha256: host.executableSha256, checks, errors, images }, null, 2))
  await host.close()
}
console.log(`atomicCancel PASS ${checks.length} checks`)
