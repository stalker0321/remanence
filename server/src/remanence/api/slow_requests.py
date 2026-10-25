"""Logs requests slower than a threshold: method, path with ids masked, status, duration."""

import logging
import re
import time

from starlette.types import ASGIApp, Message, Receive, Scope, Send

_LOGGER = logging.getLogger("remanence.slow_requests")
_IDS = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[A-Za-z0-9_-]{32,}")


class SlowRequestLogMiddleware:
    def __init__(self, app: ASGIApp, threshold_seconds: float = 1.0) -> None:
        self.app = app
        self.threshold = threshold_seconds

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        started = time.monotonic()
        status = 0

        async def send_with_status(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, send_with_status)
        finally:
            elapsed = time.monotonic() - started
            if elapsed >= self.threshold:
                _LOGGER.warning("slow request %s %s -> %s in %.2fs", scope.get("method"),
                                _IDS.sub("{id}", scope.get("path", "")), status, elapsed)
