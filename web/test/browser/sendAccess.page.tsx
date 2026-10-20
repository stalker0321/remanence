/** CLIENT_ONLY: actual App/transport; synthetic session and HTTP, no grants. */
import { store } from './sendFeedback.page'
const audit: { path: string; method: string; cache?: RequestCache; aborted: boolean; settled: boolean }[] = []
const realFetch = window.fetch.bind(window)
window.fetch = (input, init) => {
  const entry = { path: String(input), method: init?.method ?? 'GET', cache: init?.cache, aborted: init?.signal?.aborted ?? false, settled: false }
  audit.push(entry)
  init?.signal?.addEventListener('abort', () => { entry.aborted = true }, { once: true })
  return realFetch(input, init).then((value) => { entry.settled = true; return value }, (error) => { entry.settled = true; throw error })
}
Object.assign(window, { sendAccessFixture: {
  store, audit,
  switchOwner() {
    store.signIn({ accountId: 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee', bundleId: '22222222-2222-4222-8222-222222222222',
      handle: 'other_qa', accessToken: 'synthetic-other', refreshToken: 'synthetic-other-refresh',
      accessExpiresAt: '2026-10-21T00:00:00Z', refreshExpiresAt: '2026-11-21T00:00:00Z' })
    store.unlock()
  },
} })
