"""V5-A4B2A record-only interchange case emitter (stdlib only).

Generates tools/a4b2/cases/case-001.json: one deterministic, fully populated
synthetic record carrying A4b pixel-stage-shaped inputs and the expected A4a
aggregation outputs. This is NOT numeric parity and must never be named PASS
for parity; see tools/a4b2/README.md.

Stdlib only (json/hashlib/math/platform): numpy/cv2/PIL/onnxruntime are not
installed here and are recorded as null in provenance. No detector modules
are imported (no nanodet/yunet constructors), nothing is downloaded, and no
copied pixel slice is claimed authoritative: brightness comes from a
closed-form synthetic 96x96 gradient, while the five input maps are
independent synthetic closed forms (integer arithmetic only, hence
bit-deterministic on every platform), explicitly NOT pixel-derived.

The expected A4a outputs transcribe legacy_features.py:17-19 (gaussian),
73 (center), 74-99 (assembly/pack) line-for-line in float64, mirroring the
frozen FieldAggregator contract pinned at commit 32153c5.
"""
import hashlib
import json
import math
import platform
import sys
from decimal import Decimal, ROUND_HALF_EVEN

N = 48
CELLS = N * N
SCHEMA = "a4b2-interchange-v1"
CANONICAL = "a4b2-canonical-v1"
HASHED_KEYS = ["baselineField", "edgeEnergy", "importance",
               "objectField", "saliency", "structureField"]


def gaussian_field(box, n=N, expand=0.25):
    x, y, w, h = box
    out = [0.0] * (n * n)
    for j in range(n):
        yy = j / n + 0.5 / n
        for i in range(n):
            xx = i / n + 0.5 / n
            ex = (xx - x - w / 2) / max(w * (0.5 + expand), 0.025)
            ey = (yy - y - h / 2) / max(h * (0.5 + expand), 0.025)
            out[j * n + i] = math.exp(-0.5 * ex * ex - 0.5 * ey * ey)
    return out


def center_field(n=N):
    out = [0.0] * (n * n)
    for j in range(n):
        yy = j / n + 0.5 / n
        for i in range(n):
            xx = i / n + 0.5 / n
            out[j * n + i] = math.exp(-((xx - 0.5) ** 2 + (yy - 0.5) ** 2) / 0.18)
    return out


def pack4(values):
    out = []
    for v in values:
        prod = v * 10000.0
        r = int(Decimal(prod).quantize(Decimal("1"), rounding=ROUND_HALF_EVEN))
        out.append(r / 10000.0)
    return out


def aggregate(faces, objects, sal, edge, contrast, tips, tip_boxes):
    center = center_field()
    base = [0.015 + 0.34 * s + 0.25 * e + 0.12 * c + 0.12 * t + 0.025 * k
            for s, e, c, t, k in zip(sal, edge, contrast, tips, center)]
    of = [0.0] * CELLS
    ff = [0.0] * CELLS
    regions = []
    for (bx, by, bw, bh) in tip_boxes:
        regions.append({"kind": "structure-tip", "box": [bx, by, bw, bh],
                        "confidence": 0.4})
    for o in objects:
        g = gaussian_field(o["box"], expand=0.06)
        of = [max(a, gi * o["confidence"]) for a, gi in zip(of, g)]
        regions.append({"kind": "object", "box": list(o["box"]),
                        "confidence": o["confidence"], "classId": o["classId"],
                        "label": o["label"]})
    for f in faces:
        g = gaussian_field(f["box"], expand=0.25)
        ff = [max(a, gi * f["confidence"]) for a, gi in zip(ff, g)]
        regions.append({"kind": "face", "box": list(f["box"]),
                        "confidence": f["confidence"]})
        x, y, fw, fh = f["box"]
        b0, b1 = max(0, x - fw * 0.7), max(0, y - fh * 0.5)
        b = [b0, b1, min(1, x + fw * 1.7) - b0, min(1, y + fh * 3.2) - b1]
        gb = gaussian_field(b, expand=0.05)
        base = [a + gi * 0.08 for a, gi in zip(base, gb)]
        regions.append({"kind": "face-context", "box": b, "confidence": 0.3})
    tip_candidates = sorted(
        [r for r in regions
         if r["kind"] == "structure-tip" and 0.08 < r["box"][0] < 0.90],
        key=lambda r: r["box"][1])
    person_focus = len(faces) > 5 or any(
        f["box"][2] * f["box"][3] > 0.005 for f in faces)
    critical_ids = {id(r) for r in tip_candidates[:2]}
    for r in regions:
        # Freeze omits the key for non-candidates; the record materializes
        # the default false explicitly (documented in README).
        r["critical"] = (id(r) in critical_ids) and (not person_focus)
    importance = [a + 0.65 * c + 1.2 * d for a, c, d in zip(base, of, ff)]
    baseline = [a + 1.2 * d for a, d in zip(base, ff)]
    return {
        "importance": pack4(importance),
        "baselineField": pack4(baseline),
        "objectField": pack4(of),
        "saliency": pack4(sal),
        "edgeEnergy": pack4(edge),
        "structureField": pack4(tips),
        "protectedRegions": regions,
    }


def synthetic_maps():
    sal, edge, contrast, tips = [], [], [], []
    for j in range(N):
        for i in range(N):
            sal.append(((i * 7 + j * 13) % 17) / 16.0)
            edge.append(((i * 5 + j * 11 + 3) % 13) / 12.0)
            contrast.append(((i * 3 + j * 7 + 5) % 11) / 10.0)
            tips.append(((i * 11 + j * 5 + 1) % 7) / 6.0)
    return sal, edge, contrast, tips


def synthetic_brightness():
    # Closed-form 96x96 gradient; BT.601 grey mean rounded to 3 decimals
    # exactly like legacy_features.py:98 (round-half-even).
    total = 0.0
    for y in range(96):
        for x in range(96):
            r = ((x * 255) // 95) / 255.0
            g = ((y * 255) // 95) / 255.0
            b = (((x + y) * 255) // 190) / 255.0
            total += 0.299 * r + 0.587 * g + 0.114 * b
    return round(total / (96 * 96), 3)


def canonical_string(arrays):
    # a4b2-canonical-v1: "key=v,v,...\n" per sorted key, %.4f values, UTF-8.
    parts = []
    for key in HASHED_KEYS:
        parts.append(key + "=" + ",".join("%.4f" % v for v in arrays[key]) + "\n")
    return "".join(parts)


def build_case():
    faces = [
        {"box": [0.10, 0.08, 0.30, 0.28], "confidence": 0.82},
        {"box": [0.62, 0.55, 0.09, 0.12], "confidence": 0.64},
    ]
    objects = [
        {"box": [0.05, 0.30, 0.40, 0.35], "confidence": 0.77,
         "classId": 0, "label": "person"},
        {"box": [0.55, 0.10, 0.25, 0.30], "confidence": 0.58,
         "classId": 2, "label": "COCO 2"},
    ]
    sal, edge, contrast, tips = synthetic_maps()
    tip_boxes = [
        [0.20, 0.60, 0.03, 0.04],
        [0.52, 0.31, 0.03, 0.04],
        [0.03, 0.15, 0.03, 0.04],
    ]
    agg = aggregate(faces, objects, sal, edge, contrast, tips, tip_boxes)
    tip_box_objs = [{"x": b[0], "y": b[1], "width": b[2], "height": b[3]}
                    for b in tip_boxes]
    case = {
        "schema": SCHEMA,
        "provenance": {
            "freezeArchiveSha256":
                "43a8d54ee3b1e681d0d15d375d0787043d4b23bb770d7902915b34891a285a16",
            "legacyFeaturesSha256":
                "ef7f5bfde86398beef358a9db195b6fd48b2b3a0b91a0a2d820357a662c84219",
            "aggregatorCommit": "32153c5eecd7365b419516e772b8264613ec2b6d",
            "python": platform.python_version(),
            "numpy": None,
            "cv2": None,
            "pil": None,
            "onnxruntime": None,
            "toolchainNote": "numpy/cv2/PIL/onnxruntime not installed; stdlib only",
            "androidOpenCv": "4.10.0",
            "cvThreads": 2,
            "generator": "tools/a4b2/emit_case.py (stdlib-only, no detector imports, no downloads)",
            "pixelNote": "brightness from synthetic 96x96 gradient "
                         "(R=(x*255)//95 G=(y*255)//95 B=((x+y)*255)//190, BT.601 grey); "
                         "the input maps are independent synthetic closed forms, "
                         "not pixel-derived; inputs.structureField echoes the merged "
                         "tips map under its A4a input name; no real photo; no copied "
                         "pixel slice claimed authoritative",
        },
        "inputs": {
            "sourceWidth": 640,
            "sourceHeight": 480,
            "faces": faces,
            "objects": objects,
            "saliency": sal,
            "edgeEnergy": edge,
            "contrast": contrast,
            # The freeze/A4a pair knows exactly one tips map (pixel-stage
            # merged output, A4a input name `structureField`). Both keys are
            # present per the harness contract and must be element-wise equal;
            # the JVM test enforces this and feeds `tips` into
            # ImportFieldPrimitives.structureField.
            "structureField": list(tips),
            "tips": tips,
            "tipBoxes": tip_box_objs,
            "syntheticPixels": {
                "kind": "closed-form-gradient-96",
                "width": 96,
                "height": 96,
                "r": "(x*255)//95",
                "g": "(y*255)//95",
                "b": "((x+y)*255)//190",
                "grey": "BT.601",
            },
        },
        "expected": {
            "gridSize": 48,
            "brightness": synthetic_brightness(),
            "importance": agg["importance"],
            "baselineField": agg["baselineField"],
            "objectField": agg["objectField"],
            "saliency": agg["saliency"],
            "edgeEnergy": agg["edgeEnergy"],
            "structureField": agg["structureField"],
            "protectedRegions": agg["protectedRegions"],
            "crop": {
                "oracle": "frozen-JS-envelope-50",
                "window": None,
                "pythonEmitsCrop": False,
                "note": "Crop is emitted only by runtime/crop-experiment.js "
                        "V5_CROP.predict; the Python oracle has no crop output. "
                        "This block is informational and never an aggregator input.",
            },
        },
        "integrity": {},
    }
    hashed = {k: case["expected"][k] for k in HASHED_KEYS}
    digest = hashlib.sha256(canonical_string(hashed).encode("utf-8")).hexdigest()
    case["integrity"] = {
        "algorithm": "SHA-256",
        "canonical": CANONICAL,
        "sha256": digest,
    }
    return case


def self_test(case):
    assert case["schema"] == SCHEMA
    for name in ("saliency", "edgeEnergy", "contrast", "structureField", "tips"):
        assert len(case["inputs"][name]) == CELLS, name
        assert all(math.isfinite(v) for v in case["inputs"][name]), name
    for name in HASHED_KEYS:
        assert len(case["expected"][name]) == CELLS, name
        assert all(math.isfinite(v) for v in case["expected"][name]), name
    assert len(case["inputs"]["tipBoxes"]) == 3
    assert len(case["expected"]["protectedRegions"]) == 3 + 2 + 2 * 2
    assert case["expected"]["gridSize"] == 48
    assert case["expected"]["crop"]["window"] is None
    assert case["expected"]["crop"]["pythonEmitsCrop"] is False
    # Hash round-trips through the written bytes.
    text = json.dumps(case, sort_keys=True, indent=2) + "\n"
    reparsed = json.loads(text)
    hashed = {k: reparsed["expected"][k] for k in HASHED_KEYS}
    digest = hashlib.sha256(canonical_string(hashed).encode("utf-8")).hexdigest()
    assert digest == case["integrity"]["sha256"], "hash must survive serialization"
    # Determinism: a second build is byte-identical.
    assert json.dumps(build_case(), sort_keys=True, indent=2) == text.rstrip("\n")
    return text


def main(path):
    case = build_case()
    text = self_test(case)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote %s sha256=%s" % (path, case["integrity"]["sha256"]))
    print("regions=%d brightness=%s" % (
        len(case["expected"]["protectedRegions"]), case["expected"]["brightness"]))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "tools/a4b2/cases/case-001.json")
