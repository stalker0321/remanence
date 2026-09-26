# V5-A4B2A record-only interchange harness

Record-only. This directory defines a versioned JSON interchange record and
the offline tooling that emits and checks it. **This is NOT numeric parity
and must never be named PASS for parity.** It proves that a complete,
fully populated synthetic record can be parsed deterministically on the JVM
and fed through the frozen `FieldAggregator` to the exact expected outputs —
nothing about Python/cv2/device numeric agreement, real photos, detectors, or
crops.

## Files

- `emit_case.py` — stdlib-only generator (`json`/`hashlib`/`math`/`platform`
  only). Regenerate with `python3 tools/a4b2/emit_case.py
  tools/a4b2/cases/case-001.json` from the repo root; it self-tests array
  lengths, hash round-trip through the written bytes, and byte determinism
  before exiting. No detector modules are imported (no nanodet/yunet
  constructors), nothing is downloaded.
- `cases/case-001.json` — the one deterministic synthetic case (text only;
  no real-photo or binary asset).
- The JVM side is `A4b2InterchangeTest` (app unit tests), which parses this
  record with the already-present `org.json` (no new dependency) using
  test-local canonical serialization.

## Schema `a4b2-interchange-v1` (no silent renames)

Top level: exactly `schema`, `provenance`, `inputs`, `expected`,
`integrity`. Any unknown key, or any `schema` value other than
`a4b2-interchange-v1`, is rejected by the JVM test — fields are never
renamed or absorbed silently.

- `provenance`: freeze archive SHA-256, oracle `legacy_features.py` SHA-256
  (both confirmed live by `sha256sum` on 2026-09-26), the frozen
  `FieldAggregator` commit, the confirmed Python version, explicit `null`
  for numpy/cv2/PIL/onnxruntime (not installed; stdlib only), Android
  OpenCV `4.10.0` with `setNumThreads(2)`, the generator identity, and a
  pixel note.
- `inputs`: `sourceWidth`/`sourceHeight`; `faces` (`box`, `confidence`);
  `objects` (`box`, `confidence`, `classId`, `label`); `saliency`,
  `edgeEnergy`, `contrast`, `structureField`, `tips` — each exactly 2304
  finite row-major (`y * 48 + x`) values; `tipBoxes` (`x`, `y`, `width`,
  `height`); `syntheticPixels` (the closed-form gradient definition used for
  `brightness`, so the JVM test can recompute it independently). The
  freeze/A4a pair knows exactly one tips map, so `inputs.structureField`
  echoes the merged `tips` map under its A4a input name; the two arrays must
  be element-wise equal, the JVM test enforces this, and `tips` feeds
  `ImportFieldPrimitives.structureField`.
- `expected`: `gridSize` (48), `brightness` (BT.601 grey mean of the
  synthetic gradient, half-even rounded to 3 decimals, exactly like
  `legacy_features.py:98`), all six packed A4a arrays
  (`importance`, `baselineField`, `objectField`, `saliency`, `edgeEnergy`,
  `structureField`, each exactly 2304), `protectedRegions` (`kind`, `box`,
  `confidence`, `critical`, plus `classId`/`label` for objects), and `crop`.
  The freeze omits the `critical` key for non-candidate tips; this record
  materializes the default `false` explicitly.
- `crop` is explicitly separate and optional: `{"oracle":
  "frozen-JS-envelope-50", "window": null, "pythonEmitsCrop": false, ...}`.
  Crop is emitted only by `runtime/crop-experiment.js` `V5_CROP.predict`;
  the Python oracle has no crop output, and this block is never an
  aggregator input.
- `integrity`: `{"algorithm": "SHA-256", "canonical": "a4b2-canonical-v1",
  "sha256": ...}` — a real hash over complete data, not a placeholder.
  `a4b2-canonical-v1` is the UTF-8 string formed by concatenating, for the
  sorted keys `baselineField`, `edgeEnergy`, `importance`, `objectField`,
  `saliency`, `structureField`, the line `key=` + comma-joined `%.4f`
  values + `\n`. (`%.4f` is used because it renders identically in Python
  and JVM `String.format`; shortest-round-trip notations disagree on small
  magnitudes.)

## Synthetic provenance (no licensing issue)

All numbers derive from closed-form integer arithmetic (modular formulas
over cell coordinates) plus the frozen float64 aggregation transcription —
no real photo, no copied pixel slice claimed authoritative, no third-party
data. Arrays are fully populated: no empty arrays anywhere in the record.

## Explicitly OPEN (not verified by this harness)

- Any numeric tolerance claim between Python, cv2, and device outputs.
- `OpenCvFieldStage` JVM execution (it needs natives; only the androidTest
  probe drives it).
- Real-photo, detector (NanoDet/YuNet), and crop parity.
- Physical arm64-v8a plus second-ABI execution.
- `AndroidCollageCropSource`, Create wiring, and any production capability.
