import hashlib
import os
import stat
from io import BytesIO
from pathlib import Path

import pytest

from postmark.storage import (
    BlobConflictError,
    BlobIntegrityError,
    BlobNotFoundError,
    BlobStore,
    InvalidBlobKeyError,
    LocalFileBlobStore,
)

_KEY = (
    "capsules/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/"
    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.blob"
)
_CHUNK = 64 * 1024
_EMPTY_SHA = hashlib.sha256(b"").hexdigest()


def _sha(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _temps(root: Path) -> list[Path]:
    return [path for path in root.rglob("*") if path.name.startswith(".postmark-")]


def test_store_is_blobstore_protocol(tmp_path: Path) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    assert isinstance(store, BlobStore)


def test_put_empty_blob(tmp_path: Path) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    info = store.put(_KEY, BytesIO(b""), expected_size=0, expected_sha256=_EMPTY_SHA)
    assert info.size == 0
    assert info.sha256_hex == _EMPTY_SHA
    with store.open_reader(_KEY) as reader:
        assert reader.read() == b""
    assert _temps(tmp_path / "blobs") == []


def test_put_multi_chunk_bytes_stat_and_mode(tmp_path: Path) -> None:
    payload = bytes(range(256)) * ((_CHUNK // 256) + 8)
    assert len(payload) > _CHUNK
    store = LocalFileBlobStore(tmp_path / "blobs")
    info = store.put(
        _KEY,
        BytesIO(payload),
        expected_size=len(payload),
        expected_sha256=_sha(payload),
    )
    assert info.size == len(payload)
    assert info.sha256_hex == _sha(payload)
    statted = store.stat(_KEY)
    assert statted == info
    with store.open_reader(_KEY) as reader:
        assert reader.read() == payload
    path = (tmp_path / "blobs").joinpath(*_KEY.split("/"))
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert _temps(tmp_path / "blobs") == []


def test_put_reads_from_current_source_position(tmp_path: Path) -> None:
    payload = b"capsule-payload"
    source = BytesIO(b"SKIP" + payload)
    source.seek(4)
    store = LocalFileBlobStore(tmp_path / "blobs")
    store.put(_KEY, source, expected_size=len(payload), expected_sha256=_sha(payload))
    with store.open_reader(_KEY) as reader:
        assert reader.read() == payload


def test_put_same_content_is_idempotent(tmp_path: Path) -> None:
    payload = b"same-bytes"
    store = LocalFileBlobStore(tmp_path / "blobs")
    first = store.put(_KEY, BytesIO(payload), expected_size=len(payload), expected_sha256=_sha(payload))
    second = store.put(_KEY, BytesIO(payload), expected_size=len(payload), expected_sha256=_sha(payload))
    assert second == first
    with store.open_reader(_KEY) as reader:
        assert reader.read() == payload
    assert _temps(tmp_path / "blobs") == []


def test_put_different_content_conflicts_and_preserves_original(tmp_path: Path) -> None:
    original = b"original-blob"
    store = LocalFileBlobStore(tmp_path / "blobs")
    store.put(_KEY, BytesIO(original), expected_size=len(original), expected_sha256=_sha(original))
    other = b"other-blob-xx"
    with pytest.raises(BlobConflictError):
        store.put(_KEY, BytesIO(other), expected_size=len(other), expected_sha256=_sha(other))
    with store.open_reader(_KEY) as reader:
        assert reader.read() == original
    assert _temps(tmp_path / "blobs") == []


def test_put_size_mismatch_leaves_key_absent(tmp_path: Path) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    payload = b"abc"
    with pytest.raises(BlobIntegrityError):
        store.put(_KEY, BytesIO(payload), expected_size=99, expected_sha256=_sha(payload))
    with pytest.raises(BlobNotFoundError):
        store.stat(_KEY)
    assert _temps(tmp_path / "blobs") == []


def test_put_hash_mismatch_leaves_key_absent(tmp_path: Path) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    payload = b"abc"
    with pytest.raises(BlobIntegrityError):
        store.put(_KEY, BytesIO(payload), expected_size=len(payload), expected_sha256=_sha(b"xyz"))
    with pytest.raises(BlobNotFoundError):
        store.stat(_KEY)
    assert _temps(tmp_path / "blobs") == []


@pytest.mark.parametrize(
    ("size", "digest"),
    [
        (True, _EMPTY_SHA),
        (False, _EMPTY_SHA),
        (0, _EMPTY_SHA.upper()),
        (0, "abc"),
        (0, "g" * 64),
        (-1, _EMPTY_SHA),
    ],
)
def test_put_invalid_expected_metadata(tmp_path: Path, size: object, digest: object) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    with pytest.raises(BlobIntegrityError):
        store.put(_KEY, BytesIO(b""), expected_size=size, expected_sha256=digest)  # type: ignore[arg-type]
    assert _temps(tmp_path / "blobs") == []


def test_put_invalid_key(tmp_path: Path) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    with pytest.raises(InvalidBlobKeyError):
        store.put("../escape", BytesIO(b"x"), expected_size=1, expected_sha256=_sha(b"x"))
    assert _temps(tmp_path / "blobs") == []


def test_put_preexisting_final_same_and_different(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    payload = b"pre-placed"
    path = root.joinpath(*_KEY.split("/"))
    path.parent.mkdir(parents=True)
    path.write_bytes(payload)
    os.chmod(path, 0o600)
    store = LocalFileBlobStore(root)
    same = store.put(_KEY, BytesIO(payload), expected_size=len(payload), expected_sha256=_sha(payload))
    assert same.sha256_hex == _sha(payload)
    other = b"changed-final"
    with pytest.raises(BlobConflictError):
        store.put(_KEY, BytesIO(other), expected_size=len(other), expected_sha256=_sha(other))
    assert path.read_bytes() == payload
    assert _temps(root) == []
