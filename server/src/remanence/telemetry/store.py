"""Append-only bounded intake. Never log an exception or attach an account."""
from threading import BoundedSemaphore, RLock
from sqlalchemy import func, select, text
from remanence.telemetry.models import AnonymousTelemetryReport

ROW_CAP = 50000
_LOCAL_WRITE_LOCK = RLock()
_STORE_INFLIGHT = BoundedSemaphore(4)
_PG_LOCK = 0x52454D414E454E


class StoreFull(Exception):
    pass


class StoreUnavailable(Exception):
    pass


class TelemetryStore:
    def __init__(self, factory_provider, row_cap: int = ROW_CAP):
        self.factory_provider = factory_provider
        self.row_cap = row_cap

    def append(self, payload: dict) -> None:
        # Held by the synchronous worker itself, so cancelling the awaiting
        # HTTP coroutine cannot release this bound while its DB work continues.
        if not _STORE_INFLIGHT.acquire(blocking=False):
            raise StoreUnavailable()
        try:
            factory = self.factory_provider()
            if factory is None:
                raise StoreUnavailable()
            # PostgreSQL transaction lock coordinates all API processes. Local
            # lock also makes isolated SQLite rehearsals deterministic.
            with _LOCAL_WRITE_LOCK, factory() as session, session.begin():
                if session.get_bind().dialect.name == "postgresql":
                    session.execute(text("SET LOCAL lock_timeout = '2s'"))
                    session.execute(text("SET LOCAL statement_timeout = '2s'"))
                    session.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": _PG_LOCK})
                count = session.scalar(select(func.count()).select_from(AnonymousTelemetryReport))
                if count >= self.row_cap:
                    raise StoreFull()
                session.add(AnonymousTelemetryReport(payload=payload))
        except StoreFull:
            raise
        except Exception:
            raise StoreUnavailable() from None
        finally:
            _STORE_INFLIGHT.release()
