import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { extname, join } from 'node:path'

const hash = (bytes) => createHash('sha256').update(bytes).digest('hex')
const types = {
  '.html': ['text/html'],
  '.js': ['text/javascript', 'application/javascript'],
  '.css': ['text/css'],
  '.svg': ['image/svg+xml'],
  '.png': ['image/png'],
  '.otf': ['font/otf', 'application/font-sfnt'],
  '.woff': ['font/woff'],
  '.woff2': ['font/woff2'],
  '.webmanifest': ['application/manifest+json', 'application/json'],
}
const publicShell = [
  'boot.js', 'favicon.svg', 'manifest.webmanifest',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/icon-512-maskable.png',
]

export function shellBase(base) {
  if (!/^\/(?:[A-Za-z0-9._~-]+\/)*$/.test(base) ||
      base.split('/').some((part) => part === '.' || part === '..')) {
    throw new Error('Static shell requires a root-relative directory base')
  }
  return base
}

// This is the worker program, serialized into the build. It has no runtime
// manifest fetch and no runtime cache admission. Changing it changes VERSION.
function workerProgram(manifest) {
  const { base, entries, namespace, cacheName } = manifest
  const allowed = new Map(entries.map((entry) => [entry.path, entry]))
  const denied = /\/(?:api|v1|auth|recovery|music|blob|blobs|photo|photos|artwork|capsules|opencv)(?:\/|$)/i
  const hex = (buffer) => Array.from(new Uint8Array(buffer), (n) => n.toString(16).padStart(2, '0')).join('')
  const validPath = (url) => url.origin === self.location.origin &&
    !url.search && !url.hash && !denied.test(url.pathname) && allowed.has(url.pathname)

  self.addEventListener('install', (event) => {
    event.waitUntil((async () => {
      // Validate the WHOLE graph before opening a new cache. A partial cache
      // can never be used by an active worker, even after a quota/write failure.
      const responses = []
      for (const entry of entries) {
        const url = new URL(entry.path, self.location.origin)
        if (!validPath(url)) throw new Error('Invalid shell path')
        const response = await fetch(url.href, {
          credentials: 'omit', redirect: 'error', cache: 'no-store',
        })
        const mime = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase()
        if (response.status !== 200 || response.type !== 'basic' ||
            response.redirected || response.url !== url.href ||
            !entry.types.includes(mime) ||
            (response.headers.get('vary') || '').split(',').some((value) => !['', 'accept-encoding'].includes(value.trim().toLowerCase())) ||
            hex(await crypto.subtle.digest('SHA-256', await response.clone().arrayBuffer())) !== entry.sha256) {
          throw new Error('Invalid shell response')
        }
        responses.push([url.href, response])
      }
      const existed = await caches.has(cacheName)
      try {
        const cache = await caches.open(cacheName)
        for (const [url, response] of responses) await cache.put(url, response)
      } catch (error) {
        // Do not destroy a pre-existing identical version on reinstall.
        if (!existed) await caches.delete(cacheName)
        throw error
      }
    })())
  })

  self.addEventListener('activate', (event) => {
    event.waitUntil((async () => {
      for (const name of await caches.keys()) {
        if (name !== cacheName && name.startsWith(namespace) &&
            /^[a-f0-9]{64}$/.test(name.slice(namespace.length))) {
          await caches.delete(name)
        }
      }
    })())
  })

  self.addEventListener('fetch', (event) => {
    const request = event.request
    const url = new URL(request.url)
    if (request.method !== 'GET' || request.headers.has('authorization') ||
        request.headers.has('range') || url.origin !== self.location.origin ||
        url.search || denied.test(url.pathname)) return
    // Navigation requests may retain the document fragment. It is not sent
    // to the server or used as a cache key; the exact path remains mandatory.
    // Hash routes share the one built document; no other navigation fallback.
    const path = url.pathname === base ? `${base}index.html` : url.pathname
    if (!allowed.has(path)) return
    event.respondWith((async () => {
      try {
        const response = await caches.match(new URL(path, self.location.origin).href, { cacheName })
        if (response) return response
      } catch {
        // Storage may be denied or evicted after a successful install.
      }
      // Normal online behavior remains usable. Never persist this response.
      return fetch(request)
    })())
  })
}

export function shellArtifacts(base, files) {
  shellBase(base)
  const entries = [...files].map(([name, bytes]) => {
    const path = `${base}${name}`
    if (!/^[A-Za-z0-9._~/-]+$/.test(name) || name.startsWith('/') ||
        name.split('/').some((part) => part === '..' || part === '.') ||
        /^(?:api|v1|auth|recovery|music|blob|blobs|photo|photos|artwork|capsules|opencv)\//i.test(name)) {
      throw new Error(`Non-shell output: ${name}`)
    }
    const mime = types[extname(name)]
    if (!mime) throw new Error(`Unknown shell MIME: ${name}`)
    return { path, sha256: hash(bytes), types: mime }
  }).sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0)
  if (!files.has('index.html')) throw new Error('Shell document missing')
  const program = workerProgram.toString()
  const version = hash(JSON.stringify({ base, entries, program }))
  const namespace = `remanence-static-${hash(base)}-`
  const manifest = { base, version, namespace, cacheName: `${namespace}${version}`, entries }
  return { manifest, worker: `(${program})(${JSON.stringify(manifest)});\n` }
}

export function staticShell() {
  let config
  return {
    name: 'remanence-static-shell',
    apply: 'build',
    enforce: 'post',
    configResolved(resolved) { config = resolved },
    generateBundle: {
      order: 'post',
      async handler(_options, bundle) {
        const base = shellBase(config.base)
        const files = new Map()
        // Consume every emitted JS chunk, including dynamic imports and their
        // shared dependencies. Static assets come from the emitted graph.
        for (const output of Object.values(bundle)) {
          if (output.type === 'chunk') files.set(output.fileName, output.code)
          else if (types[extname(output.fileName)]) files.set(output.fileName, output.source)
        }
        for (const name of publicShell) files.set(name, await readFile(join(config.publicDir, name)))
        // Vite public font URLs remain external to the module graph. Resolve
        // only the exact font references emitted in the built CSS.
        for (const [name, bytes] of [...files]) {
          if (!name.endsWith('.css')) continue
          for (const match of String(bytes).matchAll(/url\(\s*["']?([^"')\s]+)["']?\s*\)/g)) {
            const url = new URL(match[1], `https://static.invalid/${name}`)
            if (!url.pathname.startsWith(`${base}fonts/`)) continue
            if (url.origin !== 'https://static.invalid' || url.search || url.hash) throw new Error('Invalid font URL')
            const relative = url.pathname.slice(base.length)
            if (!/^fonts\/[A-Za-z0-9_-]+\.(?:otf|woff2?)$/.test(relative)) throw new Error('Invalid shell font')
            files.set(relative, await readFile(join(config.publicDir, relative)))
          }
        }
        const { manifest, worker } = shellArtifacts(base, files)
        this.emitFile({ type: 'asset', fileName: 'sw.js', source: worker })
        this.emitFile({ type: 'asset', fileName: 'shell-manifest.json', source: JSON.stringify(manifest, null, 2) + '\n' })
      },
    },
  }
}
