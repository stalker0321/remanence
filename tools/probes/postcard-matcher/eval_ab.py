"""Slice 0 offline A/B eval seam (stdlib only).

Classifies frozen postcard-matcher result rows — and, when available,
current-pipeline result rows in the same schema — against an out-of-tree
ground-truth manifest. Never imports the matcher (no cv2/numpy/scipy),
never touches Android code, never invents labels.

Outcome categories (pre-registered, see README.md):
  correct | wrong_grant | recapture_miss | truth_absent_ambiguous

Exit codes: 0 complete; 1 infrastructure failure; 2 gate stop.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCHEMA_VERSION = 1
CATEGORIES = ("correct", "wrong_grant", "recapture_miss", "truth_absent_ambiguous")
STATUSES = ("matched", "unknown")

HONESTY_NOTES = (
    "confidence is heuristic strength, not a probability",
    "wrong_grant count is not a false-accept rate",
)


class GateStop(Exception):
    """Ground truth or result coverage is missing; refusing to label."""


class InfraFailure(Exception):
    """Unreadable input or unwritable output directory."""


def _load_json(path: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise GateStop(f"missing input (gate not satisfied): {path}") from exc
    except PermissionError as exc:
        raise InfraFailure(f"unreadable input: {path}") from exc
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateStop(f"invalid JSON input: {path}") from exc


def load_manifest(path: Path) -> dict[str, dict]:
    """Return {query_id: {"truth": str|None, "expect": "match"|"reject"}}."""
    raw = _load_json(path)
    if not isinstance(raw, dict):
        raise GateStop("corpus manifest must be a JSON object")
    if raw.get("schema_version") != SCHEMA_VERSION:
        raise GateStop("corpus manifest schema_version != 1")
    queries = raw.get("queries")
    if not isinstance(queries, list) or not queries:
        raise GateStop("corpus manifest has no queries; refusing to invent labels")
    manifest: dict[str, dict] = {}
    for entry in queries:
        if not isinstance(entry, dict):
            raise GateStop("corpus manifest query must be an object")
        qid = entry.get("query_id")
        truth = entry.get("truth_card_id")
        expect = entry.get("expect")
        if not isinstance(qid, str) or not qid:
            raise GateStop("corpus manifest query_id must be a non-empty string")
        if qid in manifest:
            raise GateStop(f"duplicate query_id in manifest: {qid}")
        if truth is not None and (not isinstance(truth, str) or not truth):
            raise GateStop(f"truth_card_id must be a string or null: {qid}")
        if expect not in ("match", "reject"):
            raise GateStop(f"expect must be 'match' or 'reject': {qid}")
        if truth is None and expect != "reject":
            raise GateStop(f"truth-absent query must expect reject: {qid}")
        manifest[qid] = {"truth": truth, "expect": expect}
    return manifest


def load_results(path: Path, manifest: dict[str, dict], side: str) -> dict[str, dict]:
    """Return {query_id: row}; fail closed on unknown ids or statuses."""
    raw = _load_json(path)
    if not isinstance(raw, list) or not raw:
        raise GateStop(f"{side} results must be a non-empty JSON array")
    rows: dict[str, dict] = {}
    for entry in raw:
        if not isinstance(entry, dict):
            raise GateStop(f"{side} result row must be an object")
        qid = entry.get("query_id")
        status = entry.get("status")
        if qid not in manifest:
            raise GateStop(f"{side} result for unknown query_id: {qid!r}")
        if status not in STATUSES:
            raise GateStop(f"{side} result has unknown status: {status!r}")
        if qid in rows:
            raise GateStop(f"duplicate {side} result for query_id: {qid}")
        rows[qid] = entry
    missing = sorted(set(manifest) - set(rows))
    if missing:
        raise GateStop(f"{side} results missing {len(missing)} manifest queries")
    return rows


def classify(truth: str | None, row: dict) -> str:
    """Map one (truth, result row) to a pre-registered category.

    Confidence is deliberately ignored: it is heuristic strength, not a
    probability, and must not move a query between categories.
    """
    if row["status"] == "matched":
        if truth is not None and row.get("card_id") == truth:
            return "correct"
        return "wrong_grant"
    if truth is not None:
        return "recapture_miss"
    return "truth_absent_ambiguous"


def summarize(manifest: dict[str, dict], rows: dict[str, dict]) -> dict[str, int]:
    counts = {category: 0 for category in CATEGORIES}
    for qid in sorted(manifest):
        counts[classify(manifest[qid]["truth"], rows[qid])] += 1
    counts["total"] = len(manifest)
    return counts


def run(
    manifest_path: Path,
    matcher_path: Path,
    remanence_path: Path | None,
    out_dir: Path,
    emit,
) -> dict:
    manifest = load_manifest(manifest_path)
    matcher_rows = load_results(matcher_path, manifest, "matcher")
    side_a = summarize(manifest, matcher_rows)
    side_b = None
    remanence_rows: dict[str, dict] = {}
    if remanence_path is not None:
        remanence_rows = load_results(remanence_path, manifest, "remanence")
        side_b = summarize(manifest, remanence_rows)
    report = {
        "side_a": side_a,
        "side_b": side_b,
        "notes": list(HONESTY_NOTES),
    }
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "summary.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except OSError as exc:
        raise InfraFailure(f"unwritable out-dir: {out_dir}") from exc
    for qid in sorted(manifest):
        emit(f"A {qid} {classify(manifest[qid]['truth'], matcher_rows[qid])}")
    for qid in sorted(manifest):
        if qid in remanence_rows:
            emit(f"B {qid} {classify(manifest[qid]['truth'], remanence_rows[qid])}")
    return report


def default_out_dir() -> Path:
    # Always outside the git tree: sibling of the worktree, never committed.
    return Path(__file__).resolve().parent.parent.parent.parent.parent / (
        "Remanence-postcard-matcher-eval-out"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Slice 0 offline A/B eval seam.")
    parser.add_argument("--corpus-manifest", type=Path, required=True)
    parser.add_argument("--matcher-results", type=Path, required=True)
    parser.add_argument("--remanence-results", type=Path, default=None)
    parser.add_argument("--out-dir", type=Path, default=None)
    args = parser.parse_args(argv)
    out_dir = args.out_dir or default_out_dir()
    try:
        run(
            args.corpus_manifest,
            args.matcher_results,
            args.remanence_results,
            out_dir,
            emit=lambda line: print(line, file=sys.stderr),
        )
    except GateStop as exc:
        print(f"gate stop: {exc}", file=sys.stderr)
        return 2
    except InfraFailure as exc:
        print(f"infrastructure failure: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
