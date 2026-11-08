import { describe, expect, it } from 'vitest'
import { href, isAppPath, parseLocation, parseRoute } from '../src/router/route'

describe('routes', () => {
  it('reads the app paths', () => {
    expect(parseRoute('/')).toBe('home')
    expect(parseRoute('/scan')).toBe('scan')
    expect(parseRoute('/create/')).toBe('create')
    expect(parseRoute('/account')).toBe('account')
    expect(parseRoute('/settings')).toBe('settings')
    expect(parseRoute('/history')).toBe('history')
    expect(parseRoute('/c')).toBe('gift')
    expect(parseRoute('/gift')).toBe('gift')
    expect(parseRoute('/scan/../account')).toBe('home')
    expect(parseRoute('/legal/privacy')).toBe('home')
  })

  it('still reads old #/ links', () => {
    expect(parseRoute('/', '#/scan')).toBe('scan')
    expect(parseRoute('/', '#/create?access=secret')).toBe('create')
    expect(JSON.stringify(parseLocation('/', '#/create?access=secret'))).not.toContain('secret')
    expect(parseRoute('/', '#/inbox')).toBe('home')
    // A nick never comes from an old hash.
    expect(parseLocation('/', '#/alice').recipient).toBeNull()
  })

  it('addresses /me and /<nick>', () => {
    expect(parseLocation('/me')).toEqual({ route: 'create', recipient: { kind: 'self' } })
    expect(parseLocation('/Iren.77')).toEqual({ route: 'create', recipient: { kind: 'named', handle: 'iren.77' } })
    expect(parseLocation('/@bob_x/')).toEqual({ route: 'create', recipient: { kind: 'named', handle: 'bob_x' } })
    expect(parseLocation('/ab').route).toBe('home')
    expect(parseLocation('/.hidden').route).toBe('home')
    expect(parseLocation('/a'.padEnd(32, 'x')).route).toBe('home')
  })

  it('round-trips known routes', () => {
    for (const route of ['home', 'scan', 'create', 'account', 'settings', 'history', 'gift'] as const) {
      expect(parseRoute(href(route))).toBe(route)
      expect(isAppPath(href(route))).toBe(true)
    }
    expect(isAppPath('/legal/privacy')).toBe(false)
    expect(isAppPath('/me')).toBe(true)
  })
})
