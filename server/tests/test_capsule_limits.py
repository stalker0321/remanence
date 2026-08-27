"""Protocol-v1 capsule limit assertions."""

import json
from dataclasses import asdict
from pathlib import Path

from remanence.capsules.limits import LIMITS_V1


def test_limits_match_protocol_fixture_exactly() -> None:
    fixture_path = Path(__file__).resolve().parents[2] / "protocol" / "fixtures" / "limits-v1.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    assert asdict(LIMITS_V1) == fixture
