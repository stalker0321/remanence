# Slice 0: frozen postcard-matcher probe + offline A/B eval seam

Status: probe scaffolding only. No Android wiring, no ORB/SIFT ADR change,
no threshold change, no corpus data in git.

## 1. Frozen snapshot (`vendor/`)

Byte-identical copies of six files from `/home/vodkolyan/postcard-matcher`,
which is **not a git repository**, so hash + mtime is the identity:

| File | SHA-256 | Source mtime (UTC) | Bytes |
| --- | --- | --- | --- |
| `vendor/engine.py` | `906349b8…1db8` | 2026-09-07 02:39:36 | 9546 |
| `vendor/vision.py` | `ca0650be…cfbb` | 2026-09-07 02:33:08 | 7288 |
| `vendor/requirements.txt` | `dd903708…4a22d0` | 2026-09-07 00:57:27 | 147 |
| `vendor/requirements.lock.txt` | `5ae9d12d…bdd9a` | 2026-09-07 00:54:34 | 383 |
| `vendor/README.md` | `9e967db5…21a4645` | 2026-09-07 11:39:48 | 8187 |
| `vendor/MATCHER_ARCHITECTURE.md` | `e946e77c…0eaace` | 2026-09-07 11:53:20 | 7422 |

Verify at any time from the worktree root (read-only, stdlib OS tooling only):

```sh
sha256sum -c tools/probes/postcard-matcher/SHA256SUMS
```

### Included / excluded

Included: the matcher runtime needed for offline evaluation
(`engine.py` → `Matcher`, `vision.py` → crop/features/verify), the pinned
dependency manifests, and the two upstream docs that state the pipeline,
thresholds, and honest evaluation limits.

Deliberately excluded: `api.py`/`run_server.sh` (serving layer; the seam
calls `Matcher` directly), `.venv/` (415 MiB, non-hermetic), reference card
images and `artifacts/index/` (identity-bearing data stays outside git),
reports/logs/CSVs, `reverse_evaluation/` harnesses, `experiments/` retired
history, secrets, caches. Upstream `prepare.py`/`evaluate.py`/`verify_*.py`
do not exist in the source directory and are therefore not freezable here;
the seam below replaces them with a smaller deterministic classifier.

### License and oracle scope (binding for this probe)

- The vendored `requirements.txt` / `requirements.lock.txt` are an
  **evaluation oracle only**: they pin the environment in which the frozen
  matcher's own numbers were produced. They are **never installed into,
  shipped in, or otherwise coupled to Remanence or the APK**.
- The lockfile's `opencv-python-headless==5.0.0.93` is a desktop-Python
  wheel. It is **not** the Android `org.opencv:opencv:4.10.0` dependency:
  different ABI, different delivery vehicle, different license review
  surface. Nothing in this probe asserts equivalence between the two.
- Any later native-SIFT product decision (dependency choice, ABI
  selection, license clearance, size budget) must be made on its own
  evidence in its own slice. Slice 0 decides none of it and changes no ADR.

### Later product posture (recorded, not decided here)

If a later slice promotes matcher ideas into product, the recorded
expectation is a **clean breaking reset**: no dual ORB/SIFT runtime, no
fingerprint/index migration, disposable rollback. That posture is a future
product decision for its own review — Slice 0 stays oracle-only and
implements none of it.

## 2. Offline A/B seam (`eval_ab.py`, stdlib only)

Compares frozen-matcher result rows against ground truth derived from the
current Remanence canonical FRONT corpus layout
(`cards/<id>/{manifest.json,capture_status.json,images/T*_front.jpg}`;
cf. `android/app/src/test/…/diagnostics/CaptureDatasetHarnessTest.kt`)
**without wiring anything into Android and without importing the matcher**
(no cv2/numpy/scipy dependency at eval time). The frozen side's result rows
are produced separately with its own environment; see §3.

Pre-registered outcome categories per query (decided before any run):

| Category | Meaning |
| --- | --- |
| `correct` | truth present, `matched`, `card_id` equals truth |
| `wrong_grant` | `matched` with `card_id` != truth, or any `matched` on a truth-absent query |
| `recapture_miss` | truth present, `unknown` (conservative refusal → recapture) |
| `truth_absent_ambiguous` | truth absent, `unknown` (correct refusal; small-sample, see below) |

Hard honesty rules, enforced by the schema and the report text: heuristic
`confidence` is passed through but **never interpreted and never called a
probability**; a `wrong_grant` count of 0 is reported as a count, **never as
FAR**. The gate **stops (exit 2) instead of inventing labels** when the corpus
manifest is missing/invalid/empty, when a manifest query has no result row,
or when a result row carries an unknown status/query id.

## 3. Exact command, inputs, outputs

```sh
# Side-A rows with the frozen matcher's own env (outside git; example):
POSTCARD_INDEX=<index-dir> /home/vodkolyan/postcard-matcher/.venv/bin/python \
  /home/vodkolyan/postcard-matcher/engine.py match <query.jpg> > /tmp/pm-row.json
# Then classify (repo Python, stdlib only, no network):
python3 tools/probes/postcard-matcher/eval_ab.py \
  --corpus-manifest <OUT-OF-TREE>/corpus.json \
  --matcher-results <OUT-OF-TREE>/side-a.json \
  [--remanence-results <OUT-OF-TREE>/side-b.json] \
  [--out-dir <OUT-OF-TREE>]
```

Dependencies: repository Python 3 stdlib only (`argparse`, `json`,
`pathlib`). No third-party installs, no network, no Android/Gradle.

Expected inputs:

- `--corpus-manifest` (required): `{"schema_version":1,"queries":[{"query_id":str,
  "truth_card_id":str|null, "expect":"match"|"reject"}]}`. `truth_card_id:null`
  with `expect:"reject"` marks truth-absent probes. Source images stay outside
  git; only ids flow through this file.
- `--matcher-results` (required): `[{"query_id":str,"status":"matched"|"unknown",
  "card_id":str|null,"reason":str,"confidence":number|null}]`.
- `--remanence-results` (optional): same row schema for side B (current
  pipeline batch-report equivalent; absent ⇒ honest side-A-only report).

Output schema (`summary.json` in out-dir; default out-dir is
`<worktree-parent>/Remanence-postcard-matcher-eval-out`, always outside the
repo; `outputs/` is additionally gitignored):

```json
{
  "side_a": {"correct": 0, "wrong_grant": 0, "recapture_miss": 0,
             "truth_absent_ambiguous": 0, "total": 0},
  "side_b": null,
  "notes": ["confidence is heuristic strength, not a probability",
            "wrong_grant count is not a false-accept rate"]
}
```

Per-query rows are echoed to stderr as `A <query_id> <category>` for the
frozen-matcher side and `B <query_id> <category>` for the current-pipeline
side (B lines appear only with `--remanence-results`): counts only, no
paths/keys. The `summary.json` report carries the same split as `side_a` /
`side_b` (`side_b` is `null` when side B is absent), so neither side can be
mistaken for the other. Exit codes: `0` complete; `1` infrastructure failure
(permission-denied input, unwritable out-dir); `2` gate stop (missing manifest
or results file, invalid/empty manifest, missing result rows, unknown status
or query id).

## 4. Decision gates (Slice 0 stops here)

- Gate G0 (this slice): snapshot frozen + manifest verifies + seam unit tests
  pass. No corpus needed.
- Gate G1 (later slice): a locked, consented FRONT corpus with ground-truth
  manifest exists out-of-tree. Until then every eval invocation must exit 2.
- Gate G2 (later slice): side-B row emitter for the current pipeline exists.
  Until then reports are side-A-only and say so.
- Non-goals recorded: no threshold tuning, no ADR change, no Android code,
  no dataset commits, no probability calibration, no FAR claim.
