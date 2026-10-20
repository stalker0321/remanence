#!/usr/bin/env node
// Reproducible brand-icon rasterizer. Uses the EXISTING pinned Chromium and
// Playwright tooling only (no new dependency, AI asset, or font/logo redesign).
// Source: web/public/favicon.svg (authored from the Android
// remainder_foreground.xml paths/transform). Output: 192/512 "any" PNGs and
// one 512 "maskable" PNG, all on the opaque hold_paper background.
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { mkdir, readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')

const web = resolve(process.argv[2] ?? '.')
const executable =
  '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const expected = '4fa61f0ba0b4f89b7bec242c32ca16d3bccf69dbfef0651924a9ba8fede5f9d6'
const actual = createHash('sha256').update(await readFile(executable)).digest('hex')
if (actual !== expected) throw new Error(`pinned Chromium hash changed: ${actual}`)

const svg = await readFile(join(web, 'public', 'favicon.svg'), 'utf8')
const out = join(web, 'public', 'icons')
await mkdir(out, { recursive: true })

const targets = [
  ['icon-192.png', 192],
  ['icon-512.png', 512],
  ['icon-512-maskable.png', 512],
]

const browser = await chromium.launch({ executablePath: executable, headless: true })
try {
  for (const [name, size] of targets) {
    const page = await browser.newPage({ viewport: { width: size, height: size }, deviceScaleFactor: 1 })
    await page.setContent(
      `<!doctype html><style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#f4f2ec}svg{display:block;width:${size}px;height:${size}px}</style>${svg}`,
    )
    await page.screenshot({ path: join(out, name), clip: { x: 0, y: 0, width: size, height: size } })
    await page.close()
    console.log(`${name} ${size}x${size}`)
  }
} finally {
  await browser.close()
}
