import { describe, expect, it } from 'vitest'
import { ALPHABET, formatCode, openSeal, parseCode, parseSeal } from '../src/gift/cardSeal'
import { toE164 } from '../src/gift/phone'
import { addressCommitment, artifactAadV2, claimIntent, envelopeContext, keyCommitment, parseBinding, parseChallenge, parseStatement, statementHash, uuidText } from '../src/gift/wire'
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

describe('delivery v2 wire', () => {
  const wire = vector.wire
  const raw = (value: string) => Uint8Array.from(atob(value), (ch) => ch.charCodeAt(0))
  const id = (name: keyof typeof wire.ids_b64) => uuidText(raw(wire.ids_b64[name]))

  it('reads the signed statement, its artifacts and ordinals', () => {
    const statement = parseStatement(raw(wire.statement_b64))
    expect(statement.capsuleId).toBe(id('capsule'))
    expect(statement.senderUserId).toBe(id('sender'))
    expect(statement.serviceKeyId).toBe(id('service'))
    expect(statement.claimExpiresAt).toBe(1893456000)
    expect(statement.blobs.map((blob) => [blob.kind, blob.ordinal, blob.ciphertextSize])).toEqual([
      ['RECOGNITION_MANIFEST', null, 1000], ['PHOTO', 4, 3000000],
    ])
    expect(statement.blobs[1]?.blobId).toBe(id('photo'))
  })

  it('reads the claim binding and challenge', () => {
    const binding = parseBinding(raw(wire.binding_b64))
    expect(binding.recipientUserId).toBe(id('recipient'))
    expect(binding.claimId).toBe(id('claim'))
    expect(binding.serviceKeyId).toBe(id('service'))
    const challenge = parseChallenge(raw(wire.challenge_b64))
    expect(challenge.recipientKeyBundleId).toBe(id('recipient_bundle'))
  })

  it('builds the exact bytes the server and Android parse', async () => {
    const challenge = parseChallenge(raw(wire.challenge_b64))
    const nonce = raw(wire.nonce_b64)
    const intent = claimIntent({
      operationId: id('operation'), capsuleId: id('capsule'), deliveryTargetId: id('target'),
      statementSha256: new Uint8Array(32).fill(3), proofId: id('proof'), challenge, nonce, nonceSha256: await statementHash(nonce),
    })
    expect([...intent]).toEqual([...raw(wire.intent_b64)])
    const context = envelopeContext({
      capsuleId: id('capsule'), deliveryTargetId: id('target'), senderUserId: id('sender'), recipientUserId: id('recipient'),
      recipientKeyBundleId: id('recipient_bundle'), statementSha256: new Uint8Array(32).fill(3), bindingSha256: new Uint8Array(32).fill(4),
    })
    expect([...context]).toEqual([...raw(wire.context_b64)])
    expect([...artifactAadV2({
      capsuleId: id('capsule'), blobId: id('blob'), kind: 'RECOGNITION_MANIFEST', ordinal: -1, senderUserId: id('sender'), deliveryTargetId: id('target'),
    })]).toEqual([...raw(wire.artifact_aad_b64)])
    expect([...await keyCommitment(id('capsule'), id('target'), raw(vector.keyset_b64))]).toEqual([...raw(wire.key_commitment_b64)])
    expect([...await addressCommitment(raw(vector.opening_b64))]).toEqual([...raw(wire.address_commitment_b64)])
  })
})
