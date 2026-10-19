/**
 * T312 — deterministic synthetic canonical frame for the extraction proof.
 *
 * Produces an already-canonical RGBA frame (the extractor's real input type) with
 * enough high-contrast structure that a real SIFT detector returns a healthy,
 * spatially distributed feature set. Fully deterministic for a given seed so the
 * same frame -> same fingerprint, and a different seed -> a differently textured
 * frame. No photographs, no real-user data, no keys.
 */

export interface CanonicalFrameFixture {
  readonly pixels: Uint8Array
  readonly width: number
  readonly height: number
}

function hashUnit(x: number, y: number, seed: number): number {
  let h = (x * 374761393 + y * 668265263 + seed * 1013904223) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177) | 0
  h ^= h >>> 16
  return (h >>> 0) / 4294967296
}

function clampByte(value: number): number {
  return value < 0 ? 0 : value > 255 ? 255 : value | 0
}

function drawDisc(pixels: Uint8Array, width: number, height: number, cx: number, cy: number, radius: number, value: number, ring: boolean): void {
  const r2 = radius * radius
  const inner = ring ? (radius - 3) * (radius - 3) : -1
  for (let y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
    for (let x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
      const d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
      if (d <= r2 && d >= inner) {
        const base = (y * width + x) * 4
        pixels[base] = value
        pixels[base + 1] = clampByte(value * 0.9)
        pixels[base + 2] = clampByte(value * 0.8)
        pixels[base + 3] = 255
      }
    }
  }
}

function drawRect(pixels: Uint8Array, width: number, height: number, x0: number, y0: number, w: number, h: number, value: number): void {
  for (let y = Math.max(0, y0); y < Math.min(height, y0 + h); y++) {
    for (let x = Math.max(0, x0); x < Math.min(width, x0 + w); x++) {
      const base = (y * width + x) * 4
      pixels[base] = value
      pixels[base + 1] = clampByte(255 - value)
      pixels[base + 2] = clampByte(value ^ 0x55)
      pixels[base + 3] = 255
    }
  }
}

/**
 * Builds a deterministic textured RGBA frame. `seed` selects a distinct texture;
 * `blank: true` returns a uniform mid-grey frame (no detectable features).
 */
export function canonicalFrame(
  width: number,
  height: number,
  seed = 0,
  blank = false,
): CanonicalFrameFixture {
  const pixels = new Uint8Array(width * height * 4)
  if (blank) {
    for (let index = 0; index < width * height; index++) {
      const base = index * 4
      pixels[base] = 128
      pixels[base + 1] = 128
      pixels[base + 2] = 128
      pixels[base + 3] = 255
    }
    return { pixels, width, height }
  }

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const texture =
        110 +
        55 * Math.sin(x * 0.13 + seed) * Math.cos(y * 0.11 - seed) +
        35 * Math.sin((x + y) * 0.05 + seed * 0.7) +
        25 * (hashUnit(x >> 1, y >> 1, seed) - 0.5)
      const value = clampByte(texture)
      const base = (y * width + x) * 4
      pixels[base] = value
      pixels[base + 1] = clampByte(value + 12 * Math.sin(y * 0.07))
      pixels[base + 2] = clampByte(255 - value)
      pixels[base + 3] = 255
    }
  }

  const shapes = 14 + (seed % 5)
  for (let index = 0; index < shapes; index++) {
    const cx = 12 + Math.floor(hashUnit(index, 7, seed) * (width - 24))
    const cy = 12 + Math.floor(hashUnit(index, 13, seed) * (height - 24))
    const radius = 5 + Math.floor(hashUnit(index, 19, seed) * 18)
    const value = hashUnit(index, 23, seed) > 0.5 ? 255 : 0
    if (index % 2 === 0) drawDisc(pixels, width, height, cx, cy, radius, value, true)
    else drawDisc(pixels, width, height, cx, cy, radius, value, false)
  }
  for (let index = 0; index < 6; index++) {
    const x0 = Math.floor(hashUnit(index, 31, seed) * (width - 40))
    const y0 = Math.floor(hashUnit(index, 37, seed) * (height - 40))
    drawRect(pixels, width, height, x0, y0, 8 + (index * 3), 6 + (index * 2), index % 2 === 0 ? 255 : 0)
  }
  return { pixels, width, height }
}
