"""Firebase Cloud Messaging push notification service.

Sends wake-up signals only — never includes message content in the push payload.
The client fetches encrypted envelopes directly from this server after wake-up.
"""

import asyncio
import logging
import warnings
from datetime import timedelta
from typing import Optional

from app.config import FIREBASE_CREDENTIALS

logger = logging.getLogger(__name__)

# How long Firebase keeps a push for a phone it can't reach (no signal, switched off).
# A call's push matters only while the call rings: later, it would ring for a call that's over.
CALL_PUSH_TTL = timedelta(seconds=60)
# A message's push waits as long as Firebase allows. Until 2026-11-20 it also lasted 60 s, so
# a phone that came back from more than a minute without signal got no wake-up, and its
# messages waited for the app's 15-minute check (or for it to be opened).
MESSAGE_PUSH_TTL = timedelta(days=28)
# No push collapses. 0.6.5 made message pushes collapsible ("new_message"), and Firebase lets a
# phone have only 20 of those, then 1 every 3 minutes ("collapsible message throttling",
# firebase.google.com/docs/cloud-messaging/throttling-and-quotas): after a busy while, messages
# stayed at "sent" until the app was opened. Ordinary pushes aren't throttled that way. While
# a phone is offline Firebase keeps up to 100 of them; past that it drops them and tells the
# app (FcmService.onDeletedMessages), which then fetches everything.

_firebase_initialized = False


def _init_firebase() -> bool:
    """Lazily initialize Firebase Admin SDK."""
    global _firebase_initialized
    if _firebase_initialized:
        return True
    if not FIREBASE_CREDENTIALS:
        logger.info("FIREBASE_CREDENTIALS not set — push notifications disabled")
        return False
    try:
        import firebase_admin
        from firebase_admin import credentials

        cred = credentials.Certificate(FIREBASE_CREDENTIALS)
        firebase_admin.initialize_app(cred)
        _firebase_initialized = True
        logger.info("Firebase Admin SDK initialized")
        return True
    except Exception:
        logger.exception("Failed to initialize Firebase Admin SDK")
        return False


async def _clear_stale_token(recipient_id: str, failed_token: str, column: str = "fcm_token") -> None:
    """Remove the push target that FCM reported as invalid.

    ``column`` is ``fcm_token`` or ``fcm_fid``. Only clears it if it is still
    the stored value: the app may have registered a new one while the failed
    push was in flight.
    """
    try:
        from sqlalchemy import update

        from app.database import async_session
        from app.models.db import Identity

        stored = getattr(Identity, column)
        async with async_session() as db:
            result = await db.execute(
                update(Identity)
                .where(Identity.user_id == recipient_id, stored == failed_token)
                .values({column: None})
            )
            await db.commit()
            if result.rowcount:
                logger.info("Cleared stale FCM token for %s", recipient_id[:8])
    except Exception:
        logger.exception("Failed to clear stale FCM token for %s", recipient_id[:8])


async def send_push_notification(
    fcm_token: Optional[str] = None,
    recipient_id: Optional[str] = None,
    sender_id: Optional[str] = None,
    fid: Optional[str] = None,
    for_call: bool = False,
    signal: str = "new_message",
) -> bool:
    """Send a wake-up push to a device.

    The device is addressed by its Firebase installation ID (``fid``, apps
    0.5.5+) when it registered one, otherwise by its FCM registration token.

    The payload is a bare wake-up signal — no sender identity and never any
    message content. The client fetches the encrypted envelope from the
    /v1/messages/pending endpoint.

    If the FCM token is invalid/expired, it is automatically cleared from
    the database so future sends fall back to polling.

    ``for_call``: a call offer's push, which lasts a minute (``CALL_PUSH_TTL``); a message's
    lasts as long as Firebase allows (``MESSAGE_PUSH_TTL``). Neither collapses.

    ``signal``: the push's ``type``. Apps treat every push as "wake up and fetch";
    ``app_release`` says why (the app release that goes with this relay is out).
    """
    if fid:
        target, column, value = {"fid": fid}, "fcm_fid", fid
    elif fcm_token:
        target, column, value = {"token": fcm_token}, "fcm_token", fcm_token
    else:
        return False
    if not _init_firebase():
        return False

    try:
        from firebase_admin import messaging

        # Wake-up signal only. The sender id is deliberately NOT included: the
        # client doesn't need it (it fetches from /pending) and it would hand
        # Google the who-talks-to-whom graph.
        data = {"type": signal}

        with warnings.catch_warnings():
            # Message.token is deprecated in favour of fid; tokens stay for apps up to 0.5.4.
            warnings.simplefilter("ignore", DeprecationWarning)
            message = messaging.Message(
                data=data,
                android=messaging.AndroidConfig(
                    priority="high",
                    ttl=CALL_PUSH_TTL if for_call else MESSAGE_PUSH_TTL,
                ),
                **target,
            )
        await asyncio.to_thread(messaging.send, message)
        return True
    except Exception as e:
        error_name = type(e).__name__
        # Firebase raises specific errors for invalid tokens
        if error_name in ("UnregisteredError", "InvalidArgumentError", "SenderIdMismatchError"):
            logger.warning("FCM push target invalid (%s) for %s — clearing",
                           error_name, (recipient_id or "unknown")[:8])
            if recipient_id:
                await _clear_stale_token(recipient_id, value, column)
        else:
            logger.exception("Failed to send push notification")
        return False
