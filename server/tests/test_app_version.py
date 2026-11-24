"""The app's version on every request: the relay keeps each account's latest and logs a change."""

import base64
import logging

import pytest
from httpx import AsyncClient
from sqlalchemy import select

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


async def _fetch(client: AsyncClient, user: dict, version: str | None) -> None:
    headers = make_auth_header(user["private_key"], user["user_id"])
    if version is not None:
        headers["Sotto-App-Version"] = version
    resp = await client.get("/v1/messages/pending", headers=headers)
    assert resp.status_code == 200


async def _stored(user_id: str) -> tuple:
    from app.models.db import Identity
    from tests.conftest import test_session

    async with test_session() as db:
        return (await db.execute(
            select(Identity.app_version, Identity.app_version_at).where(Identity.user_id == user_id)
        )).one()


@pytest.mark.asyncio
async def test_a_new_app_version_is_kept_and_logged_once(client: AsyncClient, caplog):
    user = await _bootstrap_user(client, "versions")
    caplog.set_level(logging.INFO, logger="app.auth")

    await _fetch(client, user, "0.7.23")
    first, first_at = await _stored(user["user_id"])
    await _fetch(client, user, "0.7.23")
    await _fetch(client, user, "0.7.24")
    latest, latest_at = await _stored(user["user_id"])

    assert first == "0.7.23" and first_at is not None
    assert latest == "0.7.24" and latest_at >= first_at
    lines = [r.getMessage() for r in caplog.records if r.getMessage().startswith("App version:")]
    short = user["user_id"][:8]
    # Logged when it changes, not on every request.
    assert lines == [f"App version: {short} (unknown) -> 0.7.23", f"App version: {short} 0.7.23 -> 0.7.24"]


@pytest.mark.asyncio
async def test_only_a_plain_version_is_taken(client: AsyncClient, caplog):
    """The header is the app's word: anything but a version would end up in the log."""
    user = await _bootstrap_user(client, "junk_versions")
    caplog.set_level(logging.INFO, logger="app.auth")
    await _fetch(client, user, "0.7.24")
    caplog.clear()

    for junk in ["0.7.24-debug", "0.7.24\nApp version: forged", "latest", "1", "x" * 100, ""]:
        await _fetch(client, user, junk)
    await _fetch(client, user, None)  # older apps send nothing

    assert (await _stored(user["user_id"]))[0] == "0.7.24"
    assert not [r for r in caplog.records if r.getMessage().startswith("App version:")]


def test_plain_app_version():
    from app.auth import plain_app_version

    assert plain_app_version("0.7.24") == "0.7.24"
    assert plain_app_version("1.0") == "1.0"
    assert plain_app_version("0.7.24.1") == "0.7.24.1"
    assert plain_app_version("0.7.24-debug") is None
    assert plain_app_version(None) is None
    assert plain_app_version(object()) is None


def test_migrating_inside_the_relay_leaves_its_logging_alone(tmp_path, monkeypatch):
    """The relay migrates from inside itself at startup. Alembic's fileConfig there switched
    off every logger that already existed and set the root to WARN, so the relay logged
    nothing, not even its errors."""
    import os

    from alembic import command
    from alembic.config import Config

    import app.auth  # noqa: F401  (its logger exists before the migration, as in the relay)

    server = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    monkeypatch.setenv("DATABASE_URL", f"sqlite+aiosqlite:///{tmp_path / 'migrate.db'}")
    root = logging.getLogger()
    level_before = root.level
    cfg = Config(os.path.join(server, "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(server, "migrations"))

    command.upgrade(cfg, "head")

    assert logging.getLogger("app.auth").disabled is False
    assert root.level == level_before
