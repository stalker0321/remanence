"""Process-local token-bucket rate limiting.

The server runs as a single uvicorn process behind Caddy on loopback, so
``request.client.host`` is the real client address (uvicorn trusts
X-Forwarded-For from 127.0.0.1) and in-memory buckets are authoritative.
Running several workers would split the buckets per process.
"""

from __future__ import annotations

import math
import threading
import time
from collections.abc import Callable, Hashable

from fastapi import Request


class RateLimitedError(Exception):
    def __init__(self, retry_after: int) -> None:
        super().__init__("rate limited")
        self.retry_after = retry_after


class TokenBucketLimiter:
    """Bounded per-key token buckets: ``per_minute`` sustained, ``burst`` peak."""

    def __init__(
        self,
        *,
        per_minute: float,
        burst: int,
        max_keys: int = 10_000,
        idle_evict_seconds: float = 600.0,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._refill_per_second = per_minute / 60.0
        self._burst = burst
        self._max_keys = max_keys
        self._idle_evict_seconds = idle_evict_seconds
        self._clock = clock or (lambda: time.monotonic())
        self._lock = threading.Lock()
        # key -> [tokens, last_refill, last_seen]
        self._buckets: dict[Hashable, list[float]] = {}

    def reset(self) -> None:
        with self._lock:
            self._buckets.clear()

    def check(self, key: Hashable) -> tuple[bool, int]:
        """Consume one token; return ``(allowed, retry_after_seconds)``."""
        now = self._clock()
        with self._lock:
            bucket = self._buckets.get(key)
            if bucket is None:
                self._buckets[key] = [float(self._burst - 1), now, now]
                self._evict_if_needed(now)
                return True, 0
            tokens, last_refill, _ = bucket
            tokens = min(float(self._burst), tokens + max(0.0, now - last_refill) * self._refill_per_second)
            bucket[1] = now
            bucket[2] = now
            if tokens >= 1.0:
                bucket[0] = tokens - 1.0
                return True, 0
            bucket[0] = tokens
            return False, max(1, math.ceil((1.0 - tokens) / self._refill_per_second))

    def _evict_if_needed(self, now: float) -> None:
        if len(self._buckets) <= self._max_keys:
            return
        idle_cutoff = now - self._idle_evict_seconds
        for key in [key for key, bucket in self._buckets.items() if bucket[2] < idle_cutoff]:
            del self._buckets[key]
        overflow = len(self._buckets) - self._max_keys
        if overflow > 0:
            oldest = sorted(self._buckets.items(), key=lambda item: item[1][2])
            for key, _ in oldest[:overflow]:
                del self._buckets[key]


_limiters: list[TokenBucketLimiter] = []


def reset_rate_limiters() -> None:
    """Clear every limiter created through ``limit_by_client`` (tests only)."""
    for limiter in _limiters:
        limiter.reset()


def limit_by_client(name: str, *, per_minute: float, burst: int) -> Callable[[Request], None]:
    """FastAPI dependency limiting one route group per client address."""
    limiter = TokenBucketLimiter(per_minute=per_minute, burst=burst)
    _limiters.append(limiter)

    def dependency(request: Request) -> None:
        if getattr(request.app.state, "rate_limits_disabled", False):
            return
        client = request.client.host if request.client is not None else "unknown"
        allowed, retry_after = limiter.check((name, client))
        if not allowed:
            raise RateLimitedError(retry_after)

    return dependency


# Shared route-group limits. Unauthenticated credential endpoints are the
# brute-force surface; directory lookups are the handle-enumeration surface.
auth_limit = limit_by_client("auth", per_minute=10, burst=10)
refresh_limit = limit_by_client("refresh", per_minute=30, burst=10)
recovery_limit = limit_by_client("recovery", per_minute=10, burst=10)
registration_limit = limit_by_client("registration", per_minute=30, burst=15)
directory_limit = limit_by_client("directory", per_minute=30, burst=15)
