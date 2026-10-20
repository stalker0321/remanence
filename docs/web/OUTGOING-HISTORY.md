# T368 — outgoing metadata and recent recipients

Base: `7ab89064ae56b1c3a9f35f8a7999bffb4719b25b`.
Sealed Android/web contract: coordination `tasks/T368/contract.md`,
revision `T368-history-v1`, SHA256
`7d8687d2c6265318a805efb963815a5028d0aad15278524ab0e3c4c1d64e6638`.
This candidate is not a deployment or independent acceptance.

## Implementation boundaries

- New authenticated GET `/v1/capsules/outgoing`: sender-only metadata from
  existing v1 and pending-v2 ledgers; bounded limit and owner-bound keyset cursor,
  descending `(created_at, protocol_version, capsule_id)`. Live state, not a
  snapshot. Refresh finds new rows above the current cursor.
- New GET `/v1/recipients/recent`: confirmed v1 publication history, including
  opened/revoked, grouped by known recipient UUID; excludes self, disabled and
  handleless users. No contact upload or sealed-address enrichment.
- Explicit SQL projections exclude body, envelopes, signatures, destination,
  vault and keys. SELECT only. No schema/migration, writes or code allocation.
- Existing locator ciphertext is decoded only with existing dev/test custody
  and verified against canonical code + stored HMAC. Missing/corrupt/retired/
  unsupported custody fails closed. Production/beta code remains unavailable;
  no development-key shortcut. Cross-ledger capsule-ID collision cannot attach
  another ledger's hint/code to a v1 row.
- New paths have private,no-store, including auth/database/unexpected failures.
  Their sealed 503 code is INTERNAL_UNAVAILABLE, intentionally endpoint-local:
  the legacy shared problem catalog aliases 503 to INTERNAL_ERROR. Existing
  endpoints/catalog/auth behavior are not changed.
- `#/history` is memory-only and keyed by owner + session generation. It offers
  loading/empty/error/retry, pagination, native keyboard details, public-code
  copy and explicit cancellation confirmation. No content opening/admission.
  Cancellation uses existing revoke/pending-cancel; no retry after unknown
  mutation outcome, always refetch metadata. No promise to undo opened/copied
  content. Session changes retire rows, requests, confirmations and notices.
- Lazy recent suggestions perform a fresh directory lookup, check the same
  stable identity, and use the existing explicit confirmation flow. They never
  auto-confirm or send. EN/RU/UK and narrow/wide layouts are included.
- Transport adds only exact UUID pending-cancel POST support and a bounded
  cursor length. Static-only service worker implementation remains unchanged.

## Verification and deployment boundaries

`server/tests/test_outgoing_history.py` uses real bearer auth, FastAPI, SQL and
existing cancel/revoke against unique synthetic PostgreSQL databases. Failure
injection covers query and auth-dependency errors. Existing installed tools only.
Serialize backend pytest under `/tmp/remanence-server-test.lock`.

`web/test/history.test.ts` checks DTO validation, future states/actions, session
fences, malformed/unknown mutation results, no retry and exact v2 transport.
`web/test/browser/outgoingHistoryCheck.mjs` uses the real bundled App with
synthetic loopback API responses and pinned Chromium, not a live backend.
Established creation/send/PWA/browser gates must also pass on the candidate.
All heavy web jobs use the NL host and `/tmp/remanence-web.lock`.

No live accounts, provider calls, migrations, push/merge/deploy or payment/
accounting enforcement. Existing tables through migration0015 are required.
Database performance on production volumes and real browser/provider/clipboard
permissions outside pinned Chromium remain unverified. Deployment and Android
integration/acceptance are separately authorized work.
