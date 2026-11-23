import { describe, expect, it } from 'vitest'
import { crc32, zipStore } from '../src/collage/zipStore'

describe('zipStore', () => {
  it('computes the standard CRC-32', () => {
    expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926)
  })

  it('writes stored entries a reader can walk from the central directory', () => {
    const entries = [
      { name: 'postcard.png', bytes: Uint8Array.from([1, 2, 3]) },
      { name: 'letter.txt', bytes: new TextEncoder().encode('Дорогая мама') },
      { name: 'video.mp4', bytes: new Uint8Array(1000).fill(7) },
    ]
    const zip = zipStore(entries)
    const view = new DataView(zip.buffer)
    const end = zip.length - 22
    expect(view.getUint32(end, true)).toBe(0x06054b50)
    expect(view.getUint16(end + 10, true)).toBe(3)
    let at = view.getUint32(end + 16, true)
    for (const entry of entries) {
      expect(view.getUint32(at, true)).toBe(0x02014b50)
      const nameLength = view.getUint16(at + 28, true)
      expect(new TextDecoder().decode(zip.subarray(at + 46, at + 46 + nameLength))).toBe(entry.name)
      const local = view.getUint32(at + 42, true)
      expect(view.getUint32(local, true)).toBe(0x04034b50)
      const data = local + 30 + view.getUint16(local + 26, true)
      expect([...zip.subarray(data, data + entry.bytes.length)]).toEqual([...entry.bytes])
      expect(view.getUint32(at + 16, true)).toBe(crc32(entry.bytes))
      at += 46 + nameLength
    }
    expect(() => zipStore([{ name: 'письмо.txt', bytes: new Uint8Array(1) }])).toThrow()
  })
})
