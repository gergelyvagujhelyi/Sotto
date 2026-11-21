"""Tests for WebSocket authentication and protocol.

WebSocket tests use the sync Starlette TestClient since httpx doesn't
support WebSocket connections. Each test bootstraps users via the same
sync client to ensure the database is shared.
"""

import base64
import json
import time

import pytest

from tests.auth_helpers import generate_ed25519_keypair
from tests.auth_helpers import fresh_ts_ms


def _make_token(private_key, user_id: str) -> str:
    ts = str(fresh_ts_ms())
    payload = f"{user_id}.{ts}"
    sig = private_key.sign(payload.encode("utf-8"))
    sig_b64 = base64.b64encode(sig).decode()
    return f"{payload}.{sig_b64}"


def _bootstrap_user_sync(tc, label: str) -> dict:
    """Bootstrap a user via the sync TestClient."""
    private_key, pub_key_b64 = generate_ed25519_keypair()
    resp = tc.post(
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


def test_ws_auth_success():
    """WebSocket should accept a valid signed token and return auth_ok."""
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        user = _bootstrap_user_sync(tc, "ws_user")
        token = _make_token(user["private_key"], user["user_id"])

        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": token}))
            resp = json.loads(ws.receive_text())
            assert resp["type"] == "auth_ok"

            # Ping/pong should work after auth
            ws.send_text(json.dumps({"type": "ping"}))
            pong = json.loads(ws.receive_text())
            assert pong["type"] == "pong"


def test_ws_auth_missing_token():
    """WebSocket should reject auth message without a token."""
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth"}))
            resp = json.loads(ws.receive_text())
            assert resp["type"] == "error"


def test_ws_auth_bad_token():
    """WebSocket should reject an invalid/malformed token."""
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": "bogus"}))
            resp = json.loads(ws.receive_text())
            assert resp["type"] == "error"


def test_ws_auth_unknown_user():
    """WebSocket should reject a token for a non-existent user."""
    from starlette.testclient import TestClient
    from app.main import app

    private_key, _ = generate_ed25519_keypair()
    token = _make_token(private_key, "nonexistent_user_id")

    with TestClient(app) as tc:
        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": token}))
            resp = json.loads(ws.receive_text())
            assert resp["type"] == "error"


def test_ws_auth_wrong_key():
    """WebSocket should reject a token signed with the wrong key."""
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        user = _bootstrap_user_sync(tc, "ws_wrong_key")
        wrong_key, _ = generate_ed25519_keypair()
        token = _make_token(wrong_key, user["user_id"])

        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": token}))
            resp = json.loads(ws.receive_text())
            assert resp["type"] == "error"


def test_ws_auth_ok_names_the_relay_version_and_whether_its_app_is_out():
    from starlette.testclient import TestClient
    from app import config
    from app.main import app

    with TestClient(app) as tc:
        user = _bootstrap_user_sync(tc, "ws_version")
        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": _make_token(user["private_key"], user["user_id"])}))
            resp = json.loads(ws.receive_text())
    assert resp == {"type": "auth_ok", "relay_version": config.RELAY_VERSION, "app_ready": False}


def test_signals_sent_while_the_socket_is_still_logging_in_come_after_auth_ok(monkeypatch):
    """The socket is registered before the key re-check, so a frame sent meanwhile
    went out before auth_ok, and the app took it for a failed login and dropped the
    connection. Now it gets nothing until auth_ok: call signals are buffered and
    delivered right after it."""
    from starlette.testclient import TestClient
    from app.main import app
    from app.services.websocket_manager import ws_manager

    real_register = ws_manager.register
    sent_meanwhile = []

    async def register_then_signal(user_id, websocket):
        await real_register(user_id, websocket)
        # What other requests can do during the re-check query:
        sent_meanwhile.append(await ws_manager.notify(user_id, "receipts"))
        sent_meanwhile.append(await ws_manager.relay_call_signal("caller", user_id, "call_hangup", "c1", "enc"))

    monkeypatch.setattr(ws_manager, "register", register_then_signal)
    with TestClient(app) as tc:
        user = _bootstrap_user_sync(tc, "logging_in")
        with tc.websocket_connect("/v1/ws") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": _make_token(user["private_key"], user["user_id"])}))
            assert json.loads(ws.receive_text())["type"] == "auth_ok"
            hangup = json.loads(ws.receive_text())
    assert hangup["type"] == "call_hangup" and hangup["call_id"] == "c1"
    assert sent_meanwhile == [False, False]  # not delivered: a message would get its push
