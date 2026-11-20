"""Which push target a wake-up push uses, and what a dead one clears."""

import pytest
from firebase_admin import messaging

import app.database as database
import app.services.push as push
from app.models.db import Identity
from tests.conftest import test_session as session_factory

FID = "cAbCdEfGhIjKlMnOpQr-_0"


@pytest.fixture
def sent(monkeypatch):
    """Pretend Firebase is configured and capture what would be sent."""
    messages = []
    monkeypatch.setattr(push, "_init_firebase", lambda: True)
    monkeypatch.setattr(messaging, "send", lambda message, *a, **kw: messages.append(message) or "ok")
    monkeypatch.setattr(database, "async_session", session_factory)
    return messages


async def _add_identity(**targets):
    async with session_factory() as db:
        db.add(Identity(user_id="u1", public_signing_key="s", public_identity_key="i",
                        share_code="code1", **targets))
        await db.commit()


async def _targets():
    async with session_factory() as db:
        identity = await db.get(Identity, "u1")
        return identity.fcm_token, identity.fcm_fid


async def test_installation_id_is_used_when_registered(sent):
    assert await push.send_push_notification(fcm_token="T", fid=FID, recipient_id="u1")
    assert len(sent) == 1
    assert sent[0].fid == FID and sent[0].token is None
    # Still only a wake-up signal: no sender, no content.
    assert sent[0].data == {"type": "new_message"}


async def test_token_is_used_for_older_apps(sent):
    assert await push.send_push_notification(fcm_token="T", recipient_id="u1")
    assert sent[0].token == "T" and sent[0].fid is None


async def test_nothing_is_sent_without_a_target(sent):
    assert not await push.send_push_notification(recipient_id="u1")
    assert sent == []


async def test_unregistered_installation_id_is_cleared(monkeypatch, sent):
    await _add_identity(fcm_fid=FID)

    def gone(message, *a, **kw):
        raise messaging.UnregisteredError("Requested entity was not found.")

    monkeypatch.setattr(messaging, "send", gone)
    assert not await push.send_push_notification(fid=FID, recipient_id="u1")
    assert await _targets() == (None, None)


async def test_a_dead_token_does_not_clear_a_newer_installation_id(monkeypatch, sent):
    # The app moved to its installation ID while a push to its old token was in flight.
    await _add_identity(fcm_fid=FID)

    def gone(message, *a, **kw):
        raise messaging.UnregisteredError("Requested entity was not found.")

    monkeypatch.setattr(messaging, "send", gone)
    assert not await push.send_push_notification(fcm_token="OLD", recipient_id="u1")
    assert await _targets() == (None, FID)
