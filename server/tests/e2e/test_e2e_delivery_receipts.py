"""Delivery receipts: the relay tells a message's author when the recipient fetched it."""

import base64

import pytest
from httpx import AsyncClient

from tests.e2e.conftest import User, bootstrap_user


async def send(client: AsyncClient, sender: User, recipient: User, client_message_id: str) -> str:
    resp = await client.post(
        "/v1/messages/send",
        json={
            "recipient_id": recipient.user_id,
            "encrypted_body": base64.b64encode(b"ciphertext").decode(),
            "nonce": base64.b64encode(b"nonce_12bytes").decode(),
            "signature": base64.b64encode(b"sig").decode(),
            "client_message_id": client_message_id,
        },
        headers=sender.auth_header(),
    )
    assert resp.status_code == 200, resp.text
    return resp.json()["message_id"]


async def ack(client: AsyncClient, user: User, message_ids: list[str]) -> None:
    resp = await client.post("/v1/messages/ack", json={"message_ids": message_ids}, headers=user.auth_header())
    assert resp.status_code == 200


async def delivered(client: AsyncClient, user: User) -> list[dict]:
    resp = await client.get("/v1/receipts/delivered", headers=user.auth_header())
    assert resp.status_code == 200
    return resp.json()


@pytest.mark.asyncio
async def test_ack_tells_the_author(client: AsyncClient, alice: User, bob: User):
    m1 = await send(client, alice, bob, "local-1")
    m2 = await send(client, alice, bob, "local-2")
    assert await delivered(client, alice) == []

    await ack(client, bob, [m1, m2])

    receipts = await delivered(client, alice)
    assert len(receipts) == 1
    assert receipts[0]["sender_id"] == bob.user_id
    assert sorted(receipts[0]["message_ids"]) == sorted([m1, m2])
    assert receipts[0]["timestamp"] > 0
    # Nothing for the recipient, and nothing among read receipts (older apps' queue).
    assert await delivered(client, bob) == []
    assert (await client.get("/v1/receipts/pending", headers=alice.auth_header())).json() == []


@pytest.mark.asyncio
async def test_each_author_gets_only_their_own(client: AsyncClient, alice: User, bob: User):
    carol = await bootstrap_user(client, label="carol_delivery")
    from_alice = await send(client, alice, bob, "a-1")
    from_carol = await send(client, carol, bob, "c-1")

    await ack(client, bob, [from_alice, from_carol])

    assert [r["message_ids"] for r in await delivered(client, alice)] == [[from_alice]]
    assert [r["message_ids"] for r in await delivered(client, carol)] == [[from_carol]]


@pytest.mark.asyncio
async def test_acking_unknown_or_someone_elses_messages_tells_nobody(client: AsyncClient, alice: User, bob: User):
    mallory = await bootstrap_user(client, label="mallory_delivery")
    to_bob = await send(client, alice, bob, "x-1")

    await ack(client, mallory, [to_bob, "no-such-message"])

    assert await delivered(client, alice) == []
    # Bob's message is still waiting for him.
    pending = (await client.get("/v1/messages/pending", headers=bob.auth_header())).json()
    assert to_bob in [m["message_id"] for m in pending]


@pytest.mark.asyncio
async def test_fetch_then_ack_removes_and_only_the_author_can(client: AsyncClient, alice: User, bob: User):
    await ack(client, bob, [await send(client, alice, bob, "r-1")])
    receipt_id = (await delivered(client, alice))[0]["id"]

    await client.post("/v1/receipts/delivered/ack", json={"ids": [receipt_id]}, headers=bob.auth_header())
    assert len(await delivered(client, alice)) == 1
    await client.post("/v1/receipts/delivered/ack", json={"ids": [receipt_id]}, headers=alice.auth_header())
    assert await delivered(client, alice) == []


@pytest.mark.asyncio
async def test_removed_with_either_account(client: AsyncClient, alice: User):
    dave = await bootstrap_user(client, label="dave_delivery")
    await ack(client, dave, [await send(client, alice, dave, "d-1")])
    assert len(await delivered(client, alice)) == 1

    assert (await client.delete("/v1/identity/me", headers=dave.auth_header())).status_code == 200
    assert await delivered(client, alice) == []


@pytest.mark.asyncio
async def test_queue_is_bounded(client: AsyncClient, alice: User, bob: User, monkeypatch):
    import app.routers.receipts as receipts

    monkeypatch.setattr(receipts, "MAX_PENDING_DELIVERY_RECEIPTS_PER_USER", 1)
    await ack(client, bob, [await send(client, alice, bob, "q-1")])
    await ack(client, bob, [await send(client, alice, bob, "q-2")])

    assert len(await delivered(client, alice)) == 1
