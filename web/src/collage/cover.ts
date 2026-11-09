import type { ArtworkRef } from './model'
import { arrayBuffer } from '../protocol/bytes'
import { equalBytes } from './styles'

/**
 * Song covers, the way Android handles them (`MusicBrainzCoverArtResolver`,
 * `HttpArtworkFetcher`). The server never fetches, proxies or stores a cover:
 * the sender's device looks the cover up on MusicBrainz and the Cover Art
 * Archive and seals its stable archive.org URL, SHA-256 and size; the
 * recipient's device downloads that URL and keeps it only if the bytes match.
 */

export const COVER_PROVIDER = 'coverartarchive'
const MUSICBRAINZ = 'https://musicbrainz.org/ws/2/recording/'
const COVER_ART = 'https://coverartarchive.org/release-group/'
const COVER_THUMB = 'front-500'
const MAX_METADATA_BYTES = 1024 * 1024
/** Aligned with Android's sealing ceiling. */
export const MAX_COVER_BYTES = 10 * 1024 * 1024
const ARTWORK_DIM_MAX = 4096
const ARTWORK_URL_MAX = 2048
const UNKNOWN_DATE = '9999-99-99'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])

export interface HeldCover {
  ref: ArtworkRef
  bytes: Uint8Array
}

interface RecordingLookup {
  releases?: Array<{ date?: string | null; 'release-group'?: { id?: string } | null }>
}

/** Earliest known release date, unknown dates last, release-group id as the tie-break. */
export function pickReleaseGroup(lookup: RecordingLookup): string | null {
  const candidates = (lookup.releases ?? [])
    // Like Android, only a missing date sorts last; an empty one sorts first.
    .map((release) => ({ date: release.date ?? UNKNOWN_DATE, id: release['release-group']?.id ?? '' }))
    .filter((candidate) => UUID.test(candidate.id))
  candidates.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
  return candidates[0]?.id ?? null
}

/**
 * The URL Android seals: the `archive.org/download/…` hop of the cover's
 * redirect chain. A browser only reports the last hop
 * (`https://<node>.archive.org/<n>/items/<item>/<file>`), which maps back to
 * that hop one to one.
 */
export function canonicalCoverUrl(finalUrl: string): string | null {
  let url: URL
  try {
    url = new URL(finalUrl)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' || url.search || url.hash || url.username || url.password) return null
  if (url.hostname === 'archive.org' && url.pathname.startsWith('/download/')) return url.href
  const node = /^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.archive\.org$/.test(url.hostname)
  const match = /^\/\d+\/items\/([^/]+)\/([^/]+)$/.exec(url.pathname)
  if (!node || !match) return null
  return `https://archive.org/download/${match[1]}/${match[2]}`
}

async function boundedBytes(response: Response, max: number): Promise<Uint8Array | null> {
  const declared = Number(response.headers.get('content-length') ?? '0')
  if (declared > max) return null
  const bytes = new Uint8Array(await response.arrayBuffer())
  return bytes.length > max ? null : bytes
}

async function imageSize(bytes: Uint8Array, mimeType: string): Promise<{ width: number; height: number } | null> {
  try {
    const image = await createImageBitmap(new Blob([arrayBuffer(bytes)], { type: mimeType }))
    const size = { width: image.width, height: image.height }
    image.close()
    if (size.width < 1 || size.height < 1 || size.width > ARTWORK_DIM_MAX || size.height > ARTWORK_DIM_MAX) return null
    return size
  } catch {
    return null
  }
}

function mediaType(header: string | null): string | null {
  const type = (header ?? '').split(';')[0]!.trim().toLowerCase()
  return MIME_TYPES.has(type) ? type : null
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes)))
}

/**
 * The sender's cover for a recording, or null: any provider or network
 * failure leaves the song as a text band, exactly like Android.
 */
export async function resolveCover(recordingMbid: string | null, signal?: AbortSignal): Promise<HeldCover | null> {
  if (recordingMbid === null || !UUID.test(recordingMbid)) return null
  try {
    const metadata = await fetch(`${MUSICBRAINZ}${recordingMbid}?inc=releases+release-groups&fmt=json`, {
      credentials: 'omit', referrerPolicy: 'no-referrer', signal,
    })
    if (!metadata.ok) return null
    const body = await boundedBytes(metadata, MAX_METADATA_BYTES)
    if (body === null) return null
    const releaseGroup = pickReleaseGroup(JSON.parse(new TextDecoder().decode(body)) as RecordingLookup)
    if (releaseGroup === null) return null
    const cover = await fetch(`${COVER_ART}${releaseGroup}/${COVER_THUMB}`, { credentials: 'omit', referrerPolicy: 'no-referrer', signal })
    if (!cover.ok) return null
    const url = canonicalCoverUrl(cover.url)
    const mimeType = mediaType(cover.headers.get('content-type'))
    const bytes = await boundedBytes(cover, MAX_COVER_BYTES)
    if (url === null || url.length > ARTWORK_URL_MAX || mimeType === null || bytes === null) return null
    const size = await imageSize(bytes, mimeType)
    if (size === null) return null
    return { ref: { provider: COVER_PROVIDER, url, sha256: await sha256(bytes), mimeType, width: size.width, height: size.height }, bytes }
  } catch {
    return null
  }
}

/**
 * The recipient's side: download a sealed cover and keep it only when the
 * bytes hash to the sealed SHA-256 and decode to the sealed size.
 */
export async function fetchSealedCover(ref: ArtworkRef, signal?: AbortSignal): Promise<{ bytes: Uint8Array; width: number; height: number } | null> {
  if (ref.provider !== COVER_PROVIDER || canonicalCoverUrl(ref.url) !== ref.url) return null
  try {
    const response = await fetch(ref.url, { credentials: 'omit', referrerPolicy: 'no-referrer', signal })
    if (!response.ok) return null
    const bytes = await boundedBytes(response, MAX_COVER_BYTES)
    if (bytes === null || !equalBytes(await sha256(bytes), ref.sha256)) return null
    const size = await imageSize(bytes, ref.mimeType)
    if (size === null || size.width !== ref.width || size.height !== ref.height) return null
    return { bytes, width: size.width, height: size.height }
  } catch {
    return null
  }
}
