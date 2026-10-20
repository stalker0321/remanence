#!/usr/bin/env node
// T341 R2 — injected-error regression for the real browser runner.
//
// A green semantic run is not sufficient: error-bearing execution MUST hard-fail.
// This driver takes a NEW copy of the author's UPDATED runner and applies the
// SAME one-line `addInitScript(() => console.error(...))` fault injection used
// by the root probe, then asserts the copy exits non-zero and records the
// injected console error. The root's old injected copy is untouched.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error('usage: sideMatcherErrorInjectionCheck.mjs <webDir> <outDir>')
const web = resolve(webDir)
const runner = join(web, 'test', 'browser', 'sideMatcherCheck.mjs')
const source = await readFile(runner, 'utf8')

const marker = "await page.goto('http://127.0.0.1:4189/index.html')"
assert.ok(source.includes(marker), 'updated runner goto marker missing')
const injected = source.replace(
  marker,
  "await page.addInitScript(() => console.error('T341_SYNTHETIC_CONSOLE_FAILURE'))\n    " + marker,
)
assert.notEqual(injected, source, 'fault injection did not change the runner')

await mkdir(outDir, { recursive: true, mode: 0o750 })
const work = await mkdtemp(join(tmpdir(), 'side-matcher-injected-'))
const copy = join(work, 'sideMatcherInjectedConsoleCheck.mjs')
await writeFile(copy, injected)
await copyFile(copy, join(outDir, 'sideMatcherInjectedConsoleCheck.mjs'))

const run = spawnSync(process.execPath, [copy, web, join(outDir, 'run')], {
  stdio: 'pipe',
  encoding: 'utf8',
})
process.stdout.write(run.stdout ?? '')
process.stderr.write(run.stderr ?? '')

assert.notEqual(run.status, 0, 'injected console.error run must hard-fail (nonzero exit)')
const report = JSON.parse(await readFile(join(outDir, 'run', 'side-matcher-report.json'), 'utf8'))
assert.ok(
  report.console.some((entry) => entry.type === 'error' && entry.text.includes('T341_SYNTHETIC_CONSOLE_FAILURE')),
  'injected console error was not recorded',
)
assert.ok(
  report.pages?.index?.assertions?.every((item) => item.ok === true),
  'semantic assertions should still be green; only the harness error gate fails',
)
console.log(`side-matcher injected-console HARD-FAIL PASS (exit ${run.status})`)
