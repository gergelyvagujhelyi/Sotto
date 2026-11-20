"""Regression tests: a stalled socket must not block delivery for anyone else."""

import asyncio
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


async def test_eviction_of_stalled_socket_does_not_block_other_users():
    mgr = WebSocketManager()
    stalled = FakeSocket(hang=True)
    await mgr.register("mallory", stalled)
    for _ in range(wsm.MAX_WS_CONNECTIONS_PER_USER - 1):
        await mgr.register("mallory", FakeSocket())

    victim = FakeSocket()
    await mgr.register("victim", victim)

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
    await mgr.register("bob", stalled)
    await mgr.register("bob", healthy)

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
