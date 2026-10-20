import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'

const [web, proof] = process.argv.slice(2)
const host = await fixtureHost(web, proof, 'presentationNavigation.page.tsx')
const checks = []
const errors = []
const facts = []
async function admit(page) {
  await page.evaluate(() => window.presentationNavigation.admit())
  await page.waitForFunction(() => document.querySelector('.stage canvas')?.dataset.painted === 'ready')
}
const pause = (page) => page.waitForTimeout(150)
const state = (page) => page.evaluate(() => window.presentationNavigation.state())
async function record(page, label) {
  facts.push({ label, width: page.viewportSize().width, ...(await page.evaluate(() => ({
    ...window.presentationNavigation.state(), hash: location.hash, historyLength: history.length,
    active: document.activeElement?.outerHTML, stage: document.querySelector('.stage') !== null,
    viewer: document.querySelector('.viewer') !== null,
  }))) })
}
async function back(page) { await page.goBack(); await pause(page) }
async function forward(page) { await page.goForward(); await pause(page) }
async function scenario(name, width, run, direct = false, liveCamera = false) {
  const page = await host.browser.newPage({ viewport: { width, height: width === 320 ? 568 : 720 } })
  page.on('pageerror', (error) => errors.push({ name, error: String(error) }))
  try {
    await page.goto(`${host.url}#/${direct ? 'scan' : ''}`)
    await page.waitForFunction(() => Boolean(window.presentationNavigation))
    if (!direct) await page.locator('a[href="#/scan"]').click()
    if (liveCamera) {
      await page.locator('.card .primary').click()
      await page.locator('.card video').waitFor({ state: 'attached' })
    }
    await page.locator('.card .primary').focus()
    await admit(page)
    await run(page)
    checks.push({ name, width, ok: true })
  } catch (error) { checks.push({ name, width, ok: false, error: String(error) }) }
  await page.screenshot({ path: join(proof, `${name}-${width}.png`) })
  await writeFile(join(proof, `${name}-${width}.html`), await page.content())
  await page.close()
}
try {
  for (const width of [320, 1280]) {
    await scenario('back-photo-sheet-scan-forward', width, async (page) => {
      await page.locator('.stage .hit').first().click()
      await page.locator('.viewer').waitFor()
      await record(page, 'photo-open')
      await back(page)
      assert.equal(await page.locator('.viewer').count(), 0)
      assert.equal(await page.locator('.stage').count(), 1)
      assert.equal(new URL(page.url()).hash, '#/scan')
      await forward(page)
      assert.equal(await page.locator('.viewer').count(), 1)
      await back(page)
      assert.equal(await page.evaluate(() => document.activeElement?.classList.contains('hit')), true, 'Forward-reopened viewer restores focus on Back')
      await record(page, 'forward-photo-closed')
      await back(page)
      assert.equal(await page.locator('.stage').count(), 0)
      assert.equal(new URL(page.url()).hash, '#/scan')
      const closed = await state(page)
      assert.equal(closed.held, false)
      assert.equal(closed.phase, 'authenticated')
      assert.deepEqual(closed.revoked, closed.created)
      await record(page, 'sheet-closed-and-released')
      await forward(page)
      assert.equal(await page.locator('.stage').count(), 0, 'Forward cannot resurrect released content')
      await back(page)
      assert.equal(new URL(page.url()).hash, '#/')
    })
    await scenario('stage-keyboard', width, async (page) => {
      assert.equal(await page.evaluate(() => Boolean(document.activeElement?.closest('.stage'))), true)
      assert.equal(await page.locator('.stage[role=dialog][aria-modal=true]').count(), 1)
      for (let i = 0; i < 12; i++) {
        await page.keyboard.press(i < 6 ? 'Tab' : 'Shift+Tab')
        assert.equal(await page.evaluate(() => Boolean(document.activeElement?.closest('.stage'))), true)
      }
      assert.equal(await page.locator('main.shell').getAttribute('inert'), '')
      await record(page, 'stage-keyboard-contained')
      await page.screenshot({ path: join(proof, `stage-focus-${width}.png`) })
      await writeFile(join(proof, `stage-focus-${width}.html`), await page.content())
      await page.locator('.stage .hit').first().click()
      await page.keyboard.press('Escape')
      await pause(page)
      assert.equal(await page.locator('.viewer').count(), 0)
      assert.equal(await page.locator('.stage').count(), 1)
      assert.equal(await page.evaluate(() => document.activeElement?.classList.contains('hit')), true)
      await page.keyboard.press('Escape')
      await pause(page)
      assert.equal(await page.locator('.stage').count(), 0)
      assert.equal(await page.evaluate(() => Boolean(document.activeElement?.closest('.card'))), true)
      assert.equal(await page.locator('main.shell').getAttribute('inert'), null)
    })
    await scenario('explicit-close-reopen-no-extra-back', width, async (page) => {
      await page.locator('.stage .hit').last().click()
      await page.locator('.viewer button.primary').click()
      await pause(page)
      await page.locator('.stage-chrome button').last().click()
      await pause(page)
      assert.equal(await page.locator('.stage').count(), 0)
      await admit(page)
      await back(page)
      assert.equal(await page.locator('.stage').count(), 0)
      await back(page)
      assert.equal(new URL(page.url()).hash, '#/')
    })
    await scenario('direct-scan-lock-from-photo', width, async (page) => {
      await page.locator('.stage .hit').first().click()
      await page.evaluate(() => window.presentationNavigation.lock())
      await pause(page)
      assert.equal(await page.locator('.stage').count(), 0)
      assert.equal(new URL(page.url()).hash, '#/scan')
      await forward(page)
      assert.equal((await state(page)).held, false)
      await admit(page)
      await page.locator('.stage .hit').last().click()
      await page.evaluate(() => window.presentationNavigation.switchAccount())
      await pause(page)
      assert.equal((await state(page)).phase, 'keys-unlocked', 'old navigation must not lock the replacement account')
      assert.equal(await page.locator('.stage').count(), 0)
    }, true)
    await scenario('removed-trigger-focus-return', width, async (page) => {
      await page.keyboard.press('Escape')
      await pause(page)
      assert.equal(await page.locator('.stage').count(), 0)
      assert.equal(await page.locator('.card video').count(), 0)
      assert.equal(await page.evaluate(() => document.activeElement === document.querySelector('.card .primary')), true)
      await record(page, 'removed-camera-trigger-fallback')
    }, false, true)
    await scenario('reload-discards-stale-presentation-history', width, async (page) => {
      await page.locator('.stage .hit').first().click()
      await page.reload()
      await page.waitForFunction(() => Boolean(window.presentationNavigation))
      await pause(page)
      assert.equal(await page.locator('.stage').count(), 0)
      assert.equal(new URL(page.url()).hash, '#/scan')
      await back(page)
      assert.equal(new URL(page.url()).hash, '#/')
    })
  }
} finally {
  await writeFile(join(proof, 'report.json'), JSON.stringify({ syntheticAdmittedContent: true, executableSha256: host.executableSha256, checks, facts, errors }, null, 2))
  await host.close()
}
assert.deepEqual(errors, [])
assert.equal(checks.every((check) => check.ok), true, JSON.stringify(checks))
console.log(`presentation-navigation PASS: ${checks.length} scenarios`)
