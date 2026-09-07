"""Slice 0 seam unit tests (stdlib only; no third-party imports, no network).

Covers the pre-registered category mapping, gate-stop behavior, side-B
optionality, and the honesty rules (confidence ignored; counts are counts).
"""

import json
import tempfile
import unittest
from pathlib import Path

import eval_ab


def _write(path: Path, payload) -> Path:
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def _manifest_payload():
    return {
        "schema_version": 1,
        "queries": [
            {"query_id": "q-correct", "truth_card_id": "021", "expect": "match"},
            {"query_id": "q-wrong", "truth_card_id": "021", "expect": "match"},
            {"query_id": "q-miss", "truth_card_id": "022", "expect": "match"},
            {"query_id": "q-unknown-ok", "truth_card_id": None, "expect": "reject"},
            {"query_id": "q-unknown-grant", "truth_card_id": None, "expect": "reject"},
        ],
    }


def _row(qid, status, card_id=None, confidence=None):
    return {
        "query_id": qid,
        "status": status,
        "card_id": card_id,
        "reason": "geometric_match" if status == "matched" else "insufficient_evidence",
        "confidence": confidence,
    }


def _results_payload():
    return [
        _row("q-correct", "matched", "021", confidence=1.0),
        _row("q-wrong", "matched", "035", confidence=1.0),
        _row("q-miss", "unknown", None, confidence=0.99),
        _row("q-unknown-ok", "unknown", None, confidence=None),
        _row("q-unknown-grant", "matched", "035", confidence=0.01),
    ]


class CategoryMappingTest(unittest.TestCase):
    def test_manifest_loads_all_five_queries(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp) / "corpus.json", _manifest_payload())
            manifest = eval_ab.load_manifest(path)
        self.assertEqual(
            {"q-correct", "q-wrong", "q-miss", "q-unknown-ok", "q-unknown-grant"},
            set(manifest),
        )
        self.assertIsNone(manifest["q-unknown-ok"]["truth"])

    def test_classify_matrix(self):
        cases = [
            ("021", _row("q", "matched", "021", 1.0), "correct"),
            ("021", _row("q", "matched", "035", 1.0), "wrong_grant"),
            ("022", _row("q", "unknown", None, 0.99), "recapture_miss"),
            (None, _row("q", "unknown", None, None), "truth_absent_ambiguous"),
            (None, _row("q", "matched", "035", 0.01), "wrong_grant"),
        ]
        for truth, row, expected in cases:
            with self.subTest(truth=truth, row=row):
                self.assertEqual(expected, eval_ab.classify(truth, row))

    def test_confidence_never_moves_categories(self):
        # High confidence on a refusal stays a miss; tiny confidence on a
        # correct grant stays correct: confidence is not a probability input.
        self.assertEqual(
            "recapture_miss", eval_ab.classify("022", _row("q", "unknown", None, 1.0))
        )
        self.assertEqual(
            "correct", eval_ab.classify("021", _row("q", "matched", "021", 0.0))
        )


class GateStopTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.manifest = _write(self.root / "corpus.json", _manifest_payload())
        self.results = _write(self.root / "side-a.json", _results_payload())

    def tearDown(self):
        self.tmp.cleanup()

    def _manifest(self):
        return eval_ab.load_manifest(self.manifest)

    def test_end_to_end_counts(self):
        manifest = self._manifest()
        rows = eval_ab.load_results(self.results, manifest, "matcher")
        self.assertEqual(
            {"correct": 1, "wrong_grant": 2, "recapture_miss": 1,
             "truth_absent_ambiguous": 1, "total": 5},
            eval_ab.summarize(manifest, rows),
        )

    def test_missing_manifest_is_gate_stop(self):
        with self.assertRaises(eval_ab.GateStop):
            eval_ab.load_manifest(self.root / "absent.json")

    def test_empty_queries_is_gate_stop(self):
        empty = _write(self.root / "empty.json", {"schema_version": 1, "queries": []})
        with self.assertRaises(eval_ab.GateStop):
            eval_ab.load_manifest(empty)

    def test_missing_result_rows_is_gate_stop(self):
        short = _write(self.root / "short.json", _results_payload()[:4])
        with self.assertRaises(eval_ab.GateStop):
            eval_ab.load_results(short, self._manifest(), "matcher")

    def test_unknown_status_is_gate_stop(self):
        bad = _results_payload()
        bad[0] = _row("q-correct", "maybe", "021")
        bad_path = _write(self.root / "bad.json", bad)
        with self.assertRaises(eval_ab.GateStop):
            eval_ab.load_results(bad_path, self._manifest(), "matcher")

    def test_unknown_query_id_is_gate_stop(self):
        bad = _results_payload() + [_row("q-ghost", "unknown")]
        bad_path = _write(self.root / "ghost.json", bad)
        with self.assertRaises(eval_ab.GateStop):
            eval_ab.load_results(bad_path, self._manifest(), "matcher")

    def test_side_b_absent_yields_null_side_b(self):
        out = self.root / "out"
        report = eval_ab.run(
            self.manifest, self.results, None, out, emit=lambda line: None
        )
        self.assertIsNone(report["side_b"])
        self.assertIn("not a false-accept rate", " ".join(report["notes"]))
        written = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(report, written)


class SideBParityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.manifest = _write(self.root / "corpus.json", _manifest_payload())
        self.side_a = _write(self.root / "side-a.json", _results_payload())

    def tearDown(self):
        self.tmp.cleanup()

    def _side_b_rows(self):
        # Same shape as side A but a different outcome mix: the miss is
        # recovered and one correct grant turns into a wrong grant.
        return [
            _row("q-correct", "matched", "021", confidence=0.7),
            _row("q-wrong", "matched", "035", confidence=0.7),
            _row("q-miss", "matched", "022", confidence=0.7),
            _row("q-unknown-ok", "unknown", None, confidence=None),
            _row("q-unknown-grant", "unknown", None, confidence=None),
        ]

    def test_side_b_parity_output_and_counts(self):
        side_b = _write(self.root / "side-b.json", self._side_b_rows())
        out = self.root / "out"
        lines: list[str] = []
        report = eval_ab.run(
            self.manifest, self.side_a, side_b, out, emit=lines.append
        )
        self.assertEqual(
            {"correct": 2, "wrong_grant": 1, "recapture_miss": 0,
             "truth_absent_ambiguous": 2, "total": 5},
            report["side_b"],
        )
        # Side A is unaffected by the side-B input.
        self.assertEqual(1, report["side_a"]["correct"])
        self.assertEqual(2, report["side_a"]["wrong_grant"])
        written = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(report, written)

    def test_stderr_identifies_both_sides(self):
        side_b = _write(self.root / "side-b.json", self._side_b_rows())
        lines: list[str] = []
        eval_ab.run(self.manifest, self.side_a, side_b, self.root / "out",
                    emit=lines.append)
        side_a_lines = sorted(l for l in lines if l.startswith("A "))
        side_b_lines = sorted(l for l in lines if l.startswith("B "))
        self.assertEqual(5, len(side_a_lines))
        self.assertEqual(5, len(side_b_lines))
        self.assertIn("A q-correct correct", side_a_lines)
        self.assertIn("B q-correct correct", side_b_lines)
        self.assertIn("B q-miss correct", side_b_lines)
        self.assertIn("B q-unknown-grant truth_absent_ambiguous", side_b_lines)
        self.assertEqual([], [l for l in lines
                              if not (l.startswith("A ") or l.startswith("B "))])

    def test_side_b_error_is_labeled_remanence_gate_stop(self):
        bad = self._side_b_rows()
        bad[0] = _row("q-correct", "maybe", "021")
        bad_path = _write(self.root / "side-b-bad.json", bad)
        with self.assertRaises(eval_ab.GateStop) as ctx:
            eval_ab.run(self.manifest, self.side_a, bad_path, self.root / "out",
                        emit=lambda line: None)
        self.assertIn("remanence", str(ctx.exception))

    def test_side_b_short_rows_gate_stop_without_output(self):
        short = _write(self.root / "side-b-short.json", self._side_b_rows()[:4])
        with self.assertRaises(eval_ab.GateStop):
            eval_ab.run(self.manifest, self.side_a, short, self.root / "out",
                        emit=lambda line: None)
        self.assertFalse((self.root / "out" / "summary.json").exists())


if __name__ == "__main__":
    unittest.main()
