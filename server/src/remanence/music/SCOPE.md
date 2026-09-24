# Music search slice — scope record (F5)

Authoritative architecture: `ARCHITECTURE-v1.md` in
`/home/vodkolyan/projects/Remanence-music/` (alias: Remanence-music
`CURRENT`). Older Grok/Deezer drafts are superseded and must not be used.

## This slice implements (only)

- Provider-neutral domain: own `RemanenceTrackId` (UUID), `MusicTrack`,
  `TrackSearchResult`, generic `ExternalIdentifier` (ARCH sections 2, 7, 8, 42).
- Stable `MusicSearchPort` (section 10).
- Own Meilisearch search document + index settings + variant-aware request
  payload (sections 10–12). The live adapter speaks the Meilisearch REST
  wire protocol; it is protocol-real but catalog-empty.
- Authenticated `GET /music/v1/search` API contract (sections 26–27) with
  fail-closed errors (unwired backend → 503, never misleading `200 []`).
- Fixture-backed tests (5 hardcoded tracks, TEST/DEV only). Fixtures are a
  test harness, NOT a user-facing catalog.

## Explicitly deferred (not implemented, not claimed)

- D4 — Index build/push + staging-revision switch (section 6:
  `music_rev_N` build → validate → atomic switch). The adapter exposes
  minimal `update_settings` / `put_documents` / `delete_index` test helpers
  only; there is no revision manager, no validation gate, no switch.
- Destination subsystem (sections 16–21), artwork subsystem (sections 22–25),
  capsule `TrackSnapshot` wiring (sections 14–15), service registry,
  observability (section 36).
- D11 follow-ups beyond the landed MusicBrainz sample path: full-dump
  production ingestion at scale, `SourceAdapter`/`TrackIdentityMatcher`/
  `Search Builder` hardening, and the D4 staging-revision switch all remain
  deferred.
- Production catalog activation: no PROD wiring for staging or fixtures,
  no full-dump import, no Meili production index (see M-S1 below).

## Landed in main (post-slice: importer + staging + staging search)

- `music/ingestion/`: streaming `mbdump.tar.bz2` COPY parser (allowlisted
  members only, single tar pass per call, hard read + retention caps),
  join + normalize (`recording` -> `artist_credit_name` -> `artist`, ISRC
  attach), deterministic `RemanenceTrackId = UUIDv5(namespace,
  "musicbrainz:recording:<mbid>")`, and five-pass `run_sample`
  (hash-ranked artist top-N -> candidate credits (capped count/bytes) ->
  full candidate rows -> selected-credit recordings -> kept-id ISRCs,
  each filtered while streaming) with explicit `SampleCoverage`
  metadata (`sample_method: hash-sha256-ascending`, uniform over the id
  keyspace, NOT claimed representative). Member byte caps stay mandatory:
  raise only to a `probe_member_sizes` measured value (guarded raise),
  never disable.
- Synthetic COPY text fixtures + tar built in-test only. No API/proto/
  capsule changes, no real download.
- Sample JSONL v2 (`ingestion/sample_jsonl.py`): strict superset adding
  `artist_mbids` parallel to `artists` plus nullable `duration_ms`;
  deterministic encoding, atomic budgeted writes, no name-derived identity
  anywhere on the path.
- Staging schema + loader (`music/staging/`): artist rows keyed by
  authoritative `artist_mbids` via `UUIDv5(namespace,
  "musicbrainz:artist:<mbid>")` — never by display name. Homonyms stay
  separate rows; credits ordered by position; duration nullable (never 0);
  ISRC collisions stored unmerged; v1 rows lacking `artist_mbids` are
  explicitly rejected. Migration `0008_music_staging` is code-only (never
  applied in production); loader CLI `scripts/music_load_staging.py`
  defaults to dry-run, verifies the v2 bundle, and only writes with
  explicit `--execute` + Postgres URL.
- Staging search (`music/search/staging.py`): `PostgresStagingSearch` over
  the staging tables through an injected session factory (one fresh
  session per call, read-only), with deterministic intent ranking and
  `search_with_total` for endpoint pagination. Exercised by
  `test_music_staging_search_api.py` via explicit injection.

## M-S1 — opt-in DEV/TEST staging composition (this change)

- `Settings.music_search_backend`: narrow `DISABLED` (default) /
  `POSTGRES_STAGING` enum. Default stays unwired → `GET /music/v1/search`
  fail-closed 503, never misleading `200 []`.
- `music/search/composition.py::build_music_search(settings,
  session_factory)`: `DISABLED` → `None`; `POSTGRES_STAGING` in DEV/TEST
  with an explicit session factory → `PostgresStagingSearch`; PROD staging
  or missing/non-callable factory → fail-closed `ValueError`. Unknown
  backend values are rejected.
- `create_app` enforces the same at the single choke point: PROD refuses a
  wired `PostgresStagingSearch`, a wired fixture search, and a
  `POSTGRES_STAGING` backend even when unwired; TEST auto-composes only
  with an explicit session factory, otherwise fails closed. Live DEV
  (`uvicorn remanence.main:create_app --factory`, no factory param) defers
  to lifespan, which wires from the newly built `app.state.session_factory`
  only when the backend is `POSTGRES_STAGING`; missing factory fails
  closed. No production activation, no Meili/full-import/deploy change.
- Public-endpoint safety gates (same change): per-user token-bucket rate
  limit on `GET /music/v1/search` (60/min, burst 10 → 429 `RATE_LIMITED` +
  `Retry-After`; 401 precedes limiting, 422/503 consume no budget, max
  10k tracked users with idle/oldest eviction) and a staging candidate cap
  (`STAGING_SEARCH_CANDIDATE_CAP` 5000 IDs; broader queries fail closed
  503 via `MusicSearchUnavailableError`, never truncated totals).

## Non-claim: NOT a working real catalog

This slice MUST NOT be described as a working real catalog. DoD scenario
(section 44, "505 arctic monkeys" → own index hit → capsule snapshot →
chooser) still requires destination/capsule wiring plus a production index
(D4 switch); M-S1 only enables a DEV/TEST sample-backed endpoint for
iteration. No PROD catalog, full import, or Meili production activation.
