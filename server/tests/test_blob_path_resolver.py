from pathlib import Path

import pytest

from postmark.storage import InvalidBlobKeyError, LocalBlobPathResolver

_UUID_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
_UUID_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
_NORMAL_KEY = f"capsules/{_UUID_A}/{_UUID_B}.blob"


def _resolver(tmp_path: Path) -> tuple[LocalBlobPathResolver, Path]:
    root = tmp_path / "blobs"
    root.mkdir()
    return LocalBlobPathResolver(root), root


def _assert_unchanged(root: Path, snapshot: set[Path], key: str) -> None:
    with pytest.raises(InvalidBlobKeyError):
        LocalBlobPathResolver(root).resolve(key)
    assert {path for path in root.rglob("*")} == snapshot


def test_normal_capsule_blob_key(tmp_path: Path) -> None:
    resolver, root = _resolver(tmp_path)
    resolved = resolver.resolve(_NORMAL_KEY)
    assert resolved == root / "capsules" / _UUID_A / f"{_UUID_B}.blob"
    assert resolved.is_absolute()
    assert resolved.is_relative_to(root)
    assert not (root / "capsules").exists()


@pytest.mark.parametrize(
    "key",
    [
        "",
        "/absolute",
        "leading/",
        "/both/",
        "foo/",
        "foo//bar",
        "foo/bar/",
        "foo\\bar",
        "foo\0bar",
        "foo\nbar",
        "foo:bar",
        "foo/%2e%2e/bar",
        "%2e%2e",
        "капсюля/id",
        "Capsules/id",
        "A",
        "." * 513,
        "a/" + ("b" * 129),
        ".",
        "..",
        "foo/.",
        "foo/..",
        "../foo",
        "foo/../bar",
        "foo/./bar",
        "-dash",
        ".hidden",
        "_under",
    ],
)
def test_rejects_invalid_keys_without_creating_paths(tmp_path: Path, key: str) -> None:
    root = tmp_path / "blobs"
    root.mkdir()
    snapshot = set(root.rglob("*"))
    _assert_unchanged(root, snapshot, key)


def test_rejects_overlength_total_key(tmp_path: Path) -> None:
    root = tmp_path / "blobs"
    root.mkdir()
    snapshot = set(root.rglob("*"))
    segment = "a" * 128
    key = "/".join([segment, segment, segment, segment])
    assert len(key) > 512
    _assert_unchanged(root, snapshot, key)


def test_rejects_lexical_traversal_even_if_normalized_inside(tmp_path: Path) -> None:
    resolver, root = _resolver(tmp_path)
    snapshot = set(root.rglob("*"))
    with pytest.raises(InvalidBlobKeyError):
        resolver.resolve("capsules/../capsules/id.blob")
    assert set(root.rglob("*")) == snapshot


def test_rejects_symlink_escape(tmp_path: Path) -> None:
    resolver, root = _resolver(tmp_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "secret").write_text("nope")
    (root / "escape").symlink_to(outside)
    snapshot = set(root.rglob("*"))
    with pytest.raises(InvalidBlobKeyError):
        resolver.resolve("escape")
    with pytest.raises(InvalidBlobKeyError):
        resolver.resolve("escape/secret")
    assert set(root.rglob("*")) == snapshot
    assert (outside / "secret").read_text() == "nope"


def test_rejects_in_root_symlink(tmp_path: Path) -> None:
    resolver, root = _resolver(tmp_path)
    real = root / "real"
    real.mkdir()
    (real / "file.blob").write_bytes(b"")
    (root / "alias").symlink_to(real)
    snapshot = set(root.rglob("*"))
    with pytest.raises(InvalidBlobKeyError):
        resolver.resolve("alias")
    with pytest.raises(InvalidBlobKeyError):
        resolver.resolve("alias/file.blob")
    assert set(root.rglob("*")) == snapshot
