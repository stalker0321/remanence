import { describe, expect, it } from 'vitest'
import { ALPHABET, formatCode, openSeal, parseCode, parseSeal } from '../src/gift/cardSeal'
import { toE164 } from '../src/gift/phone'
import vector from '../../protocol/fixtures/card-seal-v1.json'


const bytes = (key: keyof typeof vector) => Uint8Array.from(atob(vector[key] as string), (ch) => ch.charCodeAt(0))

describe('card seal', () => {
  it('matches the reference alphabet and format', () => {
    expect(ALPHABET).toBe(vector.alphabet)
    expect(formatCode(vector.secret, vector.phone)).toBe(vector.code_display)
  })

  it('forgives handwriting the same way as the reference', () => {
    for (const [typed, [secret, hint]] of Object.entries(vector.handwriting_inputs) as [string, [string, string]][]) {
      expect(parseCode(typed)).toEqual({ secret, hintDigits: hint })
    }
    for (const bad of vector.invalid_inputs) expect(parseCode(bad)).toBeNull()
  })

  it('opens the reference seal only with the right code', async () => {
    const seal = parseSeal(bytes('seal_b64'))
    const opened = await openSeal(vector.secret, seal)
    expect(opened).not.toBeNull()
    expect([...opened!.keyset]).toEqual([...bytes('keyset_b64')])
    expect([...opened!.opening]).toEqual([...bytes('opening_b64')])
    expect([...opened!.proof]).toEqual([...bytes('proof_b64')])
    expect(await openSeal('0000000', seal)).toBeNull()
  }, 30000)

  it('refuses weaker parameters from a server', () => {
    const raw = bytes('seal_b64')
    const weakened = raw.slice()
    const at = weakened.findIndex((value, index) => value === 0x18 && weakened[index + 1] === 0x80 && weakened[index + 2] === 0x80 && weakened[index + 3] === 0x04)
    expect(at).toBeGreaterThan(0)
    weakened[at + 3] = 0x02
    expect(() => parseSeal(weakened)).toThrow()
  })
})

describe('phone input', () => {
  it('turns local numbers into E.164', () => {
    expect(toE164('06 1234 5612', 'NL')).toBe('+31612345612')
    expect(toE164('+31 6 12345612', 'UA')).toBe('+31612345612')
    expect(toE164('0031612345612', 'DE')).toBe('+31612345612')
    expect(toE164('(050) 123-45-12', 'UA')).toBe('+380501234512')
    expect(toE164('06 12345612', 'IT')).toBe('+390612345612')
    expect(toE164('abc', 'NL')).toBeNull()
    expect(toE164('12', 'NL')).toBeNull()
  })
})
