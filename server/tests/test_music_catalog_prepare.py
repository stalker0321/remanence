import hashlib
import io
from pathlib import Path
from types import SimpleNamespace

import pytest
from remanence.music import catalog_prepare as prepare


class Response(io.BytesIO):
    def __init__(self, content, status=200, headers=None):
        super().__init__(content); self.status = status; self.headers = headers or {}


def snapshot(content=b"synthetic archive"):
    return prepare.Snapshot("20261007-002147", len(content), hashlib.sha256(content).hexdigest())


def test_plan_is_read_only_and_counts_all_explicit_budgets(tmp_path, monkeypatch):
    monkeypatch.setattr(prepare.shutil, "disk_usage", lambda _: SimpleNamespace(free=150 * prepare.GIB))
    target = tmp_path / "not-created"
    report = prepare.preflight(snapshot(), target, staging_gib=60, index_gib=20, rollback_gib=30)
    assert report["disk_admitted"] and not report["catalog_build_implemented"]
    assert report["required_bytes"] == snapshot().archive_bytes + 120 * prepare.GIB
    assert not target.exists()


def test_low_disk_and_download_budget_are_not_ignored(tmp_path, monkeypatch):
    monkeypatch.setattr(prepare.shutil, "disk_usage", lambda _: SimpleNamespace(free=1))
    assert not prepare.preflight(snapshot(), tmp_path, staging_gib=1, index_gib=1, rollback_gib=1)["disk_admitted"]
    with pytest.raises(prepare.CatalogPreparationError):
        prepare.preflight(prepare.Snapshot("20261007-002147", 13 * prepare.GIB, "a" * 64), tmp_path, staging_gib=1, index_gib=1, rollback_gib=1)


def test_verified_download_is_reused_without_second_request(tmp_path, monkeypatch):
    data = b"synthetic archive"; calls = []
    monkeypatch.setattr(prepare, "open_official", lambda request: calls.append(request.full_url) or Response(data))
    destination = prepare.fetch(snapshot(data), tmp_path, 100)
    assert destination.read_bytes() == data
    assert prepare.fetch(snapshot(data), tmp_path, 100) == destination
    assert len(calls) == 1
    assert not (tmp_path / ".catalog-prepare.lock").exists()


def test_exact_resume_preserves_partial_and_validates_whole_archive(tmp_path, monkeypatch):
    data = b"synthetic archive"; snap = snapshot(data)
    directory = tmp_path / snap.revision; directory.mkdir()
    partial = directory / (prepare.ARCHIVE + ".part"); partial.write_bytes(data[:5])
    def response(request):
        assert request.get_header("Range") == "bytes=5-"
        return Response(data[5:], 206, {"Content-Range": f"bytes 5-{len(data)-1}/{len(data)}"})
    monkeypatch.setattr(prepare, "open_official", response)
    assert prepare.fetch(snap, tmp_path, 100).read_bytes() == data


@pytest.mark.parametrize("kind", ["range", "checksum", "oversize"])
def test_invalid_download_never_becomes_final_or_erases_previous_file(tmp_path, monkeypatch, kind):
    data = b"synthetic archive"; snap = snapshot(data)
    if kind == "range":
        directory = tmp_path / snap.revision; directory.mkdir()
        (directory / (prepare.ARCHIVE + ".part")).write_bytes(data[:5])
    content = data + b"extra" if kind == "oversize" else b"wrong" if kind == "checksum" else data
    monkeypatch.setattr(prepare, "open_official", lambda _: Response(content))
    with pytest.raises(prepare.CatalogPreparationError): prepare.fetch(snap, tmp_path, 100)
    assert not (tmp_path / snap.revision / prepare.ARCHIVE).exists()


def test_metadata_discovery_pins_core_not_editor_or_derived(monkeypatch):
    snap = snapshot()
    monkeypatch.setattr(prepare, "small_text", lambda url, limit: snap.revision if url.endswith("LATEST") else f"{'b'*64}  mbdump-edit.tar.bz2\n{snap.sha256}  mbdump.tar.bz2\n")
    monkeypatch.setattr(prepare, "open_official", lambda _: Response(b"", headers={"Content-Length": str(snap.archive_bytes)}))
    assert prepare.discover() == snap
    with pytest.raises(prepare.CatalogPreparationError): prepare.discover("../../other")


def test_parallel_preparation_is_refused(tmp_path):
    with prepare.job_lock(tmp_path):
        with pytest.raises(prepare.CatalogPreparationError):
            with prepare.job_lock(tmp_path): pass


def test_zero_byte_interrupted_download_can_resume(tmp_path, monkeypatch):
    data = b"synthetic archive"; snap = snapshot(data)
    directory = tmp_path / snap.revision; directory.mkdir()
    (directory / (prepare.ARCHIVE + ".part")).touch()
    monkeypatch.setattr(prepare, "open_official", lambda _: Response(data))
    assert prepare.fetch(snap, tmp_path, 100).read_bytes() == data
