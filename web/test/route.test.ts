import { describe, expect, it } from 'vitest'
import { href, parseRoute } from '../src/router/route'

describe('routes', () => {
  it('reads the client hash routes', () => {
    expect(parseRoute('')).toBe('home')
    expect(parseRoute('#/')).toBe('home')
    expect(parseRoute('#/scan')).toBe('scan')
    expect(parseRoute('#/create/')).toBe('create')
    expect(parseRoute('#/account')).toBe('account')
    expect(parseRoute('#/inbox')).toBe('home')
  })

  it('round-trips known routes', () => {
    for (const route of ['home', 'scan', 'create', 'account'] as const) {
      expect(parseRoute(href(route))).toBe(route)
    }
  })
})
