"""Key lookups by members only, when KEY_LOOKUP_AUTH is on (0.7.11 apps sign them in)."""

import pytest
from httpx import AsyncClient

from app import config
from tests.test_push import _auth, _bootstrap_user


@pytest.mark.asyncio
async def test_lookups_stay_open_while_the_switch_is_off(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(config, "KEY_LOOKUP_AUTH", False)
    user = await _bootstrap_user(client, "lookup_open")
    assert (await client.get(f"/v1/identity/{user['user_id']}/keys")).status_code == 200
    assert (await client.get(f"/v1/identity/resolve/{user['share_code']}")).status_code == 200


@pytest.mark.asyncio
async def test_lookups_need_a_member_when_the_switch_is_on(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(config, "KEY_LOOKUP_AUTH", True)
    member = await _bootstrap_user(client, "lookup_member")
    other = await _bootstrap_user(client, "lookup_other")

    # Nobody signed in: refused, so an outsider learns nothing from a code or id.
    assert (await client.get(f"/v1/identity/{other['user_id']}/keys")).status_code == 401
    assert (await client.get(f"/v1/identity/resolve/{other['share_code']}")).status_code == 401
    # A broken token: refused too.
    bad = {"Authorization": "Bearer nobody.1.AAAA"}
    assert (await client.get(f"/v1/identity/{other['user_id']}/keys", headers=bad)).status_code == 401

    # A member: answered.
    keys = await client.get(f"/v1/identity/{other['user_id']}/keys", headers=_auth(member))
    assert keys.status_code == 200
    resolved = await client.get(f"/v1/identity/resolve/{other['share_code']}", headers=_auth(member))
    assert resolved.status_code == 200
    assert resolved.json()["user_id"] == other["user_id"]
