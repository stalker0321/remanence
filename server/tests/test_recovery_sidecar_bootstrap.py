"""T190 recovery-sidecar guards + mounting isolation (no runtime).

No browser, no DB, no server launch here: pure guard functions,
Settings composition, and create_app route mounting (no lifespan run,
no connections). Root executes all runtime checks.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from remanence.recovery.sidecar_bootstrap import (
    RECOVERY_ORIGINS,
    RECOVERY_RP_ID,
    SIDECAR_HOST,
    SIDECAR_PORT,
    SidecarRefusal,
    build_settings,
    fixed_bind,
    main,
    resolve_blob_root,
    resolve_database_url,
)

_EPOCH_DB = "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence_tmp_epoch9"


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch):
    import os

    for key in list(os.environ):
        if key.upper().startswith("REMANENCE_"):
            monkeypatch.delenv(key, raising=False)


def test_fixed_bind_is_exact_loopback() -> None:
    assert fixed_bind() == ("127.0.0.1", 18043)
    assert SIDECAR_HOST == "127.0.0.1" and SIDECAR_PORT == 18043


def test_database_url_prefers_env_and_guards_epoch(monkeypatch: pytest.MonkeyPatch) -> None:
    import os

    with pytest.raises(SidecarRefusal):
        resolve_database_url(None)
    monkeypatch.setenv("REMANENCE_DATABASE_URL", _EPOCH_DB)
    assert resolve_database_url(None) == _EPOCH_DB
    # Explicit CLI value applies only when the env is absent.
    monkeypatch.delenv("REMANENCE_DATABASE_URL", raising=False)
    assert resolve_database_url("postgresql+psycopg://u:p@h:1/remanence_tmp_other") == (
        "postgresql+psycopg://u:p@h:1/remanence_tmp_other"
    )
    for bad in (
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/t181_clone",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/remanence",
        "postgresql+psycopg://t181:isolated-t181@127.0.0.1:55481/postgres",
        "sqlite:///tmp/x.db",
        "",
    ):
        with pytest.raises(SidecarRefusal):
            resolve_database_url(bad)


def test_blob_root_required(tmp_path: Path) -> None:
    with pytest.raises(SidecarRefusal):
        resolve_blob_root(None)
    scratch = tmp_path / "scratch-blobs"
    assert resolve_blob_root(str(scratch)) == scratch
    assert scratch.is_dir()


def test_pending_env_refused_not_ignored(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    args = dict(database_url=_EPOCH_DB, blob_root=tmp_path, mode="dev")
    build_settings(**args)
    for key in (
        "REMANENCE_PENDING_PROTOCOL_ENABLED",
        "REMANENCE_PENDING_SENDS_ENABLED",
        "REMANENCE_PENDING_CLAIMS_ENABLED",
        "REMANENCE_PENDING_LOCATOR_ISSUANCE_ENABLED",
    ):
        monkeypatch.setenv(key, "true")
        with pytest.raises(SidecarRefusal):
            build_settings(**args)
        monkeypatch.setenv(key, "false")
        build_settings(**args)
        monkeypatch.delenv(key, raising=False)
    with pytest.raises(SidecarRefusal):
        build_settings(database_url=_EPOCH_DB, blob_root=tmp_path, mode="prod")


def test_settings_composition_recovery_only(tmp_path: Path) -> None:
    settings = build_settings(database_url=_EPOCH_DB, blob_root=tmp_path, mode="dev")
    assert settings.recovery_enabled is True
    assert settings.recovery_rp_id == "localhost" == RECOVERY_RP_ID
    assert settings.recovery_origins == ["http://localhost:18043"] == list(RECOVERY_ORIGINS)
    assert settings.pending_protocol_enabled is False
    assert settings.pending_sends_enabled is False
    assert settings.pending_claims_enabled is False
    assert settings.pending_locator_issuance_enabled is False


def test_create_app_mounts_recovery_without_pending_or_db(tmp_path: Path) -> None:
    """Isolation proof with zero I/O: recovery routes mounted, NO
    pending /v2 routes (no pending_ctx is ever constructed), no DB
    connection and no lifespan run."""
    from remanence.main import create_app

    settings = build_settings(database_url=_EPOCH_DB, blob_root=tmp_path, mode="dev")
    app = create_app(settings=settings)
    assert getattr(app.state, "pending_ctx", None) is None
    paths = set(app.openapi()["paths"])
    assert "/v1/recovery/status" in paths
    assert "/recovery-test/" in paths
    assert not any(path.startswith("/v2/") for path in paths)


def test_main_refuses_without_database_url(tmp_path: Path, capsys) -> None:
    code = main(["--blob-root", str(tmp_path)])
    assert code == 2
    assert "database" in capsys.readouterr().err.lower()
