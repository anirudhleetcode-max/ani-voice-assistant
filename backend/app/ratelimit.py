"""A small in-memory rate limiter.

Deliberately in-process and deliberately simple: this backend is meant to serve one
person's phone, and reaching for Redis to throttle a single user would be worse
engineering than a dictionary. Anyone running it for more than one user should replace
this with a shared store, and the seam is here.
"""

from __future__ import annotations

import time
from collections import deque


class RateLimiter:
    def __init__(self, per_minute: int) -> None:
        self._per_minute = max(1, per_minute)
        self._hits: dict[str, deque[float]] = {}

    def allow(self, key: str, now: float | None = None) -> bool:
        current = time.monotonic() if now is None else now
        window = self._hits.setdefault(key, deque())

        cutoff = current - 60.0
        while window and window[0] < cutoff:
            window.popleft()

        if len(window) >= self._per_minute:
            return False

        window.append(current)
        return True

    def forget(self, key: str) -> None:
        self._hits.pop(key, None)
