"""Read receipts are relayed separately from messages."""

import pytest
from httpx import AsyncClient

from tests.e2e.conftest import User, bootstrap_user


@pytest.mark.asyncio
async def test_receipt_round_trip_and_ack(client: AsyncClient, alice: User, bob: User):
    resp = await client.post(
        "/v1/receipts",
        json={"recipient_id": alice.user_id, "message_ids": ["m1", "m2"], "signature": "sig"},
        headers=bob.auth_header(),
    )
    assert resp.status_code == 202

    # Receipts never show up as messages (old clients would choke on them).
    assert (await client.get("/v1/messages/pending", headers=alice.auth_header())).json() == []

    pending = (await client.get("/v1/receipts/pending", headers=alice.auth_header())).json()
    assert len(pending) == 1
    assert pending[0]["sender_id"] == bob.user_id
    assert pending[0]["message_ids"] == ["m1", "m2"]
    assert pending[0]["signature"] == "sig"

    # Only the recipient can ack.
    await client.post("/v1/receipts/ack", json={"ids": [pending[0]["id"]]}, headers=bob.auth_header())
    assert len((await client.get("/v1/receipts/pending", headers=alice.auth_header())).json()) == 1
    await client.post("/v1/receipts/ack", json={"ids": [pending[0]["id"]]}, headers=alice.auth_header())
    assert (await client.get("/v1/receipts/pending", headers=alice.auth_header())).json() == []


@pytest.mark.asyncio
async def test_receipt_validation(client: AsyncClient, alice: User):
    def post(body: dict):
        return client.post("/v1/receipts", json=body, headers=alice.auth_header())

    assert (await post({"recipient_id": alice.user_id, "message_ids": ["m"], "signature": "s"})).status_code == 400
    assert (await post({"recipient_id": "nobody00000000ab", "message_ids": ["m"], "signature": "s"})).status_code == 404
    assert (await post({"recipient_id": "x", "message_ids": [], "signature": "s"})).status_code == 422
    assert (await post({"recipient_id": "x", "message_ids": ["m"] * 101, "signature": "s"})).status_code == 422


@pytest.mark.asyncio
async def test_receipts_are_removed_with_the_account(client: AsyncClient, alice: User):
    carol = await bootstrap_user(client, label="carol_receipts")
    await client.post(
        "/v1/receipts",
        json={"recipient_id": alice.user_id, "message_ids": ["m"], "signature": "s"},
        headers=carol.auth_header(),
    )
    assert (await client.delete("/v1/identity/me", headers=carol.auth_header())).status_code == 200
    assert (await client.get("/v1/receipts/pending", headers=alice.auth_header())).json() == []


@pytest.mark.asyncio
async def test_one_sender_cannot_fill_a_receipt_queue(client: AsyncClient, alice: User, bob: User, monkeypatch):
    import app.routers.receipts as receipts

    monkeypatch.setattr(receipts, "MAX_PENDING_RECEIPTS_PER_SENDER", 2)
    mallory = await bootstrap_user(client, label="mallory_receipts")
    for _ in range(4):
        await client.post(
            "/v1/receipts",
            json={"recipient_id": alice.user_id, "message_ids": ["junk"], "signature": "junk"},
            headers=mallory.auth_header(),
        )
    await client.post(
        "/v1/receipts",
        json={"recipient_id": alice.user_id, "message_ids": ["m1"], "signature": "sig"},
        headers=bob.auth_header(),
    )
    pending = (await client.get("/v1/receipts/pending", headers=alice.auth_header())).json()
    assert sorted(r["sender_id"] for r in pending) == sorted([mallory.user_id] * 2 + [bob.user_id])
