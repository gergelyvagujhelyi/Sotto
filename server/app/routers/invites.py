"""Invites: a member's one-time code that lets someone create an account here."""

from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app import config
from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import Identity, Invite
from app.models.schemas import InviteResponse
from app.services import invites
from app.services.call_rate_limiter import SlidingWindowLimiter

router = APIRouter(prefix="/v1/invites", tags=["invites"])

# A burst limit on top of the per-day count kept in the database.
limiter = SlidingWindowLimiter(limit=5)


@router.post("", response_model=InviteResponse)
async def create_invite(
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> InviteResponse:
    """Create a one-time invite. Any member may, a few at a time.

    The request is signed with the member's keys like every authenticated one.
    The code is returned once; the relay keeps only its hash.
    """
    if not await limiter.allow(x_user_id):
        raise HTTPException(status_code=429, detail="invite_rate_limited")
    # The member's row, locked: the counts and the insert can't race, and a
    # concurrent account deletion waits for this.
    member = (await db.execute(
        select(Identity.user_id).where(Identity.user_id == x_user_id).with_for_update()
    )).scalar_one_or_none()
    if member is None:
        raise HTTPException(status_code=404, detail="User not found")
    now = invites.utcnow()
    open_count = (await db.execute(
        select(func.count()).select_from(Invite).where(
            Invite.created_by == x_user_id, Invite.used_at.is_(None), Invite.expires_at > now
        )
    )).scalar_one()
    if open_count >= config.MAX_OPEN_INVITES_PER_MEMBER:
        raise HTTPException(status_code=429, detail="invite_limit_open")
    today = (await db.execute(
        select(func.count()).select_from(Invite).where(
            Invite.created_by == x_user_id, Invite.created_at > now - timedelta(days=1)
        )
    )).scalar_one()
    if today >= config.MAX_INVITES_PER_DAY:
        raise HTTPException(status_code=429, detail="invite_limit_daily")

    code = invites.new_code()
    expires_at = now + timedelta(days=config.INVITE_TTL_DAYS)
    db.add(Invite(code_hash=invites.hash_code(code), created_by=x_user_id, created_at=now, expires_at=expires_at))
    await db.commit()
    return InviteResponse(code=invites.display_code(code), expires_at=expires_at.isoformat() + "Z")
