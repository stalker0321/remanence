# T374 — CLIENT_ONLY sending access

Base: `ef171f6e6c93f45e6e1f34ff72ba6deffaf676a9`.
Proposed future read contract: coordination `tasks/T374/client-contract.md`,
revision `T374-send-access-client-v1`, SHA256
`becc1daf7e73e794161c593689a30991a1fc3634075e4f3a7dc78bfe00bd6fe8`.
This proposal is not an accepted/deployed API. T372 SEALED is unchanged.

## Client boundaries

Settings has an authenticated owner/generation-keyed sending-access section.
It reads proposed GET `/v1/me/send-access` with existing bearer transport,
no-store/omit cookies/redirect:error. No server code or new dependencies.

404 hides the block, including non-JSON404 from an old server. RECORD says beta
sends are unrestricted; it does not display/spend hypothetical free counters.
ENFORCE displays two separate remaining counts, or unlimited lifetime (printing/
postage excluded). Lifetime may coexist with historical FREE usage.
Strict exact DTO/policy/range/boolean/consistency validation fails to unavailable
with manual metadata retry, never a fresh allowance or an exhaustion claim.
Refresh removes prior values; abort/unmount/session/lease checks reject late
success and failure. No durable/private SW cache or balance mutation.

Read results are advisory and do not disable/admit Send. The server finalizer
owns admission. No snapshot can guarantee a later publication.

The actual existing v1 publisher recognizes only coherent received finalize
SEND_LIMIT_REACHED409 and SEND_ACCESS_UNAVAILABLE503, from either the bounded
seven-field v1 or four-field pending problem shape. No pending publication
client is introduced; pending-shaped responses are compatibility fixtures.
The former is an explicit limit-denial result, not network unknown. The latter
is temporary access-check failure with sending unconfirmed, not exhaustion or
rollback. Malformed, wrong status/code and lost responses keep existing unknown.
Raw problem strings, grant keys and inferred balances are never rendered.

Both outcomes remain terminal under the existing one-attempt controller.
No rearm, automatic retry, second capsule/signature, client spend/refund, queue,
payment/price/promo/checkout/grant flow. Existing authored input retention/edit/
navigation policy is unchanged. Explicit later navigation is not an automatic
retry or a promise to recover a signed attempt.

## Verification / dependency

`send-access.test.ts` covers the proposal, owner/lease fences and error envelopes.
Publisher/controller tests plus real App `sendAccessCheck.mjs` /
`sendQuotaCheck.mjs` cover actual transport/handler/no-rearm behavior. These use
synthetic loopback HTTP and golden synthetic account/key fixtures, NOT a real
read API, backend accounting integration, provider or beta activation.
Heavy web gates use the NL pinned tools under `/tmp/remanence-web.lock`.

Future server packet must review/accept or explicitly revise the proposed read
contract and implement/test owner-only counts from authoritative FREE receipts.
T373 finalize/storage is not promised to expose that endpoint. Full end-to-end
2+2/lifetime and actual rollout remain unverified/separately authorized.

Follow-up, outside this packet: broader resume/retry of a denied or uncertain
signed attempt needs an explicit one-attempt/outbox policy design. This client
does not unlock a terminal Send even after a metadata refresh or new allowance.
