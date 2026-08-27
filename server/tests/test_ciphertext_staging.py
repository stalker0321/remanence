import asyncio
import hashlib
import os
import stat
from collections.abc import AsyncIterable, AsyncIterator, Coroutine
from pathlib import Path
from typing import Any, TypeVar

import pytest

import remanence.storage.staging as staging_module
from remanence.storage import (
    CiphertextStager,
    InvalidStagingExpectationError,
    StagedBlob,
    StagingHashMismatchError,
    StagingIOError,
    StagingSizeExceededError,
    StagingSizeTruncatedError,
)

_T = TypeVar("_T")


def _run(coroutine: Coroutine[Any, Any, _T]) -> _T:
    return asyncio.run(coroutine)


async def _chunks(*chunks: bytes) -> AsyncIterator[bytes]:
    for chunk in chunks:
        yield chunk


def _digest(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _temp_files(root: Path) -> list[Path]:
    return list(root.glob(".remanence-staging-*") if root.exists() else [])


def _stage(
    root: Path,
    chunks: AsyncIterable[bytes],
    payload: bytes,
    *,
    max_bytes: int | None = None,
) -> StagedBlob:
    return _run(
        CiphertextStager(root).stage(
            chunks,
            expected_size=len(payload),
            expected_sha256=_digest(payload),
            max_bytes=len(payload) if max_bytes is None else max_bytes,
        )
    )


def test_exact_chunked_success_reader_permissions_and_cleanup(tmp_path: Path) -> None:
    root = tmp_path / "staging"
    payload = b"ciphertext-" + bytes(range(32))

    staged = _stage(root, _chunks(payload[:7], b"", payload[7:]), payload)

    assert staged.size == len(payload)
    assert staged.sha256_hex == _digest(payload)
    assert repr(staged) == "StagedBlob(<opaque>)"
    assert stat.S_IMODE(staged._path.stat().st_mode) == 0o600  # noqa: SLF001
    assert stat.S_IMODE(root.stat().st_mode) == 0o700
    with staged.open_reader() as reader:
        assert reader.read() == payload
    assert reader.closed
    staged.cleanup()
    staged.cleanup()
    assert not staged._path.exists()  # noqa: SLF001
    assert _temp_files(root) == []


@pytest.mark.parametrize(
    ("expected_size", "expected_sha256", "max_bytes"),
    [
        (0, "0" * 64, 1),
        (-1, "0" * 64, 1),
        (1, "0" * 64, 0),
        (1, "0" * 64, -1),
        (2, "0" * 64, 1),
        (1, "A" * 64, 1),
        (1, "not-a-sha256", 1),
    ],
)
def test_invalid_expectations_are_rejected_before_source_consumption(
    tmp_path: Path,
    expected_size: int,
    expected_sha256: str,
    max_bytes: int,
) -> None:
    consumed = False

    async def must_not_consume() -> AsyncIterator[bytes]:
        nonlocal consumed
        consumed = True
        raise AssertionError("source was consumed")
        yield b"unreachable"

    with pytest.raises(InvalidStagingExpectationError) as caught:
        _run(
            CiphertextStager(tmp_path / "staging").stage(
                must_not_consume(),
                expected_size=expected_size,
                expected_sha256=expected_sha256,
                max_bytes=max_bytes,
            )
        )
    assert caught.value.code == "INVALID_EXPECTATION"
    assert not consumed
    assert not (tmp_path / "staging").exists()


def test_actual_over_expected_stops_source_without_consuming_next_chunk(tmp_path: Path) -> None:
    consumed = 0

    async def source() -> AsyncIterator[bytes]:
        nonlocal consumed
        consumed += 1
        yield b"ab"
        consumed += 1
        yield b"c"
        consumed += 1
        yield b"d"
        raise AssertionError("source continued after size failure")

    with pytest.raises(StagingSizeExceededError):
        _run(
            CiphertextStager(tmp_path / "staging").stage(
                source(),
                expected_size=3,
                expected_sha256=_digest(b"abc"),
                max_bytes=3,
            )
        )
    assert consumed == 3
    assert _temp_files(tmp_path / "staging") == []


def test_actual_over_hard_cap_stops_source(tmp_path: Path) -> None:
    consumed = 0

    async def source() -> AsyncIterator[bytes]:
        nonlocal consumed
        consumed += 1
        yield b"abcd"
        consumed += 1
        yield b"e"
        raise AssertionError("source continued after hard-cap failure")

    with pytest.raises(StagingSizeExceededError):
        _run(
            CiphertextStager(tmp_path / "staging").stage(
                source(),
                expected_size=4,
                expected_sha256=_digest(b"abcd"),
                max_bytes=4,
            )
        )
    assert consumed == 2
    assert _temp_files(tmp_path / "staging") == []


def test_empty_stream_is_truncated(tmp_path: Path) -> None:
    with pytest.raises(StagingSizeTruncatedError):
        _stage(tmp_path / "staging", _chunks(b""), b"payload")
    assert _temp_files(tmp_path / "staging") == []


def test_hash_mismatch_cleans_file(tmp_path: Path) -> None:
    with pytest.raises(StagingHashMismatchError):
        _run(
            CiphertextStager(tmp_path / "staging").stage(
                _chunks(b"payload"),
                expected_size=7,
                expected_sha256=_digest(b"another"),
                max_bytes=7,
            )
        )
    assert _temp_files(tmp_path / "staging") == []


def test_invalid_chunk_type_is_redacted_and_cleans_file(tmp_path: Path) -> None:
    async def source() -> AsyncIterator[bytes]:
        yield b"good"
        yield "not bytes"  # type: ignore[misc]

    with pytest.raises(StagingIOError):
        _run(
            CiphertextStager(tmp_path / "staging").stage(
                source(),
                expected_size=9,
                expected_sha256=_digest(b"goodtext!"),
                max_bytes=9,
            )
        )
    assert _temp_files(tmp_path / "staging") == []


def test_source_exception_is_redacted_and_cleans_file(tmp_path: Path) -> None:
    async def source() -> AsyncIterator[bytes]:
        yield b"payload"
        raise RuntimeError("source secret")

    with pytest.raises(StagingIOError) as caught:
        _run(
            CiphertextStager(tmp_path / "staging").stage(
                source(),
                expected_size=99,
                expected_sha256=_digest(b"payload"),
                max_bytes=99,
            )
        )
    assert "source secret" not in f"{caught.value!s} {caught.value!r}"
    assert _temp_files(tmp_path / "staging") == []


def test_partial_os_write_is_completed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    real_write = os.write
    calls = 0

    def short_write(fd: int, data: bytes | bytearray | memoryview) -> int:
        nonlocal calls
        calls += 1
        if len(data) > 1:
            return real_write(fd, data[:1])
        return real_write(fd, data)

    monkeypatch.setattr(staging_module.os, "write", short_write)
    payload = b"partial-write-payload"
    staged = _stage(tmp_path / "staging", _chunks(payload), payload)
    try:
        with staged.open_reader() as reader:
            assert reader.read() == payload
    finally:
        staged.cleanup()
    assert calls > 1


def test_write_error_is_redacted_and_cleans_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    def fail_write(_: int, __: bytes | bytearray | memoryview) -> int:
        raise OSError(5, "secret write path")

    monkeypatch.setattr(staging_module.os, "write", fail_write)
    with pytest.raises(StagingIOError) as caught:
        _stage(tmp_path / "staging", _chunks(b"payload"), b"payload")
    assert "secret write path" not in f"{caught.value!s} {caught.value!r}"
    assert _temp_files(tmp_path / "staging") == []


def test_fsync_failure_is_redacted_and_cleans_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    def fail_fsync(_: int) -> None:
        raise OSError(5, "secret filesystem path")

    monkeypatch.setattr(staging_module.os, "fsync", fail_fsync)
    with pytest.raises(StagingIOError) as caught:
        _stage(tmp_path / "staging", _chunks(b"payload"), b"payload")
    assert "secret filesystem path" not in f"{caught.value!s} {caught.value!r}"
    assert _temp_files(tmp_path / "staging") == []


def test_open_reader_error_is_redacted(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    payload = b"open-reader-payload"
    staged = _stage(tmp_path / "staging", _chunks(payload), payload)
    real_open = Path.open

    def fail_open(path: Path, *args: Any, **kwargs: Any) -> Any:
        raise OSError(13, "secret reader path")

    monkeypatch.setattr(Path, "open", fail_open)
    try:
        with pytest.raises(StagingIOError) as caught:
            with staged.open_reader():
                raise AssertionError("reader opened")
        assert "secret reader path" not in f"{caught.value!s} {caught.value!r}"
    finally:
        monkeypatch.setattr(Path, "open", real_open)
        staged.cleanup()


def test_cancellation_cleans_file_and_propagates(tmp_path: Path) -> None:
    started = asyncio.Event()
    release = asyncio.Event()

    async def source() -> AsyncIterator[bytes]:
        started.set()
        await release.wait()
        yield b"payload"

    async def run() -> None:
        task = asyncio.create_task(
            CiphertextStager(tmp_path / "staging").stage(
                source(),
                expected_size=7,
                expected_sha256=_digest(b"payload"),
                max_bytes=7,
            )
        )
        await asyncio.wait_for(started.wait(), timeout=1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=1)

    _run(run())
    assert _temp_files(tmp_path / "staging") == []


def test_context_exit_cleans_and_open_reader_after_cleanup_is_redacted(tmp_path: Path) -> None:
    payload = b"owned-until-exit"
    staged = _stage(tmp_path / "staging", _chunks(payload), payload)
    path = staged._path  # noqa: SLF001
    with staged:
        assert path.exists()
    assert not path.exists()
    with pytest.raises(StagingIOError):
        with staged.open_reader():
            raise AssertionError("reader opened after cleanup")


def test_errors_and_handle_repr_do_not_expose_sensitive_values(tmp_path: Path) -> None:
    secret_hash = "a" * 64
    secret_body = b"secret-ciphertext"
    with pytest.raises(StagingHashMismatchError) as caught:
        _run(
            CiphertextStager(tmp_path / "sensitive-staging-root").stage(
                _chunks(secret_body),
                expected_size=len(secret_body),
                expected_sha256=secret_hash,
                max_bytes=len(secret_body),
            )
        )
    public_error = f"{caught.value!s} {caught.value!r}"
    assert str(caught.value) == "ciphertext staging failed"
    assert secret_hash not in public_error
    assert str(tmp_path) not in public_error
    assert secret_body.decode() not in public_error

    payload = b"safe-handle"
    staged = _stage(tmp_path / "sensitive-staging-root", _chunks(payload), payload)
    try:
        public_handle = f"{staged!s} {staged!r}"
        assert staged.sha256_hex == _digest(payload)
        assert _digest(payload) not in public_handle
        assert str(staged._path) not in public_handle  # noqa: SLF001
    finally:
        staged.cleanup()
