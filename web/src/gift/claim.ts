import { unb64, b64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiTransport } from '../auth/http'
import type { MemoryKeys } from '../auth/memoryKeys'
import { sealRecipientEnvelope } from '../crypto/hpkeSeal'
import { arrayBuffer } from '../protocol/bytes'
import { parseEd25519PublicKeyset, tinkPrefixKeyId } from '../crypto/tink'
import { SessionError, type SessionStore } from '../session/session'
import { openSeal, parseSeal, type OpenedSeal } from './cardSeal'
import {
  CLAIM_BINDING_DOMAIN, CLAIM_CHALLENGE_DOMAIN, CLAIMED_ENVELOPE_DOMAIN, PENDING_PUBLISH_DOMAIN,
  claimIntent, domainInput, envelopeContext, envelopePlaintext, keyCommitment, parseBinding, parseChallenge,
  parseStatement, statementHash,
} from './wire'

export interface ServicePin { keyId: string; publicKeysetB64: string }

export interface GiftDto {
  delivery_id: string
  capsule_id: string
  delivery_target_id: string
  proof_id: string
  claim_expires_at: number
  publish_statement_b64: string
  publish_signature_69_b64: string
  card_seal_b64: string
}

export type ClaimOutcome = 'claimed' | 'wrong-code' | 'refused' | 'stale'

function same(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false
  let diff = 0
  for (let i = 0; i < left.length; i++) diff |= (left[i] as number) ^ (right[i] as number)
  return diff === 0
}

/** Verifies a 69-byte Tink-framed Ed25519 signature over domain || 0x00 || message. */
export async function verifyDomainSignature(publicKeyset: Uint8Array, domain: string, message: Uint8Array, signature: Uint8Array): Promise<boolean> {
  if (signature.length !== 69) return false
  const parsed = parseEd25519PublicKeyset(publicKeyset)
  if (tinkPrefixKeyId(signature) !== parsed.keyId) return false
  const key = await crypto.subtle.importKey('raw', arrayBuffer(parsed.raw), 'Ed25519', false, ['verify'])
  return crypto.subtle.verify('Ed25519', key, arrayBuffer(signature.subarray(5)), arrayBuffer(domainInput(domain, message)))
}

export async function directoryBundle(transport: ApiTransport, token: string, bundleId: string, signal?: AbortSignal): Promise<{ userId: string; signing: Uint8Array; encryption: Uint8Array } | null> {
  const response = await transport.request({ path: `/v1/directory/key-bundles/${bundleId}`, method: 'GET', token, signal })
  const root = response.status === 200 ? response.body as Record<string, unknown> | null : null
  if (!root || root.key_bundle_id !== bundleId || typeof root.user_id !== 'string'
    || typeof root.signing_public_keyset !== 'string' || typeof root.encryption_public_keyset !== 'string') return null
  return { userId: root.user_id, signing: unb64(root.signing_public_keyset), encryption: unb64(root.encryption_public_keyset) }
}

/**
 * Tries the code on every gift of the proven number. The server never learns
 * which postcard this is. On a match the device seals the ordinary envelope to
 * its own key, proves the code, and the server signs the claim.
 */
export async function claimCardGift(deps: {
  session: SessionStore
  keys: MemoryKeys
  transport: ApiTransport
  pin: ServicePin
  secret: string
  gifts: readonly GiftDto[]
  signal?: AbortSignal
}): Promise<ClaimOutcome> {
  const { session, keys, transport, pin, secret, gifts, signal } = deps
  const view = session.getSnapshot()
  if (view.phase !== 'keys-unlocked' || view.accountId === null || view.bundleId === null) return 'stale'
  const generation = view.generation
  const caps = keys.capabilities(view)
  if (caps === null) return 'stale'
  const token = (): string => {
    try { session.bind(generation)() } catch (error) {
      if (error instanceof SessionError) throw new Error('STALE')
      throw error
    }
    const secrets = session.secretsFor(generation)
    if (!secrets) throw new Error('STALE')
    return secrets.accessToken
  }
  for (const gift of gifts) {
    const seal = parseSeal(unb64(gift.card_seal_b64))
    const opened: OpenedSeal | null = await openSeal(secret, seal)
    if (signal?.aborted) { opened?.keyset.fill(0); opened?.proof.fill(0); return 'stale' }
    if (!opened) continue
    try {
      return await claimOne(gift, opened)
    } finally {
      opened.keyset.fill(0)
      opened.proof.fill(0)
    }
  }
  return 'wrong-code'

  async function claimOne(gift: GiftDto, opened: OpenedSeal): Promise<ClaimOutcome> {
    const statementRaw = unb64(gift.publish_statement_b64)
    const statement = parseStatement(statementRaw)
    const h = await statementHash(statementRaw)
    if (statement.capsuleId !== gift.capsule_id || statement.deliveryTargetId !== gift.delivery_target_id || statement.serviceKeyId !== pin.keyId) return 'refused'
    const sender = await directoryBundle(transport, token(), statement.senderKeyBundleId, signal)
    if (!sender || sender.userId !== statement.senderUserId) return 'refused'
    if (!await verifyDomainSignature(sender.signing, PENDING_PUBLISH_DOMAIN, statementRaw, unb64(gift.publish_signature_69_b64))) return 'refused'
    // What the sender signed must commit to exactly what the code opened.
    if (!same(await keyCommitment(gift.capsule_id, gift.delivery_target_id, opened.keyset), statement.keyCommitment)) return 'refused'

    const options = await transport.request({ path: `/v2/pending-deliveries/${gift.delivery_id}/claim/options`, method: 'POST', token: token(), signal })
    const body = options.status === 200 ? options.body as Record<string, unknown> | null : null
    if (!body || typeof body.claim_challenge_b64 !== 'string' || typeof body.hpke_challenge_b64 !== 'string' || typeof body.claim_binding_b64 !== 'string') return 'refused'
    const challengeRaw = unb64(body.claim_challenge_b64)
    const challenge = parseChallenge(challengeRaw)
    if (challenge.capsuleId !== gift.capsule_id || challenge.deliveryTargetId !== gift.delivery_target_id
      || challenge.recipientUserId !== caps!.accountId || challenge.recipientKeyBundleId !== caps!.bundleId) return 'refused'
    const nonce = await caps!.openEnvelope(domainInput(CLAIM_CHALLENGE_DOMAIN, challengeRaw), unb64(body.hpke_challenge_b64))
    const nonceSha256 = await statementHash(nonce)
    if (!same(nonceSha256, challenge.nonceSha256)) return 'refused'

    const bindingRaw = unb64(body.claim_binding_b64)
    const binding = parseBinding(bindingRaw)
    const own = await directoryBundle(transport, token(), caps!.bundleId, signal)
    if (!own || !same(binding.statementSha256, h) || binding.capsuleId !== gift.capsule_id
      || binding.recipientUserId !== caps!.accountId || binding.recipientKeyBundleId !== caps!.bundleId
      || binding.serviceKeyId !== pin.keyId || !same(binding.recipientKeysetSha256, await statementHash(own.encryption))) return 'refused'

    const context = envelopeContext({
      capsuleId: gift.capsule_id,
      deliveryTargetId: gift.delivery_target_id,
      senderUserId: statement.senderUserId,
      recipientUserId: caps!.accountId,
      recipientKeyBundleId: caps!.bundleId,
      statementSha256: h,
      bindingSha256: await statementHash(bindingRaw),
    })
    const plaintext = envelopePlaintext(context, opened.keyset, opened.opening)
    let envelope: Uint8Array
    try {
      envelope = await sealRecipientEnvelope(own.encryption, domainInput(CLAIMED_ENVELOPE_DOMAIN, context), plaintext)
    } finally {
      plaintext.fill(0)
    }
    const operationId = crypto.randomUUID()
    const intent = claimIntent({
      operationId, capsuleId: gift.capsule_id, deliveryTargetId: gift.delivery_target_id, statementSha256: h,
      proofId: gift.proof_id, challenge, nonce, nonceSha256,
    })
    if (!caps!.signClaimIntent) return 'refused'
    const signature = await caps!.signClaimIntent(intent)
    const claimed = await transport.request({
      path: `/v2/pending-deliveries/${gift.delivery_id}/claim`,
      method: 'POST',
      token: token(),
      signal,
      body: {
        claim_intent_b64: b64(intent),
        claim_intent_signature_69_b64: b64(signature),
        idempotency_key: operationId,
        envelope_b64: b64(envelope),
        card_proof_b64: b64(opened.proof),
      },
    })
    const result = claimed.status === 200 ? claimed.body as Record<string, unknown> | null : null
    if (!result || typeof result.claim_binding_b64 !== 'string' || typeof result.claim_signature_69_b64 !== 'string') return 'refused'
    const signedBinding = unb64(result.claim_binding_b64)
    if (!same(signedBinding, bindingRaw)) return 'refused'
    const verified = await verifyDomainSignature(unb64(pin.publicKeysetB64), CLAIM_BINDING_DOMAIN, signedBinding, unb64(result.claim_signature_69_b64))
    return verified ? 'claimed' : 'refused'
  }
}
