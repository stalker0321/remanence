"""MusicBrainz core-dump importer, sample stage (ARCH sections 5-9).

Sample-only scope: streaming COPY-table parser with hard caps, join +
normalize into provider-neutral records, deterministic RemanenceTrackId
assignment, and explicit sample coverage metadata. No Postgres writes, no
search-index writes, no product responses — see ``run_sample``.

NOT a production import job: release/year joins, download/checksum,
staging revisions, and validation gates are deferred (see SCOPE.md).
"""

from remanence.music.ingestion.identity import (
    NAMESPACE_REMANENCE_MUSICBRAINZ_V1,
    TrackImport,
    assign_track_ids,
    track_id_for_recording,
)
from remanence.music.ingestion.limits import (
    ALLOWED_MEMBERS,
    CANDIDATE_ID_BYTES,
    MAX_CANDIDATE_BYTES,
    MAX_CANDIDATE_CREDITS,
    MAX_RETAINED_BYTES_PER_TABLE,
    MAX_RETAINED_ROWS_PER_TABLE,
    MAX_SAMPLE_ARTISTS,
    SAMPLE_TABLES,
)
from remanence.music.ingestion.mbdump import (
    SCHEMA_NOTE,
    ImporterError,
    ImporterLimitError,
    TableParseStats,
    TarParseReport,
    parse_copy_line,
    parse_member_rows,
    parse_tables,
    probe_member_sizes,
    unescape_copy_field,
)
from remanence.music.ingestion.sample_jsonl import (
    JSONL_SCHEMA_VERSION,
    TRACK_FIELDS_V2,
    serialize_coverage,
    serialize_sample_jsonl,
    serialize_track,
    write_sample_output,
)
from remanence.music.ingestion.normalize import (
    SAMPLE_METHOD,
    NormalizeStats,
    NormalizedRecord,
    artist_sample_key,
    build_allowlist,
    normalize_tables,
)
from remanence.music.ingestion.sample import (
    SAMPLE_PASSES,
    SampleCoverage,
    SampleResult,
    run_sample,
)

__all__ = [
    "ALLOWED_MEMBERS",
    "CANDIDATE_ID_BYTES",
    "MAX_CANDIDATE_BYTES",
    "MAX_CANDIDATE_CREDITS",
    "MAX_RETAINED_BYTES_PER_TABLE",
    "MAX_RETAINED_ROWS_PER_TABLE",
    "MAX_SAMPLE_ARTISTS",
    "NAMESPACE_REMANENCE_MUSICBRAINZ_V1",
    "SAMPLE_METHOD",
    "SAMPLE_PASSES",
    "SAMPLE_TABLES",
    "SCHEMA_NOTE",
    "ImporterError",
    "ImporterLimitError",
    "JSONL_SCHEMA_VERSION",
    "NormalizedRecord",
    "NormalizeStats",
    "SampleCoverage",
    "SampleResult",
    "TRACK_FIELDS_V2",
    "TableParseStats",
    "TarParseReport",
    "TrackImport",
    "artist_sample_key",
    "assign_track_ids",
    "build_allowlist",
    "normalize_tables",
    "parse_copy_line",
    "parse_member_rows",
    "parse_tables",
    "probe_member_sizes",
    "run_sample",
    "serialize_coverage",
    "serialize_sample_jsonl",
    "serialize_track",
    "track_id_for_recording",
    "unescape_copy_field",
    "write_sample_output",
]
