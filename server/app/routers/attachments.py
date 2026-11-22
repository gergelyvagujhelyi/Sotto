"""Encrypted attachments (ADR-29): the relay signs URLs, the phones move the bytes.

1. The sender reserves an upload (`POST /v1/attachments`) for a recipient and the
   ciphertext's exact size, and gets a presigned PUT whose content-length is
   signed, so the store refuses any other size.
2. It uploads straight to the store, then confirms (`POST .../complete`); the
   relay checks the stored size with a HEAD.
3. The key travels to the recipient inside an ordinary end-to-end encrypted
   message, with the attachment's id. The recipient asks for a presigned GET
   (`GET /v1/attachments/{id}`), downloads, and deletes it (`DELETE`).

Every request is signed by the account like all others. The relay learns who
sends a file to whom and its size, never its content, name or key.
"""

import secrets
from datetime import datetime, timedelta, timezone

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app import config
from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import Attachment, AttachmentUsage, Identity
from app.services.call_rate_limiter import SlidingWindowLimiter
from app.services.object_store import ObjectStore, configured_store

router = APIRouter(prefix="/v1/attachments", tags=["attachments"])

limiter = SlidingWindowLimiter(limit=20)

# How long a presigned URL works: long enough for a large file on a slow line.
UPLOAD_URL_SECONDS = 3600
DOWNLOAD_URL_SECONDS = 3600
# A reservation that was never completed is cleaned up after this.
UNFINISHED_UPLOAD_HOURS = 24
ID_PATTERN = r"^[0-9a-f]{32}$"


class ReserveRequest(BaseModel):
    recipient_id: str = Field(..., min_length=1, max_length=32)
    size: int = Field(..., gt=0)


class ReserveResponse(BaseModel):
    id: str
    upload_url: str
    expires_in: int


class DownloadResponse(BaseModel):
    download_url: str
    size: int
    expires_in: int


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def object_key(attachment_id: str) -> str:
    return f"a/{attachment_id}"


def _store() -> ObjectStore:
    store = configured_store()
    if store is None:
        raise HTTPException(status_code=503, detail="attachments_unavailable")
    return store


async def _http() -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=10)


@router.post("", response_model=ReserveResponse)
async def reserve_upload(
    request: ReserveRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> ReserveResponse:
    store = _store()
    if not await limiter.allow(x_user_id):
        raise HTTPException(status_code=429, detail="attachment_rate_limited")
    if request.size > config.ATTACHMENT_MAX_BYTES:
        raise HTTPException(status_code=413, detail="attachment_too_large")
    if request.recipient_id == x_user_id:
        raise HTTPException(status_code=400, detail="attachment_to_self")
    # The sender's row, locked: two reservations can't both slip under the allowance.
    sender = (await db.execute(
        select(Identity.user_id).where(Identity.user_id == x_user_id).with_for_update()
    )).scalar_one_or_none()
    if sender is None:
        raise HTTPException(status_code=404, detail="User not found")
    recipient = (await db.execute(
        select(Identity.user_id).where(Identity.user_id == request.recipient_id)
    )).scalar_one_or_none()
    if recipient is None:
        raise HTTPException(status_code=404, detail="Recipient not found")

    now = _now()
    month_ago = now - timedelta(days=30)
    used = (await db.execute(
        select(func.coalesce(func.sum(AttachmentUsage.size), 0)).where(
            AttachmentUsage.user_id == x_user_id, AttachmentUsage.created_at > month_ago
        )
    )).scalar_one()
    if used + request.size > config.ATTACHMENT_MONTHLY_BYTES_PER_USER:
        raise HTTPException(status_code=429, detail="attachment_monthly_limit")
    uploads = (await db.execute(
        select(func.count()).select_from(AttachmentUsage).where(AttachmentUsage.created_at > month_ago)
    )).scalar_one()
    if uploads >= config.ATTACHMENT_MONTHLY_UPLOADS:
        raise HTTPException(status_code=429, detail="attachment_relay_monthly_limit")
    stored = (await db.execute(select(func.coalesce(func.sum(Attachment.size), 0)))).scalar_one()
    if stored + request.size > config.ATTACHMENT_STORE_LIMIT_BYTES:
        raise HTTPException(status_code=507, detail="attachment_store_full")

    attachment_id = secrets.token_hex(16)
    db.add(Attachment(
        id=attachment_id,
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        size=request.size,
        created_at=now,
        expires_at=now + timedelta(days=config.ATTACHMENT_TTL_DAYS),
    ))
    db.add(AttachmentUsage(user_id=x_user_id, size=request.size, created_at=now))
    await db.commit()
    url = store.presign(
        "PUT", object_key(attachment_id), UPLOAD_URL_SECONDS,
        signed_headers={"content-length": request.size},
    )
    return ReserveResponse(id=attachment_id, upload_url=url, expires_in=UPLOAD_URL_SECONDS)


async def _own(db: AsyncSession, attachment_id: str) -> Attachment:
    if len(attachment_id) != 32 or any(c not in "0123456789abcdef" for c in attachment_id):
        raise HTTPException(status_code=404, detail="Attachment not found")
    row = await db.get(Attachment, attachment_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Attachment not found")
    return row


@router.post("/{attachment_id}/complete")
async def complete_upload(
    attachment_id: str,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """The sender's upload is done: check the stored size before the recipient may fetch it."""
    store = _store()
    row = await _own(db, attachment_id)
    if row.sender_id != x_user_id:
        raise HTTPException(status_code=404, detail="Attachment not found")
    if row.uploaded_at is not None:
        return {"status": "ok"}
    async with await _http() as client:
        try:
            size = await store.size_of(client, object_key(attachment_id))
        except httpx.HTTPError:
            raise HTTPException(status_code=502, detail="store_unreachable")
        if size is None:
            raise HTTPException(status_code=409, detail="attachment_not_uploaded")
        if size != row.size:
            # Signed sizes make this unlikely; never hand out something else than reserved.
            try:
                await store.delete(client, object_key(attachment_id))
            except httpx.HTTPError:
                pass
            await db.delete(row)
            await db.commit()
            raise HTTPException(status_code=409, detail="attachment_size_mismatch")
    row.uploaded_at = _now()
    await db.commit()
    return {"status": "ok"}


@router.get("/{attachment_id}", response_model=DownloadResponse)
async def download_url(
    attachment_id: str,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> DownloadResponse:
    """A presigned GET for the recipient, once the upload is complete."""
    store = _store()
    row = await _own(db, attachment_id)
    if row.recipient_id != x_user_id:
        raise HTTPException(status_code=404, detail="Attachment not found")
    if row.uploaded_at is None:
        raise HTTPException(status_code=409, detail="attachment_not_uploaded")
    url = store.presign("GET", object_key(attachment_id), DOWNLOAD_URL_SECONDS)
    return DownloadResponse(download_url=url, size=row.size, expires_in=DOWNLOAD_URL_SECONDS)


@router.delete("/{attachment_id}")
async def delete_attachment(
    attachment_id: str,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """The recipient has the file (or either side gives up on it): remove it from the store."""
    store = _store()
    row = await _own(db, attachment_id)
    if x_user_id not in (row.recipient_id, row.sender_id):
        raise HTTPException(status_code=404, detail="Attachment not found")
    async with await _http() as client:
        try:
            await store.delete(client, object_key(attachment_id))
        except httpx.HTTPError:
            # The row stays, so the purge tries again later.
            raise HTTPException(status_code=502, detail="store_unreachable")
    await db.delete(row)
    await db.commit()
    return {"status": "deleted"}


async def purge_attachments(db: AsyncSession) -> int:
    """Removes expired files, unfinished uploads and files whose sender or recipient is gone.

    Returns how many it removed. Usage rows older than 31 days go too.
    """
    store = configured_store()
    now = _now()
    stale = (await db.execute(
        select(Attachment).where(
            (Attachment.expires_at < now)
            | (Attachment.uploaded_at.is_(None) & (Attachment.created_at < now - timedelta(hours=UNFINISHED_UPLOAD_HOURS)))
            | ~Attachment.sender_id.in_(select(Identity.user_id))
            | ~Attachment.recipient_id.in_(select(Identity.user_id))
        ).limit(500)
    )).scalars().all()
    removed = 0
    if stale and store is not None:
        async with await _http() as client:
            for row in stale:
                try:
                    await store.delete(client, object_key(row.id))
                except httpx.HTTPError:
                    continue
                await db.delete(row)
                removed += 1
    await db.execute(delete(AttachmentUsage).where(AttachmentUsage.created_at < now - timedelta(days=31)))
    await db.commit()
    return removed
