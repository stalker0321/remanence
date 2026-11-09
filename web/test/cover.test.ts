import { afterEach, describe, expect, it, vi } from 'vitest'
import { canonicalCoverUrl, fetchSealedCover, pickReleaseGroup } from '../src/collage/cover'

describe('song covers', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('picks the earliest dated release group, unknown dates last, id as the tie-break', () => {
    const a = '11111111-1111-4111-8111-111111111111'
    const b = '22222222-2222-4222-8222-222222222222'
    const c = '33333333-3333-4333-8333-333333333333'
    expect(pickReleaseGroup({ releases: [
      { date: null, 'release-group': { id: a } },
      { date: '1975-11-21', 'release-group': { id: c } },
      { date: '1975-11-21', 'release-group': { id: b } },
      { date: '1970', 'release-group': { id: 'not-a-uuid' } },
    ] })).toBe(b)
    expect(pickReleaseGroup({ releases: [] })).toBeNull()
  })

  it('maps the browser-visible last hop back to the archive.org download URL Android seals', () => {
    expect(canonicalCoverUrl('https://dn710607.ca.archive.org/0/items/mbid-956f/mbid-956f-34025419985_thumb500.jpg'))
      .toBe('https://archive.org/download/mbid-956f/mbid-956f-34025419985_thumb500.jpg')
    expect(canonicalCoverUrl('https://archive.org/download/x/y.jpg')).toBe('https://archive.org/download/x/y.jpg')
    expect(canonicalCoverUrl('https://evil.example/0/items/x/y.jpg')).toBeNull()
    expect(canonicalCoverUrl('http://dn1.ca.archive.org/0/items/x/y.jpg')).toBeNull()
    expect(canonicalCoverUrl('https://dn1.ca.archive.org/0/items/x/y.jpg?t=1')).toBeNull()
  })

  it('keeps a downloaded cover only when its bytes hash to the sealed digest', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new Uint8Array([1, 2, 3]), { status: 200 })))
    const cover = await fetchSealedCover({
      provider: 'coverartarchive',
      url: 'https://archive.org/download/x/y.jpg',
      sha256: new Uint8Array(32),
      mimeType: 'image/jpeg',
      width: 500,
      height: 500,
    })
    expect(cover).toBeNull()
    expect(await fetchSealedCover({
      provider: 'catalog-sample', url: 'https://archive.org/download/x/y.jpg', sha256: new Uint8Array(32), mimeType: 'image/jpeg', width: 1, height: 1,
    })).toBeNull()
  })
})
