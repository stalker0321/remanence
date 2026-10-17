import { describe, expect, it } from 'vitest'
import { href, parseRoute } from '../src/router/route'

describe('routes', () => {
  it('reads the client hash routes', () => {
    expect(parseRoute('')).toBe('home')
    expect(parseRoute('#/')).toBe('home')
    expect(parseRoute('#/scan')).toBe('scan')
    expect(parseRoute('#/create/')).toBe('create')
    expect(parseRoute('#/account')).toBe('account')
    expect(parseRoute('#/settings')).toBe('settings')
    expect(parseRoute('#/settings/')).toBe('settings')
    expect(parseRoute('#/inbox')).toBe('home')
    expect(parseRoute('#/scan/../account')).toBe('home')
    expect(parseRoute('#/create?access=secret')).toBe('create')
    expect(JSON.stringify(parseRoute('#/create?access=secret'))).not.toContain('secret')
  })

  it('round-trips known routes', () => {
    for (const route of ['home', 'scan', 'create', 'account', 'settings'] as const) {
      expect(parseRoute(href(route))).toBe(route)
    }
  })
})
