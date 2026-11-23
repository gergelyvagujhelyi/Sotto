"""Per-user sliding-window limits kept in memory.

Call signals come only through ``POST /v1/calls/signal``. Every offer to an
offline user triggers a high-priority FCM push, so ``allow_call_offer`` limits
offers much more tightly than ``allow_call_signal`` limits the rest.
"""

import asyncio
import time
from collections import defaultdict

MAX_CALL_OFFERS_PER_MINUTE = 10
MAX_CALL_SIGNALS_PER_MINUTE = 100


class SlidingWindowLimiter:
    """At most ``limit`` events per key within ``window`` seconds."""

    _HIGH_WATER = 2000

    def __init__(self, limit: int, window: float = 60.0):
        self.limit = limit
        self.window = window
        self._lock = asyncio.Lock()
        self._times: dict[str, list[float]] = defaultdict(list)

    async def allow(self, key: str) -> bool:
        """Record an event for ``key``; False if it is over the limit."""
        async with self._lock:
            now = time.monotonic()
            if len(self._times) > self._HIGH_WATER:
                for k in [k for k, ts in self._times.items() if not ts or now - ts[-1] > self.window]:
                    del self._times[k]
            times = self._times[key]
            times[:] = [t for t in times if now - t < self.window]
            if len(times) >= self.limit:
                return False
            times.append(now)
            return True

    def reset(self) -> None:
        self._times.clear()


_call_offers = SlidingWindowLimiter(MAX_CALL_OFFERS_PER_MINUTE)
# All call signals of an account.
_call_signals = SlidingWindowLimiter(MAX_CALL_SIGNALS_PER_MINUTE)


async def allow_call_offer(user_id: str) -> bool:
    """Record a call_offer by ``user_id``; False if over the limit."""
    return await _call_offers.allow(user_id)


async def allow_call_signal(user_id: str) -> bool:
    """Record any call signal by ``user_id``; False if over the limit."""
    return await _call_signals.allow(user_id)


def reset() -> None:
    """Clear all state (tests)."""
    _call_offers.reset()
    _call_signals.reset()
