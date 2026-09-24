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

## Non-claim: NOT a working real catalog

This slice MUST NOT be described as a working real catalog. DoD scenario
(section 44, "505 arctic monkeys" → own index hit → capsule snapshot →
chooser) requires the deferred ingestion slice first. The next music work
is an ADR-backed ingestion slice, not an extension of the fixtures.
