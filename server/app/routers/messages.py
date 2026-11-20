"""Message endpoints: send, fetch pending, acknowledge.

The server is a relay: it stores encrypted envelopes temporarily and
delivers them to the recipient. It cannot read message content.
"""

import asyncio
import hashlib
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_auth_token
from app.config import (
    MAX_PENDING_MESSAGES_PER_USER,
    MAX_PENDING_PER_SENDER,
    NEW_ACCOUNT_AGE_HOURS,
    NEW_ACCOUNT_PENDING_PER_RECIPIENT,
    NEW_ACCOUNTS_PENDING_TOTAL,
)
from app.database import get_db
from app.models.db import Identity, PendingMessage
from app.routers.receipts import queue_delivery_receipt
from app.models.schemas import (
    AckRequest,
    PendingMessageResponse,
    SendMessageRequest,
    SendMessageResponse,
)
from app.services.push import send_push_notification
from app.services.rate_limiter import check_rate_limit
from app.services.websocket_manager import ws_manager
import app.services.websocket_manager as websocket_manager

router = APIRouter(prefix="/v1/messages", tags=["messages"])

# A socket can take the new-message notification without anybody reading it (Android
# pauses background apps without a foreground service; their sockets stay open). A
# message still unfetched after this many seconds gets the push after all.
MESSAGE_FETCH_CONFIRM_SECONDS = 3.0

_MAX_PENDING_RESPONSE_BYTES = 8 * 1024 * 1024


@router.post("/send", response_model=SendMessageResponse)
async def send_message(
    request: SendMessageRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> SendMessageResponse:
    """Submit an encrypted envelope for relay to the recipient.

    The server:
    1. Rate-checks the sender.
    2. Stores the encrypted envelope.
    3. Attempts real-time delivery via WebSocket.
    4. Falls back to FCM push if the recipient is offline.

    The server never inspects or logs the encrypted body.
    """
    # Prevent self-send
    if request.recipient_id == x_user_id:
        raise HTTPException(status_code=400, detail="Cannot send to self")

    # Rate limiting
    if not await check_rate_limit(db, x_user_id):
        raise HTTPException(status_code=429, detail="Rate limit exceeded")
    # Commit the count now: the error paths below (unknown recipient, full
    # queue) raise without committing, which rolled the increment back and left
    # floods of failing sends unthrottled.
    await db.commit()

    # Lock the recipient's Identity row to serialize concurrent inserts.
    # This prevents the TOCTOU race where two requests both pass the count
    # check before either inserts, bypassing MAX_PENDING_MESSAGES_PER_USER.
    # On SQLite (single-writer), this is a no-op; on PostgreSQL, FOR UPDATE
    # serializes concurrent senders targeting the same recipient.
    recipient_result = await db.execute(
        select(Identity)
        .where(Identity.user_id == request.recipient_id)
        .with_for_update()
    )
    recipient = recipient_result.scalar_one_or_none()
    if recipient is None:
        raise HTTPException(status_code=404, detail="Recipient not found")

    # Resend of a message we already hold (the sender never saw our response):
    # answer as before instead of relaying a second copy. The id is namespaced by
    # sender so nobody can collide with someone else's messages.
    if request.client_message_id:
        message_id = hashlib.sha256(
            f"{x_user_id}:{request.client_message_id}".encode()
        ).hexdigest()[:32]
        existing = await _find_pending(db, message_id, x_user_id)
        if existing is not None:
            return SendMessageResponse(message_id=message_id, timestamp=existing.timestamp_ms or 0)
    else:
        message_id = uuid.uuid4().hex

    # Check pending queue depth (safe from TOCTOU — Identity row is locked)
    count_result = await db.execute(
        select(func.count(PendingMessage.id))
        .where(PendingMessage.recipient_id == request.recipient_id)
    )
    pending_count = count_result.scalar_one()
    if pending_count >= MAX_PENDING_MESSAGES_PER_USER:
        raise HTTPException(
            status_code=507,
            detail="Recipient's pending queue is full",
        )

    # One sender may only use part of a recipient's queue, so a single
    # (possibly malicious) sender can't lock everyone else out of it.
    sender_count_result = await db.execute(
        select(func.count(PendingMessage.id))
        .where(
            PendingMessage.recipient_id == request.recipient_id,
            PendingMessage.sender_id == x_user_id,
        )
    )
    sender_pending = sender_count_result.scalar_one()
    if sender_pending >= MAX_PENDING_PER_SENDER:
        raise HTTPException(
            status_code=429,
            detail="Too many undelivered messages to this recipient",
        )

    # Fresh accounts cost nothing, so they get a small allowance per recipient,
    # and together may only use part of a recipient's queue. Filling someone's
    # queue then takes accounts aged in advance, not a burst of new ones.
    young_cutoff = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(hours=NEW_ACCOUNT_AGE_HOURS)
    sender_created = (await db.execute(
        select(Identity.created_at).where(Identity.user_id == x_user_id)
    )).scalar_one_or_none()
    if sender_created is not None and sender_created > young_cutoff:
        if sender_pending >= NEW_ACCOUNT_PENDING_PER_RECIPIENT:
            raise HTTPException(
                status_code=429,
                detail="Too many undelivered messages to this recipient",
            )
        # Messages whose sender no longer exists count as young too: otherwise a
        # sybil could send, delete itself (its sent messages stay queued) and
        # repeat with a fresh account, never counting against this total.
        young_total = (await db.execute(
            select(func.count(PendingMessage.id))
            .outerjoin(Identity, Identity.user_id == PendingMessage.sender_id)
            .where(
                PendingMessage.recipient_id == request.recipient_id,
                (Identity.user_id.is_(None)) | (Identity.created_at > young_cutoff),
            )
        )).scalar_one()
        if young_total >= NEW_ACCOUNTS_PENDING_TOTAL:
            raise HTTPException(
                status_code=429,
                detail="Too many undelivered messages to this recipient",
            )

    now_utc = datetime.now(timezone.utc)
    timestamp_ms = int(now_utc.timestamp() * 1000)
    now = now_utc.replace(tzinfo=None)

    pending = PendingMessage(
        message_id=message_id,
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        encrypted_body=request.encrypted_body,
        nonce=request.nonce,
        ephemeral_public_key=request.ephemeral_public_key,
        signature=request.signature,
        pqc_signature=request.pqc_signature,
        created_at=now,
        timestamp_ms=timestamp_ms,
    )
    db.add(pending)
    try:
        await db.commit()
    except IntegrityError:
        # A concurrent resend of the same message won the insert.
        await db.rollback()
        existing = await _find_pending(db, message_id, x_user_id)
        if existing is None:
            raise HTTPException(status_code=409, detail="Duplicate message id")
        return SendMessageResponse(message_id=message_id, timestamp=existing.timestamp_ms or 0)

    # Real-time delivery attempt via WebSocket
    ws_delivered = await ws_manager.notify_new_message(
        recipient_id=request.recipient_id,
        sender_id=x_user_id,
    )

    # FCM push if recipient is not connected via WebSocket, or later if the app
    # behind its socket doesn't fetch the message (paused in the background).
    if recipient.fcm_fid or recipient.fcm_token:
        if not ws_delivered:
            await send_push_notification(
                fcm_token=recipient.fcm_token,
                fid=recipient.fcm_fid,
                recipient_id=request.recipient_id,
                sender_id=x_user_id,
            )
        else:
            ws_manager.spawn(_push_if_unfetched(
                db.bind, message_id, request.recipient_id, x_user_id,
                recipient.fcm_token, recipient.fcm_fid,
            ))

    return SendMessageResponse(message_id=message_id, timestamp=timestamp_ms)


async def _find_pending(db: AsyncSession, message_id: str, sender_id: str) -> PendingMessage | None:
    result = await db.execute(
        select(PendingMessage).where(
            PendingMessage.message_id == message_id,
            PendingMessage.sender_id == sender_id,
        )
    )
    return result.scalar_one_or_none()


@router.get("/pending", response_model=List[PendingMessageResponse])
async def fetch_pending_messages(
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
    limit: int = 100,
) -> List[PendingMessageResponse]:
    """Fetch pending encrypted envelopes for the authenticated user.

    The client decrypts these locally. The envelopes remain in the database
    until the client acknowledges receipt. Use ``limit`` to paginate;
    after ACK-ing a batch the next fetch returns the next oldest messages.
    """
    if limit < 1:
        limit = 1
    elif limit > 500:
        limit = 500

    # Bound the response size, not just the row count: rows can be up to
    # ~256 KB each, and a few concurrent 500-row fetches would otherwise hold
    # hundreds of MB (rows, models and JSON are each copies). Sizes are read
    # first so oversized pages are never loaded. At least one row is returned.
    sizes = await db.execute(
        select(
            PendingMessage.id,
            func.length(PendingMessage.encrypted_body)
            + func.length(PendingMessage.nonce)
            + func.length(PendingMessage.pqc_signature),
        )
        .where(PendingMessage.recipient_id == x_user_id)
        .order_by(PendingMessage.created_at.asc(), PendingMessage.id.asc())
        .limit(limit)
    )
    ids: list[int] = []
    budget = _MAX_PENDING_RESPONSE_BYTES
    for row_id, size in sizes.all():
        if ids and (size or 0) > budget:
            break
        ids.append(row_id)
        budget -= size or 0

    if not ids:
        return []
    result = await db.execute(
        select(PendingMessage)
        .where(PendingMessage.id.in_(ids))
        .order_by(PendingMessage.created_at.asc(), PendingMessage.id.asc())
    )
    messages = result.scalars().all()

    return [
        PendingMessageResponse(
            message_id=msg.message_id,
            sender_id=msg.sender_id,
            encrypted_body=msg.encrypted_body,
            nonce=msg.nonce,
            ephemeral_public_key=msg.ephemeral_public_key,
            signature=msg.signature or "",
            pqc_signature=msg.pqc_signature or "",
            timestamp=msg.timestamp_ms or 0,
        )
        for msg in messages
    ]


@router.post("/ack")
async def acknowledge_messages(
    request: AckRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Acknowledge receipt of messages, allowing the server to delete them.

    This is the key privacy mechanism: the server does not retain envelopes
    after the recipient has fetched them. Each message's author gets a
    delivery receipt (the relay knows the message arrived; it says so).
    """
    if not request.message_ids:
        return {"status": "ok"}

    acked = (await db.execute(
        select(PendingMessage.message_id, PendingMessage.sender_id).where(
            PendingMessage.recipient_id == x_user_id,
            PendingMessage.message_id.in_(request.message_ids),
        )
    )).all()
    await db.execute(
        delete(PendingMessage).where(
            PendingMessage.recipient_id == x_user_id,
            PendingMessage.message_id.in_(request.message_ids),
        )
    )
    by_author: dict[str, List[str]] = {}
    for message_id, author_id in acked:
        by_author.setdefault(author_id, []).append(message_id)
    notify = [
        author_id
        for author_id, ids in by_author.items()
        if await queue_delivery_receipt(db, author_id=author_id, reader_id=x_user_id, message_ids=ids)
    ]
    await db.commit()
    for author_id in notify:
        await ws_manager.notify(author_id, "receipts")
    return {"status": "ok"}


async def _push_if_unfetched(
    engine, message_id: str, recipient_id: str, sender_id: str,
    fcm_token: str | None, fid: str | None,
) -> None:
    """Push after all if the message is still waiting (its app never fetched it): at
    once if the recipient's sockets die meanwhile (the notification went into a dead
    one), otherwise after MESSAGE_FETCH_CONFIRM_SECONDS."""
    deadline = time.monotonic() + MESSAGE_FETCH_CONFIRM_SECONDS
    while time.monotonic() < deadline and ws_manager.is_online(recipient_id):
        await asyncio.sleep(websocket_manager.SOCKET_CHECK_INTERVAL)
    async with AsyncSession(engine) as db:
        waiting = (await db.execute(
            select(PendingMessage.id).where(
                PendingMessage.message_id == message_id,
                PendingMessage.recipient_id == recipient_id,
            )
        )).first()
    if waiting is not None:
        await send_push_notification(
            fcm_token=fcm_token, fid=fid, recipient_id=recipient_id, sender_id=sender_id,
        )
