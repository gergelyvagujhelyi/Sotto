"""Tests for call signaling endpoints."""

import base64

import pytest
from httpx import AsyncClient

from tests.auth_helpers import generate_ed25519_keypair, make_auth_header


async def _bootstrap_user(client: AsyncClient, label: str) -> dict:
    private_key, pub_key_b64 = generate_ed25519_keypair()
    resp = await client.post(
        "/v1/identity/bootstrap",
        json={
            "public_signing_key": pub_key_b64,
            "public_identity_key": base64.b64encode(f"id_{label}_padding!".encode()).decode(),
            "pqc_encapsulation_key": "",
        },
    )
    data = resp.json()
    data["private_key"] = private_key
    return data


def _auth(user: dict) -> dict:
    return make_auth_header(user["private_key"], user["user_id"])


@pytest.mark.asyncio
async def test_ice_servers_requires_auth(client: AsyncClient):
    """GET /v1/calls/ice-servers without auth should return 422."""
    resp = await client.get("/v1/calls/ice-servers")
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_ice_servers_with_auth(client: AsyncClient):
    """GET /v1/calls/ice-servers with valid auth returns STUN server."""
    user = await _bootstrap_user(client, "ice_user")
    resp = await client.get("/v1/calls/ice-servers", headers=_auth(user))
    assert resp.status_code == 200
    data = resp.json()
    assert len(data["ice_servers"]) >= 1
    assert "stun:" in data["ice_servers"][0]["urls"]


@pytest.mark.asyncio
async def test_relay_signal_requires_auth(client: AsyncClient):
    """POST /v1/calls/signal without auth should return 422."""
    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": "someone",
            "signal_type": "call_offer",
            "call_id": "abc",
            "encrypted_payload": "data",
        },
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_relay_signal_to_offline_user(client: AsyncClient):
    """Call signal to offline recipient should return delivered=false."""
    alice = await _bootstrap_user(client, "caller")
    bob = await _bootstrap_user(client, "callee")

    resp = await client.post(
        "/v1/calls/signal",
        json={
            "recipient_id": bob["user_id"],
            "signal_type": "call_offer",
            "call_id": "call123",
            "encrypted_payload": "encrypted_sdp",
        },
        headers=_auth(alice),
    )
    assert resp.status_code == 200
    assert resp.json()["delivered"] is False


@pytest.mark.asyncio
async def test_rest_call_offers_are_limited(client: AsyncClient):
    """Each offer to an offline user sends a push: REST offers have their own cap."""
    from app.services.call_rate_limiter import MAX_CALL_OFFERS_PER_MINUTE

    alice = await _bootstrap_user(client, "offer_spammer")
    bob = await _bootstrap_user(client, "offer_victim")

    def offer(i: int) -> dict:
        return {"recipient_id": bob["user_id"], "signal_type": "call_offer",
                "call_id": f"c{i}", "encrypted_payload": "sdp"}

    for i in range(MAX_CALL_OFFERS_PER_MINUTE):
        assert (await client.post("/v1/calls/signal", json=offer(i), headers=_auth(alice))).status_code == 200
    resp = await client.post("/v1/calls/signal", json=offer(99), headers=_auth(alice))
    assert resp.status_code == 429

    # Other signal types are not affected by the offer cap.
    ice = {**offer(0), "signal_type": "call_ice_candidate"}
    assert (await client.post("/v1/calls/signal", json=ice, headers=_auth(alice))).status_code == 200


def _clear_signal_buffer():
    from app.services.websocket_manager import ws_manager
    ws_manager._pending_call_signals.clear()
    ws_manager._pending_signal_bytes = 0
    ws_manager._buffered_by_sender.clear()


@pytest.mark.asyncio
async def test_signals_to_unknown_recipients_are_not_buffered(client: AsyncClient):
    from app.services.websocket_manager import ws_manager
    _clear_signal_buffer()
    alice = await _bootstrap_user(client, "alice")
    resp = await client.post("/v1/calls/signal", headers=_auth(alice), json={
        "recipient_id": "0123456789abcdef", "signal_type": "call_ringing",
        "call_id": "c1", "encrypted_payload": "x" * 1000,
    })
    assert resp.status_code == 200
    assert resp.json()["delivered"] is False
    assert ws_manager._pending_signal_bytes == 0


@pytest.mark.asyncio
async def test_one_sender_cannot_fill_the_signal_buffer(client: AsyncClient):
    """Junk from a few accounts must not crowd out a real offline call offer."""
    from app.services import websocket_manager as wsm
    from app.services.websocket_manager import ws_manager
    _clear_signal_buffer()
    attacker = await _bootstrap_user(client, "attacker")
    victims = [await _bootstrap_user(client, f"victim{i}") for i in range(10)]
    caller = await _bootstrap_user(client, "caller")
    callee = await _bootstrap_user(client, "callee")  # offline
    for i in range(60):
        await client.post("/v1/calls/signal", headers=_auth(attacker), json={
            "recipient_id": victims[i % 10]["user_id"], "signal_type": "call_ringing",
            "call_id": f"x{i}", "encrypted_payload": "A" * 60000,
        })
    assert ws_manager._pending_signal_bytes <= wsm._MAX_PENDING_SIGNAL_BYTES_PER_SENDER

    resp = await client.post("/v1/calls/signal", headers=_auth(caller), json={
        "recipient_id": callee["user_id"], "signal_type": "call_offer",
        "call_id": "real", "encrypted_payload": "B" * 10000,
    })
    assert resp.status_code == 200
    assert callee["user_id"] in ws_manager._pending_call_signals
    _clear_signal_buffer()


@pytest.mark.asyncio
async def test_signal_rate_limit_is_shared_by_rest_and_websocket(client: AsyncClient):
    from app.services import call_rate_limiter
    alice = await _bootstrap_user(client, "alice")
    bob = await _bootstrap_user(client, "bob")
    # Signals sent over the WebSocket count against the same budget.
    for _ in range(call_rate_limiter.MAX_CALL_SIGNALS_PER_MINUTE):
        assert await call_rate_limiter.allow_call_signal(alice["user_id"])
    resp = await client.post("/v1/calls/signal", headers=_auth(alice), json={
        "recipient_id": bob["user_id"], "signal_type": "call_ringing",
        "call_id": "c1", "encrypted_payload": "",
    })
    assert resp.status_code == 429
    _clear_signal_buffer()


@pytest.mark.asyncio
async def test_ice_servers_use_own_stun_with_relay(client: AsyncClient, monkeypatch):
    """With a relay, STUN is our own coturn, never a public server, next to TURN."""
    import hashlib
    import hmac

    import app.routers.calling as calling
    from app.main import app

    monkeypatch.setattr(calling, "TURN_SECRET", "test-secret")
    monkeypatch.setattr(app.state, "turn_relay_ip", "203.0.113.7", raising=False)
    user = await _bootstrap_user(client, "ice_relay")
    resp = await client.get("/v1/calls/ice-servers", headers=_auth(user))
    assert resp.status_code == 200
    servers = resp.json()["ice_servers"]
    assert [s["urls"] for s in servers] == ["stun:203.0.113.7:3478", "turn:203.0.113.7:3478?transport=udp"]
    assert "google" not in resp.text
    turn = servers[1]
    expiry, uid = turn["username"].split(":")
    assert uid == user["user_id"] and int(expiry) > 0
    expected = base64.b64encode(
        hmac.new(b"test-secret", turn["username"].encode(), hashlib.sha1).digest()
    ).decode()
    assert turn["credential"] == expected


@pytest.mark.asyncio
async def test_ice_restart_is_buffered_for_an_offline_peer_and_never_pushed(client: AsyncClient, monkeypatch):
    import json

    import app.routers.calling as calling
    from app.services.websocket_manager import ws_manager

    pushed = []

    async def fake_push(**kwargs):
        pushed.append(kwargs)

    monkeypatch.setattr(calling, "send_push_notification", fake_push)
    _clear_signal_buffer()
    alice = await _bootstrap_user(client, "restart_alice")
    bob = await _bootstrap_user(client, "restart_bob")
    # Bob could be pushed: only a call offer should ever wake him.
    await client.post("/v1/push/register", json={"token": "bob-token"}, headers=_auth(bob))

    resp = await client.post("/v1/calls/signal", headers=_auth(alice), json={
        "recipient_id": bob["user_id"], "signal_type": "call_ice_restart",
        "call_id": "c1", "encrypted_payload": "restart",
    })
    assert resp.status_code == 200
    assert resp.json()["delivered"] is False
    buffered = [json.loads(p)["type"] for _, p in ws_manager._pending_call_signals[bob["user_id"]]]
    assert buffered == ["call_ice_restart"]
    assert pushed == []
    _clear_signal_buffer()


@pytest.mark.asyncio
async def test_ice_restarts_count_toward_the_signal_limit_not_the_offer_limit(client: AsyncClient):
    from app.services import call_rate_limiter

    _clear_signal_buffer()
    alice = await _bootstrap_user(client, "restart_limit")
    bob = await _bootstrap_user(client, "restart_limit_bob")
    for i in range(call_rate_limiter.MAX_CALL_OFFERS_PER_MINUTE + 2):
        resp = await client.post("/v1/calls/signal", headers=_auth(alice), json={
            "recipient_id": bob["user_id"], "signal_type": "call_ice_restart",
            "call_id": "c1", "encrypted_payload": f"r{i}",
        })
        assert resp.status_code == 200
    _clear_signal_buffer()
