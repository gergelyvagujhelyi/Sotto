"""Retired share codes and the queue allowance of freshly created accounts."""

import base64
from datetime import datetime, timedelta, timezone

import pytest
from httpx import AsyncClient
from sqlalchemy import update

from tests.e2e.conftest import User, bootstrap_user
from tests.e2e.test_e2e_key_rotation import _ed25519_proof, _new_ed25519


async def _bundle(client: AsyncClient, user_id: str) -> dict:
    keys = (await client.get(f"/v1/identity/{user_id}/keys")).json()
    return {
        "public_signing_key": keys["public_signing_key"],
        "public_identity_key": keys["public_identity_key"],
        "pqc_encapsulation_key": keys.get("pqc_encapsulation_key", ""),
        "pqc_signing_key": keys.get("pqc_signing_key", ""),
    }


@pytest.mark.asyncio
async def test_rotated_away_keys_cannot_reclaim_the_old_share_code(client: AsyncClient, alice: User):
    old_bundle = await _bundle(client, alice.user_id)
    new_pk, new_pub_b64 = _new_ed25519()
    resp = await client.put(
        "/v1/identity/me/keys",
        json=alice.endorse({
            "public_signing_key": new_pub_b64,
            "public_identity_key": base64.b64encode(b"rotated_identity_key").decode(),
            "new_key_proof": _ed25519_proof(new_pk, new_pub_b64),
        }),
        headers=alice.auth_header(),
    )
    assert resp.status_code == 200

    squat = await client.post("/v1/identity/bootstrap", json=old_bundle)
    assert squat.status_code == 409
    assert (await client.get(f"/v1/identity/resolve/{alice.share_code}")).status_code == 404


@pytest.mark.asyncio
async def test_deleted_keys_cannot_be_registered_again(client: AsyncClient):
    bob = await bootstrap_user(client, label="retire_bob")
    bundle = await _bundle(client, bob.user_id)
    assert (await client.delete("/v1/identity/me", headers=bob.auth_header())).status_code == 200

    again = await client.post("/v1/identity/bootstrap", json=bundle)
    assert again.status_code == 409


async def _send(client: AsyncClient, sender: User, recipient: User, body: str = "x") -> int:
    resp = await client.post(
        "/v1/messages/send",
        json={"recipient_id": recipient.user_id, "encrypted_body": body, "nonce": "n"},
        headers=sender.auth_header(),
    )
    return resp.status_code


async def _age(user: User, hours: int) -> None:
    from app.models.db import Identity
    from tests.conftest import test_session

    async with test_session() as db:
        await db.execute(
            update(Identity)
            .where(Identity.user_id == user.user_id)
            .values(created_at=datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(hours=hours))
        )
        await db.commit()


@pytest.mark.asyncio
async def test_new_accounts_have_a_small_allowance_per_recipient(client: AsyncClient, monkeypatch):
    import app.routers.messages as messages

    monkeypatch.setattr(messages, "NEW_ACCOUNT_PENDING_PER_RECIPIENT", 3)
    victim = await bootstrap_user(client, label="victim")
    fresh = await bootstrap_user(client, label="fresh")
    aged = await bootstrap_user(client, label="aged")
    await _age(aged, 48)

    assert [await _send(client, fresh, victim) for _ in range(4)] == [200, 200, 200, 429]
    # An established account is not held to the new-account allowance.
    assert [await _send(client, aged, victim) for _ in range(4)] == [200] * 4


@pytest.mark.asyncio
async def test_new_accounts_together_get_only_part_of_a_queue(client: AsyncClient, monkeypatch):
    import app.routers.messages as messages

    monkeypatch.setattr(messages, "NEW_ACCOUNT_PENDING_PER_RECIPIENT", 3)
    monkeypatch.setattr(messages, "NEW_ACCOUNTS_PENDING_TOTAL", 4)
    victim = await bootstrap_user(client, label="victim2")
    sybil1 = await bootstrap_user(client, label="sybil1")
    sybil2 = await bootstrap_user(client, label="sybil2")
    friend = await bootstrap_user(client, label="friend")
    await _age(friend, 48)

    assert [await _send(client, sybil1, victim) for _ in range(3)] == [200] * 3
    assert [await _send(client, sybil2, victim) for _ in range(2)] == [200, 429]
    # Established contacts can still reach the victim.
    assert await _send(client, friend, victim) == 200


@pytest.mark.asyncio
async def test_deleting_the_sender_does_not_free_the_young_allowance(client: AsyncClient, monkeypatch):
    import app.routers.messages as messages

    monkeypatch.setattr(messages, "NEW_ACCOUNT_PENDING_PER_RECIPIENT", 3)
    monkeypatch.setattr(messages, "NEW_ACCOUNTS_PENDING_TOTAL", 4)
    victim = await bootstrap_user(client, label="victim3")
    first = await bootstrap_user(client, label="sybil_a")
    assert [await _send(client, first, victim) for _ in range(3)] == [200] * 3
    # Its sent messages stay queued after it deletes itself...
    assert (await client.delete("/v1/identity/me", headers=first.auth_header())).status_code == 200
    second = await bootstrap_user(client, label="sybil_b")
    # ...and still count against the young-account total.
    assert [await _send(client, second, victim) for _ in range(2)] == [200, 429]
