"""Typed fail-closed process settings."""

from enum import StrEnum
from pathlib import Path
from typing import Self
from urllib.parse import urlsplit
import base64
import re

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from remanence.send_access.policy import SendAccessMode


class AppMode(StrEnum):
    TEST = "test"
    DEV = "dev"
    BETA = "beta"
    PROD = "prod"


class MusicSearchBackend(StrEnum):
    DISABLED = "disabled"
    POSTGRES_STAGING = "postgres_staging"
    FULL_CATALOG = "full_catalog"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="REMANENCE_",
        case_sensitive=False,
        extra="forbid",
        env_file=None,
    )

    mode: AppMode
    host: str = "127.0.0.1"
    port: int = Field(default=8000, ge=1, le=65535)
    database_url: SecretStr | None = None
    blob_root: Path | None = None
    music_search_backend: MusicSearchBackend = MusicSearchBackend.DISABLED
    music_catalog_path: Path | None = None
    music_search_timeout_seconds: float = Field(default=2.0, gt=0, le=10)
    recovery_enabled: bool = False
    send_access_mode: SendAccessMode = SendAccessMode.OFF
    google_sign_in_enabled: bool = False
    google_client_ids: list[str] = Field(default_factory=list)
    google_presenter_ids: list[str] = Field(default_factory=list)
    recovery_rp_id: str = "remanence.hryshyn.dev"
    recovery_origins: list[str] = Field(default_factory=lambda: ["https://remanence.hryshyn.dev"])
    # T185 experimental pending-gift flags. ALL default OFF in every mode.
    # Production/beta require configured independent custody (fail closed).
    pending_protocol_enabled: bool = False
    pending_sends_enabled: bool = False
    pending_claims_enabled: bool = False
    pending_locator_issuance_enabled: bool = False
    pending_kms_key_id: str | None = None
    pending_allowed_kms_key_ids: list[str] = Field(default_factory=list)
    # Server-secret HMAC key for address lookup tags (deployment config;
    # synthetic in tests). Required whenever the pending master flag is on:
    # without it the registry cannot bootstrap tags at runtime.
    pending_hmac_secret: SecretStr | None = None

    @model_validator(mode="after")
    def require_pending_custody(self) -> Self:
        subflags = (
            self.pending_sends_enabled,
            self.pending_claims_enabled,
            self.pending_locator_issuance_enabled,
        )
        if any(subflags) and not self.pending_protocol_enabled:
            raise ValueError("pending sub-flags require the master protocol flag")
        if self.pending_protocol_enabled and self.pending_hmac_secret is None:
            raise ValueError("pending requires an explicit address HMAC secret")
        if self.pending_protocol_enabled and self.mode in (AppMode.PROD, AppMode.BETA):
            if (
                not self.pending_kms_key_id
                or self.pending_kms_key_id not in self.pending_allowed_kms_key_ids
            ):
                raise ValueError("pending in prod/beta requires configured independent custody")
        return self

    @model_validator(mode="after")
    def require_datastore_for_non_test(self) -> Self:
        if self.send_access_mode is SendAccessMode.ENFORCE and self.mode is not AppMode.TEST:
            raise ValueError("send access enforcement is synthetic-test only")
        if self.music_search_backend is MusicSearchBackend.FULL_CATALOG:
            if self.music_catalog_path is None or not self.music_catalog_path.is_absolute():
                raise ValueError("full catalog search requires an absolute music_catalog_path")
        if self.google_sign_in_enabled:
            if self.mode is AppMode.PROD or not self.recovery_enabled:
                raise ValueError("Google onboarding requires explicit DEV/TEST/BETA recovery")
            if not self.google_client_ids or any(not re.fullmatch(r"[A-Za-z0-9_-]+\.apps\.googleusercontent\.com", v) for v in self.google_client_ids + self.google_presenter_ids):
                raise ValueError("explicit Google OAuth client IDs required")
        if self.recovery_enabled and self.mode is AppMode.PROD:
            raise ValueError("recovery requires explicit DEV/TEST/BETA mode")
        if not self.recovery_rp_id or not self.recovery_origins:
            raise ValueError("explicit recovery RP/origins required")
        if self.recovery_enabled:
            for origin in self.recovery_origins:
                if origin.startswith("android:apk-key-hash:"):
                    value = origin.removeprefix("android:apk-key-hash:")
                    if not re.fullmatch(r"[A-Za-z0-9_-]{43}", value) or base64.urlsafe_b64encode(base64.urlsafe_b64decode(value + "=")).decode().rstrip("=") != value:
                        raise ValueError("invalid Android recovery origin")
                else:
                    url = urlsplit(origin)
                    if url.hostname != self.recovery_rp_id or url.username is not None or url.password is not None or url.path or url.query or url.fragment:
                        raise ValueError("invalid browser recovery origin")
                    if url.scheme != "https" and not (url.scheme == "http" and url.hostname == "localhost"):
                        raise ValueError("HTTPS recovery origin required")
        if self.mode is AppMode.TEST:
            return self
        if self.database_url is None or self.blob_root is None:
            raise ValueError("DEV and PROD require database_url and blob_root")
        if not self.database_url.get_secret_value().startswith("postgresql+psycopg://"):
            raise ValueError('database_url must start exactly with "postgresql+psycopg://"')
        if self.mode in (AppMode.PROD, AppMode.BETA) and not self.blob_root.is_absolute():
            raise ValueError("PROD blob_root must be absolute")
        return self
