# Receive and account interruption boundaries

The v1 web receive journey now prepares the entire owned postcard locally, then
calls the existing authenticated recipient `POST /v1/capsules/{id}/first-open`
immediately before presentation. Local recognition is not server admission.
Only the exact coherent OPENED response for this capsule admits the sheet.
Revoke winning before that call prevents display even with downloaded ciphertext.

Network/lost/malformed/unavailable replies do not invent offline permission.
Explicit retry may receive the existing idempotent server replay; no local
durable opening grant, retry queue or automatic publication attempt is created.
Closing an already presented sheet keeps the existing lock/restore policy.

Abort, route departure, owner/session generation change and stale completion
cannot present an old sheet. Application-owned temporary plaintext arrays are
zeroed on all exits; prepared metadata/object references are released and object
URLs revoked. This is not a guarantee about browser/JS GC/WebCrypto internal
copies. Source unit tests, actual App browser fixtures and real TCP FastAPI/PG
controls have separate evidence boundaries in the T388 writer report.

## New account setup is gated, not a continuity redesign

Current registration can commit an immutable public bundle before an encrypted
recovery record exists. Existing recovery creation requires the resulting
authenticated account UUID/session. There is no pre-registration durable staging
or atomic account+record endpoint in the accepted protocol. A warning or
beforeunload handler cannot make reload/ambiguous registration safe.

Therefore new-account registration is paused before identity generation or
public-bundle POST. The UI explains this in EN/RU/UK. Existing-account Google
login and recovery of an already stored encrypted record remain available.
An existing account with no recovery record and no original identity held in
the tab is explicitly not repairable by this change; no replacement key is made.

Existing same-tab held-identity enrollment may read back its stored record after
an ambiguous save without creating another passkey/package. That is not fresh
registration continuity across reload. Reload can restore only an already
durable valid encrypted record using its original account/bundle/credential.

Full new-account continuity needs separately approved server protocol authority:
a resumable, expiring reservation bound to one Google subject, one immutable
bundle and one existing-format passkey recovery record, atomic exposure only
after durable verified record storage, and idempotent outcome lookup after a
lost reply. This proposal is NOT implemented or approved by this web change.
No new KDF, plaintext persistence, key reset/rotation, provider, mandatory
recovery-code ritual or server policy change is introduced.
