#!/usr/bin/env node
// T354 responsive admitted-stage and original-photo modal browser gate.
// Real CollageStage, real fonts/paint, trusted Playwright clicks/downloads and
// real CDP touch events. Synthetic photos/fixtures only.
import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { spawnSync } from "node:child_process"
import { createRequire } from "node:module"
import { createServer } from "node:http"
import { existsSync } from "node:fs"
import { cp, mkdir, readFile, writeFile } from "node:fs/promises"
import { extname, join, resolve, sep } from "node:path"
import { tmpdir } from "node:os"

const requireTool = createRequire("/srv/remanence/tools/browser-smoke/package.json")
const { chromium } = requireTool("playwright-core")
const [webDir, outDir] = process.argv.slice(2)
if (!webDir || !outDir) throw new Error("usage: stageViewerCheck.mjs <webDir> <outDir>")
const web = resolve(webDir)
await mkdir(outDir, { recursive: true, mode: 0o750 })
const report = { synthetic: true, pages: {}, console: [], errors: [], assertions: [], failures: [] }

const executable = [
  "/srv/remanence/cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell",
  chromium.executablePath(),
].find((path) => existsSync(path))
if (!executable) throw new Error("no installed chromium executable")
report.executable = executable
report.executableSha256 = createHash("sha256").update(await readFile(executable)).digest("hex")

const work = join(tmpdir(), `stage-viewer-${process.pid}`)
await mkdir(work, { recursive: true })
await writeFile(join(work, "index.ts"), "import " + JSON.stringify(join(web, "test", "browser", "stageViewer.page.tsx")) + "\n")
await writeFile(join(work, "index.html"), "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body><script type=\"module\" src=\"./index.ts\"></script></body></html>")
await writeFile(
  join(work, "vite.config.mjs"),
  "import react from " + JSON.stringify(requireTool.resolve("@vitejs/plugin-react", { paths: [web] })) + "\n" +
    "export default { root: " + JSON.stringify(work) + ", logLevel: \"warn\", plugins: [react()], esbuild: { jsx: \"automatic\" }, build: { outDir: " + JSON.stringify(join(work, "dist")) + ", emptyOutDir: true, minify: false, sourcemap: false }, onwarn() {} }\n",
)
const built = spawnSync("npx", ["vite", "build", "--config", join(work, "vite.config.mjs")], { cwd: web, env: process.env, stdio: "pipe", encoding: "utf8" })
if (built.status !== 0) {
  report.errors.push((built.stdout ?? "") + (built.stderr ?? ""))
  throw new Error("vite build of the stage-viewer fixture failed")
}

const dist = join(work, "dist")
await cp(join(web, "public", "fonts"), join(dist, "fonts"), { recursive: true })
const types = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".otf": "font/otf", ".png": "image/png" }
const server = createServer(async (req, res) => {
  const url = new URL(req.url, "http://127.0.0.1")
  if (url.pathname === "/favicon.ico") { res.writeHead(204).end(); return }
  const path = resolve(dist, "." + decodeURIComponent(url.pathname === "/" ? "/index.html" : url.pathname))
  if (!path.startsWith(dist + sep)) { res.writeHead(403).end(); return }
  try {
    res.writeHead(200, { "content-type": types[extname(path)] ?? "application/octet-stream", "cache-control": "no-store" }).end(await readFile(path))
  } catch {
    res.writeHead(404).end()
  }
})
await new Promise((ok) => server.listen(4200, "127.0.0.1", ok))

function pass(name, detail = "") { report.assertions.push({ name, ok: true, detail }) }
function fail(name, detail = "") { report.assertions.push({ name, ok: false, detail }); report.failures.push(`${name}: ${detail}`) }
function check(name, cond, detail = "") { cond ? pass(name) : fail(name, detail) }
function within(box, viewport) {
  return box && box.left >= -0.5 && box.top >= -0.5 && box.right <= viewport.width + 0.5 && box.bottom <= viewport.height + 0.5
}
function alpha(rgba) {
  const match = /rgba?\(([^)]+)\)/.exec(rgba)
  if (!match) return 1
  const parts = match[1].split(",").map((part) => Number.parseFloat(part))
  return parts.length >= 4 ? parts[3] : 1
}
function luminance(rgb) {
  const match = /rgba?\(([^)]+)\)/.exec(rgb)
  if (!match) return 0
  const [r, g, b] = match[1].split(",").slice(0, 3).map((part) => Number.parseFloat(part) / 255)
  const channel = (value) => value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}
function contrast(foreground, background) {
  const a = luminance(foreground)
  const b = luminance(background)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}
function pngSize(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const signature = [137, 80, 78, 71, 13, 10, 26, 10]
  if (!signature.every((value, index) => view.getUint8(index) === value)) throw new Error("not PNG")
  return { width: view.getUint32(16), height: view.getUint32(20) }
}
function buttonBox(selector) {
  return page.evaluate((query) => {
    const element = document.querySelector(query)
    if (!element) return null
    const box = element.getBoundingClientRect()
    const style = getComputedStyle(element)
    return {
      left: box.left, top: box.top, right: box.right, bottom: box.bottom,
      width: box.width, height: box.height,
      color: style.color, background: style.backgroundColor,
      disabled: element instanceof HTMLButtonElement ? element.disabled : false,
      text: element.textContent?.trim() ?? "",
    }
  }, selector)
}
async function center(selector) {
  const box = await buttonBox(selector)
  if (!box) throw new Error(`missing ${selector}`)
  return { x: (box.left + box.right) / 2, y: (box.top + box.bottom) / 2 }
}
async function trustedClick(selector) {
  const point = await center(selector)
  await page.mouse.click(point.x, point.y)
}
async function measureControls(viewport, label) {
  const save = await buttonBox(".stage-chrome button:first-of-type")
  const close = await buttonBox(".stage-chrome button:last-of-type")
  check(`${label}: save control is inside the viewport`, within(save, viewport), JSON.stringify(save))
  check(`${label}: close control is inside the viewport`, within(close, viewport), JSON.stringify(close))
  check(`${label}: save text contrast >= 4.5`, contrast(save.color, save.background) >= 4.5, `${save.color} on ${save.background}`)
  check(`${label}: close text contrast >= 4.5`, contrast(close.color, close.background) >= 4.5, `${close.color} on ${close.background}`)
}

let page
let failure = null
try {
  const browser = await chromium.launch({ headless: true, executablePath: executable })
  try {
    report.browser = browser.version()
    const context = await browser.newContext({ viewport: { width: 900, height: 900 }, hasTouch: true })
    page = await context.newPage()
    page.on("console", (msg) => report.console.push({ type: msg.type(), text: msg.text() }))
    page.on("pageerror", (error) => report.errors.push(String(error)))
    await page.route("**/*", async (route) => {
      const url = route.request().url()
      if (/^http:\/\/127\.0\.0\.1:4200\//.test(url)) await route.continue()
      else {
        report.errors.push("external request blocked: " + url)
        await route.abort()
      }
    })
    await page.goto("http://127.0.0.1:4200/index.html")
    await page.waitForFunction(() => document.querySelector("#stage-viewer-status")?.textContent === "ready", undefined, { timeout: 30000 })

    for (const [width, height] of [[320, 568], [390, 900], [900, 900], [1280, 720], [844, 390]]) {
      await page.setViewportSize({ width, height })
      await page.waitForTimeout(80)
      await measureControls({ width, height }, `responsive ${width}x${height}`)
      if (width === 320 || width === 900 || width === 844) {
        await page.screenshot({ path: join(outDir, `stage-${width}x${height}.png`), fullPage: true })
      }
    }

    await page.setViewportSize({ width: 320, height: 568 })
    await page.evaluate(() => { document.documentElement.style.fontSize = "36px" })
    await page.evaluate(() => window.__stageViewer.language("ru"))
    await page.waitForTimeout(100)
    for (const selector of [".stage-chrome button:first-of-type", ".stage-chrome button:last-of-type"]) {
      await page.locator(selector).scrollIntoViewIfNeeded()
      const box = await buttonBox(selector)
      check(`200% RU ${selector}: control reachable after scroll`, within(box, { width: 320, height: 568 }), JSON.stringify(box))
      check(`200% RU ${selector}: contrast >= 4.5`, contrast(box.color, box.background) >= 4.5, `${box.color} on ${box.background}`)
    }
    await page.evaluate(() => { document.documentElement.style.fontSize = "" })
    await page.evaluate(() => window.__stageViewer.language("uk"))
    await page.setViewportSize({ width: 390, height: 900 })
    await page.waitForTimeout(100)
    await measureControls({ width: 390, height: 900 }, "long UK 390x900")
    await page.evaluate(() => window.__stageViewer.language("en"))
    await page.setViewportSize({ width: 900, height: 900 })
    await page.waitForTimeout(100)

    const hit = page.locator(".hit").nth(0)
    const hitHandle = await hit.elementHandle()
    if (!hitHandle) throw new Error("first photo trigger missing")
    await hit.click()
    await page.waitForSelector(".viewer")
    check("viewer opens for the original photo", await page.locator(".viewer").count() === 1)
    await page.screenshot({ path: join(outDir, "viewer-modal-900x900.png"), fullPage: true })
    check("counter shows 1 / 3", (await page.locator(".viewer-counter").textContent())?.includes("1 / 3") === true)
    await page.waitForFunction(() => document.activeElement?.textContent?.trim() === "back")
    check("focus enters the modal close/back action", await page.evaluate(() => document.activeElement?.textContent?.trim()) === "back")
    check("underlying stage is inert", await page.locator(".stage-main").evaluate((element) => element.hasAttribute("inert")))
    const urls = await page.evaluate(() => window.__stageViewer.photoUrls())
    const firstImage = await page.locator(".viewer-image").evaluate((image) => ({
      src: image.getAttribute("src"),
      fit: getComputedStyle(image).objectFit,
      naturalWidth: image.naturalWidth,
      naturalHeight: image.naturalHeight,
      width: image.getBoundingClientRect().width,
      height: image.getBoundingClientRect().height,
    }))
    check("viewer uses the original held URL", firstImage.src === urls[0], `${firstImage.src} vs ${urls[0]}`)
    check("viewer uses object-fit contain", firstImage.fit === "contain")
    check("landscape original stays landscape (not canvas crop)", firstImage.naturalWidth === 1200 && firstImage.naturalHeight === 600 && firstImage.width > firstImage.height, JSON.stringify(firstImage))
    const viewerBackground = await page.locator(".viewer").evaluate((element) => getComputedStyle(element).backgroundColor)
    check("viewer dims but still shows the stage underneath", alpha(viewerBackground) > 0 && alpha(viewerBackground) < 1, viewerBackground)
    const panelBox = await buttonBox(".viewer-panel")
    check("viewer panel stays bounded", within(panelBox, { width: 900, height: 900 }), JSON.stringify(panelBox))
    check("previous is disabled at the first original", (await buttonBox(".viewer-actions button:nth-of-type(1)")).disabled)
    check("next is enabled at the first original", !(await buttonBox(".viewer-actions button:nth-of-type(2)")).disabled)

    await page.setViewportSize({ width: 320, height: 568 })
    await page.waitForTimeout(80)
    const narrowPanel = await buttonBox(".viewer-panel")
    check("modal panel stays bounded at 320x568", within(narrowPanel, { width: 320, height: 568 }), JSON.stringify(narrowPanel))
    await page.screenshot({ path: join(outDir, "viewer-modal-320x568.png"), fullPage: true })
    await page.setViewportSize({ width: 900, height: 900 })
    await page.waitForTimeout(80)

    await page.keyboard.press("Tab")
    check("Tab from close wraps to the first enabled modal control", await page.evaluate(() => document.activeElement?.textContent?.trim()) === "next")
    await page.keyboard.press("Shift+Tab")
    check("Shift+Tab wraps back to close", await page.evaluate(() => document.activeElement?.textContent?.trim()) === "back")

    await trustedClick(".viewer-actions button:nth-of-type(2)")
    await page.waitForTimeout(50)
    check("next pages to original 2 / 3", (await page.locator(".viewer-counter").textContent())?.includes("2 / 3") === true)
    const secondImage = await page.locator(".viewer-image").evaluate((image) => ({ src: image.getAttribute("src"), w: image.getBoundingClientRect().width, h: image.getBoundingClientRect().height }))
    check("portrait original stays portrait", secondImage.src === urls[1] && secondImage.h > secondImage.w, JSON.stringify(secondImage))
    await page.keyboard.press("ArrowRight")
    await page.waitForTimeout(50)
    check("ArrowRight reaches 3 / 3", (await page.locator(".viewer-counter").textContent())?.includes("3 / 3") === true)
    check("next is disabled at the last original", (await buttonBox(".viewer-actions button:nth-of-type(2)")).disabled)
    await page.keyboard.press("ArrowLeft")
    await page.waitForTimeout(50)
    check("ArrowLeft returns to 2 / 3", (await page.locator(".viewer-counter").textContent())?.includes("2 / 3") === true)

    const client = await context.newCDPSession(page)
    const frame = await buttonBox(".viewer-frame")
    const touchY = (frame.top + frame.bottom) / 2
    const startX = frame.left + frame.width * 0.75
    const endX = frame.left + frame.width * 0.25
    await client.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: startX, y: touchY }] })
    await client.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: endX, y: touchY }] })
    await client.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
    await page.waitForTimeout(80)
    check("horizontal touch swipe advances the original", (await page.locator(".viewer-counter").textContent())?.includes("3 / 3") === true)
    await client.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: startX, y: touchY }] })
    await client.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: startX + 10, y: touchY + 160 }] })
    await client.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
    await page.waitForTimeout(80)
    check("vertical touch does not page", (await page.locator(".viewer-counter").textContent())?.includes("3 / 3") === true)

    const savePoint = await center(".stage-chrome button:first-of-type")
    const downloadWhileModal = page.waitForEvent("download", { timeout: 600 }).then(() => true).catch(() => false)
    await page.mouse.click(savePoint.x, savePoint.y)
    check("underlying save is not interactable while the modal is open", !(await downloadWhileModal))
    check("modal remains open after blocked underlying click", await page.locator(".viewer").count() === 1)

    await trustedClick(".viewer-actions button:nth-of-type(3)")
    await page.waitForSelector(".viewer", { state: "detached" })
    check("back/Escape close removes the modal", await page.locator(".viewer").count() === 0)
    await page.waitForFunction((element) => document.activeElement === element, hitHandle)
    check("closing restores focus to the original photo trigger", await page.evaluate((element) => document.activeElement === element, hitHandle))

    await hit.click()
    await page.waitForSelector(".viewer")
    await page.keyboard.press("Escape")
    await page.waitForSelector(".viewer", { state: "detached" })
    await page.waitForFunction((element) => document.activeElement === element, hitHandle)
    check("Escape closes the modal and returns focus", await page.evaluate((element) => document.activeElement === element, hitHandle))

    await hit.click()
    await page.waitForSelector(".viewer")
    const oldSrc = await page.locator(".viewer-image").getAttribute("src")
    await page.evaluate(() => window.__stageViewer.replace())
    await page.waitForTimeout(120)
    check("held replacement closes the old viewer", await page.locator(".viewer").count() === 0)
    check("held replacement leaves no old image URL in the DOM", !(await page.locator(`img[src="${oldSrc}"]`).count()))
    check("held replacement does not restore focus to the old trigger", !(await page.evaluate((element) => document.activeElement === element, hitHandle)))
    await page.evaluate(() => window.__stageViewer.unmount())
    check("unmount removes all stale stage/viewer DOM", await page.locator(".stage, .viewer").count() === 0)

    await page.setViewportSize({ width: 900, height: 900 })
    await page.reload()
    await page.waitForFunction(() => document.querySelector("#stage-viewer-status")?.textContent === "ready", undefined, { timeout: 30000 })
    const downloadPromise = page.waitForEvent("download", { timeout: 15000 })
    await trustedClick(".stage-chrome button:first-of-type")
    const download = await downloadPromise
    const path = await download.path()
    if (!path) throw new Error("download path missing")
    const bytes = new Uint8Array(await readFile(path))
    const size = pngSize(bytes)
    check("trusted wide 900x900 click downloads the frozen composition", size.width === 360 && size.height === 640, JSON.stringify(size))
    report.png = { ...size, bytes: bytes.length, suggested: download.suggestedFilename() }
    await page.screenshot({ path: join(outDir, "stage-viewer-final.png"), fullPage: true })
    report.dom = await page.evaluate(() => document.body.outerHTML.slice(0, 200000))
    report.pass = report.failures.length === 0
  } finally {
    await browser.close()
  }
} catch (error) {
  failure = error
  fail("stage viewer connected harness", String(error))
} finally {
  server.close()
  report.pass = report.failures.length === 0
  await writeFile(join(outDir, "stage-viewer-report.json"), JSON.stringify(report, null, 2))
}
if (report.errors.length > 0) fail("no page/route/runtime errors", JSON.stringify(report.errors))
if (report.console.some((item) => item.type === "error")) fail("no console errors", JSON.stringify(report.console.filter((item) => item.type === "error")))
if (failure || report.failures.length > 0) {
  console.error("FAILURES", JSON.stringify(report.failures, null, 2))
  throw failure ?? new Error("stage viewer proof failed")
}
console.log("stage-viewer browser check PASS: " + report.assertions.length + " assertions")
