/** Public target configuration only. No IDs, credentials or tokens in URLs/storage. */
export interface AtomicTestConfig {
  environment: 'TEST'
  appOrigin: string
  apiOrigin: string
  rpId: string
}

export class AtomicTestConfigurationError extends Error {
  readonly code = 'TEST_CONFIGURATION_INVALID'
  constructor(message: string) { super(message); this.name = 'AtomicTestConfigurationError' }
}

function fail(message: string): never { throw new AtomicTestConfigurationError(message) }

/** Hosting boundary, not an unregister/cache cleanup operation. */
export function assertUncontrolledTestContext(controlled: boolean): void {
  if (controlled) fail('This TEST page is controlled by an existing service worker. Use a separate approved TEST origin; no registration started and no worker or cache was removed.')
}
function loopback(host: string): boolean { return ['localhost', '127.0.0.1', '[::1]'].includes(host) }

function origin(value: unknown): URL {
  if (typeof value !== 'string') return fail('Set explicit appOrigin and apiOrigin in atomic-test-config.json.')
  let url: URL
  try { url = new URL(value) } catch { return fail('TEST origins must be absolute origins, without paths or credentials.') }
  if (value !== url.origin || url.username || url.password || url.search || url.hash
    || (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback(url.hostname)))) {
    return fail('TEST origins must be canonical HTTPS origins (HTTP is allowed only on loopback).')
  }
  return url
}

export function parseAtomicTestConfig(value: unknown, actualOrigin: string, secureContext: boolean): AtomicTestConfig {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return fail('Provide atomic-test-config.json for the approved TEST target.')
  const config = value as Record<string, unknown>
  if (config.environment !== 'TEST') return fail('Atomic onboarding is disabled. Explicit environment TEST is required.')
  if (Object.keys(config).sort().join(',') !== 'apiOrigin,appOrigin,environment,rpId') return fail('TEST configuration requires exactly environment, appOrigin, apiOrigin and rpId.')
  const app = origin(config.appOrigin), api = origin(config.apiOrigin)
  if (!secureContext || actualOrigin !== app.origin) return fail('This page must use the configured secure TEST appOrigin. No API or provider was selected.')
  const rpId = config.rpId
  if (typeof rpId !== 'string' || rpId !== rpId.toLowerCase() || !/^[a-z0-9.-]+$/.test(rpId)
    || rpId.startsWith('.') || rpId.endsWith('.') || rpId.includes('..')
    || !(app.hostname === rpId || (!loopback(app.hostname) && app.hostname.endsWith(`.${rpId}`)))) {
    return fail('Set rpId to the existing TEST backend WebAuthn RP ID matching this page host.')
  }
  if (app.protocol === 'https:' && api.protocol !== 'https:') return fail('An HTTPS TEST page requires an HTTPS API target.')
  return Object.freeze({ environment: 'TEST', appOrigin: app.origin, apiOrigin: api.origin, rpId })
}

/** No provider/API request until this small same-origin static file is validated. */
export async function loadAtomicTestConfig(url: URL, actualOrigin: string, secureContext: boolean): Promise<AtomicTestConfig> {
  if (url.origin !== actualOrigin || url.search || url.hash) return fail('TEST configuration must be served from this entry origin.')
  let response: Response
  try { response = await fetch(url, { cache: 'no-store', credentials: 'omit', redirect: 'error' }) }
  catch { return fail('Cannot load atomic-test-config.json. No registration started.') }
  if (response.status !== 200 || !/^application\/json(?:;|$)/i.test(response.headers.get('content-type') ?? '')) {
    await response.body?.cancel()
    return fail('Serve atomic-test-config.json as application/json from the TEST entry directory.')
  }
  const reader = response.body?.getReader()
  if (!reader) return fail('TEST configuration is empty.')
  let size = 0, text = ''
  const decoder = new TextDecoder('utf-8', { fatal: true })
  try {
    for (;;) {
      const item = await reader.read()
      if (item.done) break
      size += item.value.length
      if (size > 4096) return fail('TEST configuration exceeds the public configuration size limit.')
      text += decoder.decode(item.value, { stream: true })
    }
    text += decoder.decode()
    let value: unknown
    try { value = JSON.parse(text) } catch { return fail('TEST configuration is not valid JSON.') }
    return parseAtomicTestConfig(value, actualOrigin, secureContext)
  } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
