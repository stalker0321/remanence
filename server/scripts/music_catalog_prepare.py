"""Manual plan/fetch CLI. Does not import, deploy, rotate or delete a catalog."""
import argparse
import json
from pathlib import Path

from remanence.music.catalog_prepare import CatalogPreparationError, GIB, discover, fetch, preflight


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["plan", "fetch"])
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--revision")
    parser.add_argument("--staging-gib", type=int, required=True)
    parser.add_argument("--index-gib", type=int, required=True)
    parser.add_argument("--rollback-gib", type=int, required=True)
    parser.add_argument("--reserve-gib", type=int, default=10)
    parser.add_argument("--max-download-gib", type=int, default=12)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if args.action == "fetch" and not args.execute: parser.error("fetch requires explicit --execute")
    try:
        snapshot = discover(args.revision)
        report = preflight(snapshot, args.work_dir, staging_gib=args.staging_gib, index_gib=args.index_gib,
            rollback_gib=args.rollback_gib, reserve_gib=args.reserve_gib, max_download_gib=args.max_download_gib)
        print(json.dumps(report, indent=2))
        if args.action == "fetch":
            if not report["disk_admitted"]: raise CatalogPreparationError("insufficient free space for declared build/rollback budgets")
            print(fetch(snapshot, args.work_dir.resolve(), args.max_download_gib * GIB))
    except (CatalogPreparationError, OSError, ValueError) as error:
        parser.exit(1, f"Catalog preparation refused: {error}\n")


if __name__ == "__main__": main()
