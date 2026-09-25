"""Revision write ops: explicit target UID, bounded payloads, swap, count.

Fake ``urlopen`` only (no live Meili, no DB): exact request paths/bodies,
strict UID validation, success/failure/timeout mapping, and no provider
ID or secret leakage into requests. Read-path search behavior is covered
by the existing adapter suite and is untouched here.
"""

from __future__ import annotations

import io
import json
import urllib.error
import urllib.request

import pytest

from remanence.music.ports import MusicSearchError, MusicSearchUnavailableError
from remanence.music.search.document import build_search_document
from remanence.music.search.meilisearch import (
    MEILI_MAX_DOCUMENTS_PER_PUSH,
    MeilisearchConfig,
    MeilisearchMusicSearch,
    validate_index_uid,
)
from remanence.music.domain import MusicTrack


def _doc(index: int = 0, **overrides) -> dict:
    track = MusicTrack.create(
        title=f"Track {index}",
        artists=(f"Artist {index}",),
        track_id=None,
        year=2000 + (index % 25),
        duration_ms=200000 + index,
        version=None,
    )
    document = build_search_document(track)
    document.update(overrides)
    return document


class _FakeResponse:
    def __init__(self, payload: bytes) -> None:
        self._stream = io.BytesIO(payload)

    def read(self, size: int = -1) -> bytes:
        return self._stream.read(size)

    def __enter__(self) -> _FakeResponse:
        return self

    def __exit__(self, *args: object) -> None:
        return None


class _FakeTransport:
    """Records requests; serves canned JSON per endpoint."""

    def __init__(
        self,
        task_status: str = "succeeded",
        number_of_documents: int = 5,
        fail_with: Exception | None = None,
        existing: set[str] | None = None,
    ) -> None:
        self.requests: list[urllib.request.Request] = []
        self.task_status = task_status
        self.number_of_documents = number_of_documents
        self.fail_with = fail_with
        self.existing = existing if existing is not None else set()

    def __call__(self, request, timeout=None):
        self.requests.append(request)
        if self.fail_with is not None:
            raise self.fail_with
        url = request.full_url
        if url.endswith("/swap-indexes"):
            return _FakeResponse(json.dumps({"taskUid": 9}).encode())
        if url.endswith("/settings") or url.endswith("/documents"):
            return _FakeResponse(json.dumps({"taskUid": 7}).encode())
        if request.get_method() == "POST" and url.rstrip("/").endswith("/indexes"):
            return _FakeResponse(json.dumps({"taskUid": 7}).encode())
        if url.endswith("/stats"):
            return _FakeResponse(
                json.dumps({"numberOfDocuments": self.number_of_documents}).encode()
            )
        if "/tasks/" in url:
            return _FakeResponse(json.dumps({"status": self.task_status}).encode())
        if request.get_method() == "GET" and "/indexes/" in url:
            uid = url.rsplit("/indexes/", 1)[1]
            if uid in self.existing:
                return _FakeResponse(b"{}")
            raise urllib.error.HTTPError(url, 404, "missing", {}, io.BytesIO(b"{}"))
        return _FakeResponse(b"{}")

    def bodies(self) -> list[object]:
        return [json.loads(req.data.decode()) for req in self.requests if req.data]


def _search(uid: str = "remanence_tracks_v1") -> MeilisearchMusicSearch:
    return MeilisearchMusicSearch(
        MeilisearchConfig(base_url="http://127.0.0.1:17770", index_uid=uid)
    )


def _install(monkeypatch, transport: _FakeTransport) -> _FakeTransport:
    monkeypatch.setattr(urllib.request, "urlopen", transport)
    return transport


def test_strict_uid_accepts_shapes_and_rejects_injection(monkeypatch) -> None:
    assert validate_index_uid("remanence_tracks_v1") == "remanence_tracks_v1"
    assert validate_index_uid("remanence_tracks_v1_rev0007") == "remanence_tracks_v1_rev0007"
    for bad in (
        "",
        "   ",
        "a/b",
        "a b",
        "../x",
        "a%20b",
        "-lead",
        "_lead",
        "x" * 65,
        None,
        123,
        b"bytes",
    ):
        with pytest.raises(MusicSearchError):
            validate_index_uid(bad)


def test_settings_and_documents_hit_exact_target_uid(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport())
    client = _search()
    assert client.update_settings_for("rev_a", {"a": 1}) == 7
    assert client.put_documents_to("rev_a", [_doc()]) == 7
    paths = [req.full_url for req in transport.requests]
    assert paths[0].endswith("/indexes/rev_a/settings")
    assert transport.requests[0].get_method() == "PATCH"
    assert paths[1].endswith("/indexes/rev_a/documents")
    assert transport.requests[1].get_method() == "POST"


def test_bounded_payload_rejects_empty_and_oversize(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport())
    client = _search()
    with pytest.raises(MusicSearchError):
        client.put_documents_to("rev_a", [])
    with pytest.raises(MusicSearchError):
        client.put_documents_to("rev_a", [_doc(i) for i in range(MEILI_MAX_DOCUMENTS_PER_PUSH + 1)])
    assert client.put_documents_to("rev_a", [_doc(i) for i in range(MEILI_MAX_DOCUMENTS_PER_PUSH)]) == 7


def test_legacy_helpers_delegate_to_configured_uid(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport())
    client = _search(uid="remanence_tracks_v1")
    assert client.update_settings({"a": 1}) == 7
    assert client.put_documents([_doc()]) == 7
    client.delete_index()
    assert [req.full_url for req in transport.requests] == [
        "http://127.0.0.1:17770/indexes/remanence_tracks_v1/settings",
        "http://127.0.0.1:17770/indexes/remanence_tracks_v1/documents",
        "http://127.0.0.1:17770/indexes/remanence_tracks_v1",
    ]


def test_count_reads_stats_with_validation(monkeypatch) -> None:
    _install(monkeypatch, _FakeTransport(number_of_documents=111328))
    assert _search().index_document_count("rev_a") == 111328
    with pytest.raises(MusicSearchError):
        _search().index_document_count("rev a")


def test_count_rejects_malformed_stats(monkeypatch) -> None:
    class _Bad(_FakeTransport):
        def __call__(self, request, timeout=None):
            self.requests.append(request)
            return _FakeResponse(b'{"numberOfDocuments": -1}')

    _install(monkeypatch, _Bad())
    with pytest.raises(MusicSearchError):
        _search().index_document_count("rev_a")


def test_swap_posts_exact_pair_and_validates(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport())
    assert _search().swap_indexes("rev_new", "remanence_tracks_v1") == 9
    (request,) = transport.requests
    assert request.full_url == "http://127.0.0.1:17770/swap-indexes"
    assert request.get_method() == "POST"
    assert json.loads(request.data.decode()) == [{"indexes": ["rev_new", "remanence_tracks_v1"]}]
    with pytest.raises(MusicSearchError):
        _search().swap_indexes("same", "same")
    with pytest.raises(MusicSearchError):
        _search().swap_indexes("ok_uid", "no way")


def test_failed_task_uid_and_status_map_to_errors(monkeypatch) -> None:
    class _NoUid(_FakeTransport):
        def __call__(self, request, timeout=None):
            self.requests.append(request)
            return _FakeResponse(b'{"nope": true}')

    _install(monkeypatch, _NoUid())
    with pytest.raises(MusicSearchError):
        _search().put_documents_to("rev_a", [_doc()])
    _install(monkeypatch, _FakeTransport(task_status="failed"))
    with pytest.raises(MusicSearchError):
        _search().wait_for_task(7, timeout_s=5.0)


def test_transport_timeout_maps_to_unavailable(monkeypatch) -> None:
    _install(monkeypatch, _FakeTransport(fail_with=TimeoutError("t")))
    with pytest.raises(MusicSearchUnavailableError):
        _search().put_documents_to("rev_a", [_doc()])
    with pytest.raises(MusicSearchUnavailableError):
        _search().wait_for_task(7, timeout_s=5.0)


def test_wait_deadline_exceeded_is_not_retryable_success(monkeypatch) -> None:
    _install(monkeypatch, _FakeTransport(task_status="enqueued"))
    with pytest.raises(MusicSearchError):
        _search().wait_for_task(7, timeout_s=0.0)
    _install(monkeypatch, _FakeTransport(task_status="succeeded"))
    _search().wait_for_task(7, timeout_s=5.0)


def test_requests_carry_exact_documents_and_no_secrets(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport())
    docs = [_doc(0)]
    _search().put_documents_to("rev_a", docs)
    (request,) = transport.requests
    assert json.loads(request.data.decode()) == docs
    assert request.get_header("Authorization") is None
    keyed = MeilisearchMusicSearch(
        MeilisearchConfig(base_url="http://127.0.0.1:17770", api_key="k")
    )
    transport.requests.clear()
    keyed.put_documents_to("rev_a", docs)
    assert transport.requests[0].get_header("Authorization") == "Bearer k"


def test_foreign_id_and_extra_fields_rejected_before_http(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport())
    client = _search()
    foreign = _doc()
    foreign["id"] = " tid-with-dashes-not-uuid "
    with pytest.raises(MusicSearchError):
        client.put_documents_to("rev_a", [foreign])
    leaked = _doc()
    leaked["mbid"] = "0b2f6aa0-4c3e-4e5a-9b8a-123456789012"
    with pytest.raises(MusicSearchError):
        client.put_documents_to("rev_a", [leaked])
    with pytest.raises(MusicSearchError):
        client.put_documents_to("rev_a", ["not-a-dict"])  # type: ignore[list-item]
    with pytest.raises(MusicSearchError):
        client.put_documents_to("rev_a", [None])  # type: ignore[list-item]
    assert transport.requests == []


def test_configured_uid_validated_at_construction() -> None:
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="http://127.0.0.1:17770", index_uid="a/b")
    with pytest.raises(ValueError):
        MeilisearchConfig(base_url="http://127.0.0.1:17770", index_uid="")
    config = MeilisearchConfig(
        base_url="http://127.0.0.1:17770", index_uid="remanence_tracks_v1_rev0007"
    )
    assert config.index_uid == "remanence_tracks_v1_rev0007"


def test_http_4xx_is_error_and_5xx_is_unavailable(monkeypatch) -> None:
    def http_error(code: int):
        return urllib.error.HTTPError(
            "http://127.0.0.1:17770/x", code, "e", {}, io.BytesIO(b"{}")
        )

    class _Status(_FakeTransport):
        def __init__(self, code: int) -> None:
            super().__init__()
            self.code = code

        def __call__(self, request, timeout=None):
            self.requests.append(request)
            raise http_error(self.code)

    _install(monkeypatch, _Status(400))
    with pytest.raises(MusicSearchError):
        _search().put_documents_to("rev_a", [_doc()])
    _install(monkeypatch, _Status(404))
    with pytest.raises(MusicSearchError):
        _search().index_document_count("rev_a")
    _install(monkeypatch, _Status(500))
    with pytest.raises(MusicSearchUnavailableError):
        _search().put_documents_to("rev_a", [_doc()])
    _install(monkeypatch, _Status(503))
    with pytest.raises(MusicSearchUnavailableError):
        _search().index_document_count("rev_a")


def test_canceled_task_is_terminal_failure(monkeypatch) -> None:
    _install(monkeypatch, _FakeTransport(task_status="canceled"))
    with pytest.raises(MusicSearchError):
        _search().wait_for_task(7, timeout_s=5.0)


def test_uuid_object_id_rejected_before_http(monkeypatch) -> None:
    import uuid as uuid_module

    transport = _install(monkeypatch, _FakeTransport())
    doc = _doc()
    doc["id"] = uuid_module.uuid4()
    with pytest.raises(MusicSearchError):
        _search().put_documents_to("rev_a", [doc])
    assert transport.requests == []


def test_wait_task_splits_http_4xx_and_5xx(monkeypatch) -> None:
    def http_error(code: int):
        import io as io_module

        return urllib.error.HTTPError(
            "http://127.0.0.1:17770/tasks/7", code, "e", {}, io_module.BytesIO(b"{}")
        )

    class _Status(_FakeTransport):
        def __init__(self, code: int) -> None:
            super().__init__()
            self.code = code

        def __call__(self, request, timeout=None):
            self.requests.append(request)
            raise http_error(self.code)

    _install(monkeypatch, _Status(404))
    with pytest.raises(MusicSearchError):
        _search().wait_for_task(7, timeout_s=5.0)
    _install(monkeypatch, _Status(503))
    with pytest.raises(MusicSearchUnavailableError):
        _search().wait_for_task(7, timeout_s=5.0)


def test_index_exists_probes_without_writes(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport(existing={"rev_a"}))
    assert _search().index_exists("rev_a") is True
    (request,) = transport.requests
    assert request.full_url == "http://127.0.0.1:17770/indexes/rev_a"
    assert request.get_method() == "GET"
    with pytest.raises(MusicSearchError):
        _search().index_exists("rev a")


def test_index_exists_false_on_404(monkeypatch) -> None:
    import io as io_module

    class _Missing(_FakeTransport):
        def __call__(self, request, timeout=None):
            self.requests.append(request)
            raise urllib.error.HTTPError(
                request.full_url, 404, "missing", {}, io_module.BytesIO(b"{}")
            )

    _install(monkeypatch, _Missing())
    assert _search().index_exists("rev_absent") is False


def test_create_index_posts_uid_and_primary_key(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport())
    assert _search().create_index_for("rev_new") == 7
    exists_req, create_req = transport.requests
    assert exists_req.full_url == "http://127.0.0.1:17770/indexes/rev_new"
    assert exists_req.get_method() == "GET"
    assert create_req.full_url == "http://127.0.0.1:17770/indexes"
    assert create_req.get_method() == "POST"
    assert json.loads(create_req.data.decode()) == {"uid": "rev_new", "primaryKey": "id"}
    with pytest.raises(MusicSearchError):
        _search().create_index_for("rev_new", primary_key="  ")
    with pytest.raises(MusicSearchError):
        _search().create_index_for("rev bad")


def test_create_index_refuses_existing_without_post(monkeypatch) -> None:
    transport = _install(monkeypatch, _FakeTransport(existing={"rev_a"}))
    with pytest.raises(MusicSearchError):
        _search().create_index_for("rev_a")
    assert [req.get_method() for req in transport.requests] == ["GET"]
