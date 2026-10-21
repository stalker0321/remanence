import { parseAtomicTestConfig } from '../atomicTest/config'

export interface DeploymentTarget {
  version: 1
  atomicRegistration: boolean
  environment: 'DEV' | 'TEST'
  appOrigin: string
  apiOrigin: string
  rpId: string
}
export type DeploymentConfig = DeploymentTarget | { version: 1; atomicRegistration: false }
export const disabledDeployment: DeploymentConfig = Object.freeze({ version: 1, atomicRegistration: false })
export class DeploymentConfigurationError extends Error {}
const fail = (): never => { throw new DeploymentConfigurationError('DEPLOYMENT_CONFIGURATION_UNAVAILABLE') }

export function parseDeploymentConfig(value: unknown, actualOrigin: string, secure: boolean): DeploymentConfig {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail()
  const config = value as Record<string, unknown>
  if (config.version !== 1 || typeof config.atomicRegistration !== 'boolean') return fail()
  const fields = Object.keys(config).sort().join(',')
  if (fields === 'atomicRegistration,version' && config.atomicRegistration === false) return disabledDeployment
  if (fields !== 'apiOrigin,appOrigin,atomicRegistration,environment,rpId,version'
    || !['DEV', 'TEST'].includes(config.environment as string)) return fail()
  try {
    // Reuse the exact existing origin/RP/secure-context validator. This does not
    // invoke the separate TEST document's service-worker guard or select its HTML.
    const target = parseAtomicTestConfig({ environment: 'TEST', appOrigin: config.appOrigin,
      apiOrigin: config.apiOrigin, rpId: config.rpId }, actualOrigin, secure)
    return Object.freeze({ ...target, version: 1, atomicRegistration: config.atomicRegistration,
      environment: config.environment as 'DEV' | 'TEST' })
  } catch { return fail() }
}

/** Public network-only config: no URL/storage switch, cookies, redirects or cache. */
export async function loadDeploymentConfig(url: URL, actualOrigin: string, secure: boolean): Promise<DeploymentConfig> {
  if (url.origin !== actualOrigin || url.search || url.hash) return fail()
  const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 3000)
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    const response = await fetch(url, { cache: 'no-store', credentials: 'omit', redirect: 'error', signal: abort.signal })
    if (response.status === 404) { await response.body?.cancel(); return disabledDeployment }
    if (response.status !== 200 || !/^application\/json(?:;|$)/i.test(response.headers.get('content-type') ?? '')
      || !/(?:^|,)\s*no-store\s*(?:,|$)/i.test(response.headers.get('cache-control') ?? '')
      || Number(response.headers.get('age') ?? '0') !== 0) { await response.body?.cancel(); return fail() }
    reader = response.body?.getReader()
    if (!reader) return fail()
    let size = 0, text = ''; const decoder = new TextDecoder('utf-8', { fatal: true })
    for (;;) {
      const item = await reader.read(); if (item.done) break
      size += item.value.length; if (size > 4096) return fail()
      text += decoder.decode(item.value, { stream: true })
    }
    text += decoder.decode()
    return parseDeploymentConfig(JSON.parse(text), actualOrigin, secure)
  } catch { return fail() }
  finally { clearTimeout(timer); await reader?.cancel().catch(() => undefined); reader?.releaseLock() }
}
