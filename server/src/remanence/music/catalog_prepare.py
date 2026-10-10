"""Bounded snapshot discovery/preflight/download, NOT a full catalog builder.

Only the CC0 core archive is downloaded. No extraction, production DB write,
active-index switch, archive deletion or scheduler is performed here. The full
disk-backed join/index builder remains a separately accepted implementation.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import urllib.request
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from pathlib import Path

BASE = "https://ftp.musicbrainz.org/pub/musicbrainz/data/fullexport/"
ARCHIVE = "mbdump.tar.bz2"
GIB = 1024 ** 3
SNAPSHOT = re.compile(r"[0-9]{8}-[0-9]{6}")


class CatalogPreparationError(RuntimeError):
    pass


@dataclass(frozen=True)
class Snapshot:
    revision: str
    archive_bytes: int
    sha256: str

    def __post_init__(self):
        if not SNAPSHOT.fullmatch(self.revision) or self.archive_bytes <= 0 or not re.fullmatch(r"[0-9a-f]{64}", self.sha256):
            raise CatalogPreparationError("invalid snapshot metadata")

    @property
    def url(self):
        return f"{BASE}{self.revision}/{ARCHIVE}"


def open_official(request):
    response = urllib.request.urlopen(request, timeout=30)
    # Redirects may not move metadata or download to another origin/path.
    if not response.geturl().startswith(BASE):
        response.close(); raise CatalogPreparationError("unexpected snapshot redirect")
    return response


def small_text(url: str, limit: int):
    with open_official(urllib.request.Request(url, headers={"User-Agent": "Remanence-Catalog-Preparation/1"})) as response:
        data = response.read(limit + 1)
    if len(data) > limit: raise CatalogPreparationError("metadata exceeds budget")
    return data.decode("utf-8", errors="strict")


def discover(revision: str | None = None) -> Snapshot:
    revision = revision or small_text(BASE + "LATEST", 64).strip()
    if not SNAPSHOT.fullmatch(revision): raise CatalogPreparationError("invalid snapshot revision")
    checksums = small_text(f"{BASE}{revision}/SHA256SUMS", 16384)
    matches = []
    for line in checksums.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})\s+\*?(\S+)", line)
        if match and match[2] == ARCHIVE: matches.append(match[1])
    if len(matches) != 1: raise CatalogPreparationError("missing or duplicate core checksum")
    with open_official(urllib.request.Request(f"{BASE}{revision}/{ARCHIVE}", method="HEAD")) as response:
        size = int(response.headers.get("Content-Length", "0"))
    return Snapshot(revision, size, matches[0])


def preflight(snapshot: Snapshot, work_dir: Path, *, staging_gib: int, index_gib: int,
              rollback_gib: int, reserve_gib: int = 10, max_download_gib: int = 12) -> dict:
    values = (staging_gib, index_gib, rollback_gib, reserve_gib, max_download_gib)
    if any(type(v) is not int or v <= 0 for v in values): raise CatalogPreparationError("explicit positive disk budgets required")
    if snapshot.archive_bytes > max_download_gib * GIB: raise CatalogPreparationError("core archive exceeds download budget")
    target = work_dir.resolve()
    if target == Path(target.anchor): raise CatalogPreparationError("choose a dedicated catalog directory")
    existing = target
    while not existing.exists(): existing = existing.parent
    free = shutil.disk_usage(existing).free
    # Conservative: do not subtract partial/cache data or assume compression ratios.
    required = snapshot.archive_bytes + (staging_gib + index_gib + rollback_gib + reserve_gib) * GIB
    return {"schema": "REMANENCE-CATALOG-PREFLIGHT-V1", "snapshot": asdict(snapshot),
            "work_dir": str(target), "free_bytes": free, "required_bytes": required,
            "disk_admitted": free >= required, "catalog_build_implemented": False,
            "checksum_authentication": "SHA256_OVER_OFFICIAL_HTTPS_NOT_GPG",
            "budgets_gib": dict(staging=staging_gib, index=index_gib, rollback=rollback_gib, reserve=reserve_gib)}


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""): result.update(chunk)
    return result.hexdigest()


@contextmanager
def job_lock(work_dir: Path):
    work_dir.mkdir(parents=True, exist_ok=True)
    path = work_dir / ".catalog-prepare.lock"
    try: fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError: raise CatalogPreparationError("preparation is locked; inspect the previous process before clearing its lock") from None
    try:
        os.write(fd, str(os.getpid()).encode()); os.close(fd)
        yield
    finally:
        # Only the exact lock this invocation exclusively created is removed.
        path.unlink()


def fetch(snapshot: Snapshot, work_dir: Path, max_bytes: int) -> Path:
    if snapshot.archive_bytes > max_bytes: raise CatalogPreparationError("download budget exceeded")
    with job_lock(work_dir):
        directory = work_dir / snapshot.revision
        directory.mkdir(exist_ok=True)
        destination = directory / ARCHIVE
        if destination.exists():
            if destination.stat().st_size == snapshot.archive_bytes and digest(destination) == snapshot.sha256:
                return destination
            raise CatalogPreparationError("existing archive does not match; retained without overwrite")
        partial = directory / (ARCHIVE + ".part")
        offset = partial.stat().st_size if partial.exists() else 0
        if offset > snapshot.archive_bytes: raise CatalogPreparationError("partial archive exceeds snapshot size")
        if offset < snapshot.archive_bytes:
            headers = {"User-Agent": "Remanence-Catalog-Preparation/1", "Accept-Encoding": "identity"}
            if offset: headers["Range"] = f"bytes={offset}-"
            with open_official(urllib.request.Request(snapshot.url, headers=headers)) as response:
                if offset:
                    expected = f"bytes {offset}-{snapshot.archive_bytes - 1}/{snapshot.archive_bytes}"
                    if response.status != 206 or response.headers.get("Content-Range") != expected:
                        raise CatalogPreparationError("server did not honor exact resume range; partial retained")
                elif response.status != 200:
                    raise CatalogPreparationError("unexpected download response")
                if response.headers.get("Content-Encoding", "identity") != "identity":
                    raise CatalogPreparationError("encoded download refused")
                with partial.open("ab" if partial.exists() else "xb") as output:
                    size = offset
                    while chunk := response.read(min(1024 * 1024, snapshot.archive_bytes - size + 1)):
                        size += len(chunk)
                        if size > snapshot.archive_bytes or size > max_bytes: raise CatalogPreparationError("download exceeds declared size")
                        output.write(chunk)
                    output.flush(); os.fsync(output.fileno())
        if partial.stat().st_size != snapshot.archive_bytes or digest(partial) != snapshot.sha256:
            raise CatalogPreparationError("archive integrity failed; partial retained for diagnosis")
        partial.replace(destination)
        manifest = directory / "snapshot.json"
        temp = directory / "snapshot.json.tmp"
        with temp.open("w", encoding="utf-8") as output:
            json.dump({"schema": "REMANENCE-CATALOG-DOWNLOAD-V1", **asdict(snapshot), "state": "DOWNLOAD_VERIFIED_NOT_CATALOG"}, output)
            output.flush(); os.fsync(output.fileno())
        temp.replace(manifest)
        return destination
