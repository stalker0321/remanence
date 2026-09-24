"""Streaming parser for MusicBrainz fullexport ``mbdump`` COPY tables.

Reads only allowlisted members from ``mbdump.tar.bz2`` in stream mode
(``r|bz2``): nothing is fully extracted or loaded into memory. Each member
is a PostgreSQL COPY text stream — tab-separated fields, ``\\N`` as a
whole-field NULL marker, backslash escapes — with no header row.

Column maps are positional and pinned to the fullexport schema used by the
bounded sample stage. They MUST be re-validated against the real archive
during the 1000-artist sample run before any production import; the sample
coverage metadata records the schema note for exactly this check.
"""

from __future__ import annotations

import re
import tarfile
from dataclasses import dataclass, field
from pathlib import Path

from remanence.music.ingestion.limits import (
    MAX_FIELD_CHARS,
    MAX_LINE_BYTES,
    MAX_MALFORMED_ROWS_PER_TABLE,
    MAX_MEMBER_BYTES,
    MAX_RETAINED_BYTES_PER_TABLE,
    MAX_RETAINED_ROWS_PER_TABLE,
    MAX_ROWS_PER_TABLE,
    SAMPLE_TABLES,
)

# Positional column indexes into the COPY streams. ``min_columns`` is the
# number of leading columns this slice depends on; trailing columns are
# ignored so future dump columns cannot shift our reads.
#
# artist_credit_name layout is the official fullexport order:
# artist_credit, position, artist, name, join_phrase, ... (audit STOP:
# artist is index 2, not 1). ``min_columns`` is 4 because join_phrase may
# be absent; artist/name/position are always required.
TABLE_SCHEMAS: dict[str, dict[str, object]] = {
    "artist": {
        "columns": {"id": 0, "gid": 1, "name": 2},
        "ints": ("id",),
        "min_columns": 3,
    },
    "artist_credit_name": {
        "columns": {
            "artist_credit": 0,
            "position": 1,
            "artist": 2,
            "name": 3,
            "join_phrase": 4,
        },
        "ints": ("artist_credit", "position", "artist"),
        "min_columns": 4,
    },
    "recording": {
        "columns": {"id": 0, "gid": 1, "name": 2, "artist_credit": 3, "length": 4},
        "ints": ("id", "artist_credit"),
        "optional_ints": ("length",),
        "min_columns": 4,
    },
    "isrc": {
        "columns": {"id": 0, "recording": 1, "isrc": 2},
        "ints": ("id", "recording"),
        "min_columns": 3,
    },
}

# Schema fingerprint for the sample coverage metadata. Bump only when the
# maps above are re-validated against a real fullexport snapshot.
SCHEMA_NOTE = "fullexport-COPY positional map v1 (re-validate on real sample)"

# PostgreSQL COPY text-input escapes: named escapes, 1-3 digit octal
# (\ooo), \x plus 1-2 hex digits, and backslash + any other char meaning
# the char itself (unknown escapes pass through, backslash dropped).
_COPY_ESCAPE_RE = re.compile(r"\\(?:([0-7]{1,3})|x([0-9A-Fa-f]{1,2})|(.))", re.DOTALL)
_NAMED_ESCAPES = {
    "t": "\t",
    "n": "\n",
    "r": "\r",
    "b": "\b",
    "f": "\f",
    "v": "\v",
    "\\": "\\",
}


class ImporterError(RuntimeError):
    """Importer failed fail-closed (corrupt input, missing table, unsafe tar)."""


class ImporterLimitError(ImporterError):
    """A hard record/byte/error cap was hit; the import was aborted."""


@dataclass(frozen=True, slots=True)
class TableParseStats:
    table: str
    rows_seen: int
    rows_kept: int
    rows_malformed: int
    bytes_read: int


@dataclass(frozen=True, slots=True)
class TarParseReport:
    tar_name: str
    members_seen: int
    members_parsed: int
    members_skipped_unknown: int
    tables: tuple[TableParseStats, ...] = field(default_factory=tuple)


def unescape_copy_field(raw: str) -> str | None:
    """Decode one COPY text field.

    Whole-field ``\\N`` is NULL. Otherwise backslash escapes follow the
    COPY input rules: named escapes (``\\t`` etc.), 1-3 digit octal,
    ``\\x`` + 1-2 hex digits, and backslash + any other char meaning that
    char literally. A trailing lone backslash is kept literally.
    """
    if type(raw) is not str:
        raise TypeError("unescape_copy_field requires str")
    if raw == "\\N":
        return None

    def _one(match: re.Match[str]) -> str:
        octal, hexa, single = match.group(1), match.group(2), match.group(3)
        if octal is not None:
            return chr(int(octal, 8))
        if hexa is not None:
            return chr(int(hexa, 16))
        assert single is not None
        return _NAMED_ESCAPES.get(single, single)

    decoded = _COPY_ESCAPE_RE.sub(_one, raw)
    if len(decoded) > MAX_FIELD_CHARS:
        raise ValueError("field over length cap")
    return decoded


def parse_copy_line(line: str, schema: dict[str, object]) -> dict[str, object] | None:
    """Parse one COPY line into a row dict, or None when malformed."""
    columns = schema["columns"]
    assert isinstance(columns, dict)
    min_columns = schema["min_columns"]
    assert isinstance(min_columns, int)
    parts = line.rstrip("\r\n").split("\t")
    if len(parts) < min_columns:
        return None
    row: dict[str, object] = {}
    try:
        for name, index in columns.items():
            assert isinstance(index, int)
            raw = parts[index] if index < len(parts) else "\\N"
            row[name] = unescape_copy_field(raw)
        for name in schema.get("ints", ()):  # type: ignore[union-attr]
            assert isinstance(name, str)
            value = row.get(name)
            if value is None:
                return None
            assert isinstance(value, str)
            row[name] = int(value)
        for name in schema.get("optional_ints", ()):  # type: ignore[union-attr]
            assert isinstance(name, str)
            value = row.get(name)
            if value is None:
                row[name] = None
            else:
                assert isinstance(value, str)
                row[name] = int(value)
    except (ValueError, TypeError, IndexError):
        return None
    return row


def _check_member_name(name: str) -> None:
    if not name or name.startswith("/") or ".." in name.split("/"):
        raise ImporterError(f"unsafe tar member rejected: {name!r}")


def _drain_member_stream(
    stream: object,
    table: str,
    schema: dict[str, object],
    *,
    row_cap: int,
    member_byte_cap: int,
    malformed_cap: int,
    retained_cap: int,
    retained_byte_cap: int,
    keep: object = None,
) -> tuple[list[dict[str, object]], int, int, int]:
    """Consume one member stream. Returns (rows, seen, malformed, bytes).

    ``row_cap``/``member_byte_cap`` bound what is READ; ``retained_cap``/
    ``retained_byte_cap`` bound what is KEPT after the ``keep`` filter.
    Any breach aborts fail-closed via ImporterLimitError.
    """
    rows: list[dict[str, object]] = []
    seen = 0
    malformed = 0
    bytes_read = 0
    retained_bytes = 0
    assert hasattr(stream, "readline")
    while True:
        raw_line = stream.readline(MAX_LINE_BYTES + 2)  # type: ignore[union-attr]
        if not raw_line:
            break
        bytes_read += len(raw_line)
        if bytes_read > member_byte_cap:
            raise ImporterLimitError(f"{table}: member byte cap exceeded")
        if len(raw_line) > MAX_LINE_BYTES + 1:
            # Overlong line: count as malformed and resync by discarding
            # to end-of-line (still bounded by the member byte cap).
            malformed += 1
            while raw_line and not raw_line.endswith(b"\n"):
                raw_line = stream.readline(MAX_LINE_BYTES + 2)  # type: ignore[union-attr]
                if not raw_line:
                    break
                bytes_read += len(raw_line)
                if bytes_read > member_byte_cap:
                    raise ImporterLimitError(f"{table}: member byte cap exceeded")
            if malformed > malformed_cap:
                raise ImporterLimitError(f"{table}: malformed-row cap exceeded")
            continue
        try:
            text = raw_line.decode("utf-8")
        except UnicodeDecodeError:
            malformed += 1
            if malformed > malformed_cap:
                raise ImporterLimitError(f"{table}: malformed-row cap exceeded")
            continue
        if not text.strip():
            continue
        seen += 1
        if seen > row_cap:
            raise ImporterLimitError(f"{table}: row cap exceeded")
        row = parse_copy_line(text, schema)
        if row is None:
            malformed += 1
            if malformed > malformed_cap:
                raise ImporterLimitError(f"{table}: malformed-row cap exceeded")
            continue
        if keep is not None:
            assert callable(keep)
            if not keep(row):
                continue
        retained_bytes += len(raw_line)
        if retained_bytes > retained_byte_cap:
            raise ImporterLimitError(f"{table}: retained byte cap exceeded")
        rows.append(row)
        if len(rows) > retained_cap:
            raise ImporterLimitError(f"{table}: retained row cap exceeded")
    return rows, seen, malformed, bytes_read


def _open_tar(tar_path: str | Path) -> tarfile.TarFile:
    try:
        return tarfile.open(tar_path, mode="r|bz2")
    except (tarfile.TarError, OSError, EOFError) as exc:
        raise ImporterError(f"cannot open tar: {exc}") from exc


def probe_member_sizes(tar_path: str | Path) -> dict[str, int]:
    """Read-only header probe: member name -> declared uncompressed bytes.

    B1 guarded-raise workflow: run this FIRST against the real archive,
    record the measured size of each required member, then pass an explicit
    ``member_byte_cap`` >= measured size to ``run_sample``. The bound is
    raised with justification — never disabled (caps must stay positive
    ints; the streaming reader still enforces the cap while reading, so a
    lying header cannot smuggle extra bytes past it).

    Cost: one sequential header scan (stream mode decompresses to walk
    headers, but no member bodies are parsed or retained).
    """
    sizes: dict[str, int] = {}
    with _open_tar(tar_path) as handle:
        for member in handle:
            _check_member_name(member.name or "")
            name = member.name or ""
            if member.isfile():
                size = member.size
                if type(size) is not int or size < 0:
                    raise ImporterError(f"bad header size for member: {name!r}")
                sizes[name] = size
    return sizes


def parse_member_rows(
    tar_path: str | Path,
    table: str,
    *,
    row_cap: int = MAX_ROWS_PER_TABLE,
    member_byte_cap: int = MAX_MEMBER_BYTES,
    malformed_cap: int = MAX_MALFORMED_ROWS_PER_TABLE,
    retained_cap: int = MAX_RETAINED_ROWS_PER_TABLE,
    retained_byte_cap: int = MAX_RETAINED_BYTES_PER_TABLE,
    keep: object = None,
) -> tuple[list[dict[str, object]], TableParseStats]:
    """Stream one allowlisted table out of the tar. Bounded on all axes."""
    if table not in TABLE_SCHEMAS:
        raise ImporterError(f"unknown table requested: {table!r}")
    if type(row_cap) is not int or row_cap <= 0:
        raise ImporterError("row_cap must be a positive int")
    if type(member_byte_cap) is not int or member_byte_cap <= 0:
        raise ImporterError("member_byte_cap must be a positive int")
    if type(retained_cap) is not int or retained_cap <= 0:
        raise ImporterError("retained_cap must be a positive int")
    if type(retained_byte_cap) is not int or retained_byte_cap <= 0:
        raise ImporterError("retained_byte_cap must be a positive int")
    schema = TABLE_SCHEMAS[table]
    member_name = f"mbdump/{table}"
    found = False
    result: tuple[list[dict[str, object]], int, int, int] = ([], 0, 0, 0)
    with _open_tar(tar_path) as handle:
        for member in handle:
            _check_member_name(member.name or "")
            if not member.isfile() or member.name != member_name:
                continue
            found = True
            stream = handle.extractfile(member)
            if stream is None:
                raise ImporterError(f"cannot stream member: {member_name}")
            with stream:
                result = _drain_member_stream(
                    stream,
                    table,
                    schema,
                    row_cap=row_cap,
                    member_byte_cap=member_byte_cap,
                    malformed_cap=malformed_cap,
                    retained_cap=retained_cap,
                    retained_byte_cap=retained_byte_cap,
                    keep=keep,
                )
    if not found:
        raise ImporterError(f"required member missing: {member_name}")
    rows, seen, malformed, bytes_read = result
    stats = TableParseStats(
        table=table,
        rows_seen=seen,
        rows_kept=len(rows),
        rows_malformed=malformed,
        bytes_read=bytes_read,
    )
    return rows, stats


def parse_tables(
    tar_path: str | Path,
    tables: tuple[str, ...] = SAMPLE_TABLES,
    *,
    row_cap: int = MAX_ROWS_PER_TABLE,
    member_byte_cap: int = MAX_MEMBER_BYTES,
    malformed_cap: int = MAX_MALFORMED_ROWS_PER_TABLE,
    retained_cap: int = MAX_RETAINED_ROWS_PER_TABLE,
    retained_byte_cap: int = MAX_RETAINED_BYTES_PER_TABLE,
    keep_by_table: dict[str, object] | None = None,
) -> tuple[dict[str, list[dict[str, object]]], TarParseReport]:
    """Stream the required tables in a SINGLE tar pass (bounded).

    ``keep_by_table`` maps table -> predicate(row) -> bool and filters
    WHILE streaming, so unselected rows cost time but never memory.
    """
    for table in tables:
        if table not in TABLE_SCHEMAS:
            raise ImporterError(f"unknown table requested: {table!r}")
    if keep_by_table is not None:
        for table, predicate in keep_by_table.items():
            if table not in tables or not callable(predicate):
                raise ImporterError("keep_by_table must map requested tables to callables")
    wanted = {f"mbdump/{table}": table for table in tables}
    out: dict[str, list[dict[str, object]]] = {t: [] for t in tables}
    per_table: dict[str, list[int]] = {t: [0, 0, 0] for t in tables}  # seen, malformed, bytes
    seen_members = 0
    skipped_unknown = 0
    found: set[str] = set()
    with _open_tar(tar_path) as handle:
        for member in handle:
            _check_member_name(member.name or "")
            seen_members += 1
            name = member.name or ""
            if name in ("mbdump", "./mbdump", "."):
                continue
            table = wanted.get(name)
            if table is None or not member.isfile():
                skipped_unknown += 1
                continue
            found.add(table)
            stream = handle.extractfile(member)
            if stream is None:
                raise ImporterError(f"cannot stream member: {name}")
            with stream:
                rows, seen, malformed, bytes_read = _drain_member_stream(
                    stream,
                    table,
                    TABLE_SCHEMAS[table],
                    row_cap=row_cap,
                    member_byte_cap=member_byte_cap,
                    malformed_cap=malformed_cap,
                    retained_cap=retained_cap,
                    retained_byte_cap=retained_byte_cap,
                    keep=(keep_by_table or {}).get(table),
                )
            out[table] = rows
            per_table[table] = [seen, malformed, bytes_read]
    missing = [t for t in tables if t not in found]
    if missing:
        raise ImporterError(
            f"required members missing: {[f'mbdump/{t}' for t in missing]}"
        )
    path = Path(tar_path)
    report = TarParseReport(
        tar_name=path.name,
        members_seen=seen_members,
        members_parsed=len(tables),
        members_skipped_unknown=skipped_unknown,
        tables=tuple(
            TableParseStats(
                table=table,
                rows_seen=per_table[table][0],
                rows_kept=len(out[table]),
                rows_malformed=per_table[table][1],
                bytes_read=per_table[table][2],
            )
            for table in tables
        ),
    )
    return out, report
