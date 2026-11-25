"""Per-account limit on message sends, a token bucket kept in the database.

Each account has a bucket of MESSAGE_BURST tokens that refills at MESSAGE_RATE_PER_MINUTE.
A send takes one token; with none left it's refused. A group message goes out as one
envelope per member (up to 31), so a burst must fit a few of those, while the sustained
rate stays bounded. The relay can't tell group copies from any other send, and doesn't try.

Until migration 013 this was a fixed one-minute window of 60 sends, which let up to twice
that through around a reset. A bucket has no reset: over any stretch of t seconds an
account gets at most MESSAGE_BURST + t × MESSAGE_RATE_PER_MINUTE / 60 sends through.
This is a privacy-preserving abuse control: it limits throughput without inspecting
message content or building behavioral profiles.
"""

from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app import config
from app.models.db import RateLimit


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _take(rate: RateLimit, now: datetime) -> bool:
    """Refills `rate` up to `now` and takes a token if there is one."""
    burst = float(config.MESSAGE_BURST)
    if rate.tokens is None or rate.refilled_at is None:
        tokens = burst
    else:
        elapsed = max(0.0, (now - rate.refilled_at).total_seconds())
        tokens = min(burst, rate.tokens + elapsed * config.MESSAGE_RATE_PER_MINUTE / 60.0)
    rate.refilled_at = now
    if tokens < 1.0:
        rate.tokens = tokens
        return False
    rate.tokens = tokens - 1.0
    return True


async def check_rate_limit(db: AsyncSession, user_id: str) -> bool:
    """Return True if the user may send now (and count the send), False if not."""
    now = _now()
    result = await db.execute(
        select(RateLimit).where(RateLimit.user_id == user_id).with_for_update()
    )
    rate = result.scalar_one_or_none()
    if rate is None:
        try:
            async with db.begin_nested():
                rate = RateLimit(user_id=user_id, message_count=0, window_start=now)
                allowed = _take(rate, now)
                db.add(rate)
                await db.flush()
            return allowed
        except IntegrityError:
            # Another request inserted the row concurrently; re-read it with the lock.
            result = await db.execute(
                select(RateLimit).where(RateLimit.user_id == user_id).with_for_update()
            )
            rate = result.scalar_one_or_none()
            if rate is None:
                return True  # Shouldn't happen, but allow the request
    allowed = _take(rate, now)
    await db.flush()
    return allowed
