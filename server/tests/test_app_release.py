"""The relay tells phones when the app release that goes with it can be downloaded."""

import asyncio
import re

import httpx
import pytest
from httpx import AsyncClient

from app.services import app_release as app_release_module
from app.services.app_release import AppRelease

TEMPLATE = "https://github.com/example/Sotto/releases/download/v{version}/app-release.apk"
URL = "https://github.com/example/Sotto/releases/download/v9.9.9/app-release.apk"


def _client(*statuses: int, seen: list | None = None) -> httpx.AsyncClient:
    """A client whose requests get these statuses in turn (an exception type raises it)."""
    answers = list(statuses)

    def handle(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append((request.method, str(request.url)))
        answer = answers.pop(0) if answers else 404
        if isinstance(answer, type) and issubclass(answer, Exception):
            raise answer("no route to github.com", request=request)
        return httpx.Response(answer)

    return httpx.AsyncClient(transport=httpx.MockTransport(handle))


async def test_not_ready_while_the_apk_is_missing():
    release = AppRelease(TEMPLATE, "9.9.9")
    seen = []
    async with _client(404, seen=seen) as client:
        assert await release.check(client) is False
    assert release.download_url is None
    assert seen == [("HEAD", URL)]


async def test_ready_once_github_redirects_to_the_apk():
    release = AppRelease(TEMPLATE, "9.9.9")
    async with _client(302) as client:
        assert await release.check(client) is True
    assert release.download_url == URL


async def test_a_network_error_is_not_ready_and_does_not_raise():
    release = AppRelease(TEMPLATE, "9.9.9")
    async with _client(httpx.ConnectError) as client:
        assert await release.check(client) is False
    assert release.download_url is None


async def test_watch_checks_until_the_apk_appears_then_stops():
    release = AppRelease(TEMPLATE, "9.9.9")
    seen = []
    await asyncio.wait_for(
        release.watch(interval_s=0, client=_client(404, 404, 302, seen=seen)), timeout=5
    )
    assert len(seen) == 3
    assert release.download_url == URL


async def test_without_a_release_address_nothing_is_checked():
    release = AppRelease("", "9.9.9")
    seen = []
    await asyncio.wait_for(release.watch(interval_s=0, client=_client(302, seen=seen)), timeout=5)
    assert seen == []
    assert release.download_url is None


async def test_health_carries_the_download_link_only_when_ready(client: AsyncClient, monkeypatch):
    release = AppRelease(TEMPLATE, "9.9.9")
    monkeypatch.setattr(app_release_module, "app_release", release)

    assert (await client.get("/health")).json()["app_download_url"] is None

    release.ready = True
    assert (await client.get("/health")).json()["app_download_url"] == URL


def test_the_suite_never_checks_github():
    from app import config

    assert config.APP_RELEASE_APK_URL == ""


@pytest.mark.parametrize("version", ["0.7.3", "1.0.0"])
def test_the_address_names_the_relays_own_version(version):
    assert AppRelease(TEMPLATE, version).url.endswith(f"/v{version}/app-release.apk")


# ── Every answer carries the relay's version; phones hear when the app is out ──

async def test_every_answer_names_the_relay_version_and_whether_its_app_is_out(client: AsyncClient, monkeypatch):
    from app import config

    release = AppRelease(TEMPLATE, config.RELAY_VERSION)
    monkeypatch.setattr(app_release_module, "app_release", release)

    for response in (
        await client.get("/health"),
        await client.get("/v1/messages/pending"),  # refused (no token): still says it
    ):
        assert response.headers["Sotto-Relay-Version"] == config.RELAY_VERSION
        assert response.headers["Sotto-App-Ready"] == "false"

    release.ready = True
    assert (await client.get("/v1/messages/pending")).headers["Sotto-App-Ready"] == "true"


async def test_phones_are_told_once_when_the_app_comes_out():
    release = AppRelease(TEMPLATE, "9.9.9")
    told = []

    async def tell():
        told.append(1)

    release.on_ready = tell
    async with _client(404, 302) as client:
        await release.check(client)
        assert told == []
        await release.check(client)
        await release.check(client)
    assert told == [1]


async def test_a_relay_that_starts_after_the_app_came_out_tells_no_one():
    """A restart (or a late deploy) finds the APK already there: phones heard, or see it on their next request."""
    release = AppRelease(TEMPLATE, "9.9.9")
    told = []

    async def tell():
        told.append(1)

    release.on_ready = tell
    async with _client(302) as client:
        await release.check(client)
    assert release.ready and told == []


async def test_a_failure_while_telling_phones_still_counts_the_app_as_out():
    release = AppRelease(TEMPLATE, "9.9.9")

    async def tell():
        raise RuntimeError("firebase down")

    release.on_ready = tell
    async with _client(404, 302) as client:
        await release.check(client)
        assert await release.check(client) is True


async def test_telling_phones_uses_their_socket_or_else_a_push(monkeypatch):
    from app.models.db import Identity
    from app.services import app_release as module
    from tests.conftest import test_session

    async with test_session() as db:
        for user_id, token, fid in [("online1", "tok-a", None), ("offline", None, "fid-b"), ("nopush", None, None)]:
            db.add(Identity(user_id=user_id, public_signing_key="k", public_identity_key="k",
                            share_code=f"sc-{user_id}", fcm_token=token, fcm_fid=fid))
        await db.commit()

    sent_sockets, pushes = [], []

    async def broadcast(payload):
        sent_sockets.append(payload)
        return 1

    async def push(**kwargs):
        pushes.append(kwargs)
        return True

    monkeypatch.setattr(module.ws_manager, "broadcast", broadcast)
    monkeypatch.setattr(module.ws_manager, "is_online", lambda user_id: user_id == "online1")
    monkeypatch.setattr(module, "send_push_notification", push)

    async with test_session() as db:
        await module.announce_to_phones(db, "9.9.9")

    assert sent_sockets == ['{"type": "app_release", "version": "9.9.9"}']
    assert [(p["recipient_id"], p["fid"], p["signal"]) for p in pushes] == [("offline", "fid-b", "app_release")]


# ── The release workflow's signed call ──

def _ci_key(monkeypatch):
    """A workflow key for the test, its public half installed in the relay."""
    import base64
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from app import config

    key = Ed25519PrivateKey.generate()
    public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    monkeypatch.setattr(config, "CI_RELEASE_PUBLIC_KEY", base64.b64encode(public).decode())
    return key


def _call(key, version: str, ts_ms: int | None = None) -> dict:
    import base64
    import time

    ts_ms = ts_ms if ts_ms is not None else int(time.time() * 1000)
    signature = key.sign(app_release_module.ci_call_payload(version, ts_ms))
    return {"version": version, "ts": ts_ms, "signature": base64.b64encode(signature).decode()}


@pytest.fixture
def relay_release(monkeypatch):
    """The relay's release, with a record of when it told the phones."""
    release = AppRelease(TEMPLATE, "9.9.9")
    release.told = []

    async def tell():
        release.told.append(1)

    release.on_ready = tell
    monkeypatch.setattr(app_release_module, "app_release", release)
    return release


async def test_a_signed_call_marks_the_app_out_and_tells_the_phones(client: AsyncClient, monkeypatch, relay_release):
    key = _ci_key(monkeypatch)
    response = await client.post("/v1/app-release", json=_call(key, "9.9.9"))
    assert response.status_code == 202
    assert relay_release.ready and relay_release.told == [1]
    # Said again (a rerun of the workflow): the phones were told already.
    assert (await client.post("/v1/app-release", json=_call(key, "9.9.9"))).status_code == 202
    assert relay_release.told == [1]


async def test_a_call_signed_with_another_key_is_refused(client: AsyncClient, monkeypatch, relay_release):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    _ci_key(monkeypatch)
    response = await client.post("/v1/app-release", json=_call(Ed25519PrivateKey.generate(), "9.9.9"))
    assert response.status_code == 401
    assert not relay_release.ready and relay_release.told == []


async def test_an_unsigned_or_garbled_call_is_refused(client: AsyncClient, monkeypatch, relay_release):
    import time

    _ci_key(monkeypatch)
    ts = int(time.time() * 1000)
    for signature in ("", "not base64!", "AAAA"):
        response = await client.post("/v1/app-release", json={"version": "9.9.9", "ts": ts, "signature": signature})
        assert response.status_code == 401, signature
    assert not relay_release.ready


async def test_an_old_call_is_refused(client: AsyncClient, monkeypatch, relay_release):
    import time

    key = _ci_key(monkeypatch)
    stale = int(time.time() * 1000) - 11 * 60 * 1000
    assert (await client.post("/v1/app-release", json=_call(key, "9.9.9", stale))).status_code == 401
    assert not relay_release.ready


async def test_a_call_for_another_version_is_refused(client: AsyncClient, monkeypatch, relay_release):
    """A captured call can't be reused for a later version (the signature covers the version)."""
    key = _ci_key(monkeypatch)
    assert (await client.post("/v1/app-release", json=_call(key, "9.9.8"))).status_code == 409
    forged = _call(key, "9.9.8") | {"version": "9.9.9"}
    assert (await client.post("/v1/app-release", json=forged)).status_code == 401
    assert not relay_release.ready


async def test_without_a_workflow_key_the_call_is_off(client: AsyncClient, monkeypatch, relay_release):
    from app import config

    key = _ci_key(monkeypatch)
    monkeypatch.setattr(config, "CI_RELEASE_PUBLIC_KEY", "")
    assert (await client.post("/v1/app-release", json=_call(key, "9.9.9"))).status_code == 401
    assert not relay_release.ready


def test_the_official_workflow_key_is_a_valid_ed25519_key():
    import base64
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    from app.services import app_release

    source = (app_release.__file__.rsplit("/services/", 1)[0]) + "/config.py"
    default = re.search(r'CI_RELEASE_PUBLIC_KEY.*?"([A-Za-z0-9+/=]{44})"', open(source).read()).group(1)
    Ed25519PublicKey.from_public_bytes(base64.b64decode(default))
