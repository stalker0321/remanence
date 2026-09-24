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
- D11 — Real core ingestion and persistence (sections 5–9): MusicBrainz CC0
  dump discovery/download/checksum/extract, `SourceAdapter`, normalization,
  `TrackIdentityMatcher`, Postgres `music.*` schema + migrations, `Search
  Builder`. `server/migrations/versions` contains no music revision.
- Destination subsystem (sections 16–21), artwork subsystem (sections 22–25),
  capsule `TrackSnapshot` wiring (sections 14–15), service registry,
  observability (section 36).

## Importer sample stage (uncommitted slice on top of the above)

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
- Synthetic COPY text fixtures + tar built in-test only. No Postgres
  schema, no Meili writes, no API/proto/capsule changes, no real download.
- Sample JSONL v2 (`ingestion/sample_jsonl.py`, uncommitted): strict
  superset adding `artist_mbids` parallel to `artists` plus nullable
  `duration_ms`; deterministic encoding, atomic budgeted writes, no
  name-derived identity anywhere on the path.
- Staging schema + loader (`music/staging/`, uncommitted, repaired):
  artist rows keyed by authoritative `artist_mbids` via
  `UUIDv5(namespace, "musicbrainz:artist:<mbid>")` — never by display
  name. Homonyms stay separate rows; credits ordered by position;
  duration nullable (never 0); ISRC collisions stored unmerged; v1 rows
  lacking `artist_mbids` are explicitly rejected. Migration
  `0008_music_staging` authored code-only (never applied); loader CLI
  `scripts/music_load_staging.py` defaults to dry-run, verifies the v2
  bundle, and only writes with explicit `--execute` + Postgres URL.
  No live DB touched.

## Non-claim: NOT a working real catalog

This slice MUST NOT be described as a working real catalog. DoD scenario
(section 44, "505 arctic monkeys" → own index hit → capsule snapshot →
chooser) requires the deferred ingestion slice first. The next music work
is an ADR-backed ingestion slice, not an extension of the fixtures.
