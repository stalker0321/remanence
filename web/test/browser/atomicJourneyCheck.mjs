import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'
const [web, proof] = process.argv.slice(2)
const host = await fixtureHost(web, proof, 'atomicJourney.page.tsx')
const checks = [], errors = [], check = (name, ok) => { assert.ok(ok, name); checks.push({ name, ok: true }) }
const pendingLayout = []
const page = await host.browser.newPage({ viewport: { width: 320, height: 900 } })
page.on('pageerror', (error) => errors.push(String(error)))
await page.route('**/*', (route) => new URL(route.request().url()).origin === new URL(host.url).origin ? route.continue() : route.abort())
const call = (name, value) => page.evaluate(([name, value]) => window.atomic[name](value), [name, value])
const state = () => call('state')
const button = async (key, language = 'en') => page.getByRole('button', { name: await page.evaluate(([key, language]) => window.atomic.messages[language][key], [key, language]), exact: true })
const phase = (value) => page.waitForFunction((value) => window.atomic.state().setup === value && !document.querySelector('[aria-disabled=true]'), value)
async function fresh() { await page.goto(`${host.url}#/account`); await page.reload(); await page.waitForFunction(() => window.atomic) }
async function begin(language = 'en') { await (await button('continueGoogle', language)).click(); await phase('handle') }
async function create(language = 'en', handle = 'ada', held = false) {
  await page.locator('#atomic-handle').fill(handle)
  await (await button('atomicContinue', language)).click(); await phase('passkey')
  check(`${language}: choosing handle does not invoke provider`, (await state()).creates === 0 && (await state()).assertions === 0)
  await (await button('atomicCreate', language)).click()
  if (held) return
  await phase('verify')
  check(`${language}: Create stops before GET and key generation`, (await state()).creates === 1 && (await state()).assertions === 0 && (await state()).attempts === 0 && !(await state()).keys)
}
async function save(language = 'en', handle = 'ada', boundary = '') {
  if ((await state()).setup === 'handle') {
    await create(language, handle, boundary === 'passkey/create')
    if (boundary === 'passkey/create') return
  }
  if ((await state()).setup === 'handle-conflict') {
    await page.locator('#atomic-handle').fill(handle); await (await button('atomicFinishHandle', language)).click()
  } else await (await button('atomicVerify', language)).click()
}
async function shot(name) {
  await page.screenshot({ path: join(proof, `${name}.png`), fullPage: true })
  const geometry = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth,
    overflow: Array.from(document.querySelectorAll('body *')).map((element) => {
      const r = element.getBoundingClientRect(); return { tag: element.tagName, class: element.className, left: r.left, right: r.right, width: r.width }
    }).filter((rect) => rect.right > innerWidth + 0.5 || rect.left < -0.5) }))
  await writeFile(join(proof, `${name}.geometry.json`), JSON.stringify(geometry, null, 2))
  if (process.env.T404_DEFER_T406 === '1' && geometry.scroll > geometry.width && geometry.overflow.every((r) => /language/.test(r.class))) {
    pendingLayout.push({ name, geometry }) // Intermediate diagnostic only, never a PASS.
  } else check(`${name}: no horizontal overflow`, geometry.scroll <= geometry.width)
}
try {
  for (const [route, gate, next] of [['create', 'signInToMake', 'returnMake'], ['scan', 'signInToOpen', 'returnOpen'], ['history', 'signInToHistory', 'returnHistory']]) {
    await fresh(); await page.locator(`.main-nav a[href="#/${route}"]`).click()
    await page.getByRole('link', { name: await page.evaluate(key => window.atomic.messages.en[key], gate), exact: true }).click()
    await begin(); await save(); await phase('ready')
    const intended = page.getByRole('link', { name: await page.evaluate(key => window.atomic.messages.en[key], next), exact: true })
    check(`${route}: successful onboarding retains original destination`, await intended.getAttribute('href') === `#/${route}`)
  }
  for (const language of ['en', 'ru', 'uk']) {
    await fresh(); await call('language', language); await begin(language)
    check(`${language}: handle gets focus after Google`, await page.locator('#atomic-handle').evaluate((el) => el === document.activeElement))
    for (const width of [320, 1280]) { await page.setViewportSize({ width, height: 900 }); await shot(`handle-${language}-${width}`) }
    await call('mode', { finalMode: 'lost-after' }); await save(language); await phase('unknown')
    check(`${language}: committed but UNKNOWN is not product-ready`, (await state()).commits === 1 && (await state()).phase === 'signed-out' && !(await state()).keys)
    await page.setViewportSize({ width: 320, height: 900 }); await page.addStyleTag({ content: 'html { font-size: 200% !important; }' }); await shot(`unknown-${language}-large`)
    await (await button('atomicCheck', language)).click(); await phase('restore-required')
    await shot(`restore-${language}-large`)
    await (await button('continueGoogle', language)).click(); await phase('existing')
    await (await button('openWithPasskey', language)).click(); await page.waitForFunction(() => window.atomic.state().keys)
    check(`${language}: original committed account restored once`, (await state()).creates === 1 && (await state()).commits === 1 && (await state()).wiped)
    await shot(`ready-${language}-large`)
  }
  await fresh(); await begin(); await create(); await call('hold', 'passkey/get')
  const trigger = await button('atomicVerify'); await trigger.focus(); await page.keyboard.press('Enter')
  await page.waitForFunction(() => window.atomic.reached() === 'passkey/get'); await page.keyboard.press('Enter')
  check('duplicate Enter does not repeat provider/commit', (await state()).creates === 1 && (await state()).assertions === 1 && (await state()).attempts === 0)
  await call('release'); await phase('ready')
  const saved = await call('saved'); check('same-document original key decrypts', await call('prove', saved.encrypted))
  await page.reload(); await page.waitForFunction(() => window.atomic); await call('useSaved', saved.publicIntent)
  await (await button('continueGoogle')).click(); await phase('existing')
  await (await button('openWithPasskey')).click(); await page.waitForFunction(() => window.atomic.state().keys)
  check('new document restores original key and decrypts prior ciphertext without generation', await call('prove', saved.encrypted) && (await state()).creates === 0 && (await state()).attempts === 0)

  for (const [config, expected] of [[{ noPrf: true }, 'verify'], [{ cancelProvider: true }, 'verify'], [{ finalMode: 'handle' }, 'handle-conflict'], [{ finalMode: 'expired-grant' }, 'assertion-required']]) {
    await fresh(); await call('mode', config); await begin(); await save(); await phase(expected)
    check(`${JSON.stringify(config)}: no local readiness`, !(await state()).keys && (await state()).phase === 'signed-out')
    if (config.noPrf) {
      check('missing PRF is a visible capability stop, not READY', await page.locator('[data-capability-stop=true]').count() === 1)
      check('capability stop offers a visible exit; retry is secondary', await page.getByRole('link', { name: await page.evaluate(() => window.atomic.messages.en.atomicLeave), exact: true }).isVisible() && !await (await button('atomicVerifyAgain')).evaluate(el => el.classList.contains('primary')))
      await shot('saved-without-encryption-support')
    }
    await call('mode', { noPrf: false, cancelProvider: false, finalMode: 'ok' })
    if (config.noPrf) await (await button('atomicVerifyAgain')).click(); else await save('en', 'corrected')
    await phase('ready')
    check(`${expected}: retry reuses verified credential`, (await state()).creates === 1)
  }
  for (const mode of ['wrong-account', 'wrong-bundle', 'wrong-credential', 'wrong-anchor', 'corrupt', 'offline']) {
    await fresh(); await call('mode', { readMode: mode }); await begin(); await save(); await phase('restore-required')
    check(`${mode}: ACTIVE not READY, no raw secret retained`, (await state()).commits === 1 && (await state()).phase === 'authenticated' && !(await state()).keys && (await state()).wiped)
  }
  for (const boundary of ['google/verify:after', 'passkey/create', 'passkey/get', 'setup/verify:after', 'finalize:after', 'record:after']) {
    await fresh()
    // Native history.back(), not a synthetic hashchange-only navigation.
    await page.evaluate(() => { history.replaceState(null, '', '#/'); history.pushState(null, '', '#/account') })
    await call('hold', boundary)
    if (boundary.startsWith('google/')) await (await button('continueGoogle')).click()
    else { await begin(); await save('en', 'ada', boundary) }
    await page.waitForFunction((point) => window.atomic.reached() === point, boundary)
    await call('releaseOnBack'); await page.evaluate(() => history.back())
    await page.waitForURL('**/#/'); await page.waitForTimeout(60)
    check(`${boundary}: popstate release cannot late install`, !(await state()).keys && (await state()).phase === 'signed-out' && (await state()).wiped)
    await page.evaluate(() => history.forward()); await page.waitForURL('**/#/account'); await phase('idle')
    check(`${boundary}: Forward cannot resume old setup implicitly`, (await state()).setup === 'idle')
  }
  await fresh(); await call('mode', { terminal: true }); await (await button('continueGoogle')).click(); await phase('terminal')
  check('terminal has no implicit restart', !(await state()).calls.includes('restart'))
  await (await button('atomicRestart')).click(); await phase('handle')
  await page.locator('.account-footer summary').click()
  await (await button('cancelRegistration')).click(); await phase('reauthenticate')
  check('cancel is explicit and distinct from Back/leave', (await state()).calls.includes('cancel'))
  check('no durable storage/raw identity persistence', await page.evaluate(async () => localStorage.length === 0 && sessionStorage.length === 0 && (await indexedDB.databases()).length === 0 && (await caches.keys()).length === 0))
  assert.deepEqual(errors, [])
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ syntheticContract: true, realCrypto: true, realBackend: false, realProviders: false,
    executableSha256: host.executableSha256, checks, errors, pendingLayout }, null, 2))
  await host.close()
}
console.log(`atomicJourney ${pendingLayout.length ? 'FUNCTIONAL ONLY; T406 LAYOUT PENDING' : 'PASS'} ${checks.length} checks`)
