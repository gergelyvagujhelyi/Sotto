"""Regression tests: a stalled socket must not block delivery for anyone else."""

import asyncio
import json
import time

import pytest

import app.services.websocket_manager as wsm
from app.services.ip_rate_limiter import _is_trusted, _parse_networks
from app.services.websocket_manager import WebSocketManager


class FakeSocket:
    """Socket whose send/close can be made to hang like a peer that stopped reading."""

    def __init__(self, hang: bool = False):
        self.hang = hang
        self.sent: list[str] = []
        self.closed = False

    async def send_text(self, payload: str) -> None:
        if self.hang:
            await asyncio.sleep(3600)
        self.sent.append(payload)

    async def close(self, code: int = 1000, reason: str = "") -> None:
        if self.hang:
            await asyncio.sleep(3600)
        self.closed = True


@pytest.fixture(autouse=True)
def short_timeout(monkeypatch):
    monkeypatch.setattr(wsm, "_SEND_TIMEOUT", 0.05)


async def logged_in(mgr: WebSocketManager, user_id: str, ws) -> None:
    """Register a socket that already got auth_ok and had its flush (of nothing)."""
    await mgr.register(user_id, ws)
    mgr._connections[user_id][ws] = True


async def test_eviction_of_stalled_socket_does_not_block_other_users():
    mgr = WebSocketManager()
    stalled = FakeSocket(hang=True)
    await mgr.register("mallory", stalled)
    for _ in range(wsm.MAX_WS_CONNECTIONS_PER_USER - 1):
        await mgr.register("mallory", FakeSocket())

    victim = FakeSocket()
    await logged_in(mgr, "victim", victim)

    async def evict():
        # 6th connection evicts the stalled one.
        await mgr.register("mallory", FakeSocket())

    started = time.monotonic()
    await asyncio.wait_for(asyncio.gather(evict(), mgr.notify_new_message("victim", "x")), timeout=2)
    assert time.monotonic() - started < 1
    assert victim.sent  # delivery to an unrelated user went through


async def test_send_to_stalled_socket_times_out_and_drops_it():
    mgr = WebSocketManager()
    stalled = FakeSocket(hang=True)
    healthy = FakeSocket()
    await logged_in(mgr, "bob", stalled)
    await logged_in(mgr, "bob", healthy)

    delivered = await asyncio.wait_for(mgr.notify_new_message("bob", "alice"), timeout=2)
    assert delivered
    assert healthy.sent
    assert mgr.connection_count() == 1  # stalled socket was dropped


async def test_disconnect_user_closes_sockets_and_drops_buffered_signals():
    mgr = WebSocketManager()
    ws = FakeSocket()
    await mgr.register("alice", ws)
    await mgr._store_pending_signal("alice", "{}")
    await mgr.disconnect_user("alice", "Keys rotated")
    assert ws.closed
    assert not mgr.is_online("alice")
    assert "alice" not in mgr._pending_call_signals
    assert mgr._pending_signal_bytes == 0


async def test_disconnect_user_also_drops_offers_awaiting_an_answer():
    # The account is deleted (or its keys change) in the seconds before its app answers
    # an offer. The wake task then parks the offer: it must find nothing to buffer.
    mgr = WebSocketManager()
    ws = FakeSocket()
    await logged_in(mgr, "bob", ws)
    assert await mgr.relay_call_signal("alice", "bob", "call_offer", "c1", "offer", expect_answer=True)
    await mgr.relay_call_signal("alice", "bob", "call_ice_candidate", "c1", "ice")
    assert ("bob", "c1") in mgr._unconfirmed

    await mgr.disconnect_user("bob", "Identity deleted")

    assert not mgr.offer_sockets_open("bob", "c1")
    assert await mgr.park_unconfirmed_offer("bob", "c1") is None
    assert "bob" not in mgr._pending_call_signals
    assert mgr._pending_signal_bytes == 0


async def test_buffered_signals_have_a_global_byte_budget(monkeypatch):
    monkeypatch.setattr(wsm, "_MAX_PENDING_SIGNAL_BYTES", 1000)
    mgr = WebSocketManager()
    payload = "x" * 300
    for i in range(10):
        await mgr._store_pending_signal(f"nobody{i}", payload)
    assert mgr._pending_signal_bytes <= 1000
    assert sum(len(v) for v in mgr._pending_call_signals.values()) == 3


def test_trusted_proxies_accept_cidr(monkeypatch):
    import app.services.ip_rate_limiter as rl

    monkeypatch.setattr(rl, "_TRUSTED_NETWORKS", _parse_networks(["127.0.0.1", "172.16.0.0/12", "bogus"]))
    assert _is_trusted("127.0.0.1")
    assert _is_trusted("172.18.0.5")
    assert not _is_trusted("8.8.8.8")
    assert not _is_trusted("unknown")


async def test_stale_token_cleanup_keeps_a_newer_token(monkeypatch):
    import app.database as database
    from app.models.db import Identity
    from app.services.push import _clear_stale_token
    from tests.conftest import test_session

    monkeypatch.setattr(database, "async_session", test_session)
    async with test_session() as db:
        db.add(Identity(user_id="u1", public_signing_key="s", public_identity_key="i",
                        share_code="code1", fcm_token="NEW"))
        await db.commit()

    # A push to the OLD token failed after the app registered NEW.
    await _clear_stale_token("u1", "OLD")
    async with test_session() as db:
        assert (await db.get(Identity, "u1")).fcm_token == "NEW"

    await _clear_stale_token("u1", "NEW")
    async with test_session() as db:
        assert (await db.get(Identity, "u1")).fcm_token is None


async def test_full_signal_buffer_still_takes_the_hangup():
    import json

    mgr = WebSocketManager()
    await mgr.relay_call_signal("alice", "bob", "call_offer", "c1", "offer")
    for i in range(wsm._MAX_PENDING_SIGNALS_PER_USER + 5):
        await mgr.relay_call_signal("alice", "bob", "call_ice_candidate", "c1", f"ice{i}")
    await mgr.relay_call_signal("alice", "bob", "call_hangup", "c1", "")

    types = [json.loads(p)["type"] for _, p in mgr._pending_call_signals["bob"]]
    assert len(types) == wsm._MAX_PENDING_SIGNALS_PER_USER
    assert types[0] == "call_offer"
    assert types[-1] == "call_hangup"
    assert mgr._pending_signal_bytes == sum(len(p) for _, p in mgr._pending_call_signals["bob"])


async def test_full_signal_buffer_still_takes_an_ice_restart():
    import json

    mgr = WebSocketManager()
    await mgr.relay_call_signal("alice", "bob", "call_offer", "c1", "offer")
    for i in range(wsm._MAX_PENDING_SIGNALS_PER_USER + 5):
        await mgr.relay_call_signal("alice", "bob", "call_ice_candidate", "c1", f"ice{i}")
    await mgr.relay_call_signal("alice", "bob", "call_ice_restart", "c1", "restart")

    types = [json.loads(p)["type"] for _, p in mgr._pending_call_signals["bob"]]
    assert len(types) == wsm._MAX_PENDING_SIGNALS_PER_USER
    assert types[0] == "call_offer"
    assert types[-1] == "call_ice_restart"


class BreaksAfter:
    """Socket that takes [ok] messages, then fails every send."""

    def __init__(self, ok: int):
        self.ok = ok
        self.sent: list[str] = []

    async def send_text(self, payload: str) -> None:
        if len(self.sent) >= self.ok:
            raise ConnectionError("socket broke")
        self.sent.append(payload)

    async def close(self, code: int = 1000, reason: str = "") -> None:
        pass


async def _buffer(mgr: WebSocketManager, user: str, types: list[str]) -> list[str]:
    payloads = [f'{{"type": "{t}", "n": {i}}}' for i, t in enumerate(types)]
    for p, t in zip(payloads, types):
        await mgr._store_pending_signal(user, p, t, "alice")
    return payloads


async def test_signals_a_broken_flush_missed_wait_for_the_next_connection():
    mgr = WebSocketManager()
    payloads = await _buffer(mgr, "bob", ["call_offer", "call_ice_candidate", "call_ice_candidate", "call_hangup"])
    total = sum(len(p) for p in payloads)

    broken = BreaksAfter(ok=1)
    await mgr.register("bob", broken)
    await mgr.flush_pending_signals("bob", broken)
    assert broken.sent == payloads[:1]
    assert mgr.connection_count() == 0  # the broken socket was dropped
    # The rest, the one whose send failed included, stays buffered and counted.
    assert [p for _, p in mgr._pending_call_signals["bob"]] == payloads[1:]
    assert mgr._pending_signal_bytes == total - len(payloads[0])

    fresh = FakeSocket()
    await mgr.register("bob", fresh)
    await mgr.flush_pending_signals("bob", fresh)
    assert fresh.sent == payloads[1:]
    assert "bob" not in mgr._pending_call_signals
    assert mgr._pending_signal_bytes == 0


async def test_requeued_signals_go_before_ones_buffered_during_the_flush():
    mgr = WebSocketManager()
    first = await _buffer(mgr, "bob", ["call_offer", "call_ice_candidate"])

    class BuffersMoreThenBreaks(BreaksAfter):
        async def send_text(self, payload: str) -> None:
            await mgr._store_pending_signal("bob", '{"type": "call_hangup"}', "call_hangup", "alice")
            raise ConnectionError("socket broke")

    broken = BuffersMoreThenBreaks(ok=0)
    await mgr.register("bob", broken)
    await mgr.flush_pending_signals("bob", broken)
    queued = [p for _, p in mgr._pending_call_signals["bob"]]
    assert queued == first + ['{"type": "call_hangup"}']
    assert mgr._pending_signal_bytes == sum(len(p) for p in queued)


async def test_a_broken_flush_hands_its_signals_to_the_users_other_socket():
    mgr = WebSocketManager()
    payloads = await _buffer(mgr, "bob", ["call_offer", "call_ice_candidate"])
    broken, other = BreaksAfter(ok=0), FakeSocket()
    await mgr.register("bob", broken)
    await logged_in(mgr, "bob", other)  # connected after; its own flush found nothing

    await mgr.flush_pending_signals("bob", broken)
    assert other.sent == payloads
    assert "bob" not in mgr._pending_call_signals
    assert mgr._pending_signal_bytes == 0


async def test_flush_does_not_requeue_expired_signals(monkeypatch):
    mgr = WebSocketManager()
    await _buffer(mgr, "bob", ["call_offer"])
    later = time.monotonic() + wsm._PENDING_SIGNAL_TTL + 1
    monkeypatch.setattr(wsm.time, "monotonic", lambda: later)

    broken = BreaksAfter(ok=0)
    await mgr.register("bob", broken)
    await mgr.flush_pending_signals("bob", broken)
    assert broken.sent == []
    assert "bob" not in mgr._pending_call_signals
    assert mgr._pending_signal_bytes == 0


async def test_a_full_requeue_drops_ice_candidates_not_a_hangup_buffered_meanwhile():
    mgr = WebSocketManager()
    cap = wsm._MAX_PENDING_SIGNALS_PER_USER
    first = await _buffer(mgr, "bob", ["call_offer"] + ["call_ice_candidate"] * (cap - 1))
    hangup = '{"type": "call_hangup"}'

    class BuffersHangupThenBreaks(BreaksAfter):
        async def send_text(self, payload: str) -> None:
            await mgr._store_pending_signal("bob", hangup, "call_hangup", "carol")
            raise ConnectionError("socket broke")

    broken = BuffersHangupThenBreaks(ok=0)
    await mgr.register("bob", broken)
    await mgr.flush_pending_signals("bob", broken)
    queued = [p for _, p in mgr._pending_call_signals["bob"]]
    assert len(queued) == cap
    assert queued[0] == first[0]  # the offer stays
    assert queued[-1] == hangup  # and so does the hangup
    assert first[1] not in queued  # the oldest ICE candidate gave way
    assert mgr._pending_signal_bytes == sum(len(p) for p in queued)


async def test_a_socket_gets_nothing_until_its_flush():
    """Until the endpoint sent auth_ok and called flush_pending_signals, a socket is
    not offered to sends: the app takes any other first frame for a failed login."""
    mgr = WebSocketManager()
    ws = FakeSocket()
    await mgr.register("bob", ws)

    assert not mgr.is_online("bob")
    assert not await mgr.notify("bob", "receipts")
    assert await mgr.broadcast('{"type": "app_release"}') == 0
    assert not await mgr.relay_call_signal("alice", "bob", "call_offer", "c1", "enc")
    assert ws.sent == []
    assert mgr.connection_count() == 1  # still counted, and closed by disconnect_user

    await mgr.flush_pending_signals("bob", ws)
    assert [json.loads(p)["type"] for p in ws.sent] == ["call_offer"]
    assert mgr.is_online("bob")
    assert await mgr.notify("bob", "receipts")


async def test_a_hangup_delivered_to_a_socket_is_kept_for_the_next_one_too():
    """A socket that died without closing still takes the send; the phone, reconnecting,
    must still hear that the call is over (it kept trying to reconnect the call)."""
    mgr = WebSocketManager()
    stale = FakeSocket()
    await logged_in(mgr, "bob", stale)

    delivered = await mgr.relay_call_signal("alice", "bob", "call_hangup", "c1", "")
    assert delivered is True

    # Bob's phone comes back on a new socket: the hangup is there for it.
    fresh = FakeSocket()
    await mgr.register("bob", fresh)
    await mgr.flush_pending_signals("bob", fresh)
    assert [json.loads(p)["type"] for p in fresh.sent] == ["call_hangup"]


async def test_other_delivered_signals_are_not_kept():
    mgr = WebSocketManager()
    await logged_in(mgr, "bob", FakeSocket())
    await mgr.relay_call_signal("alice", "bob", "call_ice_candidate", "c1", "ice")
    assert "bob" not in mgr._pending_call_signals
