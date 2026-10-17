import { describe, expect, it } from 'vitest'
import { nameUuidFromBytes } from '../src/protocol/uuid'

describe('bundle id binding', () => {
  it('matches Java UUID.nameUUIDFromBytes for known inputs', () => {
    expect(nameUuidFromBytes(new Uint8Array())).toBe('d41d8cd9-8f00-3204-a980-0998ecf8427e')
    expect(nameUuidFromBytes(new TextEncoder().encode('hello'))).toBe('5d41402a-bc4b-3a76-b971-9d911017c592')
  })
})
