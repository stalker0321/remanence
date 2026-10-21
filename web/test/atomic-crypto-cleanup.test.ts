import { describe, expect, it, vi } from 'vitest'
import { DhkemX25519HkdfSha256 } from '@hpke/core'
import { generateLocalIdentity } from '../src/crypto/identity'
import { unlockAccount } from '../src/crypto/keyOwner'
import { syntheticRecovery } from './recoveryFixture'

describe('existing crypto cleanup on atomic setup failure', () => {
  it('clears the already-exported encryption seed if signing key generation rejects', async () => {
    const Original = globalThis.Uint8Array
    const owned: Uint8Array[] = []
    const watched = new Proxy(Original, { construct(target, args, newTarget) {
      const result = Reflect.construct(target, args, newTarget) as Uint8Array
      // okp's base64 decoder allocates one 32-byte seed by length; public bytes
      // use ArrayBuffer construction. Preserve builtin prototype/instanceof.
      if (args.length === 1 && args[0] === 32) owned.push(result)
      return result
    } })
    const generate = crypto.subtle.generateKey.bind(crypto.subtle)
    const spy = vi.spyOn(crypto.subtle, 'generateKey').mockImplementation((...args) => {
      if (args[0] === 'Ed25519') return Promise.reject(new Error('synthetic second-key failure'))
      return generate(...args)
    })
    vi.stubGlobal('Uint8Array', watched)
    try {
      await expect(generateLocalIdentity()).rejects.toThrow('synthetic second-key failure')
      expect(owned.length).toBeGreaterThan(0)
      expect(owned.every((bytes) => bytes.every((byte) => byte === 0))).toBe(true)
    } finally { vi.unstubAllGlobals(); spy.mockRestore() }
  })

  it('clears the readback raw-key copy even when the HPKE import rejects', async () => {
    const fixture = await syntheticRecovery(); const captured: Uint8Array[] = []
    const spy = vi.spyOn(DhkemX25519HkdfSha256.prototype, 'importKey').mockImplementation(async (_format, key) => {
      if (key instanceof ArrayBuffer) captured.push(new Uint8Array(key))
      throw new Error('synthetic import failure')
    })
    try {
      await expect(unlockAccount(fixture.reply, fixture.prf)).rejects.toThrow('synthetic import failure')
      expect(captured).toHaveLength(1)
      expect(captured[0]!.every((byte) => byte === 0)).toBe(true)
    } finally { spy.mockRestore(); fixture.prf.fill(0); fixture.code.fill(0) }
  })
})
