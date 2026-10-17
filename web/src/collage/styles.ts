import { arrayBuffer, bytesToHex } from '../protocol/bytes'
import { CanonicalWriter, roundAwayFromZero } from './canonical'
import records from './styles.json'

export interface TextRole {
  font: string
  size: number
  weight: number
  italic: boolean
}

export interface CollageStyle {
  id: string
  version: string
  grammar: number
  palette: {
    paper: string
    ink: string
    sound: string
    soundInk: string
    surface: string
    edge: string
  }
  brand: TextRole
  sender: TextRole
  note: TextRole
  music: TextRole
  tracking: number
  surfaceMode: string
}

const styles = new Map<string, CollageStyle>((records as CollageStyle[]).map((style) => [style.id, style]))
const digests = new Map<string, Promise<Uint8Array>>()

export const PALETTE_VERSION = 1

export function styleById(id: string): CollageStyle | null {
  return styles.get(id) ?? null
}

export function styleIds(): string[] {
  return [...styles.keys()]
}

export function canonicalStyleBytes(style: CollageStyle): Uint8Array {
  const out = new CanonicalWriter()
  out.ascii('STYD01')
  out.str(style.version)
  out.str(style.id)
  out.int(style.grammar)
  for (const key of ['paper', 'ink', 'sound', 'soundInk', 'surface', 'edge'] as const) {
    out.str(key)
    out.str(style.palette[key])
  }
  for (const role of [style.brand, style.sender, style.note, style.music]) {
    out.str(role.font)
    out.int(role.size)
    out.int(role.weight)
    out.byte(role.italic ? 1 : 0)
  }
  out.int(roundAwayFromZero(style.tracking * 1000))
  out.str(style.surfaceMode)
  return out.finish()
}

export function styleDigest(id: string): Promise<Uint8Array | null> {
  const style = styles.get(id)
  if (!style) return Promise.resolve(null)
  const cached = digests.get(id)
  if (cached) return cached
  const pending = crypto.subtle.digest('SHA-256', arrayBuffer(canonicalStyleBytes(style))).then((digest) => new Uint8Array(digest))
  digests.set(id, pending)
  return pending
}

export async function styleDigestHex(id: string): Promise<string | null> {
  const digest = await styleDigest(id)
  return digest ? bytesToHex(digest) : null
}

export function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false
  let diff = 0
  for (let i = 0; i < left.length; i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0)
  return diff === 0
}

export function opaqueColor(hex: string): number {
  if (!/^#[0-9a-f]{6}$/.test(hex)) throw new Error('PALETTE_COLOR')
  return (0xff000000 + Number.parseInt(hex.slice(1), 16)) >>> 0
}

export function cssColor(color: number): string {
  const value = color >>> 0
  const alpha = ((value >>> 24) & 255) / 255
  return `rgba(${(value >>> 16) & 255}, ${(value >>> 8) & 255}, ${value & 255}, ${alpha})`
}
