"""Dispatch BEFORE request IDs and ordinary exception handlers.

Upstream proxy/transport logs require their own deployment isolation. This does
not claim to anonymize the network or prevent operator correlation.
"""
import asyncio
import json
import logging
from threading import BoundedSemaphore, Lock
from time import monotonic
from urllib.parse import unquote
from remanence.telemetry.schemas import TelemetryReport
from remanence.telemetry.store import StoreFull, TelemetryStore

PATH = "/v1/telemetry/remanence"
MAX_BYTES = 32768


def reserved_path(path: str) -> bool:
    return path == PATH or path.startswith(PATH + "/")


class TelemetryAccessFilter(logging.Filter):
    def filter(self, record):
        # Uvicorn's access record: peer, method, path+query, version, status.
        if isinstance(record.args, tuple) and len(record.args) == 5:
            path = record.args[2]
            if isinstance(path, str) and reserved_path(unquote(path.split("?", 1)[0])):
                return False
        return True


class TelemetryErrorFilter(logging.Filter):
    def filter(self, record):
        # Catch ASGI response/transport exceptions AFTER a reserved scope is
        # available, without formatting exception messages or frame locals.
        # Pre-routing malformed HTTP has no attributable scope: deployment gate.
        if record.exc_info:
            traceback = record.exc_info[2]
            while traceback is not None:
                scope = traceback.tb_frame.f_locals.get("scope")
                if isinstance(scope, dict) and reserved_path(str(scope.get("path", ""))):
                    return False
                traceback = traceback.tb_next
        return True


def install_access_filter() -> None:
    logger = logging.getLogger("uvicorn.access")
    if not any(isinstance(f, TelemetryAccessFilter) for f in logger.filters):
        logger.addFilter(TelemetryAccessFilter())
    error_logger = logging.getLogger("uvicorn.error")
    if not any(isinstance(f, TelemetryErrorFilter) for f in error_logger.filters):
        error_logger.addFilter(TelemetryErrorFilter())


class GlobalIntakeLimit:
    """Process-wide burst20, refill1/sec; no IP or account keyed state."""
    def __init__(self, capacity=20, refill=1.0, clock=monotonic):
        self.capacity, self.refill, self.clock = capacity, refill, clock
        self.tokens, self.last = float(capacity), clock()
        self.lock = Lock()

    def take(self):
        with self.lock:
            now = self.clock()
            self.tokens = min(self.capacity, self.tokens + max(0, now - self.last) * self.refill)
            self.last = now
            if self.tokens < 1:
                return False
            self.tokens -= 1
            return True


GLOBAL_LIMIT = GlobalIntakeLimit()
GLOBAL_INFLIGHT = BoundedSemaphore(4)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate property")
        result[key] = value
    return result


def _invalid_constant(_):
    raise ValueError("non-finite number")


class TelemetryIngress:
    def __init__(self, store: TelemetryStore, limiter=None):
        self.store = store
        self.limiter = GLOBAL_LIMIT if limiter is None else limiter

    async def __call__(self, scope, receive, send):
        # Capture presence (not values), then scrub the ORIGINAL shared scope.
        headers = scope.get("headers", ())
        credentialed = any(k.lower() in {b"authorization", b"cookie", b"proxy-authorization"} for k, _ in headers)
        relevant = [(k.lower(), v) for k, v in headers if k.lower() in
                    {b"content-type", b"content-length", b"content-encoding"}]
        path, method = scope.get("path"), scope.get("method")
        queried = bool(scope.get("query_string"))
        scope.update(client=None, server=None, headers=[], query_string=b"", state={},
                     path=PATH, raw_path=PATH.encode(), root_path="", extensions={})
        for key in ("user", "auth", "session", "path_params"):
            scope.pop(key, None)
        del headers
        status = 503
        admitted = False
        try:
            if credentialed:
                status = 403
            elif path != PATH:
                status = 404
            elif method != "POST":
                status = 405
            elif queried:
                status = 400
            elif not self.limiter.take():
                status = 429
            elif not GLOBAL_INFLIGHT.acquire(blocking=False):
                status = 429
            else:
                admitted = True
                status = await self._ingest(relevant, receive)
        except Exception:
            # Never rethrow payload/SQL errors to Starlette/Uvicorn loggers.
            status = 503
        finally:
            if admitted:
                GLOBAL_INFLIGHT.release()
        await send({"type": "http.response.start", "status": status, "headers": [
            (b"content-type", b"application/json"), (b"cache-control", b"no-store")]})
        await send({"type": "http.response.body", "body":
                    b'{"status":"ACCEPTED"}' if status == 202 else b'{"status":"REJECTED"}'})

    async def _ingest(self, headers, receive):
        if len({key for key, _ in headers}) != len(headers):
            return 400
        headers = dict(headers)
        content_type = headers.get(b"content-type", b"").lower().replace(b" ", b"")
        if content_type not in {b"application/json", b"application/json;charset=utf-8"}:
            return 415
        if b"content-encoding" in headers:
            return 415
        try:
            length = headers.get(b"content-length")
            if length is not None and (not length.isdigit() or int(length) > MAX_BYTES):
                return 413
            body = bytearray()
            deadline = monotonic() + 5
            while True:
                remaining = deadline - monotonic()
                if remaining <= 0:
                    return 408
                message = await asyncio.wait_for(receive(), timeout=remaining)
                if message["type"] != "http.request":
                    return 400
                chunk = message.get("body", b"")
                if len(body) + len(chunk) > MAX_BYTES:
                    return 413
                body.extend(chunk)
                if not message.get("more_body", False):
                    break
            if length is not None and int(length) != len(body):
                return 400
            parsed = json.loads(body.decode("utf-8"), object_pairs_hook=_unique_object,
                                parse_constant=_invalid_constant)
            payload = TelemetryReport.model_validate(parsed).model_dump(exclude_none=True)
        except TimeoutError:
            return 408
        except Exception:
            return 422
        finally:
            if "body" in locals():
                body.clear()
        try:
            await asyncio.to_thread(self.store.append, payload)
        except StoreFull:
            return 503
        return 202
