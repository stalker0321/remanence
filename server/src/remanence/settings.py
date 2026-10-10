"""Typed fail-closed process settings."""

from enum import StrEnum
from pathlib import Path
from typing import Self
from urllib.parse import urlsplit
import base64
import re

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppMode(StrEnum):
    TEST = "test"
    DEV = "dev"
    PROD = "prod"


class MusicSearchBackend(StrEnum):
    DISABLED = "disabled"
    POSTGRES_STAGING = "postgres_staging"


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
    recovery_enabled: bool = False
    recovery_rp_id: str = "remanence.hryshyn.dev"
    recovery_origins: list[str] = Field(default_factory=lambda: ["https://remanence.hryshyn.dev"])

    @model_validator(mode="after")
    def require_datastore_for_non_test(self) -> Self:
        if self.recovery_enabled and self.mode is AppMode.PROD:
            raise ValueError("experimental recovery is DEV/TEST only")
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
        if self.mode is AppMode.PROD and not self.blob_root.is_absolute():
            raise ValueError("PROD blob_root must be absolute")
        return self
