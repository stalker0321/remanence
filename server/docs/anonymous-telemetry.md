# Optional telemetry deployment gate (T181)

The frozen v1 payload is in the control-plane T181/contract.md. The API reserves
`/v1/telemetry/remanence` before ordinary RequestId middleware, scrubs the original
ASGI scope's peer/server/headers/query/state and refuses Authorization, Cookie or
Proxy-Authorization (even empty). Unknown fields, non-finite numbers, duplicate
JSON keys, incorrect boxes/indices and non-public grammar/style pairs are refused.
Consented client reports follow explicit design selection or a successfully
committed crop correction to an already selected design; unchanged/rejected/
cancelled corrections do not emit. This changes no wire schema or server limits.
Responses are fixed JSON with no request ID. Payload maximum: 32768 bytes; read
deadline: 5 seconds; process-global bucket: burst 20 and one request/second.
At most four receiving requests and four synchronous store workers are admitted;
worker admission remains held even if its awaiting HTTP coroutine is cancelled.
No IP/account limiter state. Multiple workers multiply the bucket rate, so use
one ingestion worker for this beta. Shared PostgreSQL advisory transaction lock
makes the 50000-row cap atomic across processes. PostgreSQL transaction lock and
statement waits are bounded to two seconds. At cap or database failure the
endpoint fails closed. No automatic deletion/rotation is implemented.

The additive migration `0012_anonymous_telemetry` creates exactly `id` (random
UUIDv4 assigned by server) and `payload` JSONB, no timestamp/index/account FK.
Only validated payloads enter that table. No existing data is rewritten.

## Deployment responsibility (not performed by writer)

Current public base is `https://remanence.hryshyn.dev/onboarding-test/`, NOT root.
The exact public ingestion path is `/onboarding-test/v1/telemetry/remanence` and
must be handled before the existing `/onboarding-test/*` catch-all. Strip only
the `/onboarding-test` prefix before forwarding to API `/v1/telemetry/remanence`.
Preserve ordinary API, music, legal, safety and authentication ingress/logging.

App filtering alone is not an ingress privacy guarantee. Existing Caddy 2.6.2
operational errors and malformed HTTP/TLS requests may be logged before ASGI
dispatch. Absence of Caddy access logs does NOT establish error-log containment.
The Uvicorn access filter is installed at app factory creation and suppresses
only the reserved telemetry path; own parser/storage errors are consumed rather
than handed to the generic traceback logger. Other routes keep normal logs.
An error filter also drops exceptions with an attributable reserved ASGI scope,
including send failures. It cannot classify pre-routing transport errors.

Root must configure/rehearse narrow Caddy access AND operational-error isolation,
including upstream unavailable, oversized/invalid request, wrong method, auth
headers, parser failure and successful ingestion. If that cannot be established
on the shared listener, use a dedicated loopback ingestion process (e.g. 8018),
no access logs, and a dedicated safe logging configuration, while leaving 8017
ordinary logs intact. Generic transport errors cannot reliably be attributed to
a URL before a request is parsed. Also avoid outer proxy/CDN request traces.
Do not enable client collection before this gate passes. Retain only synthetic
canary evidence, never real request headers or rejected user payloads.

Deploy reviewed image and forward-only additive migration using existing secret
references; never print resolved compose environment or database URL. Verify
ordinary prefixed legal200 and safety401 as well as telemetry responses. Exclude
credentials from telemetry client and use correct prefixed API base in Android.

## Rollback and limits

Disable client opt-ins/sends and/or exact ingress route; revert API image while
leaving the empty/additive table in place. Do not downgrade a live migration to
erase collected reports or clear accounts/capsules. Retention/backup policies
require an explicit operator decision, not an automatic destructive job.

This is anonymous-format optional feedback, not mathematical anonymization.
Operators/backups/network observers and low-volume correlation remain limits.
The endpoint cannot authenticate claimed consent without reintroducing identity;
consent is enforced by the reviewed client. Unauthenticated abuse can consume
the shared bucket or fill the bounded store; telemetry must never block product
use and must not gain credentials to address that availability limitation.
