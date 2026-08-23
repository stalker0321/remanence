import hashlib
from pathlib import Path

import pytest

from postmark.storage.base import BlobNotFoundError, BlobStoreError, InvalidBlobKeyError
from postmark.storage.local import LocalBlobPathResolver, LocalFileBlobStore

_KEY = (
    "capsules/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/"
    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.blob"
)
_CHUNK = 64 * 1024


def _place(root: Path, key: str, payload: bytes) -> Path:
    path = LocalBlobPathResolver(root).resolve(key)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return path


def test_open_reader_reads_and_closes(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    payload = b"postcard-bytes"
    _place(root, _KEY, payload)
    store = LocalFileBlobStore(root)
    with store.open_reader(_KEY) as reader:
        assert reader.read() == payload
        assert not reader.closed
    assert reader.closed


def test_stat_size_and_hash(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    payload = b"sha-fixture"
    _place(root, _KEY, payload)
    info = LocalFileBlobStore(root).stat(_KEY)
    assert info.key == _KEY
    assert info.size == len(payload)
    assert info.sha256_hex == hashlib.sha256(payload).hexdigest()
    assert info.sha256_hex == info.sha256_hex.lower()


def test_stat_hashes_multiple_chunks(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    payload = bytes(range(256)) * ((_CHUNK // 256) + 8)
    assert len(payload) > _CHUNK
    _place(root, _KEY, payload)
    info = LocalFileBlobStore(root).stat(_KEY)
    assert info.size == len(payload)
    assert info.sha256_hex == hashlib.sha256(payload).hexdigest()


def test_missing_reader_and_stat(tmp_path: Path) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    with pytest.raises(BlobNotFoundError):
        with store.open_reader(_KEY):
            raise AssertionError("missing blob opened")
    with pytest.raises(BlobNotFoundError):
        store.stat(_KEY)


def test_delete_is_idempotent(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    path = _place(root, _KEY, b"gone")
    store = LocalFileBlobStore(root)
    store.delete(_KEY)
    assert not path.exists()
    store.delete(_KEY)
    assert path.parent.exists()


def test_invalid_traversal_rejected(tmp_path: Path) -> None:
    store = LocalFileBlobStore(tmp_path / "blobs")
    with pytest.raises(InvalidBlobKeyError):
        store.stat("capsules/../secret")
    with pytest.raises(InvalidBlobKeyError):
        store.delete("../outside")


def test_final_symlink_rejected(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    target = tmp_path / "outside.blob"
    target.write_bytes(b"escape")
    link = _place(root, _KEY, b"placeholder")
    link.unlink()
    link.symlink_to(target)
    store = LocalFileBlobStore(root)
    with pytest.raises(InvalidBlobKeyError):
        with store.open_reader(_KEY):
            raise AssertionError("symlink opened")
    assert target.read_bytes() == b"escape"


def test_ancestor_symlink_rejected(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    real = tmp_path / "elsewhere"
    real.mkdir()
    (real / "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.blob").write_bytes(b"via-ancestor")
    capsules = root / "capsules"
    capsules.mkdir(parents=True)
    (capsules / "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").symlink_to(real)
    store = LocalFileBlobStore(root)
    with pytest.raises(InvalidBlobKeyError):
        store.stat(_KEY)


def test_directory_is_not_a_blob(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    key = "capsules/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    path = LocalBlobPathResolver(root).resolve(key)
    path.mkdir(parents=True)
    store = LocalFileBlobStore(root)
    with pytest.raises(BlobNotFoundError):
        with store.open_reader(key):
            raise AssertionError("directory opened")
    with pytest.raises(BlobNotFoundError):
        store.stat(key)
    with pytest.raises(BlobNotFoundError):
        store.delete(key)
    assert path.is_dir()


def test_root_is_file_rejected(tmp_path: Path) -> None:
    root = tmp_path / "not-a-dir"
    root.write_bytes(b"file")
    with pytest.raises(BlobStoreError) as caught:
        LocalFileBlobStore(root)
    assert str(root) not in str(caught.value)
    assert root.as_posix() not in str(caught.value)
    assert root.name not in str(caught.value)
