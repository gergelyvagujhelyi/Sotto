"""A socket nobody reads: Android pauses background apps without a foreground service,
and their sockets stay open. Calls and messages must still reach them by push."""

import base64
import json
import time
from dataclasses import dataclass

import pytest

from tests.e2e.conftest import Ed25519PrivateKey, serialization


@dataclass
class Phone:
    user_id: str
    key: Ed25519PrivateKey

    def token(self) -> str:
        payload = f"{self.user_id}.{int(time.time() * 1000)}"
        return f"{payload}.{base64.b64encode(self.key.sign(payload.encode())).decode()}"

    def auth(self) -> dict:
        return {"Authorization": f"Bearer {self.token()}"}


def boot(tc, label: str, with_push: bool = False) -> Phone:
    key = Ed25519PrivateKey.generate()
    pub = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    resp = tc.post("/v1/identity/bootstrap", json={
        "public_signing_key": base64.b64encode(pub).decode(),
        "public_identity_key": base64.b64encode(f"id_{label}_{time.monotonic_ns()}".encode()).decode(),
        "pqc_encapsulation_key": "",
    })
    phone = Phone(resp.json()["user_id"], key)
    if with_push:
        assert tc.post("/v1/push/register", json={"fid": f"fid_{label}"}, headers=phone.auth()).status_code == 200
    return phone


def connect(tc, phone: Phone):
    ws = tc.websocket_connect("/v1/ws")
    sock = ws.__enter__()
    sock.send_text(json.dumps({"type": "auth", "token": phone.token()}))
    assert json.loads(sock.receive_text())["type"] == "auth_ok"
    return ws, sock


def signal(tc, sender: Phone, recipient: Phone, signal_type: str, call_id: str, payload: str = "") -> dict:
    resp = tc.post("/v1/calls/signal", json={
        "recipient_id": recipient.user_id,
        "signal_type": signal_type,
        "call_id": call_id,
        "encrypted_payload": payload,
    }, headers=sender.auth())
    assert resp.status_code == 200, resp.text
    return resp.json()


def still_open(sock) -> bool:
    sock.send_text(json.dumps({"type": "ping"}))
    return json.loads(sock.receive_text())["type"] == "pong"


@pytest.fixture
def pushes(monkeypatch):
    import app.routers.calling as calling
    import app.routers.messages as messages
    import app.services.websocket_manager as websocket_manager

    monkeypatch.setattr(websocket_manager, "OFFER_CONFIRM_SECONDS", 0.2)
    monkeypatch.setattr(messages, "MESSAGE_FETCH_CONFIRM_SECONDS", 0.2)
    sent: list[dict] = []

    async def fake_push(**kwargs):
        sent.append(kwargs)

    monkeypatch.setattr(calling, "send_push_notification", fake_push)
    monkeypatch.setattr(messages, "send_push_notification", fake_push)
    return sent


def test_an_unanswered_offer_reaches_the_paused_app_by_push(pushes):
    from starlette.testclient import TestClient
    from starlette.websockets import WebSocketDisconnect
    from app.main import app

    with TestClient(app) as tc:
        alice, bob = boot(tc, "unread_alice"), boot(tc, "unread_bob", with_push=True)
        ws, sock = connect(tc, bob)
        assert signal(tc, alice, bob, "call_offer", "unread_1", "offer_sdp")["delivered"] is True
        signal(tc, alice, bob, "call_ice_candidate", "unread_1", "candidate_1")

        # Bob's app is paused and never answers: the relay gives up on that socket...
        time.sleep(0.6)
        with pytest.raises(WebSocketDisconnect) as closed:
            while True:
                sock.receive_text()
        assert closed.value.code == 4011
        ws.__exit__(None, None, None)
        assert [p["recipient_id"] for p in pushes] == [bob.user_id]

        # ...and the app the push woke gets the whole call on its next connection.
        ws, sock = connect(tc, bob)
        got = [json.loads(sock.receive_text()) for _ in range(2)]
        assert [(m["type"], m["encrypted_payload"]) for m in got] == [
            ("call_offer", "offer_sdp"), ("call_ice_candidate", "candidate_1"),
        ]
        ws.__exit__(None, None, None)


def test_an_answered_offer_leaves_the_socket_alone(pushes):
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        alice, bob = boot(tc, "answered_alice"), boot(tc, "answered_bob", with_push=True)
        ws, sock = connect(tc, bob)
        signal(tc, alice, bob, "call_offer", "answered_1", "offer_sdp")
        assert json.loads(sock.receive_text())["type"] == "call_offer"
        signal(tc, bob, alice, "call_ringing", "answered_1")

        time.sleep(0.6)
        assert still_open(sock)
        assert pushes == []
        ws.__exit__(None, None, None)


def test_a_call_hung_up_before_any_answer_wakes_nobody(pushes):
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        alice, bob = boot(tc, "hungup_alice"), boot(tc, "hungup_bob", with_push=True)
        ws, sock = connect(tc, bob)
        signal(tc, alice, bob, "call_offer", "hungup_1", "offer_sdp")
        signal(tc, alice, bob, "call_hangup", "hungup_1")
        assert [json.loads(sock.receive_text())["type"] for _ in range(2)] == ["call_offer", "call_hangup"]

        time.sleep(0.6)
        assert still_open(sock)
        assert pushes == []
        ws.__exit__(None, None, None)


def send_message(tc, sender: Phone, recipient: Phone, client_id: str) -> str:
    resp = tc.post("/v1/messages/send", json={
        "recipient_id": recipient.user_id,
        "encrypted_body": base64.b64encode(b"ciphertext").decode(),
        "nonce": base64.b64encode(b"nonce_12bytes").decode(),
        "signature": base64.b64encode(b"sig").decode(),
        "client_message_id": client_id,
    }, headers=sender.auth())
    assert resp.status_code == 200, resp.text
    return resp.json()["message_id"]


def test_an_unfetched_message_gets_the_push_a_fetched_one_does_not(pushes):
    from starlette.testclient import TestClient
    from app.main import app

    with TestClient(app) as tc:
        alice, bob = boot(tc, "msg_alice"), boot(tc, "msg_bob", with_push=True)
        ws, sock = connect(tc, bob)

        send_message(tc, alice, bob, "m-1")
        sock.receive_text()  # the new-message notification, which nobody acts on
        time.sleep(0.6)
        assert [p["recipient_id"] for p in pushes] == [bob.user_id]

        pushes.clear()
        sock_msg = send_message(tc, alice, bob, "m-2")
        sock.receive_text()
        pending = tc.get("/v1/messages/pending", headers=bob.auth()).json()
        tc.post("/v1/messages/ack", json={"message_ids": [m["message_id"] for m in pending]}, headers=bob.auth())
        assert sock_msg in [m["message_id"] for m in pending]
        time.sleep(0.6)
        assert pushes == []
        ws.__exit__(None, None, None)


def test_a_message_into_a_dead_socket_pushes_at_once(pushes, monkeypatch):
    """The notification went into a socket that turns out dead (the phone's kernel
    answers with a reset): no point waiting for the fetch deadline."""
    from starlette.testclient import TestClient
    from app.main import app
    import app.routers.messages as messages

    monkeypatch.setattr(messages, "MESSAGE_FETCH_CONFIRM_SECONDS", 5.0)
    with TestClient(app) as tc:
        alice, bob = boot(tc, "dead_msg_alice"), boot(tc, "dead_msg_bob", with_push=True)
        ws, sock = connect(tc, bob)
        send_message(tc, alice, bob, "d-1")
        sock.receive_text()
        ws.__exit__(None, None, None)  # the socket dies right after the notification
        time.sleep(0.8)
        assert [p["recipient_id"] for p in pushes] == [bob.user_id]


def test_an_offer_into_a_dead_socket_is_kept_and_pushed_at_once(pushes, monkeypatch):
    from starlette.testclient import TestClient
    from app.main import app
    import app.services.websocket_manager as websocket_manager

    monkeypatch.setattr(websocket_manager, "OFFER_CONFIRM_SECONDS", 5.0)
    with TestClient(app) as tc:
        alice, bob = boot(tc, "dead_call_alice"), boot(tc, "dead_call_bob", with_push=True)
        ws, sock = connect(tc, bob)
        signal(tc, alice, bob, "call_offer", "dead_1", "offer_sdp")
        sock.receive_text()
        ws.__exit__(None, None, None)
        time.sleep(0.8)
        assert [p["recipient_id"] for p in pushes] == [bob.user_id]

        ws, sock = connect(tc, bob)
        offer = json.loads(sock.receive_text())
        assert (offer["type"], offer["encrypted_payload"]) == ("call_offer", "offer_sdp")
        ws.__exit__(None, None, None)
