"""Typed fail-closed process settings."""

from enum import StrEnum
from pathlib import Path
from typing import Self

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppMode(StrEnum):
    TEST = "test"
    DEV = "dev"
    PROD = "prod"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="POSTMARK_",
        case_sensitive=False,
        extra="forbid",
        env_file=None,
    )

    mode: AppMode
    host: str = "127.0.0.1"
    port: int = Field(default=8000, ge=1, le=65535)
    database_url: SecretStr | None = None
    blob_root: Path | None = None

    @model_validator(mode="after")
    def require_datastore_for_non_test(self) -> Self:
        if self.mode is AppMode.TEST:
            return self
        if self.database_url is None or self.blob_root is None:
            raise ValueError("DEV and PROD require database_url and blob_root")
        if not self.database_url.get_secret_value().startswith("postgresql+psycopg://"):
            raise ValueError('database_url must start exactly with "postgresql+psycopg://"')
        if self.mode is AppMode.PROD and not self.blob_root.is_absolute():
            raise ValueError("PROD blob_root must be absolute")
        return self
