"""Receipts, kept apart from the message queue so clients that don't know them
never see them.

- Read receipts: "I have read these messages", relayed from reader to sender.
  The relay learns which of a sender's messages were read and when; they are
  only sent by clients whose user turned them on, and are signed by the reader
  so the relay can't forge them.
- Delivery receipts: "these messages reached the recipient's phone". The relay
  writes them itself when the recipient acks messages (it knows that anyway),
  so they are unsigned. Apps from 0.5.10 fetch them from /delivered.
"""

import json
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import DeliveryReceipt, Identity, ReadReceipt
from app.models.schemas import DeliveryReceiptResponse, ReceiptAckRequest, ReceiptRequest, ReceiptResponse
from app.services.call_rate_limiter import SlidingWindowLimiter
from app.services.websocket_manager import ws_manager

router = APIRouter(prefix="/v1/receipts", tags=["receipts"])

MAX_PENDING_RECEIPTS_PER_USER = 1000
# One sender's share of a recipient's receipt queue: without it a single
# (junk-signing) sender could fill the queue and every legitimate receipt to
# that recipient would be dropped until the purge.
MAX_PENDING_RECEIPTS_PER_SENDER = 50
# An author whose app never fetches delivery receipts (0.5.9 and older) must not
# grow its queue without bound; the purge clears the rest.
MAX_PENDING_DELIVERY_RECEIPTS_PER_USER = 1000
limiter = SlidingWindowLimiter(limit=60)


def _millis(created_at: datetime | None) -> int:
    when = created_at or datetime.now(timezone.utc).replace(tzinfo=None)
    return int(when.replace(tzinfo=timezone.utc).timestamp() * 1000)


async def queue_delivery_receipt(db: AsyncSession, author_id: str, reader_id: str, message_ids: List[str]) -> bool:
    """Tell [author_id] that [reader_id]'s phone fetched these messages of theirs.

    Called by the message ack, inside its transaction. Best effort: dropped when
    the author's queue is full. True if a receipt was queued (worth a notify).
    """
    pending = (await db.execute(
        select(func.count(DeliveryReceipt.id)).where(DeliveryReceipt.recipient_id == author_id)
    )).scalar_one()
    if pending >= MAX_PENDING_DELIVERY_RECEIPTS_PER_USER:
        return False
    db.add(DeliveryReceipt(
        sender_id=reader_id,
        recipient_id=author_id,
        message_ids=json.dumps(message_ids),
    ))
    return True


@router.post("", status_code=202)
async def send_receipt(
    request: ReceiptRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    if request.recipient_id == x_user_id:
        raise HTTPException(status_code=400, detail="Cannot send to self")
    if not await limiter.allow(x_user_id):
        raise HTTPException(status_code=429, detail="Rate limit exceeded")
    if await db.get(Identity, request.recipient_id) is None:
        raise HTTPException(status_code=404, detail="Recipient not found")
    pending = (await db.execute(
        select(func.count(ReadReceipt.id)).where(ReadReceipt.recipient_id == request.recipient_id)
    )).scalar_one()
    if pending >= MAX_PENDING_RECEIPTS_PER_USER:
        # Receipts are best-effort: drop rather than grow without bound.
        return {"status": "dropped"}
    from_sender = (await db.execute(
        select(func.count(ReadReceipt.id)).where(
            ReadReceipt.recipient_id == request.recipient_id,
            ReadReceipt.sender_id == x_user_id,
        )
    )).scalar_one()
    if from_sender >= MAX_PENDING_RECEIPTS_PER_SENDER:
        return {"status": "dropped"}
    db.add(ReadReceipt(
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        message_ids=json.dumps(request.message_ids),
        signature=request.signature,
    ))
    await db.commit()
    await ws_manager.notify(request.recipient_id, "receipts")
    return {"status": "accepted"}


@router.get("/pending", response_model=List[ReceiptResponse])
async def pending_receipts(
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> List[ReceiptResponse]:
    rows = (await db.execute(
        select(ReadReceipt)
        .where(ReadReceipt.recipient_id == x_user_id)
        .order_by(ReadReceipt.id.asc())
        .limit(100)
    )).scalars().all()
    return [
        ReceiptResponse(
            id=r.id,
            sender_id=r.sender_id,
            message_ids=json.loads(r.message_ids),
            signature=r.signature,
            timestamp=_millis(r.created_at),
        )
        for r in rows
    ]


@router.post("/ack")
async def ack_receipts(
    request: ReceiptAckRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    if request.ids:
        await db.execute(
            delete(ReadReceipt).where(
                ReadReceipt.id.in_(request.ids),
                ReadReceipt.recipient_id == x_user_id,
            )
        )
        await db.commit()
    return {"status": "ok"}


@router.get("/delivered", response_model=List[DeliveryReceiptResponse])
async def pending_delivery_receipts(
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> List[DeliveryReceiptResponse]:
    rows = (await db.execute(
        select(DeliveryReceipt)
        .where(DeliveryReceipt.recipient_id == x_user_id)
        .order_by(DeliveryReceipt.id.asc())
        .limit(100)
    )).scalars().all()
    return [
        DeliveryReceiptResponse(
            id=r.id,
            sender_id=r.sender_id,
            message_ids=json.loads(r.message_ids),
            timestamp=_millis(r.created_at),
        )
        for r in rows
    ]


@router.post("/delivered/ack")
async def ack_delivery_receipts(
    request: ReceiptAckRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    if request.ids:
        await db.execute(
            delete(DeliveryReceipt).where(
                DeliveryReceipt.id.in_(request.ids),
                DeliveryReceipt.recipient_id == x_user_id,
            )
        )
        await db.commit()
    return {"status": "ok"}
