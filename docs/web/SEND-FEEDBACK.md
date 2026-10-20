# Send outcomes and leaving Create

This is a presentation correction over the existing one-attempt-per-mounted-
Create-visit controller. It does not change publishing, idempotency, storage,
session ownership, retry policy, or the classification returned by the publisher.

| Existing outcome | Visible result | Next step |
| --- | --- | --- |
| refused/preflight or seal | This attempt stopped during local preparation | Leave; a later attempt requires reopening Create and preparing composition/front again |
| refused/draft | The server returned an error creating the postcard | Do not send a duplicate because of this error; leaving does not undo server work |
| unknown | Sending was not confirmed; the postcard may have been sent | Same conservative guidance, with no retry action |
| sent | Existing sent message | Unchanged |
| stale | No completion announcement | Existing full-visit guard remains authoritative |

The fixed-key presenter never renders arbitrary error codes, response bodies,
credentials or paths. Both the local result and App notice use the same mapping.
In particular, an HTTP503 draft error remains `refused/draft` under the existing
contract, but the UI does **not** infer absence of server effects from that name.
Changing that classification would require a separate publisher-contract review.
The earlier preparation-only descriptions ("nothing sent", photos not sent)
are hidden once an attempt starts, so they cannot contradict working, unknown
or completed results. The recipient's "front not captured yet" description also
disappears when a front has been captured; the recipient identity stays visible.

Working copy explains that leaving does not guarantee cancellation. Terminal
failures have an explicit Home link. Neither that link nor language changes
reset/rearm a controller, publish again or acknowledge a remote rollback.
Existing unmount invalidation/abort and draft retention rules are unchanged:
front capture/fingerprint and frozen composition are discarded; the photo/note
drafts remain only in existing session memory. No new durable draft or outbox.

Asynchronous errors retain the existing alert semantics without moving keyboard
focus. The exit is an ordinary focusable link, operable with Enter.

## Verification boundary

The browser fixture uses the actual App, normalization/composer/front processing,
sealer, send controller, publisher and fetch transport against a loopback HTTP
server. Only test golden keys, synthetic JPEGs and a fake camera are involved.
Modes cover local signing failure, draft422/503, malformed draft receipt, lost
finalize, and leaving while a draft/finalize response is held. Request counts,
double activation, fixed localized messages and keyboard exit are checked.
Application `fetch` invocations and server-observed HTTP requests are counted
separately: Chromium can resend a request after an empty response on a reused
connection. The lost-finalize fixture requires one application finalize fetch,
unchanged capsule/body on any bounded network resend, and no new application
attempt. Finalize has no `Idempotency-Key` header: the existing
[protocol contract](../protocol.md) binds replay to sender, capsule and canonical
finalize material. This fixture does not establish real backend deduplication.

CSS layout magnification is explicitly distinguished from native toolbar zoom;
neither it nor Chromium automation establishes physical-device, screen-reader,
Safari/Firefox, real provider, real account or production-backend acceptance.
Previous immutable browser/PWA/root regression gates remain required.

References: [WAI-ARIA alert pattern](https://www.w3.org/WAI/ARIA/apg/patterns/alert/)
for non-focus-stealing alerts, and [CSS zoom property](https://drafts.csswg.org/css-viewport/#zoom-property)
for the layout-magnification test boundary. The distinction between an application
retry and Chromium's connection-error resend is consistent with
[Chromium HttpNetworkTransaction](https://chromium.googlesource.com/chromium/src/+/f0d01d5c/net/http/http_network_transaction.cc)
(`ShouldResendRequest` and `kEmptyResponse`); the fixture measures both layers.
