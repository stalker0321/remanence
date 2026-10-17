import { AuthError } from '../auth/errors'
import type { ApiTransport } from '../auth/http'
import type { SessionStore } from '../session/session'

/** Server bounds: 100 Unicode code points, first page only. */
export const MUSIC_QUERY_MAX_CODE_POINTS = 100
export const MUSIC_PAGE_LIMIT = 10
export const MUSIC_PAGE_OFFSET = 0
export const MUSIC_SEARCH_DEBOUNCE_MS = 300

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const HIT_KEYS = ['id', 'title', 'artists', 'version', 'release', 'year', 'durationMs', 'artworkAvailable', 'recordingMbid']

export interface MusicHit {
  id: string
  title: string
  artists: readonly string[]
  version: string | null
  release: string | null
  year: number | null
  durationMs: number | null
  artworkAvailable: boolean
  recordingMbid: string | null
}

export type MusicSearch = { kind: 'empty' } | { kind: 'page'; hits: readonly MusicHit[]; total: number }

/**
 * First page of GET /music/v1/search.
 * A blank query does not call the network. Cover bytes are not fetched.
 */
export async function searchMusic(
  session: SessionStore,
  transport: ApiTransport,
  rawQuery: string,
  signal?: AbortSignal,
): Promise<MusicSearch> {
  const view = session.getSnapshot()
  if (view.phase === 'signed-out' || view.phase === 'authenticated' || view.accountId === null) {
    throw new AuthError('ILLEGAL_PHASE')
  }
  const query = rawQuery.trim()
  if (query.length === 0) return { kind: 'empty' }
  if ([...query].length > MUSIC_QUERY_MAX_CODE_POINTS) throw new AuthError('MUSIC_REJECTED')
  const generation = view.generation
  const accountId = view.accountId
  const live = () => {
    if (signal?.aborted) throw new AuthError('CANCELLED')
    session.bind(generation)()
    const secrets = session.secretsFor(generation)
    const current = session.getSnapshot()
    const locked = current.phase === 'signed-out' || current.phase === 'authenticated'
    if (!secrets || current.accountId !== accountId || locked) throw new AuthError('STALE')
    return secrets.accessToken
  }
  const result = await transport.request({
    path: '/music/v1/search',
    method: 'GET',
    token: live(),
    signal,
    query: { q: query, limit: String(MUSIC_PAGE_LIMIT), offset: String(MUSIC_PAGE_OFFSET) },
  })
  live()
  if (result.status === 429 || result.status === 503) throw new AuthError('MUSIC_UNAVAILABLE')
  if (result.status !== 200) throw new AuthError('MUSIC_REJECTED')
  const page = readPage(result.body)
  if (!page) throw new AuthError('MUSIC_REJECTED')
  return page
}

/** Title line only. The recording id is not part of the line. */
export function musicLine(hit: MusicHit): string {
  const names = hit.artists.join(', ')
  return hit.version ? `${hit.title} — ${names} (${hit.version})` : `${hit.title} — ${names}`
}

export function musicCoverKey(hit: MusicHit): 'musicCoverLater' | 'musicNoCover' {
  return hit.artworkAvailable ? 'musicCoverLater' : 'musicNoCover'
}

/** One selected hit for this visit. A late page cannot replace it. */
export class MusicDraft {
  #ticket = 0
  #hits: readonly MusicHit[] = []
  #selected: MusicHit | null = null

  nextTicket(): number {
    this.#ticket += 1
    this.#hits = []
    return this.#ticket
  }

  offer(ticket: number, hits: readonly MusicHit[]): boolean {
    if (ticket !== this.#ticket) return false
    this.#hits = hits.slice()
    return true
  }

  hits(): readonly MusicHit[] {
    return this.#hits
  }

  selected(): MusicHit | null {
    return this.#selected
  }

  select(id: string): boolean {
    const hit = this.#hits.find((item) => item.id === id)
    if (!hit) return false
    this.#selected = hit
    return true
  }

  remove(): void {
    this.#selected = null
  }

  wipe(): void {
    this.#ticket += 1
    this.#hits = []
    this.#selected = null
  }

  attach(session: SessionStore): () => void {
    return session.subscribe(() => {
      const view = session.getSnapshot()
      if (view.phase === 'signed-out' || view.phase === 'authenticated') this.wipe()
    })
  }
}

function readPage(body: unknown): { kind: 'page'; hits: MusicHit[]; total: number } | null {
  const root = exact(body, ['results', 'total', 'offset'])
  if (!root || !Array.isArray(root.results)) return null
  if (root.offset !== MUSIC_PAGE_OFFSET) return null
  if (root.results.length > MUSIC_PAGE_LIMIT) return null
  if (typeof root.total !== 'number' || !Number.isInteger(root.total)) return null
  if (root.total < root.results.length || root.total > 1_000_000) return null
  const hits: MusicHit[] = []
  const seen = new Set<string>()
  for (const item of root.results) {
    const hit = readHit(item)
    if (!hit || seen.has(hit.id)) return null
    seen.add(hit.id)
    hits.push(hit)
  }
  return { kind: 'page', hits, total: root.total }
}

function readHit(value: unknown): MusicHit | null {
  const body = record(value)
  if (!body) return null
  for (const key of Object.keys(body)) {
    if (!HIT_KEYS.includes(key)) return null
  }
  if (typeof body.id !== 'string' || !UUID.test(body.id)) return null
  if (typeof body.title !== 'string' || body.title.trim().length === 0 || [...body.title].length > 300) return null
  if (!Array.isArray(body.artists) || body.artists.length === 0 || body.artists.length > 16) return null
  const artists: string[] = []
  for (const name of body.artists) {
    if (typeof name !== 'string' || name.trim().length === 0 || [...name].length > 200) return null
    artists.push(name)
  }
  const version = optionalText(body, 'version')
  const release = optionalText(body, 'release')
  const year = optionalInt(body, 'year', 0, 9999)
  const durationMs = optionalInt(body, 'durationMs', 0, 86_400_000)
  if (version === undefined || release === undefined || year === undefined || durationMs === undefined) return null
  let artworkAvailable = false
  if (Object.hasOwn(body, 'artworkAvailable')) {
    if (typeof body.artworkAvailable !== 'boolean') return null
    artworkAvailable = body.artworkAvailable
  }
  let recordingMbid: string | null = null
  if (Object.hasOwn(body, 'recordingMbid') && body.recordingMbid !== null) {
    if (typeof body.recordingMbid !== 'string' || !UUID.test(body.recordingMbid)) return null
    recordingMbid = body.recordingMbid
  }
  return {
    id: body.id,
    title: body.title,
    artists,
    version,
    release,
    year,
    durationMs,
    artworkAvailable,
    recordingMbid,
  }
}

function optionalText(body: Record<string, unknown>, key: string): string | null | undefined {
  if (!Object.hasOwn(body, key) || body[key] === null) return null
  const value = body[key]
  if (typeof value !== 'string' || [...value].length > 300) return undefined
  return value
}

function optionalInt(
  body: Record<string, unknown>,
  key: string,
  min: number,
  max: number,
): number | null | undefined {
  if (!Object.hasOwn(body, key) || body[key] === null) return null
  const value = body[key]
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) return undefined
  return value
}

function record(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

function exact(value: unknown, keys: readonly string[]): Record<string, unknown> | null {
  const body = record(value)
  if (!body || Object.keys(body).length !== keys.length) return null
  for (const key of keys) if (!Object.hasOwn(body, key)) return null
  return body
}
