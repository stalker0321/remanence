import type { Rect } from './model'

export interface BandLayout {
  column: boolean
  gap: number
  padTop: number
  padSide: number
  padBottom: number
  verticalText: boolean
}

export interface CoverPlacement {
  cover: Rect
  text: Rect
  fit: 'contain' | 'cover'
}

export function grammarNumber(grammar: string): string {
  return grammar.startsWith('g') ? grammar.slice(1) : grammar
}

export function isTransparentMusicBand(grammarId: string, styleId: string): boolean {
  const grammar = grammarNumber(grammarId)
  return (grammar === '5' && styleId === 'blue-letter') || (grammar === '27' && styleId === 'porcelain')
}

export function bandLayout(grammar: string, mode: string, regionHeight: number): BandLayout {
  const g = grammarNumber(grammar)
  if (regionHeight < 65) {
    if (mode === 'spine') return { column: false, gap: 7, padTop: 3, padSide: 5, padBottom: 3, verticalText: true }
    if ((g === '11' || g === '24') && (mode === 'tile-art' || mode === 'tile-text')) {
      return { column: true, gap: 7, padTop: 3, padSide: 5, padBottom: 3, verticalText: false }
    }
    return { column: false, gap: 7, padTop: 3, padSide: 5, padBottom: 3, verticalText: false }
  }
  if (mode === 'spine') return { column: false, gap: 10, padTop: 8, padSide: 3, padBottom: 8, verticalText: true }
  if (g === '11' && (mode === 'tile-art' || mode === 'tile-text')) {
    return { column: true, gap: 7, padTop: 9, padSide: 9, padBottom: 9, verticalText: false }
  }
  if (g === '24' && (mode === 'tile-art' || mode === 'tile-text')) {
    return { column: true, gap: 5, padTop: 7, padSide: 7, padBottom: 7, verticalText: false }
  }
  if (mode === 'sleeve') return { column: false, gap: 14, padTop: 9, padSide: 0, padBottom: 9, verticalText: false }
  if (mode === 'pulse') return { column: false, gap: 10, padTop: 9, padSide: 9, padBottom: 9, verticalText: false }
  if (mode === 'inline' && g === '23') return { column: false, gap: 8, padTop: 5, padSide: 7, padBottom: 5, verticalText: false }
  if (mode === 'inline') return { column: false, gap: 8, padTop: 4, padSide: 0, padBottom: 4, verticalText: false }
  if (mode === 'thumbnail' && g === '6') return { column: false, gap: 9, padTop: 5, padSide: 8, padBottom: 5, verticalText: false }
  if ((mode === 'text' || mode === 'thumbnail') && g === '12') return { column: false, gap: 9, padTop: 3, padSide: 6, padBottom: 3, verticalText: false }
  if ((mode === 'text' || mode === 'thumbnail') && g === '18') return { column: false, gap: 9, padTop: 4, padSide: 5, padBottom: 4, verticalText: false }
  if ((mode === 'text' || mode === 'thumbnail') && g === '21') return { column: false, gap: 9, padTop: 3, padSide: 3, padBottom: 3, verticalText: false }
  return { column: false, gap: 9, padTop: 7, padSide: 4, padBottom: 7, verticalText: false }
}

export function placeCover(region: Rect, grammar: string, mode: string): CoverPlacement | null {
  const spec = coverSpec(grammar, mode, region.height)
  if (!spec) return null
  const layout = bandLayout(grammar, mode, region.height)
  const contentX = region.x + layout.padSide
  const contentY = region.y + layout.padTop
  const contentW = region.width - 2 * layout.padSide
  const contentH = region.height - layout.padTop - layout.padBottom
  if (contentW < 1 || contentH < 1) return null
  if (layout.column) {
    const coverW = spec.width ?? contentW
    const textY = contentY + spec.height + layout.gap
    const textH = region.y + region.height - layout.padBottom - textY
    if (coverW < 1 || textH < 1) return null
    return {
      cover: { x: contentX, y: contentY, width: coverW, height: spec.height },
      text: { x: contentX, y: textY, width: contentW, height: textH },
      fit: spec.fit,
    }
  }
  if (spec.width === null) return null
  const coverY = contentY + Math.floor((contentH - spec.height) / 2)
  const textX = contentX + spec.width + layout.gap
  const textW = region.x + region.width - layout.padSide - textX
  if (textW < 1 || contentH < 1) return null
  return {
    cover: { x: contentX, y: coverY, width: spec.width, height: spec.height },
    text: { x: textX, y: contentY, width: textW, height: contentH },
    fit: spec.fit,
  }
}

function coverSpec(grammar: string, mode: string, regionHeight: number): { width: number | null; height: number; fit: 'contain' | 'cover' } | null {
  const g = grammarNumber(grammar)
  if (mode === 'spine' || mode === 'text' || mode === 'tile-text' || mode === 'sleeve-empty') return null
  if (regionHeight < 65) return { width: 36, height: 36, fit: 'contain' }
  if (g === '11' && mode === 'tile-art') return { width: null, height: 60, fit: 'cover' }
  if (g === '24' && mode === 'tile-art') return { width: 50, height: 50, fit: 'contain' }
  if (g === '20' && mode === 'sleeve') return { width: 120, height: 120, fit: 'contain' }
  if (g === '24' && mode === 'pulse') return { width: 57, height: 57, fit: 'contain' }
  if (mode === 'inline' && g === '23') return { width: 37, height: 37, fit: 'contain' }
  if (mode === 'inline') return { width: 39, height: 39, fit: 'contain' }
  return { width: 40, height: 40, fit: 'contain' }
}

export interface MusicLayout {
  copy: Rect
  play: Rect
}

export function musicLayout(region: Rect, grammar: string, mode: string, artwork: boolean): MusicLayout | null {
  const band = bandLayout(grammar, mode, region.height)
  const size = mode === 'spine' || region.height < 65 || grammarNumber(grammar) === '11' ? 34 : 36
  const x = region.x + band.padSide
  const y = region.y + band.padTop
  const width = region.width - 2 * band.padSide
  const height = region.height - band.padTop - band.padBottom
  if (width < size || height < size) return null
  if (mode === 'spine') return { copy: region, play: { x: x + Math.floor((width - size) / 2), y, width: size, height: size } }
  if (artwork) {
    const cover = placeCover(region, grammar, mode)
    if (!cover) return null
    const g = grammarNumber(grammar)
    const overlay = g === '11' && mode === 'tile-art'
      ? { x: region.x + 12, y: region.y + 12, width: size, height: size }
      : g === '24' && mode === 'tile-art'
        ? { x: region.x + 61, y: region.y + 15, width: size, height: size }
        : g === '20' && mode === 'sleeve'
          ? { x: region.x + 7, y: region.y + region.height - 13 - size, width: size, height: size }
          : null
    if (overlay) {
      if (overlay.x < region.x || overlay.y < region.y || overlay.x + overlay.width > region.x + region.width || overlay.y + overlay.height > region.y + region.height) return null
      return { copy: cover.text, play: overlay }
    }
    const copy = { ...cover.text, x: cover.text.x + size + band.gap, width: cover.text.width - size - band.gap }
    if (copy.width < 1) return null
    return { copy, play: { x: cover.text.x, y: y + Math.floor((height - size) / 2), width: size, height: size } }
  }
  const copy = band.column
    ? { x, y: y + size + band.gap, width, height: height - size - band.gap }
    : { x: x + size + band.gap, y, width: width - size - band.gap, height }
  if (copy.width < 1 || copy.height < 1) return null
  return { copy, play: { x, y: band.column ? y : y + Math.floor((height - size) / 2), width: size, height: size } }
}

export function roundRadii(grammar: string, width: number, height: number): number[] {
  if (grammarNumber(grammar) === '11') return [4, 4, 26, 26, 4, 4, 4, 4]
  const rx = 0.12 * width
  const ry = 0.12 * height
  return [rx, ry, rx, ry, rx, ry, rx, ry]
}

export function decorFill(kind: string): { background: 'surface' | 'sound' | 'edge'; border: 'surface' | 'sound' | 'edge' | null } {
  if (kind === 'carrier') return { background: 'surface', border: 'edge' }
  if (kind === 'acid-ribbon') return { background: 'sound', border: null }
  return { background: 'surface', border: null }
}

export function printPlaneShadow(grammar: string, surfaceMode: string): boolean {
  return !(grammarNumber(grammar) === '25' && surfaceMode === 'edge')
}

export function carrierBorderWidth(surfaceMode: string): number {
  return surfaceMode === 'edge' ? 2 : 1
}

export const SPINE_PLAY = 34
export const SPINE_COPY_GAP = 6

export function spineReadingBudget(region: Rect, band: BandLayout): number {
  return region.height - band.padTop - band.padBottom - SPINE_PLAY - band.gap
}

export function spineCrossBudget(region: Rect, band: BandLayout): number {
  return region.width - 2 * band.padSide
}
