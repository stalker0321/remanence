"""Explicit isolated full build; never activates the current app backend."""
import argparse
import json
import resource
from pathlib import Path

from remanence.music.catalog_prepare import Snapshot
from remanence.music.catalog_build import build_catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--snapshot-json", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--max-build-gib", type=int, default=100)
    parser.add_argument("--reserve-gib", type=int, default=15)
    parser.add_argument("--memory-mib", type=int, default=768)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute: parser.error("isolated build requires explicit --execute")
    if not 256 <= args.memory_mib <= 1024: parser.error("memory budget must be 256..1024MiB")
    resource.setrlimit(resource.RLIMIT_AS, (args.memory_mib * 1024**2, args.memory_mib * 1024**2))
    metadata = json.loads(args.snapshot_json.read_text())
    if metadata.get("state") != "DOWNLOAD_VERIFIED_NOT_CATALOG": parser.error("verified download manifest required")
    snapshot = Snapshot(metadata["revision"], metadata["archive_bytes"], metadata["sha256"])
    build_catalog(args.archive, snapshot, args.output_dir, source_commit=args.source_commit,
                  max_build_gib=args.max_build_gib, reserve_gib=args.reserve_gib)


if __name__ == "__main__": main()
