import { describe, expect, it } from 'vitest'
import { AuthError } from '../src/auth/errors'
import type { ApiRequest, ApiResult, ApiTransport } from '../src/auth/http'
import { MusicDraft, musicCoverKey, musicLine, searchMusic } from '../src/create/music'
import { SessionStore } from '../src/session/session'

const ACCOUNT = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const BUNDLE = '22222222-2222-4222-8222-222222222222'
const ID = '11111111-1111-4111-8111-111111111111'
const OTHER = '33333333-3333-4333-8333-333333333333'
const MBID = 'abcdefab-cdef-4abc-8def-abcdefabcdef'

function scripted(handle: (input: ApiRequest) => ApiResult): { transport: ApiTransport; calls: ApiRequest[] } {
  const calls: ApiRequest[] = []
  return {
    calls,
    transport: {
      request(input) {
        calls.push(input)
        return Promise.resolve(handle(input))
      },
    },
  }
}

function unlock(session: SessionStore): void {
  session.signIn({
    accountId: ACCOUNT,
    bundleId: BUNDLE,
    handle: 'ada',
    accessToken: 'access-token-value',
    refreshToken: 'refresh-token-value',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
}

function hit(id = ID, recordingMbid: string | null = MBID) {
  return {
    id,
    title: 'Night',
    artists: ['Ada', 'Bea'],
    version: 'live',
    release: null,
    year: 1998,
    durationMs: 180000,
    artworkAvailable: true,
    recordingMbid,
  }
}

function page(results: unknown[], total = results.length) {
  return { results, total, offset: 0 }
}

describe('music search', () => {
  it('keeps the first page and does not request a blank or overlong query', async () => {
    const session = new SessionStore()
    unlock(session)
    const { transport, calls } = scripted((input) => {
      expect(input.token).toBe('access-token-value')
      expect(input.path).toBe('/music/v1/search')
      expect(input.query).toEqual({ q: input.query?.q, limit: '10', offset: '0' })
      return { status: 200, body: page([hit()]) }
    })
    expect(await searchMusic(session, transport, '   ')).toEqual({ kind: 'empty' })
    await expect(searchMusic(session, transport, 'я'.repeat(101))).rejects.toMatchObject({ code: 'MUSIC_REJECTED' })
    await expect(searchMusic(session, transport, '🙂'.repeat(101))).rejects.toMatchObject({ code: 'MUSIC_REJECTED' })
    expect(calls).toHaveLength(0)
    const found = await searchMusic(session, transport, '  night  ')
    expect(found).toMatchObject({
      kind: 'page',
      total: 1,
      hits: [{ id: ID, title: 'Night', artworkAvailable: true, recordingMbid: MBID }],
    })
    expect(calls[0]?.query).toEqual({ q: 'night', limit: '10', offset: '0' })
    if (found.kind !== 'page') return
    const line = musicLine(found.hits[0]!)
    expect(line).toBe('Night — Ada, Bea (live)')
    expect(line).not.toContain(MBID)
    expect(JSON.stringify(line)).not.toContain('http')
    expect(musicCoverKey(found.hits[0]!)).toBe('musicCoverLater')
    const quiet = await searchMusic(session, scripted(() => ({ status: 200, body: page([hit(OTHER, null)]) })).transport, '🙂'.repeat(100))
    if (quiet.kind !== 'page') throw new Error('expected a page')
    expect(quiet.hits[0]?.recordingMbid).toBeNull()
    expect(musicCoverKey({ ...quiet.hits[0]!, artworkAvailable: false })).toBe('musicNoCover')
    const empty = await searchMusic(session, scripted(() => ({ status: 200, body: page([]) })).transport, 'none')
    expect(empty).toEqual({ kind: 'page', hits: [], total: 0 })
  })

  it('rejects a bad page and treats an outage as unavailable', async () => {
    const session = new SessionStore()
    unlock(session)
    const cases: Array<[string, unknown]> = [
      ['root artwork url', { ...page([hit()]), artworkUrl: 'https://example.invalid/cover.jpg' }],
      ['hit artwork url', page([{ ...hit(), artworkUrl: 'https://example.invalid/cover.jpg' }])],
      ['uppercase recording id', page([{ ...hit(), recordingMbid: MBID.toUpperCase() }])],
      ['empty artists', page([{ ...hit(), artists: [] }])],
      ['duplicate id', page([hit(), hit()])],
      ['wrong offset', { results: [hit()], total: 1, offset: 1 }],
      ['second page', page(Array.from({ length: 11 }, (_, index) => hit(`11111111-1111-4111-8111-${String(index).padStart(12, '0')}`)))],
      ['total under the page', page([hit()], 0)],
    ]
    for (const [name, body] of cases) {
      const { transport } = scripted(() => ({ status: 200, body }))
      await expect(searchMusic(session, transport, name), name).rejects.toMatchObject({ code: 'MUSIC_REJECTED' })
    }
    const denied = scripted(() => ({ status: 401, body: { code: 'AUTH_INVALID', query: 'secret-query-value' } }))
    try {
      await searchMusic(session, denied.transport, 'secret-query-value')
      throw new Error('expected a refusal')
    } catch (error) {
      expect(error).toMatchObject({ code: 'MUSIC_REJECTED' })
      expect(String(error)).not.toContain('secret-query-value')
    }
    const invalid = scripted(() => ({ status: 422, body: { code: 'VALIDATION_FAILED' } }))
    await expect(searchMusic(session, invalid.transport, 'secret-query-value')).rejects.toMatchObject({ code: 'MUSIC_REJECTED' })
    const down = scripted(() => ({ status: 503, body: { results: [], total: 0, offset: 0 } }))
    await expect(searchMusic(session, down.transport, 'secret-query-value')).rejects.toMatchObject({ code: 'MUSIC_UNAVAILABLE' })
    const limited = scripted(() => ({ status: 429, body: { code: 'RATE_LIMITED' } }))
    await expect(searchMusic(session, limited.transport, 'secret-query-value')).rejects.toMatchObject({ code: 'MUSIC_UNAVAILABLE' })
    session.lock()
    const locked = scripted(() => ({ status: 200, body: page([hit()]) }))
    await expect(searchMusic(session, locked.transport, 'night')).rejects.toMatchObject({ code: 'ILLEGAL_PHASE' })
    expect(locked.calls).toHaveLength(0)
  })

  it('keeps one selected hit and drops a late page when the visit ends', async () => {
    const session = new SessionStore()
    unlock(session)
    const chosen = {
      id: ID,
      title: 'Night',
      artists: ['Ada'],
      version: null,
      release: null,
      year: null,
      durationMs: null,
      artworkAvailable: false,
      recordingMbid: MBID,
    }
    const other = { ...chosen, id: OTHER, title: 'Day' }
    const draft = new MusicDraft()
    draft.attach(session)
    const first = draft.nextTicket()
    expect(draft.offer(first, [chosen])).toBe(true)
    expect(draft.select(ID)).toBe(true)
    expect(draft.select('missing')).toBe(false)
    expect(musicLine(draft.selected()!)).not.toContain(MBID)
    const second = draft.nextTicket()
    expect(draft.hits()).toEqual([])
    expect(draft.selected()?.id).toBe(ID)
    expect(draft.offer(first, [other])).toBe(false)
    expect(draft.selected()?.title).toBe('Night')
    expect(draft.offer(second, [other])).toBe(true)
    expect(draft.select(OTHER)).toBe(true)
    draft.remove()
    expect(draft.selected()).toBeNull()
    const held = draft.nextTicket()
    expect(draft.offer(held, [chosen])).toBe(true)
    draft.select(ID)
    const { transport, calls } = scripted(() => {
      session.signOut()
      return { status: 200, body: page([hit(OTHER)]) }
    })
    await expect(searchMusic(session, transport, 'night')).rejects.toMatchObject({ code: 'STALE' })
    expect(calls).toHaveLength(1)
    expect(draft.selected()).toBeNull()
    expect(draft.hits()).toEqual([])
    expect(String(new AuthError('STALE'))).not.toContain('night')
  })
})
