import { limits } from '../protocol/limits'

const PHOTO_PLAINTEXT_MAX = limits.photoCiphertext - limits.artifactOverhead

/** SOF0 / SOF2 size. A missing or truncated marker is not a guessed dimension. */
export function jpegSize(bytes: Uint8Array): { width: number; height: number } | null {
  if (bytes.length < 4 || bytes.length > PHOTO_PLAINTEXT_MAX || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null
  let pos = 2
  while (pos + 4 < bytes.length) {
    if (bytes[pos] !== 0xff) return null
    pos += 1
    while (pos < bytes.length && bytes[pos] === 0xff) pos += 1
    const marker = bytes[pos++] ?? 0
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd9)) {
      if (marker === 0xd9 || marker === 0xda) return null
      continue
    }
    if (pos + 1 >= bytes.length) return null
    const length = ((bytes[pos] ?? 0) << 8) | (bytes[pos + 1] ?? 0)
    if (length < 2 || pos + length > bytes.length) return null
    if (marker === 0xc0 || marker === 0xc2) {
      if (length < 7) return null
      const height = ((bytes[pos + 3] ?? 0) << 8) | (bytes[pos + 4] ?? 0)
      const width = ((bytes[pos + 5] ?? 0) << 8) | (bytes[pos + 6] ?? 0)
      if (width < 1 || height < 1) return null
      return { width, height }
    }
    pos += length
  }
  return null
}

/** Minimal SOF0 bytes for a dimension gate. Not a drawable photograph. */
export function jpegDimensionFixture(width: number, height: number): Uint8Array {
  return Uint8Array.of(
    0xff, 0xd8,
    0xff, 0xc0, 0x00, 11, 8,
    (height >> 8) & 255, height & 255,
    (width >> 8) & 255, width & 255,
    1, 1, 0x11, 0,
    0xff, 0xd9,
  )
}
