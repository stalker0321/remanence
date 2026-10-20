import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { join, resolve, extname } from 'node:path'
import { tmpdir } from 'node:os'

const [webDir, proof] = process.argv.slice(2)
const web = resolve(webDir)
await mkdir(proof, { recursive: true })
const work = await mkdtemp(join(tmpdir(), 't359-scan-'))
const requireTool = createRequire('/srv/remanence/tools/browser-smoke/package.json')
const { chromium } = requireTool('playwright-core')
const executable = '/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const executableSha256 = createHash('sha256').update(await readFile(executable)).digest('hex')
assert.equal(executableSha256, '4fa61f0ba0b4f89b7bec242c32ca16d3bccf69dbfef0651924a9ba8fede5f9d6')
await writeFile(join(work, 'index.html'), '<html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><script type="module" src="./entry.ts"></script></body></html>')
await writeFile(join(work, 'entry.ts'), `import ${JSON.stringify(join(web, 'test/browser/scanLifecycle.page.tsx'))}`)
await writeFile(join(work, 'config.mjs'), `import react from ${JSON.stringify(requireTool.resolve('@vitejs/plugin-react', { paths: [web] }))};export default {root:${JSON.stringify(work)},plugins:[react()],build:{outDir:${JSON.stringify(join(work, 'dist'))}}}`)
const built = spawnSync('npx', ['vite', 'build', '--config', join(work, 'config.mjs')], { cwd: web, encoding: 'utf8' })
await writeFile(join(proof, 'build.log'), (built.stdout || '') + (built.stderr || ''))
assert.equal(built.status, 0)
const server = createServer(async (req, res) => {
  const name = new URL(req.url, 'http://fixture.invalid').pathname
  const file = name.startsWith('/fonts/') ? join(web, 'public', name) : join(work, 'dist', name === '/' ? 'index.html' : name)
  try {
    const bytes = await readFile(file)
    res.writeHead(200, { 'content-type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.otf': 'font/otf' }[extname(file)], 'cache-control': 'no-store' }).end(bytes)
  } catch { res.writeHead(404).end() }
})
await new Promise((ok) => server.listen(0, '127.0.0.1', ok))
const browser = await chromium.launch({ executablePath: executable, headless: true })
const errors = []
let report
try {
  const page = await browser.newPage({ viewport: { width: 320, height: 568 } })
  page.on('pageerror', (error) => errors.push(String(error)))
  await page.goto(`http://127.0.0.1:${server.address().port}/`)
  await page.waitForFunction(() => Boolean(window.scanLifecycle))
  report = await page.evaluate(() => window.scanLifecycle)
  await page.screenshot({ path: join(proof, 'scan-lifecycle.png') })
  await writeFile(join(proof, 'scan-lifecycle.html'), await page.content())
  assert.equal(report.pass, true, JSON.stringify(report.checks))
  assert.deepEqual(errors, [])
} finally {
  await writeFile(join(proof, 'scan-lifecycle-report.json'), JSON.stringify({ executableSha256, synthetic: true, report, errors }, null, 2))
  await browser.close()
  server.close()
}
console.log(`scan-lifecycle PASS: ${report.checks.length} checks`)
