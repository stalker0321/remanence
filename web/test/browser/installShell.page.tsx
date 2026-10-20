/**
 * T356 H01 install-shell proof (real production build, root and explicit
 * Vite --base=/test-prefix/). Checks manifest/start/scope, icons + maskable
 * safe radius, favicon/theme, boot, base-aware fonts/OpenCV with unchanged
 * hashes, hash routes, and narrow/wide shell geometry. No install/offline/
 * physical/provider claim; no matcher bypass.
 */
import { loadOpenCvModule, OPENCV_VENDOR_BASE } from '../../src/scan/opencvRuntime'

interface Check { name: string; ok: boolean; detail?: string }
const checks: Check[] = []
const failures: string[] = []
function check(name: string, ok: boolean, detail = ''): void {
  checks.push({ name, ok, detail: ok ? undefined : detail })
  if (!ok) failures.push(`${name}: ${detail}`)
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource))
  return [...digest].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

async function waitFor(cond: () => boolean, what: string, timeoutMs = 15000): Promise<void> {
  const start = Date.now()
  while (!cond()) {
    if (Date.now() - start > timeoutMs) throw new Error(`timeout: ${what}`)
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
}

const PAPER = [244, 242, 236]
interface IconMeasure { width: number; height: number; inkPixels: number; maxForegroundRadius: number; cornerPaper: boolean }

async function measureIcon(url: string): Promise<IconMeasure> {
  const response = await fetch(url, { cache: 'no-store' })
  if (!response.ok) throw new Error(`icon ${response.status}`)
  const bitmap = await createImageBitmap(await response.blob())
  const canvas = document.createElement('canvas')
  canvas.width = bitmap.width
  canvas.height = bitmap.height
  const context = canvas.getContext('2d', { willReadFrequently: true })!
  context.drawImage(bitmap, 0, 0)
  const data = context.getImageData(0, 0, bitmap.width, bitmap.height).data
  const cx = bitmap.width / 2
  const cy = bitmap.height / 2
  let inkPixels = 0
  let maxForegroundRadius = 0
  for (let y = 0; y < bitmap.height; y++) {
    for (let x = 0; x < bitmap.width; x++) {
      const i = (y * bitmap.width + x) * 4
      const delta = Math.abs(data[i]! - PAPER[0]!) + Math.abs(data[i + 1]! - PAPER[1]!) + Math.abs(data[i + 2]! - PAPER[2]!)
      if (delta > 30) {
        inkPixels += 1
        const radius = Math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if (radius > maxForegroundRadius) maxForegroundRadius = radius
      }
    }
  }
  const corner = [data[0]!, data[1]!, data[2]!]
  return {
    width: bitmap.width,
    height: bitmap.height,
    inkPixels,
    maxForegroundRadius,
    cornerPaper: Math.abs(corner[0]! - PAPER[0]!) <= 2 && Math.abs(corner[1]! - PAPER[1]!) <= 2 && Math.abs(corner[2]! - PAPER[2]!) <= 2,
  }
}

async function main(): Promise<void> {
  const status = document.createElement('p')
  status.id = 'install-shell-status'
  status.textContent = 'starting'
  document.body.appendChild(status)
  const report: Record<string, unknown> = { pass: false, mode: new URL(location.href).searchParams.get('mode') }
  try {
    const basePath = new URL('.', document.baseURI).pathname
    const manifestUrl = new URL('manifest.webmanifest', document.baseURI)
    const manifestLink = document.querySelector<HTMLLinkElement>('link[rel="manifest"]')
    check('manifest link is present', manifestLink !== null)
    check('manifest link stays under the served base', manifestLink !== null && new URL(manifestLink.href).pathname.startsWith(basePath))
    const response = await fetch(manifestUrl, { cache: 'no-store' })
    check('manifest fetched same-origin', response.ok, String(response.status))
    const manifest = (await response.json()) as Record<string, unknown>
    report.manifest = manifest
    check('manifest name', manifest.name === 'Remanence' && manifest.short_name === 'Remanence')
    check('manifest lang/dir', manifest.lang === 'en' && manifest.dir === 'ltr')
    check('manifest display standalone', manifest.display === 'standalone')
    check('manifest theme/background paper', manifest.theme_color === '#f4f2ec' && manifest.background_color === '#f4f2ec')
    check('manifest omits id', !('id' in manifest))
    const start = new URL(String(manifest.start_url), manifestUrl).pathname
    const scope = new URL(String(manifest.scope), manifestUrl).pathname
    check('start_url under served base', start === basePath, `${start} vs ${basePath}`)
    check('scope under served base', scope === basePath, `${scope} vs ${basePath}`)

    const icons = Array.isArray(manifest.icons) ? (manifest.icons as Array<Record<string, unknown>>) : []
    const iconResults: Array<Record<string, unknown>> = []
    for (const icon of icons) {
      const url = new URL(String(icon.src), manifestUrl)
      const measured = await measureIcon(url.href)
      const size = String(icon.sizes)
      const expected = size === '192x192' ? 192 : 512
      check(`icon ${size} same-origin under base`, url.pathname.startsWith(basePath) && url.origin === location.origin)
      check(`icon ${size} dimensions`, measured.width === expected && measured.height === expected, `${measured.width}x${measured.height}`)
      check(`icon ${size} contains the ink mark`, measured.inkPixels > 100, String(measured.inkPixels))
      check(`icon ${size} opaque paper background`, measured.cornerPaper)
      if (String(icon.purpose) === 'maskable') {
        check('maskable foreground inside 40% radius', measured.maxForegroundRadius <= expected * 0.4, String(measured.maxForegroundRadius))
        report.maskableMaxForegroundRadius = measured.maxForegroundRadius
      }
      iconResults.push({ src: url.pathname, sizes: size, purpose: icon.purpose, ...measured })
    }
    check('manifest has 192/512 any and 512 maskable icons', icons.length === 3)
    report.icons = iconResults

    const favicon = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
    check('favicon is not the placeholder', favicon !== null && !favicon.href.startsWith('data:'))
    check('favicon stays under the base', favicon !== null && new URL(favicon.href).pathname.startsWith(basePath))
    const faviconText = favicon ? await (await fetch(favicon.href, { cache: 'no-store' })).text() : ''
    check('favicon is the authored mark', faviconText.includes('<svg') && faviconText.includes('#333951'))
    const theme = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')?.content
    check('theme-color paper', theme === '#f4f2ec', String(theme))

    const boot = (window as unknown as { __remanenceBoot?: { errors?: unknown[]; resources?: unknown[] } }).__remanenceBoot
    check('boot recorder present and error-free', boot !== undefined && (boot.errors?.length ?? 0) === 0 && (boot.resources?.length ?? 0) === 0)

    // Force all nine font files to load, then hash the actual responses.
    const probe = document.createElement('div')
    probe.style.cssText = 'position:absolute;left:-9999px;font-size:16px'
    const specs: Array<[string, string, string]> = [
      ['StudySans', '400', 'normal'], ['StudySans', '700', 'normal'],
      ['StudySerif', '400', 'normal'], ['StudySerif', '400', 'italic'], ['StudySerif', '700', 'normal'],
      ['StudyMono', '400', 'normal'], ['Narrow', '700', 'normal'],
      ['Gothic', '400', 'normal'], ['Gothic', '700', 'normal'],
    ]
    for (const [family, weight, style] of specs) {
      const span = document.createElement('span')
      span.style.fontFamily = family
      span.style.fontWeight = weight
      span.style.fontStyle = style
      span.textContent = 'Remanence 0123456789'
      probe.appendChild(span)
    }
    document.body.appendChild(probe)
    await document.fonts.ready
    const fontEntries = performance.getEntriesByType('resource').filter((entry) => /\.otf(\?|$)/.test(entry.name))
    const fontHashes: Array<{ path: string; hash: string; bytes: number }> = []
    for (const entry of fontEntries) {
      const url = new URL(entry.name)
      if (!url.pathname.startsWith(basePath)) {
        check('font under served base', false, url.pathname)
        continue
      }
      const bytes = new Uint8Array(await (await fetch(url, { cache: 'no-store' })).arrayBuffer())
      fontHashes.push({ path: url.pathname, hash: await sha256Hex(bytes), bytes: bytes.length })
    }
    check('nine font files loaded under the base', fontHashes.length === 9, String(fontHashes.length))
    report.fonts = fontHashes

    // Real pinned OpenCV ready (path is base-aware; bytes hashed).
    const cv = await loadOpenCvModule()
    check('OpenCV runtime ready', typeof cv.Mat === 'function' && typeof cv.CV_8UC1 === 'number')
    const buildInfo = typeof (cv as unknown as { getBuildInformation?: () => string }).getBuildInformation === 'function'
      ? (cv as unknown as { getBuildInformation: () => string }).getBuildInformation()
      : ''
    report.openCvBuildInfo = buildInfo.split('\n')[0] ?? null
    const openCv: Array<{ path: string; hash: string; bytes: number }> = []
    for (const file of ['opencv.js', 'opencv_js.wasm']) {
      const url = new URL(`${OPENCV_VENDOR_BASE}${file}`, document.baseURI)
      check(`OpenCV ${file} under the base`, url.pathname.startsWith(basePath))
      const bytes = new Uint8Array(await (await fetch(url, { cache: 'no-store' })).arrayBuffer())
      openCv.push({ path: url.pathname, hash: await sha256Hex(bytes), bytes: bytes.length })
    }
    report.openCv = openCv

    // The production app bundle is already mounted by the real built index.html.
    // This test module only asserts its shell/routes; it never mounts a second App.
    await waitFor(() => document.querySelector('.shell') !== null, 'initial shell')
    location.hash = '#/scan'
    await waitFor(() => document.querySelector('.shell') !== null, 'scan shell')
    check('scan hash route renders the shell', (document.body.textContent?.length ?? 0) > 0)
    location.hash = '#/'
    await waitFor(() => document.querySelector('.shell') !== null, 'home shell')
    check('home hash route renders the shell', (document.body.textContent?.length ?? 0) > 0)
    check('locked scan did not open presentation', document.querySelector('.stage') === null)

    const shell = document.querySelector('.shell')?.getBoundingClientRect()
    const overflow = document.documentElement.scrollWidth - window.innerWidth
    check('shell is visible', shell !== undefined && shell.width > 0 && shell.height > 0)
    check('no horizontal shell overflow', overflow <= 1, String(overflow))
    report.geometry = { viewport: [window.innerWidth, window.innerHeight], shell, overflow }
    report.pass = failures.length === 0
  } catch (error) {
    check('install shell harness', false, String(error))
  } finally {
    status.textContent = failures.length === 0 ? 'done' : 'failed'
    ;(window as unknown as { __installShell?: unknown }).__installShell = { checks, failures, report }
  }
}

void main()
