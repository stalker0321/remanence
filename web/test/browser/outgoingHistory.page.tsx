/** Actual App; retained golden synthetic session, no live account/provider. */
import { store, recipient } from './sendFeedback.page'
const audit: { path: string; cache?: RequestCache; method?: string }[] = []
const realFetch = window.fetch.bind(window)
window.fetch = (input, init) => {
  audit.push({ path: String(input), cache: init?.cache, method: init?.method })
  return realFetch(input, init)
}
Object.assign(window, { historyFixture: {
  store, recipient, audit,
  switchOwner() {
    store.signIn({ accountId: 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee', bundleId: '22222222-2222-4222-8222-222222222222',
      handle: 'other_qa', accessToken: 'synthetic-other', refreshToken: 'synthetic-other-refresh',
      accessExpiresAt: '2026-10-21T00:00:00Z', refreshExpiresAt: '2026-11-21T00:00:00Z' })
    store.unlock()
  },
} })
