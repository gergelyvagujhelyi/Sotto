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
        self.multipart: dict[str, list] = {}
        self.joined: dict[str, list] = {}
        self.aborted: list[str] = []
        self.pending_size: dict[str, int] = {}

    def presign(self, method, key, expires, signed_headers=None, now=None):
        size = (signed_headers or {}).get("content-length", "")
        return f"https://bucket.example/{key}?method={method}&size={size}"

    async def size_of(self, client, key):
        return self.objects.get(key)

    async def delete(self, client, key):
        self.objects.pop(key, None)
        self.deleted.append(key)

    async def create_multipart(self, client, key):
        self.multipart[key] = []
        return f"up-{key}"

    def presign_part(self, key, upload_id, number, size, expires):
        return f"https://bucket.example/{key}?part={number}&size={size}&upload={upload_id}"

    async def complete_multipart(self, client, key, upload_id, etags):
        self.joined[key] = list(etags)
        self.objects[key] = self.multipart.pop(key, None) or self.pending_size.get(key, 0)

    async def abort_multipart(self, client, key, upload_id):
        self.aborted.append(key)


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


@pytest.mark.asyncio
async def test_a_large_file_goes_up_in_parts_and_can_resume(client: AsyncClient, store: FakeStore, monkeypatch):
    monkeypatch.setattr(config, "ATTACHMENT_PART_BYTES", 100)
    alice, bob = await _pair(client)
    att = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 250}, headers=_auth(alice))).json()
    assert att["upload_url"] == "" and att["part_size"] == 100
    sizes = [parse_qs(urlsplit(u).query)["size"][0] for u in att["part_urls"]]
    assert sizes == ["100", "100", "50"]
    # Resuming: fresh URLs for the same upload, only for the sender.
    again = await client.post(f"/v1/attachments/{att['id']}/parts", headers=_auth(alice))
    assert again.status_code == 200 and len(again.json()["part_urls"]) == 3
    assert (await client.post(f"/v1/attachments/{att['id']}/parts", headers=_auth(bob))).status_code == 404
    # Completing needs every part's ETag; then the size is checked as usual.
    r = await client.post(f"/v1/attachments/{att['id']}/complete", json={"etags": ["a", "b"]}, headers=_auth(alice))
    assert r.status_code == 400
    store.pending_size[f"a/{att['id']}"] = 250
    r = await client.post(f"/v1/attachments/{att['id']}/complete", json={"etags": ["a", "b", "c"]}, headers=_auth(alice))
    assert r.status_code == 200, r.text
    assert store.joined[f"a/{att['id']}"] == ["a", "b", "c"]
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 200


@pytest.mark.asyncio
async def test_an_abandoned_multipart_upload_is_aborted(client: AsyncClient, store: FakeStore, monkeypatch):
    monkeypatch.setattr(config, "ATTACHMENT_PART_BYTES", 100)
    alice, bob = await _pair(client)
    att = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 250}, headers=_auth(alice))).json()
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(alice))).status_code == 200
    assert store.aborted == [f"a/{att['id']}"]


def test_part_urls_sign_their_part_number_upload_and_size():
    store = ObjectStore("https://t3.storage.dev", "b", "id", "secret")
    url = store.presign_part("a/x", "UP 1", 2, 100, 60)
    q = parse_qs(urlsplit(url).query)
    assert q["partNumber"] == ["2"] and q["uploadId"] == ["UP 1"]
    assert q["X-Amz-SignedHeaders"] == ["content-length;host"]


@pytest.mark.asyncio
async def test_completing_again_after_the_parts_were_joined_still_works(client: AsyncClient, store: FakeStore, monkeypatch):
    # The parts were joined, but the size check after it failed: the retry mustn't join again.
    monkeypatch.setattr(config, "ATTACHMENT_PART_BYTES", 100)
    alice, bob = await _pair(client)
    att = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 250}, headers=_auth(alice))).json()
    store.objects[f"a/{att['id']}"] = 250  # already joined in the store

    async def refuse(*args, **kwargs):
        raise AssertionError("joined twice")
    monkeypatch.setattr(store, "complete_multipart", refuse)
    r = await client.post(f"/v1/attachments/{att['id']}/complete", json={"etags": ["a", "b", "c"]}, headers=_auth(alice))
    assert r.status_code == 200, r.text
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 200


@pytest.mark.asyncio
async def test_health_reports_attachments_only_with_a_whole_store_configuration(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(config, "S3_ENDPOINT", "https://t3.storage.dev")
    monkeypatch.setattr(config, "S3_BUCKET", "b")
    monkeypatch.setattr(config, "S3_ACCESS_KEY_ID", "id")
    monkeypatch.setattr(config, "S3_SECRET", "")
    assert (await client.get("/health")).json()["attachment_max_bytes"] == 0
    monkeypatch.setattr(config, "S3_SECRET", "s")
    assert (await client.get("/health")).json()["attachment_max_bytes"] == config.ATTACHMENT_MAX_BYTES


async def _group(client, n: int):
    from app.services.ip_rate_limiter import _buckets

    users = []
    for i in range(n):
        _buckets.clear()
        users.append(await _bootstrap_user(client, f"g{i}"))
    return users


@pytest.mark.asyncio
async def test_a_group_file_goes_up_once_for_every_member(client: AsyncClient, store: FakeStore):
    alice, bob, carol, dave = await _group(client, 4)
    eve = await _bootstrap_user(client, "eve_g")
    r = await client.post("/v1/attachments", json={
        "recipient_ids": [bob["user_id"], carol["user_id"], dave["user_id"]], "size": 500,
    }, headers=_auth(alice))
    assert r.status_code == 200, r.text
    att = r.json()
    store.objects[f"a/{att['id']}"] = 500
    assert (await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(alice))).status_code == 200

    # Every named recipient gets a URL; nobody else does, the sender included.
    for member in (bob, carol, dave):
        assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(member))).status_code == 200
    for other in (alice, eve):
        assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(other))).status_code == 404
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(eve))).status_code == 404

    # A recipient's delete drops only its own claim.
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 200
    assert store.deleted == []
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 404
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(carol))).status_code == 200
    # Deleting twice is refused rather than counted again.
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 404
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(carol))).status_code == 200
    assert store.deleted == []
    # The last one takes the object with it.
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(dave))).status_code == 200
    assert store.deleted == [f"a/{att['id']}"]
    assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(dave))).status_code == 404


@pytest.mark.asyncio
async def test_the_sender_can_withdraw_a_group_file(client: AsyncClient, store: FakeStore):
    alice, bob, carol = await _group(client, 3)
    att = (await client.post("/v1/attachments", json={
        "recipient_ids": [bob["user_id"], carol["user_id"]], "size": 10,
    }, headers=_auth(alice))).json()
    store.objects[f"a/{att['id']}"] = 10
    await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(alice))
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(alice))).status_code == 200
    assert store.deleted == [f"a/{att['id']}"]
    for member in (bob, carol):
        assert (await client.get(f"/v1/attachments/{att['id']}", headers=_auth(member))).status_code == 404


@pytest.mark.asyncio
async def test_a_group_file_counts_once_against_the_allowance_and_the_store(client: AsyncClient, store: FakeStore, monkeypatch):
    alice, bob, carol, dave = await _group(client, 4)
    monkeypatch.setattr(config, "ATTACHMENT_MONTHLY_BYTES_PER_USER", 150)
    monkeypatch.setattr(config, "ATTACHMENT_STORE_LIMIT_BYTES", 150)
    trio = [bob["user_id"], carol["user_id"], dave["user_id"]]
    assert (await client.post("/v1/attachments", json={"recipient_ids": trio, "size": 100}, headers=_auth(alice))).status_code == 200
    over = await client.post("/v1/attachments", json={"recipient_ids": trio, "size": 51}, headers=_auth(alice))
    assert over.status_code == 429 and over.json()["detail"] == "attachment_monthly_limit"
    assert (await client.post("/v1/attachments", json={"recipient_ids": trio, "size": 50}, headers=_auth(alice))).status_code == 200
    full = await client.post("/v1/attachments", json={"recipient_id": alice["user_id"], "size": 1}, headers=_auth(bob))
    assert full.status_code == 507


@pytest.mark.asyncio
async def test_group_reservations_are_checked(client: AsyncClient, store: FakeStore):
    alice, bob, carol = await _group(client, 3)
    def reserve(body):
        return client.post("/v1/attachments", json={**body, "size": 10}, headers=_auth(alice))
    assert (await reserve({"recipient_ids": [bob["user_id"], alice["user_id"]]})).status_code == 400
    assert (await reserve({"recipient_ids": [bob["user_id"], bob["user_id"]]})).status_code == 400
    assert (await reserve({"recipient_ids": [bob["user_id"], "nobody"]})).status_code == 404
    assert (await reserve({"recipient_id": bob["user_id"], "recipient_ids": [carol["user_id"]]})).status_code == 400
    assert (await reserve({})).status_code == 422
    assert (await reserve({"recipient_ids": []})).status_code == 422
    assert (await reserve({"recipient_ids": [f"u{i}" for i in range(32)]})).status_code == 422
    assert (await reserve({"recipient_ids": [""]})).status_code == 422


@pytest.mark.asyncio
async def test_purge_forgets_deleted_recipients_and_removes_a_file_nobody_waits_for(client: AsyncClient, store: FakeStore):
    from app.models.db import Attachment, AttachmentRecipient, Identity
    from sqlalchemy import delete, select
    from tests.conftest import test_session

    alice, bob, carol = await _group(client, 3)
    both = (await client.post("/v1/attachments", json={
        "recipient_ids": [bob["user_id"], carol["user_id"]], "size": 10,
    }, headers=_auth(alice))).json()
    only_bob = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 10}, headers=_auth(alice))).json()
    for att in (both, only_bob):
        store.objects[f"a/{att['id']}"] = 10
        await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(alice))
    async with test_session() as db:
        await db.execute(delete(Identity).where(Identity.user_id == bob["user_id"]))
        await db.commit()
        assert await attachments.purge_attachments(db) == 1
        assert await db.get(Attachment, only_bob["id"]) is None
        assert await db.get(Attachment, both["id"]) is not None
        left = (await db.execute(
            select(AttachmentRecipient.recipient_id).where(AttachmentRecipient.attachment_id == both["id"])
        )).scalars().all()
        assert left == [carol["user_id"]]
    assert store.deleted == [f"a/{only_bob['id']}"]
    assert (await client.get(f"/v1/attachments/{both['id']}", headers=_auth(carol))).status_code == 200


@pytest.mark.asyncio
async def test_the_migration_gives_existing_attachments_their_recipient(tmp_path):
    """Upgrading a relay keeps every file in flight downloadable by its recipient."""
    import sqlite3

    from alembic import command
    from alembic.config import Config

    url = f"sqlite+aiosqlite:///{tmp_path / 'relay.db'}"
    cfg = Config("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", url)
    import os
    old = os.environ.get("DATABASE_URL")
    os.environ["DATABASE_URL"] = url
    try:
        await asyncio_to_thread(command.upgrade, cfg, "013")
        con = sqlite3.connect(tmp_path / "relay.db")
        con.execute(
            "INSERT INTO attachments (id, sender_id, recipient_id, size, created_at, expires_at) "
            "VALUES ('a1', 's', 'r', 5, '2026-11-25', '2026-12-25')"
        )
        con.commit()
        await asyncio_to_thread(command.upgrade, cfg, "head")
        assert con.execute("SELECT attachment_id, recipient_id FROM attachment_recipients").fetchall() == [("a1", "r")]
        assert {r[1] for r in con.execute("PRAGMA table_info(rate_limits)")} >= {"tokens", "refilled_at"}
        con.close()
    finally:
        if old is None:
            os.environ.pop("DATABASE_URL", None)
        else:
            os.environ["DATABASE_URL"] = old


async def asyncio_to_thread(fn, *args):
    import asyncio
    return await asyncio.to_thread(fn, *args)


@pytest.mark.asyncio
async def test_a_malformed_reservation_uses_no_token_of_the_limit(client: AsyncClient, store: FakeStore, monkeypatch):
    alice, bob = await _pair(client)
    for _ in range(25):
        r = await client.post("/v1/attachments", json={"recipient_ids": [], "size": 10}, headers=_auth(alice))
        assert r.status_code == 422
    assert (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 10}, headers=_auth(alice))).status_code == 200


@pytest.mark.asyncio
async def test_deleting_an_account_drops_its_claims(client: AsyncClient, store: FakeStore):
    from app.models.db import AttachmentRecipient
    from sqlalchemy import select
    from tests.conftest import test_session

    alice, bob, carol = await _group(client, 3)
    att = (await client.post("/v1/attachments", json={
        "recipient_ids": [bob["user_id"], carol["user_id"]], "size": 10,
    }, headers=_auth(alice))).json()
    assert (await client.delete("/v1/identity/me", headers=_auth(bob))).status_code == 200
    async with test_session() as db:
        left = (await db.execute(
            select(AttachmentRecipient.recipient_id).where(AttachmentRecipient.attachment_id == att["id"])
        )).scalars().all()
    assert left == [carol["user_id"]]


@pytest.mark.asyncio
async def test_a_second_delete_after_the_file_is_gone_is_a_404_not_an_error(client: AsyncClient, store: FakeStore):
    alice, bob = await _pair(client)
    att = (await client.post("/v1/attachments", json={"recipient_id": bob["user_id"], "size": 10}, headers=_auth(alice))).json()
    store.objects[f"a/{att['id']}"] = 10
    await client.post(f"/v1/attachments/{att['id']}/complete", headers=_auth(alice))
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 200
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(alice))).status_code == 404


@pytest.mark.asyncio
async def test_a_delete_locks_the_attachment_row(client: AsyncClient, store: FakeStore, monkeypatch):
    """Deletes of one file take turns on PostgreSQL (SQLite, which the tests use, ignores the lock)."""
    from sqlalchemy.ext.asyncio import AsyncSession

    alice, bob, carol = await _group(client, 3)
    att = (await client.post("/v1/attachments", json={
        "recipient_ids": [bob["user_id"], carol["user_id"]], "size": 10,
    }, headers=_auth(alice))).json()
    seen = []
    real = AsyncSession.execute

    async def spy(self, statement, *args, **kwargs):
        seen.append(statement)
        return await real(self, statement, *args, **kwargs)

    monkeypatch.setattr(AsyncSession, "execute", spy)
    assert (await client.delete(f"/v1/attachments/{att['id']}", headers=_auth(bob))).status_code == 200
    assert any(getattr(st, "_for_update_arg", None) is not None for st in seen)
