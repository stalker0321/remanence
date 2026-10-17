import { describe, expect, it } from 'vitest'
import { openRecovery } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { unlockAccount } from '../src/crypto/keyOwner'
import { utf8 } from '../src/protocol/bytes'
import { recipientEnvelopeInfo } from '../src/protocol/context'
import { ProtocolError } from '../src/protocol/errors'
import { sealTo, syntheticRecovery } from './recoveryFixture'

const info = recipientEnvelopeInfo({
  capsuleId: '00010203-0405-0607-0809-0a0b0c0d0e0f',
  senderUserId: '10111213-1415-1617-1819-1a1b1c1d1e1f',
  recipientUserId: '20212223-2425-2627-2829-2a2b2c2d2e2f',
  recipientKeyBundleId: '40414243-4445-4647-4849-4a4b4c4d4e4f',
})

describe('memory key owner', () => {
  it('keeps the existing probe diagnostic and opens an envelope without exposing seeds', async () => {
    const fixture = await syntheticRecovery()
    expect(fixture.producer).toBe('js-webcrypto')
    const probed = await openRecovery(fixture.reply, fixture.prf.slice())
    expect(probed).toEqual({
      status: 'PASS',
      identityValidated: true,
      identityInstalled: false,
      plaintextBuffersCleared: true,
    })
    const owner = await unlockAccount(fixture.reply, fixture.prf.slice())
    expect(JSON.stringify(owner)).not.toContain('333333')
    const sealed = await sealTo(fixture.publicKey, fixture.encryptionKeyId, info, utf8('same-memory'))
    expect(new TextDecoder().decode(await owner.openEnvelope(info, sealed))).toBe('same-memory')
    const statement = utf8('statement')
    const signature = await owner.sign(statement)
    expect(signature.length).toBe(69)
    owner.lock()
    await expect(owner.openEnvelope(info, sealed)).rejects.toMatchObject({ code: 'LOCKED' })
    await expect(owner.sign(statement)).rejects.toMatchObject({ code: 'LOCKED' })
  })

  it('does not install a key owner for the wrong secret or a mismatched bundle id', async () => {
    const fixture = await syntheticRecovery()
    const wrong = new Uint8Array(32).fill(9)
    await expect(unlockAccount(fixture.reply, wrong)).rejects.toBeInstanceOf(ProtocolError)
    const mismatched = await syntheticRecovery(false)
    await expect(openRecovery(mismatched.reply, mismatched.prf.slice())).resolves.toMatchObject({ identityValidated: true })
    await expect(unlockAccount(mismatched.reply, mismatched.prf.slice())).rejects.toMatchObject({ code: 'BUNDLE_MISMATCH' })
  })
})
