"""Message sends: a token bucket per account (burst, then a steady refill)."""

import datetime as dt

import pytest
from httpx import AsyncClient

from app import config
from app.models.db import RateLimit
from app.services import rate_limiter
from tests.test_messages import _auth, _bootstrap_user


def _send(to: dict, n: int = 0) -> dict:
    return {"recipient_id": to["user_id"], "encrypted_body": f"x{n}", "nonce": "n"}


@pytest.fixture
def clock(monkeypatch):
    """A clock the test moves by hand."""
    now = {"t": dt.datetime(2026, 11, 25, 12, 0, 0)}
    monkeypatch.setattr(rate_limiter, "_now", lambda: now["t"])
    return now


@pytest.fixture
def small_bucket(monkeypatch):
    monkeypatch.setattr(config, "MESSAGE_BURST", 5)
    monkeypatch.setattr(config, "MESSAGE_RATE_PER_MINUTE", 60)  # one a second
    # Keep the queue limits out of the way: these tests are about the bucket.
    monkeypatch.setattr("app.routers.messages.NEW_ACCOUNT_PENDING_PER_RECIPIENT", 10_000)
    monkeypatch.setattr("app.routers.messages.NEW_ACCOUNTS_PENDING_TOTAL", 10_000)


def test_take_refills_with_time_and_caps_at_the_burst(monkeypatch):
    monkeypatch.setattr(config, "MESSAGE_BURST", 3)
    monkeypatch.setattr(config, "MESSAGE_RATE_PER_MINUTE", 60)
    t0 = dt.datetime(2026, 1, 1)
    rate = RateLimit(user_id="u")
    assert [rate_limiter._take(rate, t0) for _ in range(4)] == [True, True, True, False]
    # Half a second gives half a token: still nothing.
    assert rate_limiter._take(rate, t0 + dt.timedelta(milliseconds=500)) is False
    assert rate_limiter._take(rate, t0 + dt.timedelta(seconds=1)) is True
    # A long pause fills the bucket only up to the burst.
    later = t0 + dt.timedelta(hours=1)
    assert [rate_limiter._take(rate, later) for _ in range(4)] == [True, True, True, False]
    # A clock that went back gives nothing (and takes nothing away).
    assert rate_limiter._take(rate, t0) is False


@pytest.mark.asyncio
async def test_a_burst_goes_through_then_the_refill_rate(client: AsyncClient, clock, small_bucket):
    alice = await _bootstrap_user(client, "alice_tb")
    bob = await _bootstrap_user(client, "bob_tb")
    codes = [(await client.post("/v1/messages/send", json=_send(bob, i), headers=_auth(alice))).status_code
             for i in range(6)]
    assert codes == [200] * 5 + [429]
    clock["t"] += dt.timedelta(seconds=2)
    codes = [(await client.post("/v1/messages/send", json=_send(bob, 10 + i), headers=_auth(alice))).status_code
             for i in range(3)]
    assert codes == [200, 200, 429]


@pytest.mark.asyncio
async def test_each_account_has_its_own_bucket(client: AsyncClient, clock, small_bucket):
    alice = await _bootstrap_user(client, "alice_own")
    bob = await _bootstrap_user(client, "bob_own")
    for i in range(5):
        assert (await client.post("/v1/messages/send", json=_send(bob, i), headers=_auth(alice))).status_code == 200
    assert (await client.post("/v1/messages/send", json=_send(bob, 9), headers=_auth(alice))).status_code == 429
    assert (await client.post("/v1/messages/send", json=_send(alice, 0), headers=_auth(bob))).status_code == 200


@pytest.mark.asyncio
async def test_the_default_burst_fits_a_message_to_a_full_group(client: AsyncClient, clock, monkeypatch):
    monkeypatch.setattr("app.routers.messages.NEW_ACCOUNT_PENDING_PER_RECIPIENT", 10_000)
    monkeypatch.setattr("app.routers.messages.NEW_ACCOUNTS_PENDING_TOTAL", 10_000)
    assert config.MESSAGE_BURST >= 5 * 31
    from app.services.ip_rate_limiter import _buckets

    sender = await _bootstrap_user(client, "sender_group")
    members = []
    for i in range(31):
        _buckets.clear()  # sign-ups are limited per address; not what this tests
        members.append(await _bootstrap_user(client, f"member_{i}"))
    for round_ in range(5):
        for m in members:
            r = await client.post("/v1/messages/send", json=_send(m, round_), headers=_auth(sender))
            assert r.status_code == 200, r.text


@pytest.mark.asyncio
async def test_the_bucket_row_is_read_with_a_lock(clock, small_bucket):
    """Concurrent sends serialize on the bucket's row (FOR UPDATE, PostgreSQL in production).

    SQLite, which the tests use, ignores the lock, so this checks the lock is asked for.
    """
    from tests.conftest import test_session

    seen = []
    async with test_session() as db:
        real = db.execute

        async def spy(statement, *args, **kwargs):
            seen.append(statement)
            return await real(statement, *args, **kwargs)

        db.execute = spy
        assert await rate_limiter.check_rate_limit(db, "someone") is True
        assert await rate_limiter.check_rate_limit(db, "someone") is True
    selects = [st for st in seen if getattr(st, "_for_update_arg", None) is not None]
    assert len(selects) == 2


@pytest.mark.asyncio
async def test_a_resend_of_a_pending_message_still_takes_a_token(client: AsyncClient, clock, small_bucket):
    """Resends are rare; counting them keeps a retry loop from bypassing the limit."""
    alice = await _bootstrap_user(client, "alice_rs")
    bob = await _bootstrap_user(client, "bob_rs")
    body = {**_send(bob), "client_message_id": "same-id"}
    ids = set()
    for _ in range(5):
        r = await client.post("/v1/messages/send", json=body, headers=_auth(alice))
        assert r.status_code == 200
        ids.add(r.json()["message_id"])
    assert len(ids) == 1
    assert (await client.post("/v1/messages/send", json=body, headers=_auth(alice))).status_code == 429
