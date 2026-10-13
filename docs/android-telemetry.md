# Optional Android telemetry v1

Two independent device-only preferences are OFF by default. They appear at the
existing terms form and in Settings; neither gates required terms or Create.
Existing accepted terms are not reset. Every sharedpref domain is excluded from
legacy backup, cloud backup and device transfer, and allowBackup remains false.

Design consent sends only photo count, codepoint text-length bucket, music/cover
booleans, the closed bundled grammar/style presentation catalog, shown indices,
and selected index. Distinct presented geometries retain separate positions even
when their public grammar/style pair is equal. Consecutive redraws dedupe; actual
A→B→A returns do not. Only the ready, nonzero, window-visible canvas while the
activity is RESUMED observes exposure. Hidden CONTENT eager preparation and
uninvoked canvas parcels do not. Fresh builds invalidate the cohort.

Crop consent independently sends selected public grammar/style, slot, coarse
aspect bucket, clipped 0..16 face boxes, pooled 8×8 importance bins (0..15), and
initial/final sealed crop integers. It uses existing cached authoritative
analysis only; no detectors or image reads are triggered by telemetry. Missing
or mismatched analysis omits the record. No source image/ID, face identity,
landmarks, object classes or six full-resolution analysis maps are exported.
The original crop is snapshotted before Done and remains immutable across the
candidate's local ID migration; final is the actual successful selected crop.
Pre-consent corrections are not backfilled. Cancelled/rejected drafts never
commit; successful Done on an already selected candidate emits an updated final
snapshot, with unchanged repeat selection/Done deduped.

Reports are bounded and ephemeral: no media, content hashes, owner/account/
session/device/capsule/photo identifiers, handles, exact timestamps, text, track
identity, URLs, EXIF or embeddings. No disk queue, retry or third-party analytics.
Admission tokens carry a local consent epoch: disabling/re-enabling or session/
account boundary changes reject already captured observations and selections.
The coordinator fences admission and send enqueue with revocation, purges all
cohorts and best-effort cancels active transport. Consent itself remains device
scoped across logout. Any failed preference save turns both choices OFF for this
process and displays a localized warning, cleared only after a successful save.
Physically broken disk persistence cannot guarantee previous ON state will not
return after a process restart; the warning asks the user to check next launch.

Transport is a separate OkHttp client (not the authenticated API stack), with no
authenticator, cookie jar, logging or shared interceptors. Redirects and
connection retry are disabled, request bodies are one-shot, call time is bounded
to 10 seconds, failure is dropped, and completion/cancellation clears the retained
wire buffer. Endpoint resolution preserves the existing API prefix:
`ApiBaseUrl.resolve("v1/telemetry/remanence")`. Collection and network errors never
gate freeze, Done, Send or rendering.

Withdrawal cannot retract already received reports, and best-effort cancellation
cannot unsend bytes already transmitted. This is deliberately NOT a claim of
mathematical anonymity: coarse image-derived analysis is disclosed, and network
operators/server ingress can observe connection metadata and correlate timing.
Production ingress/log isolation and backend limits require separate operator
acceptance. Android source/JVM/native fixtures alone do not establish that.

Exact shared schema fixture: app `src/test/resources/telemetry/v1-report.json`.
`TelemetryConsentDeviceTest` uses fake in-memory preferences/transport only; it
never opts the real QA/owner application into transmission. Its screenshots and
reports prove actual native exposure/UI only, not live networking acceptance.
