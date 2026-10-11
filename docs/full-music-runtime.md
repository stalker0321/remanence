# Full music runtime

Set `REMANENCE_MUSIC_SEARCH_BACKEND=full_catalog` and
`REMANENCE_MUSIC_CATALOG_PATH=/var/lib/remanence/music/catalog.sqlite3`.
The default remains `disabled`; no fixture or staging fallback is used.
Mount one verified READY revision **read-only**, outside the account/blob DB.
Do not modify a live SQLite file in place. Rotation mounts a separately verified
revision and recreates only the API; retain the previous revision/config for
rollback. Neither schema migration nor Android rebuild is required.

Startup validates READY metadata and required schema without scanning all rows.
Integrity, FTS content coverage, source preservation and SHA256 must be checked
before activation (T170). Runtime uses read-only connections and the existing
authenticated `/music/v1/search` contract, stable Remanence track IDs, exact
totals, pagination, per-user rate limit and a two-second query deadline.
`REMANENCE_MUSIC_SEARCH_TIMEOUT_SECONDS` can explicitly bound it to >0 and <=10.
Broad expensive queries return retryable 503, never truncated/sampled results.
FTS is AND-prefix search, not typo-tolerant Meilisearch. Artwork/year/release
metadata not imported by this snapshot remain absent.
