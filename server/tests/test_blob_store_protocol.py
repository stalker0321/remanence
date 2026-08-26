import inspect
from contextlib import AbstractContextManager, contextmanager
from dataclasses import FrozenInstanceError
from typing import BinaryIO, get_args, get_origin, get_type_hints

import pytest

from remanence.storage import (
    BlobConflictError,
    BlobInfo,
    BlobIntegrityError,
    BlobNotFoundError,
    BlobStore,
    BlobStoreError,
    InvalidBlobKeyError,
)


class _FakeBlobStore:
    def put(
        self,
        key: str,
        source: BinaryIO,
        *,
        expected_size: int,
        expected_sha256: str,
    ) -> BlobInfo:
        return BlobInfo(key=key, size=expected_size, sha256_hex=expected_sha256)

    def open_reader(self, key: str) -> AbstractContextManager[BinaryIO]:
        @contextmanager
        def _missing() -> AbstractContextManager[BinaryIO]:
            raise BlobNotFoundError(key)
            yield  # pragma: no cover

        return _missing()

    def stat(self, key: str) -> BlobInfo:
        raise BlobNotFoundError(key)

    def delete(self, key: str) -> None:
        return None


def test_blob_store_error_hierarchy() -> None:
    for exc in (
        InvalidBlobKeyError,
        BlobNotFoundError,
        BlobConflictError,
        BlobIntegrityError,
    ):
        assert issubclass(exc, BlobStoreError)
        assert issubclass(exc, Exception)
        assert exc is not BlobStoreError


def test_blob_info_is_frozen_and_slotted() -> None:
    info = BlobInfo(key="k", size=1, sha256_hex="abc")
    assert info.key == "k"
    assert info.size == 1
    assert info.sha256_hex == "abc"
    with pytest.raises(FrozenInstanceError):
        info.size = 2  # type: ignore[misc]
    assert not hasattr(info, "__dict__")


def test_fake_store_matches_runtime_protocol() -> None:
    store = _FakeBlobStore()
    assert isinstance(store, BlobStore)
    info = store.put("k", source=None, expected_size=0, expected_sha256="0" * 64)  # type: ignore[arg-type]
    assert info == BlobInfo(key="k", size=0, sha256_hex="0" * 64)
    store.delete("k")


def test_put_signature_is_keyword_only_for_expected_fields() -> None:
    signature = inspect.signature(BlobStore.put)
    params = list(signature.parameters.values())
    assert [p.name for p in params] == [
        "self",
        "key",
        "source",
        "expected_size",
        "expected_sha256",
    ]
    assert params[1].kind is inspect.Parameter.POSITIONAL_OR_KEYWORD
    assert params[2].kind is inspect.Parameter.POSITIONAL_OR_KEYWORD
    assert params[3].kind is inspect.Parameter.KEYWORD_ONLY
    assert params[4].kind is inspect.Parameter.KEYWORD_ONLY
    assert signature.return_annotation is BlobInfo


def test_remaining_method_signatures() -> None:
    open_reader = inspect.signature(BlobStore.open_reader)
    assert list(open_reader.parameters) == ["self", "key"]
    open_return = get_type_hints(BlobStore.open_reader)["return"]
    assert get_origin(open_return) is AbstractContextManager
    assert get_args(open_return)[0] is BinaryIO

    stat = inspect.signature(BlobStore.stat)
    assert list(stat.parameters) == ["self", "key"]
    assert get_type_hints(BlobStore.stat)["return"] is BlobInfo

    delete = inspect.signature(BlobStore.delete)
    assert list(delete.parameters) == ["self", "key"]
    assert get_type_hints(BlobStore.delete)["return"] is type(None)
