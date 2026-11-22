"""Encrypted attachments: the relay signs URLs, checks sizes and cleans up (ADR-29)."""

import datetime as dt
from urllib.parse import parse_qs, urlsplit

import pytest
from httpx import AsyncClient

from app import config
from app.routers import attachments
from app.services.object_store import ObjectStore
from tests.test_messages import _auth, _bootstrap_user


def test_presigned_url_matches_aws_published_example():
    # "Example: presigned URL" in the AWS docs for Signature Version 4 query auth.
    store = ObjectStore(
        endpoint="https://s3.amazonaws.com",
        bucket="examplebucket",
        access_key_id="AKIAIOSFODNN7EXAMPLE",
        secret="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region="us-east-1",
    )
    url = store.presign("GET", "test.txt", 86400, now=dt.datetime(2013, 5, 24, tzinfo=dt.timezone.utc))
    assert url.startswith("https://examplebucket.s3.amazonaws.com/test.txt?")
    assert parse_qs(urlsplit(url).query)["X-Amz-Signature"] == [
        "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
    ]


def test_a_signed_size_is_part_of_the_signature():
    store = ObjectStore("https://t3.storage.dev", "b", "id", "secret")
    when = dt.datetime(2026, 11, 23, tzinfo=dt.timezone.utc)
    a = store.presign("PUT", "a/x", 60, {"content-length": 10}, now=when)
    b = store.presign("PUT", "a/x", 60, {"content-length": 11}, now=when)
    assert "X-Amz-SignedHeaders=content-length%3Bhost" in a
    assert a != b


class FakeStore:
    """Stands in for the bucket: remembers objects by key, signs nothing real."""

    def __init__(self):
        self.objects: dict[str, int] = {}
        self.deleted: list[str] = []

    def presign(self, method, key, expires, signed_headers=None, now=None):
        size = (signed_headers or {}).get("content-length", "")
        return f"https://bucket.example/{key}?method={method}&size={size}"

    async def size_of(self, client, key):
        return self.objects.get(key)

    async def delete(self, client, key):
        self.objects.pop(key, None)
        self.deleted.append(key)


@pytest.fixture
def store(monkeypatch):
    fake = FakeStore()
    monkeypatch.setattr(attachments, "configured_store", lambda: fake)
    return fake


async def _pair(client):
    return await _bootstrap_user(client, "alice"), await _bootstrap_user(client, "bob")


@pytest.mark.asyncio
async def test_upload_download_delete(client: AsyncClient, store: FakeStore):
    alice, bob = await _pair(client)
    r = await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 1000}, headers=_auth(alice))
    assert r.status_code == 200, r.text
    att = r.json()
    assert "PUT" in att["upload_url"] and "size=1000" in att["upload_url"]

    # Not there yet: the recipient can't fetch it, and completing fails.
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 409
    assert (await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(alice))).status_code == 409

    store.objects[f"a/{att['id']}"] = 1000
    assert (await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(alice))).status_code == 200

    # Only the recipient gets a download URL.
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(alice))).status_code == 404
    r = await client.get(f"/v1/attachments/{att['id']}", headers=_auth(bob))
    assert r.status_code == 200 and "GET" in r.json()["download_url"] and r.json()["size"] == 1000

    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 200
    assert store.deleted == [f"a/{att['id']}"]
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 404


@pytest.mark.asyncio
async def test_a_different_stored_size_is_thrown_away(client: AsyncClient, store: FakeStore):
    alice, bob = await _pair(client)
    att = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 1000}, headers=_auth(alice))).json()
    store.objects[f"a/{att['id']}"] = 5_000_000
    r = await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(alice))
    assert r.status_code == 409 and r.json()["detail"] == "attachment_size_mismatch"
    assert f"a/{att['id']}" not in store.objects
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 404


@pytest.mark.asyncio
async def test_others_cannot_touch_an_attachment(client: AsyncClient, store: FakeStore):
    alice, bob = await _pair(client)
    eve = await _bootstrap_user(client, "eve")
    att = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 10}, headers=_auth(alice))).json()
    store.objects[f"a/{att['id']}"] = 10
    assert (await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(eve))).status_code == 404
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(eve))).status_code == 404
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(eve))).status_code == 404
    assert (await client.get("/v1/attachments/../../x", headers=_auth(bob))).status_code == 404


@pytest.mark.asyncio
async def test_limits(client: AsyncClient, store: FakeStore, monkeypatch):
    alice, bob = await _pair(client)
    monkeypatch.setattr(config, "ATTACHMENT_MAX_BYTES", 100)
    monkeypatch.setattr(config, "ATTACHMENT_MONTHLY_BYTES_PER_USER", 150)
    big = await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 101}, headers=_auth(alice))
    assert big.status_code == 413
    assert (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 100}, headers=_auth(alice))).status_code == 200
    # The allowance counts what was reserved, even once the file is gone.
    over = await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 51}, headers=_auth(alice))
    assert over.status_code == 429 and over.json()["detail"] == "attachment_monthly_limit"
    # Bob has his own allowance; the bucket's total is the next limit.
    monkeypatch.setattr(config, "ATTACHMENT_STORE_LIMIT_BYTES", 120)
    full = await client.post("/v1/attachments", json={"recipient_id": alice["user_id"], "size": 30}, headers=_auth(bob))
    assert full.status_code == 507
    assert (await client.post("/v1/attachments", json={"recipient_id": "nobody", "size": 1}, headers=_auth(bob))).status_code == 404
    assert (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 1}, headers=_auth(bob))).status_code == 400
    assert (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 0}, headers=_auth(alice))).status_code == 422


@pytest.mark.asyncio
async def test_without_a_store_there_are_no_attachments(client: AsyncClient, monkeypatch):
    alice, bob = await _pair(client)
    monkeypatch.setattr(attachments, "configured_store", lambda: None)
    r = await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 10}, headers=_auth(alice))
    assert r.status_code == 503
    health = (await client.get("/health")).json()
    assert health["attachment_max_bytes"] == 0


@pytest.mark.asyncio
async def test_purge_removes_expired_and_unfinished(client: AsyncClient, store: FakeStore):
    from app.models.db import Attachment
    from tests.conftest import test_session

    alice, bob = await _pair(client)
    ids = []
    for _ in range(3):
        att = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 10}, headers=_auth(alice))).json()
        store.objects[f"a/{att['id']}"] = 10
        ids.append(att["id"])
    await client.post(f"/v1/attachments/{ids[0]}/complete", headers=_auth(alice))
    await client.post(f"/v1/attachments/{ids[1]}/complete", headers=_auth(alice))
    async with test_session() as db:
        old = attachments._now() - dt.timedelta(days=2)
        (await db.get(Attachment, ids[1])).expires_at = old  # uncollected too long
        (await db.get(Attachment, ids[2])).created_at = old  # never completed
        await db.commit()
        assert await attachments.purge_attachments(db) == 2
        assert await db.get(Attachment, ids[0]) is not None
    assert sorted(store.deleted) == sorted([f"a/{ids[1]}", f"a/{ids[2]}"])
