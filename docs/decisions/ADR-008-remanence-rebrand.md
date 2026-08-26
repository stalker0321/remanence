# ADR-008: Remanence rebrand — naming inventory and compatibility boundary

Status: ACCEPTED

Date: 2026-08-26

Deciders: product owner (stalker0321), implementation agent

## Context

The project was built under the working name **Postmark** (display name
**Postcard Memory Capsules**). The product is being rebranded to **Remanence**
(repository already lives at `stalker0321/remanence`; public backend URL stays
`https://remanence.hryshyn.dev/`). Architecture, security model, M1 scope, and
stack are unchanged. This ADR defines exactly what is renamed, what is kept,
and why, so that no occurrence of the old name survives by accident and no
compatibility-sensitive identifier is changed silently.

This rebrand happens before production (M1 test builds only).

## Inventory at base commit b6553a6

Case-insensitive counts over tracked files:

| Pattern | Matches | Files |
| --- | --- | --- |
| `postmark` | 2135 | 361 |
| `postcard` | 133 | 52 |
| phrase `Postcard Memory Capsules` | 2 | 2 (`README.md`, `strings.xml`) |

Distribution of `postmark*` occurrences:

1. Android Kotlin packages `app.postmark.memory.*` (app module) and
   `postmark.core.{model,data,crypto,recognition}` (library modules) — package
   declarations, imports, fully qualified usages.
2. Gradle: root project name `Postmark`, AGP namespaces, applicationId
   `app.postmark.memory`, build property `postmark.apiBaseUrl`.
3. Proto sources: `protocol/proto/postmark/**`, java_package
   `app.postmark.protocol.v1` / `app.postmark.recognition.v1`, outer class
   `PostmarkV1`.
4. Backend Python package `server/src/postmark/**`, pyproject name
   `postmark-server`, uvicorn entrypoint `postmark.main:create_app`.
5 Deployment: Dockerfile user/group/paths, compose service env vars
   `POSTMARK_*`, postgres db/user `postmark`, volumes
   `postmark-postgres-data`/`postmark-blob-data`, blob root
   `/var/lib/postmark/blobs`, `scripts/verify-m0.sh` env/path conventions.
6. Crypto domain-separation strings (see "Kept as legacy protocol
   identifiers").
7. Device-persisted identifiers (see "Persisted identifiers").
8. Docs prose and historical evidence records.
9. Display strings: app label `Postcard Memory Capsules`,
   `Theme.Postmark`, FastAPI title `Postmark API`.

## Decision boundary

### 1. Branding/source namespace — RENAMED to Remanence

Canonical new identifiers:

- Product/display/project name: **Remanence**
- Android `namespace` and `applicationId`: `dev.hryshyn.remanence`
- Kotlin packages: `dev.hryshyn.remanence.*` (app:
  `dev.hryshyn.remanence`; libraries: `dev.hryshyn.remanence.core.{model,data,crypto,recognition}`)
- Generated proto packages: `dev.hryshyn.remanence.protocol.v1`,
  `dev.hryshyn.remanence.recognition.v1`; proto file roots moved from
  `protocol/proto/postmark/**` to `protocol/proto/remanence/**`
- Backend Python package/module: `remanence` (project `remanence-server`)
- Gradle root project name `Remanence`; property `remanence.apiBaseUrl`
- Env var prefix `REMANENCE_` (compose/scripts/settings)
- Deployment names: image `remanence-api:m0`, postgres db/user `remanence`,
  volumes `remanence-postgres-data`/`remanence-blob-data`, blob root
  `/var/lib/remanence/blobs`, container user/group `remanence`
- Display: app label `Remanence`, theme `Theme.Remanence`, OpenAPI title
  `Remanence API`

Proto wire format is unaffected by these renames: protobuf encoding carries
field numbers only, never package or message names. Golden fixtures pin the
byte-level output and guard this claim.

One documented-but-unimplemented REST element is renamed with this same
rationale: the capsule blob-upload header `X-Postmark-Ciphertext-SHA256`
becomes `X-Remanence-Ciphertext-SHA256`. Neither the server route nor any
client uploader exists yet (M1 implements auth/directory/health only), so
there is no deployed consumer; it carries no relation to crypto AAD or
persisted state.

### 2. Kept as legacy protocol identifiers — NOT renamed

These strings are domain-separation labels baked into ciphertext, signatures,
and sealed local records created by earlier M1 builds. Renaming them would
make existing capsule/key material unverifiable:

- `postmark/artifact/v1` — AEAD associated-data prefix (ADR-005/ADR-006)
- `postmark/envelope/v1` — HPKE context-info prefix (ADR-006)
- `postmark/publish/v1` — publish-statement signature domain prefix (ADR-007)
- `postmark/kek/wrap/v1` — KEK keyset-wrap domain prefix
- `postmark/session/v1` — session-token sealing prefix
- `postmark/local-fp/v1` — local fingerprint-sealing AEAD prefix
  (`EncryptedFingerprintStore`)
- fixture marker `postmark-envelope-plaintext-v1` in
  `protocol/fixtures/recipient-envelope-v1.json`

They stay byte-for-byte identical and are pinned by golden tests
(`RecipientEnvelopeCryptorGoldenTest`, `publish-signature-v1.json`,
`recipient-envelope-v1.json`). They are documented legacy protocol
identifiers, not accidental leftovers. Any future protocol version that wants
`remanence/...` labels must define a new versioned format and a migration.

### 3. Domain vocabulary — KEPT

The word **postcard** (lowercase, or `Postcard` inside technical identifiers
that name the physical object) is domain vocabulary, not branding:
`docs/product.md` makes the physical postcard the primary scanned object, and
that thesis is unchanged by the rebrand. Examples retained intentionally:
proto message `PostcardFingerprint`, `PostcardContourDetector`. The brand
phrase "Postcard Memory Capsules" itself is removed everywhere.

### 4. Persisted device-local identifiers — RENAMED cleanly, no fallback needed

Device-local persisted names carry no cross-install contract because the new
`applicationId` installs into a separate Android sandbox (new UID): data
written by old `app.postmark.memory` test builds is physically unreachable
from `dev.hryshyn.remanence` and vice versa. Therefore:

- Room database filename `postmark.db` → `remanence.db`
- Keystore aliases `postmark.session.v1` → `remanence.session.v1`,
  `postmark.identity.v1` → `remanence.identity.v1`,
  `postmark.fingerprint.v1` → `remanence.fingerprint.v1`
- Files-dir subroots (`fingerprints/`, `identity/`, `session/`) are already
  brand-free and unchanged

No one-time data migration exists or is needed across the applicationId
boundary; private keys/account identity are regenerated on first run of the
new build. **Expected pre-production consequence:** installing a
`dev.hryshyn.remanence` build does not upgrade an existing
`app.postmark.memory` test install; both may sit side by side; old test-build
data (including its Keystore keys) is abandoned with the old package and is
removed by uninstalling the old build.

Room exported-schema history is preserved: schema JSONs move intact to the
folder named for the renamed database class
(`schemas/dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase/`),
keeping versions 1–3 available to `MigrationTestHelper`.

### 5. Server-side deployment state — renamed with documented fresh-state consequence

Renaming the postgres database/user/volume and blob-root paths means the next
deployment starts with empty server state (fresh accounts directory). This is
accepted pre-production: clients also get a fresh sandbox (section 4), so
client/server state resets consistently on the next deployment of the renamed
stack. Alembic migration history files (`0001_*`, `0002_*`) keep their
revision identities untouched; only their Python imports follow the package
rename. No destructive reset is performed by application code.

### 6. Historical records — UNCHANGED

Immutable history, excluded from renaming:

- `docs/evidence/m1-implementation-evidence.md` (historical evidence record)
- `docs/decisions/ADR-001..007` (decision history; their descriptions of the
  `postmark/*` crypto prefixes remain accurate because those prefixes are kept)
- `.agent/*` process-state artifacts

Current prose docs (`README.md`, `docs/product.md`, `docs/architecture.md`,
`docs/security.md`, `docs/protocol.md`, `docs/recognition.md`,
`docs/development.md`, `docs/milestones.md`, `docs/acceptance-criteria.md`,
`docs/test-strategy.md`, `docs/implementation-plan.md`) are updated where they
name the brand, keeping historical narrative truthful.

## Consequences

- Every remaining case-insensitive `postmark` occurrence in tracked files
  after this ADR is either a section-2 legacy protocol identifier, a section-3
  domain-vocabulary term, or inside section-6 historical records — verified by
  the final audit (case-insensitive rg) recorded in the closing evidence doc.
- Existing capsules/key material remain verifiable (crypto prefixes frozen).
- Old test builds keep working against their own sandbox until uninstalled;
  new builds start clean under `dev.hryshyn.remanence`.
- Next server deployment under the new names begins with fresh server state;
  production URL and REST API paths are unchanged.
