"""No owner, network identifiers, timestamp or account foreign keys."""
from uuid import UUID, uuid4
from sqlalchemy import JSON, Uuid
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column
from remanence.db.base import Base


class AnonymousTelemetryReport(Base):
    __tablename__ = "anonymous_telemetry_reports"
    id: Mapped[UUID] = mapped_column(Uuid(), primary_key=True, default=uuid4)
    payload: Mapped[dict] = mapped_column(JSON().with_variant(JSONB(), "postgresql"), nullable=False)
