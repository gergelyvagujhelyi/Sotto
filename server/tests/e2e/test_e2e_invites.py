"""Invite-only sign-up: members invite, a newcomer redeems an invite once, and a
fresh relay's first account needs none."""

import base64
import os
import sqlite3
import tempfile
import time
from datetime import timedelta

import pytest
from alembic import command
from alembic.config import Config
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from sqlalchemy import update

from app import config
from app.models.db import Invite
from app.routers import invites as invites_router
from app.services import invites
from tests.conftest import test_session as session_factory
from tests.e2e.conftest import bootstrap_user


@pytest.fixture
def invite_only(monkeypatch):
    monkeypatch.setattr(config, "INVITE_ONLY", True)


def _bundle(label: str = "newcomer") -> dict:
    key = Ed25519PrivateKey.generate().public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    return {
        "public_signing_key": base64.b64encode(key).decode(),
        "public_identity_key": base64.b64encode(f"id_{label}_{time.monotonic_ns()}".encode()).decode(),
    }


async def _sign_up(client, invite_code: str = "", bundle: dict | None = None):
    body = dict(bundle or _bundle())
    if invite_code:
        body["invite_code"] = invite_code
    return await client.post("/v1/identity/bootstrap", json=body)


async def _invite(client, member) -> str:
    resp = await client.post("/v1/invites", headers=member.auth_header())
    assert resp.status_code == 200, resp.text
    return resp.json()["code"]


async def _health(client) -> dict:
    resp = await client.get("/health")
    assert resp.status_code == 200
    return resp.json()


@pytest.mark.asyncio
async def test_the_first_account_founds_the_relay_then_invites_are_required(client, invite_only):
    before = await _health(client)
    assert before["service"] == "sotto-relay"
    assert before["invite_required"] is False

    await bootstrap_user(client, label="founder")  # no invite: it founds the relay

    assert (await _health(client))["invite_required"] is True
    refused = await _sign_up(client)
    assert refused.status_code == 403
    assert refused.json()["detail"] == "invite_required"


@pytest.mark.asyncio
async def test_a_members_invite_admits_one_newcomer(client, invite_only):
    founder = await bootstrap_user(client, label="founder")
    code = await _invite(client, founder)
    assert len(code) == 14 and code[4] == "-" and code[9] == "-"

    # Typed by hand: lower case, spaces for dashes.
    joined = await _sign_up(client, code.lower().replace("-", " "))
    assert joined.status_code == 200, joined.text

    again = await _sign_up(client, code)
    assert again.status_code == 403
    assert again.json()["detail"] == "invite_used"


@pytest.mark.asyncio
async def test_unknown_and_expired_invites_are_refused(client, invite_only):
    founder = await bootstrap_user(client, label="founder")
    for typed in ("ZZZZ-ZZZZ-ZZZZ", "not a code"):
        unknown = await _sign_up(client, typed)
        assert unknown.status_code == 403
        assert unknown.json()["detail"] == "invite_invalid"

    code = await _invite(client, founder)
    async with session_factory() as db:
        await db.execute(
            update(Invite).values(expires_at=invites.utcnow() - timedelta(minutes=1))
        )
        await db.commit()
    expired = await _sign_up(client, code)
    assert expired.status_code == 403
    assert expired.json()["detail"] == "invite_expired"


@pytest.mark.asyncio
async def test_signing_up_again_with_the_same_keys_returns_the_same_account(client, invite_only):
    founder = await bootstrap_user(client, label="founder")
    code = await _invite(client, founder)
    bundle = _bundle()

    first = await _sign_up(client, code, bundle)
    assert first.status_code == 200, first.text
    # The reply got lost and the app tries again: the same account, the invite isn't "used".
    retry = await _sign_up(client, code, bundle)
    assert retry.status_code == 200
    assert retry.json() == first.json()
    without_code = await _sign_up(client, "", bundle)
    assert without_code.json() == first.json()


@pytest.mark.asyncio
async def test_a_member_has_at_most_five_open_invites_and_ten_a_day(client, invite_only, monkeypatch):
    monkeypatch.setattr(invites_router.limiter, "limit", 100)
    founder = await bootstrap_user(client, label="founder")
    for _ in range(5):
        await _invite(client, founder)
    sixth = await client.post("/v1/invites", headers=founder.auth_header())
    assert sixth.status_code == 429
    assert sixth.json()["detail"] == "invite_limit_open"

    monkeypatch.setattr(config, "MAX_OPEN_INVITES_PER_MEMBER", 100)
    monkeypatch.setattr(config, "MAX_INVITES_PER_DAY", 7)
    await _invite(client, founder)
    await _invite(client, founder)
    eighth = await client.post("/v1/invites", headers=founder.auth_header())
    assert eighth.status_code == 429
    assert eighth.json()["detail"] == "invite_limit_daily"


@pytest.mark.asyncio
async def test_invites_in_a_burst_are_slowed_down(client, invite_only, monkeypatch):
    monkeypatch.setattr(config, "MAX_OPEN_INVITES_PER_MEMBER", 100)
    founder = await bootstrap_user(client, label="founder")
    for _ in range(5):
        await _invite(client, founder)
    burst = await client.post("/v1/invites", headers=founder.auth_header())
    assert burst.status_code == 429
    assert burst.json()["detail"] == "invite_rate_limited"


@pytest.mark.asyncio
async def test_deleting_an_account_withdraws_its_unused_invites(client, invite_only):
    founder = await bootstrap_user(client, label="founder")
    code = await _invite(client, founder)
    deleted = await client.delete("/v1/identity/me", headers=founder.auth_header())
    assert deleted.status_code == 200
    refused = await _sign_up(client, code)
    assert refused.status_code == 403
    assert refused.json()["detail"] == "invite_invalid"


@pytest.mark.asyncio
async def test_only_members_create_invites(client, invite_only):
    await bootstrap_user(client, label="founder")
    anonymous = await client.post("/v1/invites")
    assert anonymous.status_code in (401, 422)
    forged = await client.post("/v1/invites", headers={"Authorization": "Bearer 0123456789abcdef.1.AAAA"})
    assert forged.status_code == 401


@pytest.mark.asyncio
async def test_without_invite_only_anyone_joins(client):
    await bootstrap_user(client, label="founder")
    assert (await _health(client))["invite_required"] is False
    assert (await _sign_up(client)).status_code == 200


def _migrate_with(tmp_path: str, monkeypatch, accounts: int) -> int:
    """Run the migrations up to 009, add accounts, then 010; returns relay_state.founded."""
    path = os.path.join(tmp_path, "relay.db")
    monkeypatch.setenv("DATABASE_URL", "sqlite+aiosqlite:///" + path)
    cfg = Config("alembic.ini")
    command.upgrade(cfg, "009")
    with sqlite3.connect(path) as db:
        for i in range(accounts):
            db.execute(
                "INSERT INTO identities (user_id, public_signing_key, public_identity_key, share_code) "
                "VALUES (?, 'k', 'k', ?)",
                (f"{i:016x}", f"code{i:012d}"),
            )
    command.upgrade(cfg, "010")
    with sqlite3.connect(path) as db:
        return db.execute("SELECT founded FROM relay_state WHERE id = 1").fetchone()[0]


def test_the_migration_founds_a_relay_that_already_has_accounts(monkeypatch):
    assert _migrate_with(tempfile.mkdtemp(), monkeypatch, accounts=3) == 1
    assert _migrate_with(tempfile.mkdtemp(), monkeypatch, accounts=0) == 0
