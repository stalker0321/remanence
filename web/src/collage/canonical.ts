import { concat } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'

const MAX_BYTES = 65536

export class CanonicalWriter {
  readonly #parts: Uint8Array[] = []

  ascii(value: string): void {
    for (let i = 0; i < value.length; i++) {
      if (value.charCodeAt(i) > 127) throw new ProtocolError('INVALID_ENCODING')
    }
    this.#parts.push(new TextEncoder().encode(value))
  }

  byte(value: number): void {
    if (!Number.isInteger(value) || value < 0 || value > 255) throw new ProtocolError('INVALID_ENCODING')
    this.#parts.push(Uint8Array.of(value))
  }

  int(value: number): void {
    if (!Number.isInteger(value) || value < -0x80000000 || value > 0x7fffffff) throw new ProtocolError('INVALID_ENCODING')
    const bytes = new Uint8Array(4)
    new DataView(bytes.buffer).setInt32(0, value, false)
    this.#parts.push(bytes)
  }

  long(value: bigint): void {
    const bytes = new Uint8Array(8)
    new DataView(bytes.buffer).setBigInt64(0, value, false)
    this.#parts.push(bytes)
  }

  str(value: string): void {
    const bytes = new TextEncoder().encode(value)
    this.int(bytes.length)
    this.#parts.push(bytes)
  }

  raw(value: Uint8Array): void {
    this.#parts.push(value.slice())
  }

  finish(): Uint8Array {
    const bytes = concat(this.#parts)
    if (bytes.length > MAX_BYTES) throw new ProtocolError('OVERSIZED')
    return bytes
  }
}

export class CanonicalReader {
  #position = 0

  constructor(private readonly bytes: Uint8Array) {
    if (bytes.length > MAX_BYTES) throw new ProtocolError('OVERSIZED')
  }

  get finished(): boolean {
    return this.#position === this.bytes.length
  }

  ascii(length: number): string {
    const raw = this.raw(length)
    let text = ''
    for (const piece of raw) {
      if (piece > 127) throw new ProtocolError('INVALID_ENCODING')
      text += String.fromCharCode(piece)
    }
    return text
  }

  byte(): number {
    return this.raw(1)[0] ?? 0
  }

  int(): number {
    const raw = this.raw(4)
    return new DataView(raw.buffer, raw.byteOffset, 4).getInt32(0, false)
  }

  long(): bigint {
    const raw = this.raw(8)
    return new DataView(raw.buffer, raw.byteOffset, 8).getBigInt64(0, false)
  }

  str(): string {
    const length = this.int()
    if (length < 0 || length > MAX_BYTES) throw new ProtocolError('INVALID_ENCODING')
    return new TextDecoder('utf-8', { fatal: true }).decode(this.raw(length))
  }

  raw(length: number): Uint8Array {
    if (!Number.isInteger(length) || length < 0 || this.#position + length > this.bytes.length) {
      throw new ProtocolError('TRUNCATED')
    }
    const out = this.bytes.slice(this.#position, this.#position + length)
    this.#position += length
    return out
  }
}

export function roundAwayFromZero(value: number): number {
  return Math.sign(value) * Math.round(Math.abs(value))
}
