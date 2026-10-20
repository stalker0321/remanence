import { describe, it, expect } from 'vitest'
import { runInNewContext } from 'node:vm'
import { webcrypto } from 'node:crypto'
import { shellArtifacts, shellBase } from '../tools/static-shell.mjs'

const files = () => new Map([['index.html', '<p>public</p>'], ['assets/lazy.js', 'export default 1']])
function rig(base = '/', options = {}) {
  const { manifest, worker } = shellArtifacts(base, files())
  const events = {}
  const stores = new Map(options.stores)
  const requests = []
  const deletions = []
  let puts = 0
  const caches = {
    has: async (name) => stores.has(name),
    keys: async () => [...stores.keys()],
    delete: async (name) => { deletions.push(name); return stores.delete(name) },
    open: async (name) => {
      if (!stores.has(name)) stores.set(name, new Map())
      return {
        put: async (url, response) => {
          if (++puts === options.failPut) throw new Error('quota')
          stores.get(name).set(url, response)
        },
      }
    },
    match: async (url, { cacheName }) => {
      if (options.failRead) throw new Error('storage unavailable')
      return stores.get(cacheName)?.get(url)
    },
  }
  const fetch = async (url, init) => {
    if (typeof url !== 'string') url = url.url
    requests.push({ url, init })
    const path = new URL(url).pathname
    const name = path.slice(base.length)
    const response = new Response(options.badBody ? 'private data' : files().get(name), {
      status: options.status ?? 200,
      headers: { 'content-type': options.mime ?? (name.endsWith('.js') ? 'text/javascript' : 'text/html') },
    })
    Object.defineProperties(response, {
      url: { value: options.redirect ? 'https://other.invalid/' : url },
      type: { value: options.type ?? 'basic' },
    })
    return response
  }
  runInNewContext(worker, {
    self: { location: { origin: 'https://app.invalid' }, addEventListener: (name, fn) => { events[name] = fn } },
    caches, fetch, crypto: webcrypto, URL, Map, Uint8Array,
  })
  const lifecycle = (event) => new Promise((resolve, reject) => {
    events[event]({ waitUntil: (promise) => promise.then(resolve, reject) })
  })
  return { manifest, stores, requests, deletions, lifecycle, events }
}

describe('static shell safety', () => {
  it('versions bytes and the static base deterministically, regardless of map order', () => {
    expect(shellArtifacts('/', files())).toEqual(shellArtifacts('/', new Map([...files()].reverse())))
    const changed = files()
    changed.set('assets/lazy.js', 'export default 2')
    expect(shellArtifacts('/', changed).manifest.version).not.toBe(shellArtifacts('/', files()).manifest.version)
    expect(shellArtifacts('/prefix/', files()).manifest.namespace).not.toBe(shellArtifacts('/', files()).manifest.namespace)
  })
  it('rejects external, relative and noncanonical bases and sensitive output', () => {
    for (const base of ['./', 'https://cdn.invalid/', '//host/', '/a/../', '/a?b/']) expect(() => shellBase(base)).toThrow()
    for (const name of ['v1/private.js', 'opencv/opencv.js', '../escape.js', 'assets/secret.json']) {
      expect(() => shellArtifacts('/', new Map([...files(), [name, 'x']]))).toThrow()
    }
  })
  it('installs only the complete verified graph with anonymous network requests', async () => {
    const r = rig('/prefix/')
    await r.lifecycle('install')
    expect([...r.stores.get(r.manifest.cacheName).keys()].sort()).toEqual(r.manifest.entries.map((e) => `https://app.invalid${e.path}`).sort())
    for (const request of r.requests) expect(request.init).toEqual({ credentials: 'omit', redirect: 'error', cache: 'no-store' })
  })
  for (const [name, option] of Object.entries({
    'wrong bytes': { badBody: true }, 'wrong MIME': { mime: 'application/json' },
    '404': { status: 404 }, 'opaque': { type: 'opaque' }, 'redirect': { redirect: true },
  })) {
    it(`rejects ${name} before cache admission`, async () => {
      const r = rig('/', option)
      await expect(r.lifecycle('install')).rejects.toThrow()
      expect(r.stores.size).toBe(0)
    })
  }
  it('removes only its incomplete new cache after a write failure', async () => {
    const r = rig('/', { failPut: 2, stores: [['another-app', new Map()]] })
    await expect(r.lifecycle('install')).rejects.toThrow()
    expect([...r.stores.keys()]).toEqual(['another-app'])
    expect(r.deletions).toEqual([r.manifest.cacheName])
  })
  it('retires only exact same-base versions', async () => {
    const r = rig()
    const old = `${r.manifest.namespace}${'a'.repeat(64)}`
    const sibling = shellArtifacts('/prefix/', files()).manifest.cacheName
    const similar = `${r.manifest.namespace}not-a-version`
    for (const name of [old, sibling, similar, 'other']) r.stores.set(name, new Map())
    await r.lifecycle('install')
    await r.lifecycle('activate')
    expect(r.deletions).toEqual([old])
    expect(r.stores.has(sibling)).toBe(true)
    expect(r.stores.has(similar)).toBe(true)
  })
  it('falls back to network when cache reads are denied without admitting that response', async () => {
    const r = rig('/', { failRead: true })
    let result
    r.events.fetch({ request: new Request('https://app.invalid/assets/lazy.js'), respondWith: (promise) => { result = promise } })
    expect(await (await result).text()).toBe('export default 1')
    expect(r.stores.size).toBe(0)
    expect(r.requests).toHaveLength(1)
  })
  it('ignores negative requests entirely; root alias only reads the built document', async () => {
    const r = rig()
    await r.lifecycle('install')
    for (const [url, init] of [
      ['/v1/auth', {}], ['/recovery/x', {}], ['/music/v1/search', {}],
      ['/assets/lazy.js?token=x', {}], ['/unknown', {}], ['https://cross.invalid/assets/lazy.js', {}],
      ['/assets/lazy.js', { method: 'POST' }], ['/assets/lazy.js', { headers: { authorization: 'Bearer x' } }],
      ['/assets/lazy.js', { headers: { range: 'bytes=0-1' } }],
    ]) {
      let intercepted = false
      r.events.fetch({ request: new Request(new URL(url, 'https://app.invalid'), init), respondWith: () => { intercepted = true } })
      expect(intercepted, url).toBe(false)
    }
    let result
    r.events.fetch({ request: new Request('https://app.invalid/#/settings'), respondWith: (promise) => { result = promise } })
    expect(await (await result).text()).toBe('<p>public</p>')
    expect(r.requests).toHaveLength(2)
  })
})
