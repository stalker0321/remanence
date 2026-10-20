import { describe, expect, it } from 'vitest'
import { PresentationHistory, type HistoryPort } from '../src/router/presentationHistory'

function fixture() {
  const entries: unknown[] = [{ external: 'preserved' }]
  let at = 0
  let sequence = 0
  let url = 'https://fixture.invalid/#/scan'
  const moves: number[] = []
  const port: HistoryPort = {
    state: () => entries[at],
    url: () => url,
    id: () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`,
    write(mode, state) {
      if (mode === 'push') { entries.splice(at + 1); entries.push(state); at++ }
      else entries[at] = state
    },
    go(delta) { moves.push(delta) },
  }
  const nav = new PresentationHistory(port)
  nav.initialize()
  const owner = {}
  let closed = 0
  const close = () => { closed++; nav.present(null, () => undefined) }
  return { nav, port, owner, entries, moves, close, closed: () => closed, at: () => at,
    flush() { const delta = moves.shift(); if (delta !== undefined) { at += delta; nav.navigated() } },
    pop(delta: number) { at += delta; nav.navigated() },
    route() { url = 'https://fixture.invalid/#/settings'; entries.push(null); at = entries.length - 1; nav.navigated() },
  }
}
describe('ephemeral presentation history', () => {
  it('pushes once per owner, preserves other state, uses no content in markers', () => {
    const h = fixture()
    const sensitive = { bytes: 'private photo', token: 'secret' }
    h.nav.present(sensitive, h.close)
    h.nav.present(sensitive, h.close)
    expect(h.entries).toHaveLength(2)
    expect(h.entries.every((entry) => (entry as { external: string }).external === 'preserved')).toBe(true)
    expect(JSON.stringify(h.entries)).not.toMatch(/private photo|secret|bytes|token/)
  })
  it('back and forward of the photo do not lock or release its sheet', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.nav.openPhoto(1)
    h.nav.selectPhoto(2)
    expect(h.entries).toHaveLength(3)
    h.pop(-1)
    expect(h.nav.getSnapshot()).toEqual({ owner: h.owner, index: null })
    h.pop(1)
    expect(h.nav.getSnapshot()).toEqual({ owner: h.owner, index: 2 })
    expect(h.closed()).toBe(0)
  })
  it('retired forward markers bounce to base without recreating the sheet', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.pop(-1)
    expect(h.closed()).toBe(1)
    h.pop(1)
    expect(h.nav.getSnapshot()).toBeNull()
    h.flush()
    expect(h.at()).toBe(0)
    expect(h.closed()).toBe(1)
  })
  it('explicit photo close consumes one back only even when clicked twice', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.nav.openPhoto(0)
    h.nav.closePhoto()
    h.nav.closePhoto()
    expect(h.moves).toEqual([-1])
    h.flush()
    expect(h.nav.getSnapshot()?.index).toBeNull()
    expect(h.closed()).toBe(0)
  })
  it('explicit sheet close from a photo consumes two layers exactly once', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.nav.openPhoto(0)
    h.nav.closeSheet()
    h.nav.closeSheet()
    expect(h.moves).toEqual([-2])
    expect(h.closed()).toBe(1)
    h.flush()
    expect(h.at()).toBe(0)
    h.nav.present({}, h.close)
    expect(h.entries).toHaveLength(2)
  })
  it('replacement waits for old unwind and never invokes the old close callback', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.nav.openPhoto(1)
    const newer = {}
    h.nav.present(newer, () => undefined)
    expect(h.nav.getSnapshot()).toBeNull()
    h.flush()
    expect(h.closed()).toBe(0)
    expect(h.nav.getSnapshot()).toEqual({ owner: newer, index: null })
    expect(h.entries).toHaveLength(2)
  })
  it('route changes close the sheet without navigating away from the new route', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.route()
    expect(h.closed()).toBe(1)
    expect(h.moves).toEqual([])
    expect(h.nav.getSnapshot()).toBeNull()
  })
  it('external lock unwinds layers but does not call close on the replacement session', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.nav.openPhoto(1)
    h.nav.present(null, h.close)
    expect(h.moves).toEqual([-2])
    h.flush()
    expect(h.closed()).toBe(0)
  })
  it('a reloaded document discards stale markers with no duplicate initialize traversal', () => {
    const h = fixture()
    h.nav.present(h.owner, h.close)
    h.nav.openPhoto(1)
    const reload = new PresentationHistory(h.port)
    reload.initialize()
    reload.initialize()
    expect(h.moves).toEqual([-2])
    expect(reload.getSnapshot()).toBeNull()
  })
  it('explicit close remains available after pushState fails', () => {
    const h = fixture()
    h.port.write = () => { throw new Error('history denied') }
    h.nav.present(h.owner, h.close)
    h.nav.openPhoto(0)
    h.nav.closePhoto()
    expect(h.nav.getSnapshot()?.index).toBeNull()
    h.nav.closeSheet()
    expect(h.closed()).toBe(1)
    expect(h.moves).toEqual([])
  })
})
