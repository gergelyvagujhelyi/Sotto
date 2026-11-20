"""REST endpoints for voice call signaling and ICE server configuration."""

import asyncio
import base64
import hashlib
import hmac
import time

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_auth_token
from app.database import get_db
from app.models.db import Identity
from app.models.schemas import (
    CallSignalRequest,
    CallSignalResponse,
    IceServer,
    IceServersResponse,
)
from app.config import TURN_CREDENTIAL_TTL, TURN_SECRET
from app.services.call_rate_limiter import allow_call_offer, allow_call_signal
from app.services.push import send_push_notification
import app.services.websocket_manager as websocket_manager
from app.services.websocket_manager import ws_manager

router = APIRouter(prefix="/v1/calls", tags=["calling"])

from app.services.call_signals import ALLOWED_SIGNAL_TYPES as _ALLOWED_SIGNAL_TYPES


@router.post("/signal", response_model=CallSignalResponse)
async def relay_signal(
    request: CallSignalRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> CallSignalResponse:
    if request.signal_type not in _ALLOWED_SIGNAL_TYPES:
        raise HTTPException(status_code=400, detail="Invalid signal type")
    if request.recipient_id == x_user_id:
        raise HTTPException(status_code=400, detail="Cannot signal self")

    # Per-user rate limit, shared with the WebSocket path.
    if not await allow_call_signal(x_user_id):
        raise HTTPException(status_code=429, detail="Rate limit exceeded")

    # Offers wake the recipient with a high-priority push: limit them separately
    # (shared with the WebSocket path).
    if request.signal_type == "call_offer" and not await allow_call_offer(x_user_id):
        raise HTTPException(status_code=429, detail="Too many calls")

    async def recipient_exists() -> bool:
        result = await db.execute(
            select(Identity.user_id).where(Identity.user_id == request.recipient_id)
        )
        return result.scalar_one_or_none() is not None

    delivered = await ws_manager.relay_call_signal(
        recipient_exists=recipient_exists,
        sender_id=x_user_id,
        recipient_id=request.recipient_id,
        signal_type=request.signal_type,
        call_id=request.call_id,
        encrypted_payload=request.encrypted_payload,
        signature=request.signature,
        pqc_signature=request.pqc_signature,
        kem_ciphertext=request.kem_ciphertext,
        expect_answer=request.signal_type == "call_offer",
    )

    if request.signal_type == "call_offer":
        if not delivered:
            # FCM push fallback — wake the recipient's app so it connects its
            # WebSocket and picks up the buffered signal.
            await _wake_for_call(db, request.recipient_id, x_user_id)
        else:
            # A socket took it, but the app behind it may be paused in the background:
            # if it doesn't answer soon, keep the call for it and wake it after all.
            ws_manager.spawn(_wake_unless_answered(db.bind, request.recipient_id, request.call_id))

    return CallSignalResponse(delivered=delivered)


async def _wake_for_call(db: AsyncSession, callee_id: str, caller_id: str) -> None:
    """Push to the callee's phone, if it has a push target."""
    target = (await db.execute(
        select(Identity.fcm_token, Identity.fcm_fid).where(Identity.user_id == callee_id)
    )).one_or_none()
    if target is not None and (target.fcm_fid or target.fcm_token):
        await send_push_notification(
            fcm_token=target.fcm_token,
            fid=target.fcm_fid,
            recipient_id=callee_id,
            sender_id=caller_id,
            for_call=True,
        )


async def _wake_unless_answered(engine, callee_id: str, call_id: str) -> None:
    # Until the app answers (or the call ends), or OFFER_CONFIRM_SECONDS pass. If the
    # sockets that took the offer die meanwhile, it was never read: act at once.
    deadline = time.monotonic() + websocket_manager.OFFER_CONFIRM_SECONDS
    while time.monotonic() < deadline and ws_manager.offer_sockets_open(callee_id, call_id):
        await asyncio.sleep(websocket_manager.SOCKET_CHECK_INTERVAL)
    caller_id = await ws_manager.park_unconfirmed_offer(callee_id, call_id)
    if caller_id is None:
        return  # the app answered, or the call ended
    async with AsyncSession(engine) as db:
        await _wake_for_call(db, callee_id, caller_id)


@router.get("/ice-servers", response_model=IceServersResponse)
async def get_ice_servers(
    request: Request,
    user_id: str = Depends(verify_auth_token),
) -> IceServersResponse:
    """Return ICE server configuration for WebRTC calls.

    STUN comes from our own coturn, never a third party: phones send STUN requests to
    every listed server while gathering candidates, even for relay-only calls, so a
    public STUN server would learn each phone's IP address and when it calls.
    """
    relay_ip = getattr(request.app.state, "turn_relay_ip", None)
    if not (relay_ip and TURN_SECRET):
        # Development and tests, without a relay of our own.
        return IceServersResponse(ice_servers=[IceServer(urls="stun:stun.l.google.com:19302")])

    # Ephemeral credentials (coturn --use-auth-secret compatible)
    expiry = int(time.time()) + TURN_CREDENTIAL_TTL
    username = f"{expiry}:{user_id}"
    credential = base64.b64encode(
        hmac.new(TURN_SECRET.encode(), username.encode(), hashlib.sha1).digest()
    ).decode()

    return IceServersResponse(ice_servers=[
        IceServer(urls=f"stun:{relay_ip}:3478"),
        IceServer(
            urls=f"turn:{relay_ip}:3478?transport=udp",
            username=username,
            credential=credential,
        ),
    ])
