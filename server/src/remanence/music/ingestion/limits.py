"""Hard limits for the MusicBrainz core-dump importer (ARCH section 6).

Every bound here is fail-closed: hitting a cap aborts the import with
``ImporterLimitError`` instead of silently truncating the catalog. The
caps are sized for the bounded 1000-artist real sample stage, not for a
full-catalog import (which needs its own capacity review).
"""

from __future__ import annotations

# Tables parsed in the sample stage. Joins: recording.artist_credit ->
# artist_credit_name -> artist (names); isrc.recording -> recording (ISRCs).
# The artist_credit table itself is intentionally NOT parsed: credit
# membership comes from artist_credit_name, so retaining it would only
# spend memory for zero information (audit R-stop).
SAMPLE_TABLES: tuple[str, ...] = (
    "artist",
    "artist_credit_name",
    "recording",
    "isrc",
)

# Exact member names accepted inside mbdump.tar.bz2. Anything else is
# skipped and counted; path traversal is rejected outright (see mbdump.py).
ALLOWED_MEMBERS: frozenset[str] = frozenset(f"mbdump/{t}" for t in SAMPLE_TABLES)

# Deterministic sample scope: first N artists by ascending integer id.
MAX_SAMPLE_ARTISTS: int = 1000

# Per-member decompressed byte budget. The full fullexport is ~7 GiB;
# individual core tables stream well under this cap.
MAX_MEMBER_BYTES: int = 4 * 1024**3

# Hard stop on rows per table. The real recording table holds tens of
# millions of rows; this cap only guards against runaway/corrupt input.
MAX_ROWS_PER_TABLE: int = 100_000_000

# Single COPY line budget. Real rows are small (names/titles); a 1 MiB
# line is already corrupt-or-hostile input.
MAX_LINE_BYTES: int = 1024 * 1024

# Single field budget after unescaping. Generous for names/titles.
MAX_FIELD_CHARS: int = 4096

# Malformed-row budget per table. Past this the input is treated as
# corrupt rather than noisy: abort instead of ingesting garbage.
MAX_MALFORMED_ROWS_PER_TABLE: int = 100_000

# Retention budgets: rows/bytes KEPT after streaming filters, per table.
# Streaming caps bound what we read; these bound what we hold. The sample
# stage retains thousands of rows, so breaching these means the filters
# are wrong — abort fail-closed, never silently truncate.
MAX_RETAINED_ROWS_PER_TABLE: int = 5_000_000
MAX_RETAINED_BYTES_PER_TABLE: int = 1024**3

# Candidate-credit set budgets (B2): pass 2a collects credit ids touching
# the allowlist before their full rows are fetched. For a 1000-artist
# sample this is thousands of ids; the caps below only trip on a broken
# filter or hostile input — fail-closed, never silent.
MAX_CANDIDATE_CREDITS: int = 1_000_000
# Estimated bytes per retained candidate id (Python int + set overhead,
# documented estimate — deliberately conservative vs ~72 B measured).
CANDIDATE_ID_BYTES: int = 64
MAX_CANDIDATE_BYTES: int = MAX_CANDIDATE_CREDITS * CANDIDATE_ID_BYTES
