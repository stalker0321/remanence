"""Persisted index-revision ledger (D4-B2b2a, code-only).

SQLAlchemy models on the dedicated ``MusicBase`` plus a small
``RevisionStateStore`` with short per-call transactions: create/get
READY revisions, open PENDING activations only when no flight is open,
independently commit the ``post_attempted`` sentinel, store the task
UID, CAS state transitions, and read the open activation.

At most one open flight (PENDING/SWAPPED/UNKNOWN) per stable UID is
enforced by a DB-level partial unique index, so a check-then-insert
race fails closed at the database (``IntegrityError`` -> typed
conflict) instead of forking two flights. The session-level advisory
try-lock remains deferred to the activation slice.

Returned rows are plain snapshot dataclasses (never detached ORM
instances). Uniqueness/check violations and illegal transitions raise
``RevisionStoreConflictError``; programming errors (bad factory,
bad argument types) raise ``TypeError``/``ValueError`` before any I/O.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    Index,
    Integer,
    String,
    UniqueConstraint,
    func,
    select,
    text,
    update,
)
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Mapped, Session, mapped_column

from remanence.music.staging.models import MUSIC_SCHEMA, MusicBase

OPEN_FLIGHT_STATES = ("PENDING", "SWAPPED", "UNKNOWN")

REVISION_STATUSES = ("READY", "ACTIVE", "SUPERSEDED", "FAILED")

ACTIVATION_STATES = ("PENDING", "SWAPPED", "CONFIRMED", "FAILED", "UNKNOWN")

ALLOWED_ACTIVATION_TRANSITIONS: dict[str, frozenset[str]] = {
    "PENDING": frozenset({"SWAPPED", "FAILED", "UNKNOWN"}),
    "SWAPPED": frozenset({"CONFIRMED", "UNKNOWN"}),
    "UNKNOWN": frozenset({"CONFIRMED", "FAILED"}),
    "CONFIRMED": frozenset(),
    "FAILED": frozenset(),
}


class RevisionStoreConflictError(RuntimeError):
    """Ledger conflict: duplicate, open flight, or illegal transition."""


class IndexRevision(MusicBase):
    """One built revision candidate (content location is by UID, not rev)."""

    __tablename__ = "music_index_revision"
    __table_args__ = (
        UniqueConstraint("candidate_uid", name="uq_music_index_revision_candidate_uid"),
        CheckConstraint("doc_count >= 0", name="ck_music_index_revision_doc_count"),
        CheckConstraint(
            "status IN ('READY', 'ACTIVE', 'SUPERSEDED', 'FAILED')",
            name="ck_music_index_revision_status",
        ),
        {"schema": MUSIC_SCHEMA},
    )

    rev: Mapped[int] = mapped_column(Integer, primary_key=True)
    candidate_uid: Mapped[str] = mapped_column(String(64), nullable=False)
    doc_count: Mapped[int] = mapped_column(Integer, nullable=False)
    settings_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="READY")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class IndexActivation(MusicBase):
    """One activation attempt, including crash-recovery sentinels."""

    __tablename__ = "music_index_activation"
    __table_args__ = (
        CheckConstraint("to_rev >= 0", name="ck_music_index_activation_to_rev"),
        CheckConstraint(
            "(from_rev IS NULL OR from_rev >= 0)",
            name="ck_music_index_activation_from_rev",
        ),
        CheckConstraint(
            "task_uid IS NULL OR task_uid >= 0",
            name="ck_music_index_activation_task",
        ),
        CheckConstraint(
            "state IN ('PENDING', 'SWAPPED', 'CONFIRMED', 'FAILED', 'UNKNOWN')",
            name="ck_music_index_activation_state",
        ),
        Index(
            "uq_music_index_activation_open_flight",
            "stable_uid",
            unique=True,
            postgresql_where=text(
                "state IN ('PENDING', 'SWAPPED', 'UNKNOWN')"
            ),
            sqlite_where=text("state IN ('PENDING', 'SWAPPED', 'UNKNOWN')"),
        ),
        {"schema": MUSIC_SCHEMA},
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    stable_uid: Mapped[str] = mapped_column(String(64), nullable=False)
    partner_uid: Mapped[str] = mapped_column(String(64), nullable=False)
    from_rev: Mapped[int | None] = mapped_column(Integer, nullable=True)
    to_rev: Mapped[int] = mapped_column(Integer, nullable=False)
    post_attempted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    task_uid: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    state: Mapped[str] = mapped_column(String(16), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )


@dataclass(frozen=True, slots=True)
class RevisionRecord:
    rev: int
    candidate_uid: str
    doc_count: int
    settings_hash: str
    status: str


@dataclass(frozen=True, slots=True)
class ActivationRecord:
    id: int
    stable_uid: str
    partner_uid: str
    from_rev: int | None
    to_rev: int
    post_attempted: bool
    task_uid: int | None
    state: str


def _require_uid(value: object, label: str) -> str:
    if type(value) is not str or not value.strip():
        raise ValueError(f"{label} must be a non-empty string")
    return value


def _require_rev(value: object, label: str) -> int:
    if type(value) is not int or isinstance(value, bool) or value < 0:
        raise ValueError(f"{label} must be an int >= 0")
    assert type(value) is int
    return value


class RevisionStateStore:
    """Short-transaction ledger over the revision/activation tables."""

    def __init__(self, session_factory: Callable[[], Session]) -> None:
        if not callable(session_factory):
            raise TypeError("RevisionStateStore requires a session factory")
        self._sessions = session_factory

    def create_revision(
        self, rev: int, candidate_uid: str, doc_count: int, settings_hash: str
    ) -> RevisionRecord:
        """Insert one READY revision row (duplicate rev/UID conflicts)."""
        rev = _require_rev(rev, "rev")
        candidate_uid = _require_uid(candidate_uid, "candidate_uid")
        if type(doc_count) is not int or isinstance(doc_count, bool) or doc_count < 0:
            raise ValueError("doc_count must be an int >= 0")
        if type(settings_hash) is not str or not settings_hash:
            raise ValueError("settings_hash must be a non-empty string")
        with self._sessions() as session:
            row = IndexRevision(
                rev=rev,
                candidate_uid=candidate_uid,
                doc_count=doc_count,
                settings_hash=settings_hash,
                status="READY",
            )
            session.add(row)
            try:
                session.flush()
            except IntegrityError as exc:
                raise RevisionStoreConflictError(
                    f"revision {rev} conflicts"
                ) from exc
            record = RevisionRecord(
                rev=row.rev,
                candidate_uid=row.candidate_uid,
                doc_count=row.doc_count,
                settings_hash=row.settings_hash,
                status=row.status,
            )
            session.commit()
            return record

    def get_revision(self, rev: int) -> RevisionRecord | None:
        """Read one revision row (read-only, no commit)."""
        _require_rev(rev, "rev")
        with self._sessions() as session:
            row = session.get(IndexRevision, rev)
            if row is None:
                return None
            return RevisionRecord(
                rev=row.rev,
                candidate_uid=row.candidate_uid,
                doc_count=row.doc_count,
                settings_hash=row.settings_hash,
                status=row.status,
            )

    def open_activation(
        self,
        stable_uid: str,
        partner_uid: str,
        from_rev: int | None,
        to_rev: int,
    ) -> ActivationRecord:
        """Open one PENDING flight; a second open flight conflicts.

        The pre-check keeps the common case cheap, but the DB-level
        partial unique index is the real guard: a check-then-insert race
        surfaces as ``IntegrityError`` -> typed conflict, never two
        flights.
        """
        stable_uid = _require_uid(stable_uid, "stable_uid")
        partner_uid = _require_uid(partner_uid, "partner_uid")
        if from_rev is not None:
            from_rev = _require_rev(from_rev, "from_rev")
        to_rev = _require_rev(to_rev, "to_rev")
        with self._sessions() as session:
            existing = session.scalars(
                select(IndexActivation.id)
                .where(IndexActivation.stable_uid == stable_uid)
                .where(IndexActivation.state.in_(OPEN_FLIGHT_STATES))
                .order_by(IndexActivation.id.desc())
                .limit(1)
            ).first()
            if existing is not None:
                raise RevisionStoreConflictError(
                    f"stable {stable_uid} already has an open flight"
                )
            row = IndexActivation(
                stable_uid=stable_uid,
                partner_uid=partner_uid,
                from_rev=from_rev,
                to_rev=to_rev,
                post_attempted=False,
                task_uid=None,
                state="PENDING",
            )
            session.add(row)
            try:
                session.flush()
            except IntegrityError as exc:
                raise RevisionStoreConflictError(
                    f"stable {stable_uid} already has an open flight"
                ) from exc
            record = ActivationRecord(
                id=row.id,
                stable_uid=row.stable_uid,
                partner_uid=row.partner_uid,
                from_rev=row.from_rev,
                to_rev=row.to_rev,
                post_attempted=row.post_attempted,
                task_uid=row.task_uid,
                state=row.state,
            )
            session.commit()
            return record

    def mark_attempted(self, activation_id: int) -> None:
        """CAS the post_attempted sentinel false -> true while PENDING.

        An already-set sentinel is rejected, never re-armed: it must not
        be treated as permission to send another swap POST.
        """
        activation_id = _require_rev(activation_id, "activation_id")
        with self._sessions() as session:
            updated = session.execute(
                update(IndexActivation)
                .where(IndexActivation.id == activation_id)
                .where(IndexActivation.state == "PENDING")
                .where(IndexActivation.post_attempted.is_(False))
                .values(post_attempted=True)
            ).rowcount
            if updated != 1:
                raise RevisionStoreConflictError(
                    f"activation {activation_id} cannot be marked attempted"
                )
            session.commit()

    def store_task_uid(self, activation_id: int, task_uid: int) -> None:
        """CAS a single task UID write while PENDING and attempted.

        Single-assignment: an already-stored UID is rejected, never
        overwritten (a second POST must never silently replace the
        recorded task).
        """
        activation_id = _require_rev(activation_id, "activation_id")
        if type(task_uid) is not int or isinstance(task_uid, bool) or task_uid < 0:
            raise ValueError("task_uid must be an int >= 0")
        with self._sessions() as session:
            updated = session.execute(
                update(IndexActivation)
                .where(IndexActivation.id == activation_id)
                .where(IndexActivation.state == "PENDING")
                .where(IndexActivation.post_attempted.is_(True))
                .where(IndexActivation.task_uid.is_(None))
                .values(task_uid=task_uid)
            ).rowcount
            if updated != 1:
                raise RevisionStoreConflictError(
                    f"activation {activation_id} cannot store task"
                )
            session.commit()

    def transition(self, activation_id: int, expected: str, new: str) -> ActivationRecord:
        """CAS one activation to ``new`` iff currently ``expected``."""
        activation_id = _require_rev(activation_id, "activation_id")
        if expected not in ALLOWED_ACTIVATION_TRANSITIONS:
            raise RevisionStoreConflictError(f"unknown activation state {expected!r}")
        if new not in ALLOWED_ACTIVATION_TRANSITIONS[expected]:
            raise RevisionStoreConflictError(
                f"forbidden activation transition {expected} -> {new}"
            )
        with self._sessions() as session:
            updated = session.execute(
                update(IndexActivation)
                .where(IndexActivation.id == activation_id)
                .where(IndexActivation.state == expected)
                .values(state=new)
            ).rowcount
            if updated != 1:
                raise RevisionStoreConflictError(
                    f"activation {activation_id} not in {expected}"
                )
            session.commit()
            row = session.get(IndexActivation, activation_id)
            assert row is not None
            return ActivationRecord(
                id=row.id,
                stable_uid=row.stable_uid,
                partner_uid=row.partner_uid,
                from_rev=row.from_rev,
                to_rev=row.to_rev,
                post_attempted=row.post_attempted,
                task_uid=row.task_uid,
                state=row.state,
            )

    def read_open_activation(self, stable_uid: str | None = None) -> ActivationRecord | None:
        """Read the latest open flight, optionally scoped to one stable UID.

        ``None`` preserves the legacy global read (latest open flight
        across all stable UIDs, for operator diagnostics). Activation
        logic must always pass an explicit UID.
        """
        with self._sessions() as session:
            statement = (
                select(IndexActivation)
                .where(IndexActivation.state.in_(OPEN_FLIGHT_STATES))
                .order_by(IndexActivation.id.desc())
                .limit(1)
            )
            if stable_uid is not None:
                statement = statement.where(
                    IndexActivation.stable_uid == _require_uid(stable_uid, "stable_uid")
                )
            row = session.scalars(statement).first()
            if row is None:
                return None
            return ActivationRecord(
                id=row.id,
                stable_uid=row.stable_uid,
                partner_uid=row.partner_uid,
                from_rev=row.from_rev,
                to_rev=row.to_rev,
                post_attempted=row.post_attempted,
                task_uid=row.task_uid,
                state=row.state,
            )

    def find_active_revision(self) -> RevisionRecord | None:
        """Return the single ACTIVE revision, fail closed on multiples.

        Rejects when a SWAPPED/UNKNOWN activation row is open in the same
        read session: served content may already have moved, so any ACTIVE
        answer would be unreliable. A PENDING flight is tolerated (no swap
        could have been sent yet).
        """
        with self._sessions() as session:
            unsettled = session.scalars(
                select(IndexActivation.id)
                .where(IndexActivation.state.in_(("SWAPPED", "UNKNOWN")))
                .limit(1)
            ).first()
            if unsettled is not None:
                raise RevisionStoreConflictError(
                    "unsettled activation flight: ACTIVE revision unreliable"
                )
            rows = session.scalars(
                select(IndexRevision).where(IndexRevision.status == "ACTIVE")
            ).all()
            if len(rows) > 1:
                raise RevisionStoreConflictError("multiple ACTIVE revisions")
            if not rows:
                return None
            row = rows[0]
            return RevisionRecord(
                rev=row.rev,
                candidate_uid=row.candidate_uid,
                doc_count=row.doc_count,
                settings_hash=row.settings_hash,
                status=row.status,
            )

    def confirm_activation(self, activation_id: int) -> ActivationRecord:
        """Atomically confirm one activation and flip revision statuses.

        Exactly one short transaction: CAS activation SWAPPED->CONFIRMED,
        CAS to_rev READY->ACTIVE, CAS from_rev ACTIVE->SUPERSEDED (when
        non-null), a single commit at the end. Any conflict rolls back
        all rows — a crash can never leave a half-confirmed state.

        Bootstrap (``from_rev`` NULL) is rejected when any ACTIVE
        revision already exists; ``to_rev == from_rev`` is rejected.
        A repeated call is idempotent only when the activation is
        already CONFIRMED *and* the revision statuses already match
        (no writes in that case).
        """
        activation_id = _require_rev(activation_id, "activation_id")
        with self._sessions() as session:
            row = session.get(IndexActivation, activation_id)
            if row is None:
                raise RevisionStoreConflictError(
                    f"activation {activation_id} not found"
                )
            if row.state == "CONFIRMED":
                return self._confirmed_snapshot(session, row)
            if row.state != "SWAPPED":
                raise RevisionStoreConflictError(
                    f"activation {activation_id} is {row.state}, not SWAPPED"
                )
            if row.to_rev == row.from_rev:
                raise RevisionStoreConflictError(
                    f"activation {activation_id} has to_rev == from_rev"
                )
            if row.from_rev is None:
                if self._count_active(session) > 0:
                    raise RevisionStoreConflictError(
                        f"activation {activation_id} bootstraps over an ACTIVE revision"
                    )
            else:
                # Non-bootstrap must see exactly one ACTIVE revision and it
                # must be from_rev: merely finding from_rev ACTIVE while
                # another ACTIVE exists would bless a forked catalog.
                active_revs = session.scalars(
                    select(IndexRevision.rev).where(
                        IndexRevision.status == "ACTIVE"
                    )
                ).all()
                if len(active_revs) != 1 or active_revs[0] != row.from_rev:
                    raise RevisionStoreConflictError(
                        f"activation {activation_id} has no single ACTIVE from_rev"
                    )
            updated = session.execute(
                update(IndexActivation)
                .where(IndexActivation.id == activation_id)
                .where(IndexActivation.state == "SWAPPED")
                .values(state="CONFIRMED")
            ).rowcount
            if updated != 1:
                raise RevisionStoreConflictError(
                    f"activation {activation_id} not in SWAPPED"
                )
            if (
                session.execute(
                    update(IndexRevision)
                    .where(IndexRevision.rev == row.to_rev)
                    .where(IndexRevision.status == "READY")
                    .values(status="ACTIVE")
                ).rowcount
                != 1
            ):
                raise RevisionStoreConflictError(
                    f"revision {row.to_rev} not READY"
                )
            if row.from_rev is not None and (
                session.execute(
                    update(IndexRevision)
                    .where(IndexRevision.rev == row.from_rev)
                    .where(IndexRevision.status == "ACTIVE")
                    .values(status="SUPERSEDED")
                ).rowcount
                != 1
            ):
                raise RevisionStoreConflictError(
                    f"revision {row.from_rev} not ACTIVE"
                )
            session.commit()
            return self._activation_snapshot(
                session.get(IndexActivation, activation_id)
            )

    @staticmethod
    def _activation_snapshot(row: IndexActivation | None) -> ActivationRecord:
        assert row is not None
        return ActivationRecord(
            id=row.id,
            stable_uid=row.stable_uid,
            partner_uid=row.partner_uid,
            from_rev=row.from_rev,
            to_rev=row.to_rev,
            post_attempted=row.post_attempted,
            task_uid=row.task_uid,
            state=row.state,
        )

    def _confirmed_snapshot(
        self, session: Session, row: IndexActivation
    ) -> ActivationRecord:
        """Idempotent re-confirm: succeed only if statuses already match."""
        to_row = session.get(IndexRevision, row.to_rev)
        from_ok = row.from_rev is None or (
            (from_row := session.get(IndexRevision, row.from_rev)) is not None
            and from_row.status == "SUPERSEDED"
        )
        if (
            to_row is not None
            and to_row.status == "ACTIVE"
            and from_ok
        ):
            return self._activation_snapshot(row)
        raise RevisionStoreConflictError(
            f"activation {row.id} CONFIRMED but statuses diverge"
        )

    @staticmethod
    def _count_active(session: Session) -> int:
        return int(
            session.scalar(
                select(func.count())
                .select_from(IndexRevision)
                .where(IndexRevision.status == "ACTIVE")
            )
            or 0
        )
