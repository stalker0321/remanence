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
    const page = await host.browser.newPage({ viewport: { width: 390, height: 760 } })
    activePage = page
    page.on('pageerror', error => errors.push(String(error)))
    await page.route('**/*', route => new URL(route.request().url()).origin === new URL(host.url).origin ? route.continue() : route.abort())
    await page.goto(`${host.url}?lang=${lang}#/scan`)
    await page.waitForFunction(() => window.candidateUI)
    const text = key => page.evaluate(([lang, key]) => window.candidateUI.catalog[lang][key], [lang, key])
    const button = async key => page.getByRole('button', { name: await text(key), exact: true })
    const state = () => page.evaluate(() => window.candidateUI.state())
    const mode = value => page.evaluate(value => window.candidateUI.setCameraMode(value), value)
    const shot = async name => {
      await page.evaluate(() => document.fonts.ready)
      for (const fullPage of [false, true]) {
        const file = `${lang}-${name}${fullPage ? '-full' : '-viewport'}.png`
        await page.screenshot({ path: join(proof, file), fullPage }); images.push(file)
      }
    }
    const recover = async (route, kind, enlarged = false) => {
      const label = `${lang}/${route}/${kind}/${enlarged ? 'text200' : 'normal'}`
      await mode(kind)
      const before = (await state()).cameraAttempts
      await (await button('useCamera')).click()
      const feedback = page.locator('.camera-feedback')
      await feedback.waitFor()
      await page.waitForFunction(() => document.activeElement?.classList.contains('camera-feedback'))
      const metrics = await feedback.evaluate(el => {
        const box = el.getBoundingClientRect(), title = el.firstElementChild.getBoundingClientRect()
        return { focused: el === document.activeElement, top: box.top, bottom: box.bottom, titleBottom: title.bottom, height: innerHeight,
          overflow: document.documentElement.scrollWidth > innerWidth,
          textWidth: el.firstElementChild.getBoundingClientRect().width, textSize: parseFloat(getComputedStyle(el.firstElementChild).fontSize),
          nearControls: el.nextElementSibling?.classList.contains('actions'),
          beforeAccount: !el.parentElement.querySelector('.scan-account') || !!(el.compareDocumentPosition(el.parentElement.querySelector('.scan-account')) & Node.DOCUMENT_POSITION_FOLLOWING) }
      })
      check(`${label}: failure focused and headline in viewport`, metrics.focused && metrics.top >= 0 && metrics.titleBottom <= metrics.height)
      check(`${label}: feedback at initiating controls, before identity gate`, metrics.nearControls && metrics.beforeAccount)
      check(`${label}: no horizontal overflow`, !metrics.overflow)
      check(`${label}: readable feedback text measure`, metrics.textWidth >= Math.min(250, metrics.textSize * 7))
      check(`${label}: exact truthful recovery, not guessed dismissal`, (await feedback.textContent()).includes(await text(kind === 'denied' || kind === 'blocked' ? 'cameraDeniedHelp' : 'cameraUnavailableHelp')) && !(await feedback.textContent()).includes('dismissed'))
      check(`${label}: one explicit attempt; no camera or global duplicate`, (await state()).cameraAttempts === before + 1 && await page.locator('video.preview').count() === 0 && await page.locator('main > .notice').count() === 0)
      await shot(`${route}-${kind}${enlarged ? '-text200' : ''}`)
    }
    for (const route of ['scan', 'create']) {
      if (route === 'create') {
        await page.locator('.main-nav a[href="#/create"]').click()
        await page.evaluate(() => window.candidateUI.unlock())
        await page.locator('form.handle-form input').fill('friend_qa')
        await page.locator('form.handle-form button[type=submit]').click()
        await (await button('recipientConfirm')).click()
        await page.locator('.content-workspace textarea').fill('A moment by the sea.')
        await page.evaluate(() => window.candidateUI.pick())
        await page.waitForFunction(() => window.candidateUI.state().photos === 3 && document.querySelector('[data-testid="creator-canvas"]')?.getAttribute('data-painted') === 'ready', undefined, { timeout: 60000 })
        await (await button('createChooseComposition')).click()
        await page.locator('[data-testid=creator-freeze]').click()
        await page.locator('.postcard-workspace').waitFor()
      }
      for (const kind of ['denied', 'blocked', 'unavailable', 'missing']) await recover(route, kind)
      await page.setViewportSize({ width: 360, height: 700 })
      await page.evaluate(() => { document.documentElement.style.fontSize = '36px' })
      await recover(route, 'denied', true)
      await page.evaluate(() => { document.documentElement.style.fontSize = '' })
      await page.setViewportSize({ width: 390, height: 760 })
      await mode('ready'); await (await button('useCamera')).click(); await page.locator('video.preview').waitFor()
      check(`${lang}/${route}: explicit retry clears failure and opens one preview`, await page.locator('.camera-feedback').count() === 0)
      await (await button('closeCamera')).click()
      await mode('pending-denied'); await (await button('useCamera')).click()
      await page.locator('.mark').click(); await page.waitForURL(url => url.hash === '#/')
      await page.evaluate(() => window.candidateUI.rejectCamera())
      await page.waitForTimeout(60)
      check(`${lang}/${route}: stale denial cannot steal focus or publish on Home`, await page.locator('.camera-feedback').count() === 0 && await page.locator('.home-intro h1').evaluate(el => el === document.activeElement))
    }
    check(`${lang}: no auth/publication/admission mutation`, (await state()).forbidden.length === 0 && (await state()).calls.every(c => c.method === 'GET'))
    await page.close()
  }
  assert.deepEqual(errors, []); complete = true
} catch (error) {
  failure = String(error.stack || error)
  await activePage?.screenshot({ path: join(proof, 'failure.png'), fullPage: true }).catch(() => {})
  throw error
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ complete, actualApp: true, actualBrowserCameraAdapter: true, syntheticMediaDevices: true,
    realCameraOrProvider: false, executableSha256: host.executableSha256, checks, errors, images, failure }, null, 2))
  await host.close()
}
console.log(`camera recovery PASS ${checks.length} checks`)
