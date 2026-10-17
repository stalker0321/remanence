import { concat } from './bytes'
import { ProtocolError } from './errors'

export function protoVarint(field: number, value: number): Uint8Array {
  return concat([varint((field << 3) | 0), varint(value)])
}

export function protoBytes(field: number, value: Uint8Array): Uint8Array {
  return concat([varint((field << 3) | 2), varint(value.length), value])
}

export function zigzag32(value: number): number {
  if (!Number.isInteger(value) || value < -0x80000000 || value > 0x7fffffff) {
    throw new ProtocolError('INVALID_ORDINAL')
  }
  return ((value << 1) ^ (value >> 31)) >>> 0
}

/** Bounded single-level parser. Duplicate and unexpected fields fail closed. */
export function readMessage(bytes: Uint8Array, allowed: readonly number[]): Map<number, number | Uint8Array> {
  if (bytes.length > 65536) throw new ProtocolError('OVERSIZED')
  const fields = new Map<number, number | Uint8Array>()
  let pos = 0
  const integer = (): number => {
    let value = 0
    let factor = 1
    for (let i = 0; i < 5; i++) {
      if (pos >= bytes.length) throw new ProtocolError('INVALID_PROTO')
      const piece = bytes[pos++]
      value += (piece & 127) * factor
      if ((piece & 128) === 0) {
        if (!Number.isSafeInteger(value) || value > 0xffffffff) throw new ProtocolError('INVALID_PROTO')
        return value
      }
      factor *= 128
    }
    throw new ProtocolError('INVALID_PROTO')
  }
  while (pos < bytes.length) {
    const tag = integer()
    const field = Math.floor(tag / 8)
    const wire = tag % 8
    if (!allowed.includes(field) || fields.has(field)) throw new ProtocolError('INVALID_PROTO')
    if (wire === 0) fields.set(field, integer())
    else if (wire === 2) {
      const length = integer()
      if (length > bytes.length - pos) throw new ProtocolError('INVALID_PROTO')
      fields.set(field, bytes.slice(pos, pos + length))
      pos += length
    } else {
      throw new ProtocolError('INVALID_PROTO')
    }
  }
  return fields
}

export function fieldBytes(fields: Map<number, number | Uint8Array>, field: number): Uint8Array {
  const value = fields.get(field)
  if (!(value instanceof Uint8Array)) throw new ProtocolError('INVALID_PROTO')
  return value
}

export function fieldNumber(fields: Map<number, number | Uint8Array>, field: number, fallback?: number): number {
  const value = fields.get(field)
  if (value === undefined) {
    if (fallback === undefined) throw new ProtocolError('INVALID_PROTO')
    return fallback
  }
  if (typeof value !== 'number') throw new ProtocolError('INVALID_PROTO')
  return value
}

function varint(value: number): Uint8Array {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) throw new ProtocolError('INVALID_PROTO')
  const out: number[] = []
  let rest = value
  while (rest > 127) {
    out.push((rest & 127) | 128)
    rest = Math.floor(rest / 128)
  }
  out.push(rest)
  return Uint8Array.from(out)
}
