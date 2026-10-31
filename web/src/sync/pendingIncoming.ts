import { unb64 } from '../../../server/src/remanence/recovery/web/crypto.mjs'
import type { ApiTransport } from '../auth/http'
import { directoryBundle, verifyDomainSignature, type ServicePin } from '../gift/claim'
import { CLAIM_BINDING_DOMAIN, PENDING_PUBLISH_DOMAIN, parseBinding, parseStatement, statementHash } from '../gift/wire'
import type { IncomingCapsule } from './incoming'

const MAX_ITEMS = 50

function same(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

/**
 * ADR-020: claimed first gifts, shaped like ordinary incoming postcards so the
 * scan treats them the same. Every signature is checked before an item counts;
 * one bad item is dropped, it never blocks the rest.
 */
export async function fetchPendingIncoming(
  transport: ApiTransport,
  token: () => string,
  account: { accountId: string; bundleId: string },
  pin: ServicePin,
  signal?: AbortSignal,
): Promise<IncomingCapsule[]> {
  const feed = await transport.request({ path: '/v2/pending-incoming', method: 'GET', token: token(), signal })
  // A server without the feature answers 404: there is simply nothing here.
  if (feed.status !== 200) return []
  const rows = (feed.body as { items?: unknown } | null)?.items
  if (!Array.isArray(rows)) return []
  const items: IncomingCapsule[] = []
  for (const row of rows.slice(0, MAX_ITEMS) as { capsule_id?: unknown; delivery_id?: unknown }[]) {
    if (typeof row.delivery_id !== 'string' || typeof row.capsule_id !== 'string') continue
    try {
      const item = await loadOne(row.delivery_id, row.capsule_id)
      if (item) items.push(item)
    } catch (error) {
      if (signal?.aborted) throw error
    }
  }
  return items

  async function loadOne(deliveryId: string, capsuleId: string): Promise<IncomingCapsule | null> {
    const claim = await transport.request({ path: `/v2/pending-deliveries/${deliveryId}/claim`, method: 'GET', token: token(), signal })
    const control = await transport.request({ path: `/v2/pending-deliveries/${deliveryId}/control`, method: 'GET', token: token(), signal })
    if (claim.status !== 200 || control.status !== 200) return null
    const claimBody = claim.body as Record<string, string>
    const controlBody = control.body as Record<string, string>
    const statementRaw = unb64(controlBody.publish_statement_b64 as string)
    const statement = parseStatement(statementRaw)
    const h = await statementHash(statementRaw)
    if (statement.capsuleId !== capsuleId || statement.serviceKeyId !== pin.keyId) return null
    const sender = await directoryBundle(transport, token(), statement.senderKeyBundleId, signal)
    if (!sender || sender.userId !== statement.senderUserId) return null
    if (!await verifyDomainSignature(sender.signing, PENDING_PUBLISH_DOMAIN, statementRaw, unb64(controlBody.publish_signature_69_b64 as string))) return null
    const bindingRaw = unb64(claimBody.claim_binding_b64 as string)
    if (!await verifyDomainSignature(unb64(pin.publicKeysetB64), CLAIM_BINDING_DOMAIN, bindingRaw, unb64(claimBody.claim_signature_69_b64 as string))) return null
    const binding = parseBinding(bindingRaw)
    if (binding.capsuleId !== capsuleId || !same(binding.statementSha256, h)
      || binding.recipientUserId !== account.accountId || binding.serviceKeyId !== pin.keyId) return null
    return {
      capsuleId,
      senderUserId: statement.senderUserId,
      recipientUserId: account.accountId,
      senderKeyBundleId: statement.senderKeyBundleId,
      recipientKeyBundleId: binding.recipientKeyBundleId,
      envelopeCiphertext: unb64(claimBody.envelope_b64 as string),
      statementSha256: h,
      blobs: statement.blobs,
      pending: {
        deliveryId,
        deliveryTargetId: statement.deliveryTargetId,
        keyCommitment: statement.keyCommitment,
        addressCommitment: statement.addressCommitment,
        bindingSha256: await statementHash(bindingRaw),
      },
    }
  }
}
