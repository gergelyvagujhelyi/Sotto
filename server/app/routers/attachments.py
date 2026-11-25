"""Encrypted attachments (ADR-29): the relay signs URLs, the phones move the bytes.

1. The sender reserves an upload (`POST /v1/attachments`) for a recipient (or, for a
   group, up to MAX_RECIPIENTS of them) and the ciphertext's exact size, and gets a presigned PUT whose content-length is
   signed, so the store refuses any other size.
2. It uploads straight to the store, then confirms (`POST .../complete`); the
   relay checks the stored size with a HEAD.
3. The key travels to the recipient inside an ordinary end-to-end encrypted
   message, with the attachment's id. The recipient asks for a presigned GET
   (`GET /v1/attachments/{id}`), downloads, and deletes it (`DELETE`). With several
   recipients, a recipient's delete drops only its own claim; the object goes with the last.

Every request is signed by the account like all others. The relay learns who
sends a file to whom and its size, never its content, name or key. For a file with
several recipients it learns that they all got the same file, never that they form a group.
"""

import secrets
from datetime import datetime, timedelta, timezone
from typing import Optional

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app import config
from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import Attachment, AttachmentRecipient, AttachmentUsage, Identity
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
# A group has at most 32 members, so a file goes to at most 31 others.
MAX_RECIPIENTS = 31


class ReserveRequest(BaseModel):
    # One recipient (`recipient_id`, as before) or several (`recipient_ids`), not both.
    recipient_id: str = Field("", max_length=32)
    recipient_ids: list[str] = Field(default_factory=list, max_length=MAX_RECIPIENTS)
    size: int = Field(..., gt=0)

    def recipients(self) -> list[str]:
        if self.recipient_id and self.recipient_ids:
            raise HTTPException(status_code=400, detail="attachment_recipients_ambiguous")
        wanted = [self.recipient_id] if self.recipient_id else self.recipient_ids
        if not wanted or any(not r or len(r) > 32 for r in wanted):
            raise HTTPException(status_code=422, detail="attachment_recipients_invalid")
        if len(set(wanted)) != len(wanted):
            raise HTTPException(status_code=400, detail="attachment_recipients_repeated")
        return wanted


class ReserveResponse(BaseModel):
    id: str
    # One URL for a small file; for a large one, upload_url is empty and part_urls has one
    # URL per part_size bytes (the last part is the rest), uploaded in that order.
    upload_url: str
    expires_in: int
    part_size: int = 0
    part_urls: list[str] = []


class PartsResponse(BaseModel):
    part_size: int
    part_urls: list[str]
    expires_in: int


class CompleteRequest(BaseModel):
    # The ETag the store returned for each part, in order (multipart uploads only).
    etags: list[str] = Field(default_factory=list, max_length=10_000)


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
    # A malformed request is refused before it uses up a token of the limit.
    recipients = request.recipients()
    if x_user_id in recipients:
        raise HTTPException(status_code=400, detail="attachment_to_self")
    if not await limiter.allow(x_user_id):
        raise HTTPException(status_code=429, detail="attachment_rate_limited")
    if request.size > config.ATTACHMENT_MAX_BYTES:
        raise HTTPException(status_code=413, detail="attachment_too_large")
    # The sender's row, locked: two reservations can't both slip under the allowance.
    sender = (await db.execute(
        select(Identity.user_id).where(Identity.user_id == x_user_id).with_for_update()
    )).scalar_one_or_none()
    if sender is None:
        raise HTTPException(status_code=404, detail="User not found")
    found = (await db.execute(
        select(Identity.user_id).where(Identity.user_id.in_(recipients))
    )).scalars().all()
    if len(set(found)) != len(recipients):
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
    upload_id = None
    if request.size > config.ATTACHMENT_PART_BYTES:
        async with await _http() as client:
            try:
                upload_id = await store.create_multipart(client, object_key(attachment_id))
            except httpx.HTTPError:
                raise HTTPException(status_code=502, detail="store_unreachable")
    db.add(Attachment(
        id=attachment_id,
        sender_id=x_user_id,
        recipient_id=recipients[0],
        size=request.size,
        created_at=now,
        expires_at=now + timedelta(days=config.ATTACHMENT_TTL_DAYS),
        upload_id=upload_id,
    ))
    for recipient_id in recipients:
        db.add(AttachmentRecipient(attachment_id=attachment_id, recipient_id=recipient_id))
    db.add(AttachmentUsage(user_id=x_user_id, size=request.size, created_at=now))
    await db.commit()
    if upload_id is not None:
        urls = _part_urls(store, attachment_id, upload_id, request.size)
        return ReserveResponse(
            id=attachment_id, upload_url="", expires_in=UPLOAD_URL_SECONDS,
            part_size=config.ATTACHMENT_PART_BYTES, part_urls=urls,
        )
    url = store.presign(
        "PUT", object_key(attachment_id), UPLOAD_URL_SECONDS,
        signed_headers={"content-length": request.size},
    )
    return ReserveResponse(id=attachment_id, upload_url=url, expires_in=UPLOAD_URL_SECONDS)


def _part_urls(store: ObjectStore, attachment_id: str, upload_id: str, size: int) -> list[str]:
    part = config.ATTACHMENT_PART_BYTES
    count = (size + part - 1) // part
    return [
        store.presign_part(
            object_key(attachment_id), upload_id, n + 1,
            min(part, size - n * part), UPLOAD_URL_SECONDS,
        )
        for n in range(count)
    ]


@router.post("/{attachment_id}/parts", response_model=PartsResponse)
async def part_urls(
    attachment_id: str,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> PartsResponse:
    """Fresh part URLs for the sender's unfinished multipart upload, to resume it."""
    store = _store()
    row = await _own(db, attachment_id)
    if row.sender_id != x_user_id or row.upload_id is None or row.uploaded_at is not None:
        raise HTTPException(status_code=404, detail="Attachment not found")
    return PartsResponse(
        part_size=config.ATTACHMENT_PART_BYTES,
        part_urls=_part_urls(store, attachment_id, row.upload_id, row.size),
        expires_in=UPLOAD_URL_SECONDS,
    )


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
    body: Optional[CompleteRequest] = None,
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
        if row.upload_id is not None:
            parts = (row.size + config.ATTACHMENT_PART_BYTES - 1) // config.ATTACHMENT_PART_BYTES
            etags = body.etags if body else []
            if len(etags) != parts or any(not t or len(t) > 200 for t in etags):
                raise HTTPException(status_code=400, detail="attachment_parts_missing")
            try:
                # A retry after the parts were joined (only the check below failed) finds
                # the object there already; joining again would be refused.
                if await store.size_of(client, object_key(attachment_id)) is None:
                    await store.complete_multipart(client, object_key(attachment_id), row.upload_id, etags)
            except httpx.HTTPError:
                raise HTTPException(status_code=409, detail="attachment_not_uploaded")
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
            await db.execute(delete(AttachmentRecipient).where(AttachmentRecipient.attachment_id == attachment_id))
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
    if not await _is_recipient(db, attachment_id, x_user_id):
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
    """A recipient has the file, or the sender gives up on it.

    A recipient's delete drops only its own claim; the object goes from the store once no
    recipient is left. The sender's removes it for everyone.
    """
    store = _store()
    # The row, locked: deletes of one attachment (its last two recipients at once, or the
    # sender and a recipient) take turns, so the last one sees no claim left and removes the
    # object, and a later one finds the row gone (404) instead of deleting it twice.
    await _own(db, attachment_id)
    row = (await db.execute(
        select(Attachment).where(Attachment.id == attachment_id).with_for_update()
    )).scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=404, detail="Attachment not found")
    if x_user_id != row.sender_id:
        if not await _is_recipient(db, attachment_id, x_user_id):
            raise HTTPException(status_code=404, detail="Attachment not found")
        await db.execute(delete(AttachmentRecipient).where(
            AttachmentRecipient.attachment_id == attachment_id,
            AttachmentRecipient.recipient_id == x_user_id,
        ))
        left = (await db.execute(
            select(func.count()).select_from(AttachmentRecipient)
            .where(AttachmentRecipient.attachment_id == attachment_id)
        )).scalar_one()
        if left > 0:
            await db.commit()
            return {"status": "deleted"}
    async with await _http() as client:
        try:
            await _remove(store, client, row)
        except httpx.HTTPError:
            # The row stays, so the purge tries again later.
            raise HTTPException(status_code=502, detail="store_unreachable")
    await db.execute(delete(AttachmentRecipient).where(AttachmentRecipient.attachment_id == attachment_id))
    # A plain DELETE: a purge that removed the row meanwhile leaves nothing to fail on.
    await db.execute(delete(Attachment).where(Attachment.id == attachment_id))
    await db.commit()
    return {"status": "deleted"}


async def _is_recipient(db: AsyncSession, attachment_id: str, user_id: str) -> bool:
    return (await db.execute(
        select(AttachmentRecipient.recipient_id).where(
            AttachmentRecipient.attachment_id == attachment_id,
            AttachmentRecipient.recipient_id == user_id,
        )
    )).scalar_one_or_none() is not None


async def _remove(store: ObjectStore, client: httpx.AsyncClient, row: Attachment) -> None:
    """Deletes the object, and an unfinished multipart upload's parts (which a delete leaves)."""
    if row.upload_id is not None and row.uploaded_at is None:
        await store.abort_multipart(client, object_key(row.id), row.upload_id)
    await store.delete(client, object_key(row.id))


async def purge_attachments(db: AsyncSession) -> int:
    """Removes expired files, unfinished uploads and files whose sender or every recipient is gone.

    Returns how many it removed. Usage rows older than 31 days go too.
    """
    store = configured_store()
    now = _now()
    # A deleted account no longer waits for anything.
    await db.execute(delete(AttachmentRecipient).where(
        ~AttachmentRecipient.recipient_id.in_(select(Identity.user_id))
    ))
    stale = (await db.execute(
        select(Attachment).where(
            (Attachment.expires_at < now)
            | (Attachment.uploaded_at.is_(None) & (Attachment.created_at < now - timedelta(hours=UNFINISHED_UPLOAD_HOURS)))
            | ~Attachment.sender_id.in_(select(Identity.user_id))
            | ~Attachment.id.in_(select(AttachmentRecipient.attachment_id))
        ).limit(500)
    )).scalars().all()
    removed = 0
    if stale and store is not None:
        async with await _http() as client:
            for row in stale:
                try:
                    await _remove(store, client, row)
                except httpx.HTTPError:
                    continue
                await db.execute(delete(AttachmentRecipient).where(AttachmentRecipient.attachment_id == row.id))
                await db.execute(delete(Attachment).where(Attachment.id == row.id))
                removed += 1
    await db.execute(delete(AttachmentUsage).where(AttachmentUsage.created_at < now - timedelta(days=31)))
    await db.commit()
    return removed
