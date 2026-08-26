"""Parity tests for canonical handle normalization against the shared fixture."""

import inspect
import json
from pathlib import Path

import pytest

from remanence.users.handles import INVALID, normalize_handle

FIXTURE_PATH = Path(__file__).resolve().parents[2] / "protocol" / "fixtures" / "handles-v1.json"


def _load_fixture() -> tuple[list[dict[str, str]], list[str]]:
    root = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    assert set(root.keys()) == {"schema_version", "valid", "invalid"}
    schema_version = root["schema_version"]
    assert isinstance(schema_version, int) and not isinstance(schema_version, bool)
    assert schema_version == 1
    valid = root["valid"]
    assert isinstance(valid, list) and len(valid) > 0
    for entry in valid:
        assert isinstance(entry, dict)
        assert set(entry.keys()) == {"input", "normalized"}
        assert isinstance(entry["input"], str)
        assert isinstance(entry["normalized"], str)
    invalid = root["invalid"]
    assert isinstance(invalid, list) and len(invalid) > 0
    for entry in invalid:
        assert isinstance(entry, str)
    return valid, invalid


def test_fixture_schema_and_paths_exist() -> None:
    valid, invalid = _load_fixture()
    assert len(valid) >= 10
    assert len(invalid) >= 10


def test_every_valid_entry_normalizes_exactly() -> None:
    valid, _ = _load_fixture()
    for entry in valid:
        assert normalize_handle(entry["input"]) == entry["normalized"], entry


def test_every_invalid_entry_rejects_with_generic_message() -> None:
    _, invalid = _load_fixture()
    for raw in invalid:
        with pytest.raises(ValueError) as excinfo:
            normalize_handle(raw)
        assert str(excinfo.value) == INVALID
        assert str(excinfo.value) == "invalid handle"
        assert len(str(excinfo.value)) == len(INVALID)


def test_ascii_fold_is_ascii_only_and_prefix_exactly_once() -> None:
    assert normalize_handle("BOB") == "bob"
    assert normalize_handle("AbC_x.9") == "abc_x.9"
    assert normalize_handle("@BOB") == "bob"
    assert normalize_handle("@_A.B0") == "_a.b0"


def test_non_ascii_is_never_unicode_folded() -> None:
    for raw in ("ａｂｃ", "ＡＢＣ", "\u212abc", "İST", "ÉTUDES", "café", "abcя"):
        with pytest.raises(ValueError):
            normalize_handle(raw)


def test_source_avoids_unicode_folding_apis() -> None:
    source = inspect.getsource(normalize_handle)
    for banned in ("casefold", ".lower", ".upper", "unicodedata", "NFKC", "str.translate"):
        assert banned not in source, banned


def test_none_input_raises_naturally_without_value_leak() -> None:
    with pytest.raises((TypeError, AttributeError)) as excinfo:
        normalize_handle(None)  # type: ignore[arg-type]
    assert not isinstance(excinfo.value, ValueError)
