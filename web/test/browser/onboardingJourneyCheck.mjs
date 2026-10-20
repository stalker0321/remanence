import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'
const [web, proof] = process.argv.slice(2)
const host = await fixtureHost(web, proof, 'onboardingJourney.page.tsx')
const checks = [], errors = [], check = (name, ok) => { assert.ok(ok, name); checks.push({ name, ok: true }) }
const page = await host.browser.newPage({ viewport: { width: 320, height: 900 } })
page.on('pageerror', (e) => errors.push(String(e)))
await page.route('**/*', async (route) => new URL(route.request().url()).origin === new URL(host.url).origin ? route.continue() : route.abort())
const call = (name, value) => page.evaluate(([name, value]) => window.journey[name](value), [name, value])
const state = () => call('state')
const button = async (key, language = 'en') => page.getByRole('button', { name: await page.evaluate(([key, lang]) => window.journey.messages[lang][key], [key, language]), exact: true })
const notice = async (key) => page.getByRole('alert').filter({ hasText: await page.evaluate((key) => window.journey.messages.en[key], key) }).waitFor()
async function sign() { await (await button('continueGoogle')).click(); await page.waitForFunction(() => window.journey.state().phase === 'authenticated') }
async function open() { await (await button('openWithPasskey')).click(); await page.waitForFunction(() => window.journey.state().keys) }
async function shot(name) {
  check(`${name} no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true })
}
try {
  await page.goto(`${host.url}#/account`); await page.waitForFunction(() => window.journey)
  for (const language of ['en', 'ru', 'uk']) {
    await call('language', language); await call('mode', 'new')
    await (await button('continueGoogle', language)).click()
    await page.getByText(await page.evaluate((lang) => window.journey.messages[lang].registrationPaused, language), { exact: true }).first().waitFor()
    check(`${language} gated without handle/private registration`, (await state()).phase === 'signed-out' && await page.locator('input').count() === 0)
    for (const width of [320, 1280]) { await page.setViewportSize({ width, height: 900 }); await shot(`gate-${language}-${width}`) }
    await (await button('cancelRegistration', language)).click()
  }
  check('no registration or recovery write from gate', await page.evaluate(() => !window.journey.calls.some((p) => /register|recovery/.test(p))))
  await call('language', 'en'); await call('mode', 'existing'); await sign()
  const saved = await call('saved')
  await open()
  check('actual App restores original private identity', await call('prove', saved.challenge))
  check('PRF cleared after unlock', await call('wiped'))
  await page.reload(); await page.waitForFunction(() => window.journey)
  await call('useSaved', saved.reply); await sign(); await open()
  check('reload existing-account restore opens original challenge', await call('prove', saved.challenge))
  for (const [mode, key] of [['missing', 'identityContinuityMissing'], ['pending', 'recoveryPending'], ['cancelled', 'passkeyCancelled'], ['no-prf', 'prfUnavailable'], ['wrong-record', 'wrongPasskey'], ['offline', 'authFailed']]) {
    await call('lock'); await call('mode', mode)
    await (await button('openWithPasskey')).click()
    await notice(key)
    await page.waitForFunction(() => document.querySelector('[aria-disabled=true]') === null)
    // Key names are audited against the production explanation, not translated guesses.
    check(`${mode} never unlocks or generates identity`, !(await state()).keys && (await state()).phase === 'authenticated')
    check(`${mode} has a nonempty honest alert`, (await page.getByRole('alert').innerText()).length > 10)
    if (mode === 'missing') {
      for (const language of ['en', 'ru', 'uk']) {
        await call('language', language)
        for (const width of [320, 1280]) { await page.setViewportSize({ width, height: 900 }); await shot(`missing-${language}-${width}`) }
      }
      await call('language', 'en')
    }
  }
  await call('mode', 'existing')
  for (const [boundary, interruption] of [['options','cancel'], ['passkey','logout'], ['record','replace'], ['record','back']]) {
    await call('logout'); await page.evaluate(() => { location.hash = '#/account' }); await sign()
    await call('hold', boundary)
    const trigger = await button('openWithPasskey'); await trigger.focus(); await page.keyboard.press('Enter')
    await page.waitForFunction((point) => window.journey.reached() === point, boundary)
    check(`${boundary}: focus retained while busy`, await trigger.evaluate((el) => el === document.activeElement))
    await page.keyboard.press('Enter')
    if (interruption === 'cancel') {
      await (await button('accountCancel')).click()
      check('cancel returns keyboard focus to retry', await trigger.evaluate((el) => el === document.activeElement))
    } else if (interruption === 'back') { await page.getByRole('link', { name: 'back', exact: true }).click() }
    else await call(interruption)
    await call('release')
    await page.waitForTimeout(100)
    check(`${boundary}/${interruption}: no stale unlock`, !(await state()).keys)
    if (interruption === 'replace') check('replacement owner preserved', (await state()).accountId.startsWith('cccc'))
  }
  await call('logout'); await page.evaluate(() => { location.hash = '#/account' }); await call('hold', 'google-verify')
  await (await button('continueGoogle')).click(); await page.waitForFunction(() => window.journey.reached() === 'google-verify')
  await (await button('accountCancel')).click(); await call('release'); await page.waitForTimeout(100)
  check('cancelled Google cannot late sign in', (await state()).phase === 'signed-out')
  check('no identity/record writes throughout existing-account journey', await page.evaluate(() => !window.journey.calls.some((p) => /register|POST \/v1\/recovery\/record/.test(p))))
  check('no durable private browser storage', await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await indexedDB.databases()).length === 0))
  assert.deepEqual(errors, [])
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ synthetic: true, realBackend: false, realProviders: false,
    executableSha256: host.executableSha256, checks, errors }, null, 2))
  await host.close()
}
console.log(`onboardingJourney PASS ${checks.length} checks`)
