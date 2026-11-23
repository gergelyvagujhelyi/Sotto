"""Push target registration endpoints.

Clients register their Firebase installation ID (apps 0.5.5+) or, from older
apps, their FCM registration token, so the server can send wake-up signals
when new messages arrive and the recipient is offline.
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import Identity
from app.models.schemas import PushTokenRequest

router = APIRouter(prefix="/v1/push", tags=["push"])


@router.post("/register")
async def register_push_token(
    request: PushTokenRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Register or update the push target for the authenticated user.

    Registering one kind of target replaces the other: an app that moved to
    installation IDs stops being pushed through its old token.
    """
    result = await db.execute(
        select(Identity).where(Identity.user_id == x_user_id)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="User not found")

    if request.fid is not None:
        identity.fcm_fid = request.fid
        identity.fcm_token = None
    else:
        identity.fcm_token = request.token
        identity.fcm_fid = None
    await db.commit()
    return {"status": "ok"}
