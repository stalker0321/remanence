import { beforeAll, describe, expect, it } from 'vitest'
import vector from '../../protocol/fixtures/card-seal-v1.json'
import { b64 } from '../../server/src/remanence/recovery/web/crypto.mjs'
import { claimCardGift, verifyDomainSignature, type GiftDto } from '../src/gift/claim'
import { parseSeal } from '../src/gift/cardSeal'
import type { ApiRequest, ApiTransport } from '../src/auth/http'
import type { MemoryKeys } from '../src/auth/memoryKeys'
import type { SessionStore } from '../src/session/session'

const raw = (value: string) => Uint8Array.from(atob(value), (ch) => ch.charCodeAt(0))
const account = '05050505-0505-0505-0505-050505050505'
const bundle = '06060606-0606-0606-0606-060606060606'

function fakeSession(): SessionStore {
  const view = { phase: 'keys-unlocked', accountId: account, bundleId: bundle, generation: 1 }
  return { getSnapshot: () => view, bind: () => () => undefined, secretsFor: () => ({ accessToken: 'token' }) } as unknown as SessionStore
}

async function ed25519Keyset() {
  const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']) as CryptoKeyPair
  const publicRaw = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey))
  // Minimal Tink public keyset: single ENABLED Ed25519 key, TINK prefix, key id 7.
  const inner = new Uint8Array([0x12, 0x20, ...publicRaw])
  const type = new TextEncoder().encode('type.googleapis.com/google.crypto.tink.Ed25519PublicKey')
  const keyData = new Uint8Array([0x0a, type.length, ...type, 0x12, inner.length, ...inner, 0x18, 0x03])
  const key = new Uint8Array([0x0a, keyData.length, ...keyData, 0x10, 0x01, 0x18, 0x07, 0x20, 0x01])
  return { keyset: new Uint8Array([0x08, 0x07, 0x12, key.length, ...key]), pair }
}

async function sign69(pair: CryptoKeyPair, domain: string, message: Uint8Array): Promise<Uint8Array> {
  const input = new Uint8Array([...new TextEncoder().encode(domain), 0, ...message])
  const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', pair.privateKey, input))
  return new Uint8Array([1, 0, 0, 0, 7, ...signature])
}

describe('claimCardGift', () => {
  let sender: Awaited<ReturnType<typeof ed25519Keyset>>
  beforeAll(async () => { sender = await ed25519Keyset() })

  it('finds no match with a wrong code and asks the server nothing', async () => {
    const calls: ApiRequest[] = []
    const transport: ApiTransport = { request: async (input) => { calls.push(input); return { status: 500, body: null } } }
    const keys = { capabilities: () => ({ accountId: account, bundleId: bundle }) } as unknown as MemoryKeys
    const gift: GiftDto = {
      delivery_id: 'd', capsule_id: 'c', delivery_target_id: 't', proof_id: 'p', claim_expires_at: 0,
      publish_statement_b64: '', publish_signature_69_b64: '', card_seal_b64: vector.seal_b64,
    }
    const outcome = await claimCardGift({
      session: fakeSession(), keys, transport, pin: { keyId: 'k', publicKeysetB64: '' }, secret: '0000000', gifts: [gift],
    })
    expect(outcome).toBe('wrong-code')
    expect(calls).toEqual([])
    // The code never appears anywhere near a request.
    expect(JSON.stringify(calls)).not.toContain('0000000')
  }, 30000)

  it('verifies Tink-framed signatures under their own domain only', async () => {
    const message = new TextEncoder().encode('statement')
    const signature = await sign69(sender.pair, 'postmark/pending-publish/v2', message)
    expect(await verifyDomainSignature(sender.keyset, 'postmark/pending-publish/v2', message, signature)).toBe(true)
    expect(await verifyDomainSignature(sender.keyset, 'postmark/claim-binding/v1', message, signature)).toBe(false)
    expect(await verifyDomainSignature(sender.keyset, 'postmark/pending-publish/v2', new Uint8Array(3), signature)).toBe(false)
    expect(b64(signature).length).toBeGreaterThan(0)
    expect(parseSeal(raw(vector.seal_b64)).serverHalf.length).toBe(32)
  })
})
