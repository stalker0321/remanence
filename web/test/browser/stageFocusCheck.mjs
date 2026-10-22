/** Actual browser DOM, actual production fallback helper, no rendered sheet. */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fixtureHost } from './fixtureHost.mjs'
const [web, proof] = process.argv.slice(2)
const host = await fixtureHost(web, proof, 'stageFocus.page.ts')
const errors = [], external = []
let checks = []
try {
  const page = await host.browser.newPage()
  page.on('pageerror', e => errors.push(String(e)))
  await page.route('**/*', r => { if (new URL(r.request().url()).origin === new URL(host.url).origin) return r.continue(); external.push(r.request().url()); return r.abort() })
  await page.goto(host.url)
  await page.waitForFunction(() => window.stageFocusResults)
  checks = await page.evaluate(() => window.stageFocusResults)
  assert.equal(checks.length, 5)
  assert.ok(checks.every(c => c.ok), JSON.stringify(checks))
  assert.deepEqual(errors, []); assert.deepEqual(external, [])
} finally {
  await host.close()
  await writeFile(join(proof, 'report.json'), JSON.stringify({ actualProductionDOMHelper: true, renderedSheet: false, admissionGrant: false, executableSha256: host.executableSha256, checks, errors, external, closedOwnedRuntime: true }, null, 2))
}
console.log(`stage focus DOM PASS: ${checks.length} checks`)
