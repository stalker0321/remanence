import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
const [web, proof] = process.argv.slice(2)
const { fixtureHost } = await import(pathToFileURL(join(web, 'test/browser/fixtureHost.mjs')))
const host = await fixtureHost(web, proof, 'candidateUsability.page.tsx')
const checks = [], errors = [], images = []
let complete = false, activePage = null, failure = null
const check = (name, ok) => { assert.ok(ok, name); checks.push({ name, ok: true }) }
try {
  for (const lang of ['en', 'ru', 'uk']) {
    const page = await host.browser.newPage({ viewport: { width: 320, height: 700 } })
    activePage = page
    page.on('pageerror', error => errors.push(String(error)))
    await page.route('**/*', route => new URL(route.request().url()).origin === new URL(host.url).origin ? route.continue() : route.abort())
    await page.goto(`${host.url}?lang=${lang}#/`)
    await page.waitForFunction(() => window.candidateUI)
    const state = () => page.evaluate(() => window.candidateUI.state())
    const text = key => page.evaluate(([lang, key]) => window.candidateUI.catalog[lang][key], [lang, key])
    const button = async key => page.getByRole('button', { name: await text(key), exact: true })
    const shot = async name => { const file = `${lang}-${name}.png`; await page.screenshot({ path: join(proof, file), fullPage: true }); images.push(file) }
    await page.locator('a[href="#/scan"]').click()
    await (await button('useCamera')).waitFor()
    check(`${lang}: receive entry does not start camera automatically`, (await state()).cameraStarts === 0)
    await (await button('useCamera')).click(); await page.locator('video.preview').waitFor()
    await shot('receive')
    await page.evaluate(() => history.back()); await page.waitForURL(url => url.hash === '#/')
    await page.waitForFunction(() => window.candidateUI.state().cameraStops === 1)
    check(`${lang}: native Back closes owned preview`, (await state()).cameraStarts === 1 && await page.locator('video.preview').count() === 0)
    await page.evaluate(() => history.forward()); await page.waitForURL(url => url.hash === '#/scan')
    await (await button('useCamera')).waitFor()
    check(`${lang}: Forward is idle, not capture/resume`, (await state()).cameraStarts === 1)
    await page.locator('a[href="#/"]').click()
    await page.locator('a[href="#/create"]').click()
    check(`${lang}: signed-out Create directs to Account`, await page.locator('a[href="#/account"]').count() === 1 && await page.locator('.photo-draft').count() === 0)
    await page.evaluate(() => window.candidateUI.unlock())
    const handle = page.locator('form.handle-form input')
    await handle.fill('missing_qa'); await page.locator('form.handle-form button[type=submit]').click()
    await page.getByText(await text('recipientNotFound'), { exact: true }).waitFor()
    check(`${lang}: missing recipient is honest/unconfirmed`, (await state()).confirmed === null)
    await handle.fill('friend_qa'); await page.locator('form.handle-form button[type=submit]').click()
    await (await button('recipientConfirm')).click()
    await page.locator('.photo-draft input[type=file]').waitFor()
    check(`${lang}: explicit confirmation selects intended recipient`, (await state()).confirmed === 'friend_qa')
    await page.evaluate(() => window.candidateUI.pick())
    await page.waitForFunction(() => window.candidateUI.state().photos === 3 && document.querySelector('[data-testid="creator-canvas"]')?.getAttribute('data-painted') === 'ready', undefined, { timeout: 60000 })
    check(`${lang}: real photo input/normalizer paints composer`, (await state()).photos === 3)
    const music = page.locator('.music-draft input')
    await music.fill('empty'); await page.getByText(await text('musicNone'), { exact: true }).waitFor()
    check(`${lang}: empty music has no selectable hit`, await page.locator('.music-draft li').count() === 0)
    await music.fill('down'); await page.getByText(await text('musicUnavailable'), { exact: true }).waitFor()
    await music.fill('shore'); await page.locator('.music-draft li button').click()
    check(`${lang}: real music search/select remains local metadata`, (await state()).music !== null)
    await shot('composer-music')
    await (await button('musicRemove')).click()
    check(`${lang}: music remove preserves normalized photos`, (await state()).music === null && (await state()).photos === 3)
    const calls = (await state()).calls.length
    await music.fill('x'.repeat(101)); await page.waitForTimeout(400)
    check(`${lang}: overlong music query never dispatches`, (await state()).calls.length === calls)
    await music.fill('slow'); await page.waitForFunction(() => window.candidateUI.state().calls.some(c => c.query?.q === 'slow'))
    check(`${lang}: late-response test starts with an empty pending search`, (await state()).hits === 0)
    const event = lang === 'ru' ? 'hashchange' : 'popstate'
    await page.evaluate(event => window.candidateUI.releaseOnNavigation(event), event)
    if (event === 'popstate') await page.evaluate(() => history.back())
    else await page.locator('a[href="#/"]').click()
    await page.waitForURL(url => url.hash === '#/')
    await page.waitForTimeout(50)
    check(`${lang}: leaving Create fences late music response`, (await state()).hits === 0)
    check(`${lang}: navigation aborts the request before its released reply`, (await state()).musicReplies.at(-1)?.aborted === true)
    await page.locator('a[href="#/create"]').click()
    await page.locator('.music-draft input').fill('slow')
    await page.waitForFunction(() => window.candidateUI.state().calls.filter(c => c.query?.q === 'slow').length === 2)
    await page.evaluate(() => { window.candidateUI.releaseOnNavigation('hashchange'); location.hash = '#/create?panel=music' })
    await page.waitForFunction(() => window.candidateUI.state().hits === 1)
    check(`${lang}: same-route hash keeps legitimate search alive`, (await state()).musicReplies.at(-1)?.aborted === false)
    await page.locator('a[href="#/"]').click()
    await page.locator('a[href="#/settings"]').click()
    await page.locator('.settings-group .language-trigger').click()
    await page.getByRole('option', { name: 'English', exact: true }).click()
    check(`${lang}: settings language selection uses existing preference only`, (await state()).preferences.some(([key, value]) => key === 'remanence.language' && value === 'en'))
    await page.evaluate(() => window.candidateUI.lock())
    check(`${lang}: lock wipes in-memory drafts`, (await state()).photos === 0 && (await state()).music === null && (await state()).confirmed === null)
    check(`${lang}: no provider/capsule/auth/storage mutation`, (await state()).forbidden.length === 0 && (await state()).calls.every(c => c.method === 'GET'))
    check(`${lang}: no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await page.close()
  }
  assert.deepEqual(errors, []); complete = true
} catch (error) {
  failure = { error: String(error.stack || error), state: await activePage?.evaluate(() => window.candidateUI.state()).catch(() => null) }
  await activePage?.screenshot({ path: join(proof, 'failure.png'), fullPage: true }).catch(() => {})
  throw error
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ complete, synthetic: true, actualApp: true, realAPI: false,
    cameraDevice: false, capsuleAdmission: false, executableSha256: host.executableSha256, checks, errors, images, failure }, null, 2))
  await host.close()
}
console.log(`candidate usability PASS ${checks.length} checks`)
