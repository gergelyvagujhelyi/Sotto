"""WebSocket endpoint for real-time message delivery signals.

Clients authenticate with a signed token and receive JSON notifications when
new messages are available. Message content is never sent over WebSocket —
only a signal to fetch from the REST API.

Protocol:
- Client sends: {"type": "auth", "token": "<user_id>.<ts_ms>.<sig_b64>"}
- Server sends: {"type": "auth_ok", "relay_version": "...", "app_ready": bool} on success
- Server sends: {"type": "app_release", "version": "..."} when the app of its version is out
- Server sends: {"type": "new_message", "sender_id": "..."}
- Client sends: {"type": "ping"} periodically
- Server sends: {"type": "pong"}
"""

import asyncio
import json
import logging
from typing import Optional

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_token
from app.config import WS_IDLE_TIMEOUT_SECONDS
from app.database import get_db
from app.models.db import Identity
from app.services import app_release as app_release_service
from app.services.websocket_manager import ws_manager

router = APIRouter(tags=["websocket"])
logger = logging.getLogger(__name__)

_MAX_AUTH_MESSAGE_BYTES = 8192      # Auth/ping messages are small
_MAX_SIGNAL_MESSAGE_BYTES = 81920   # Call signals can carry large encrypted payloads



@router.websocket("/v1/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    db: AsyncSession = Depends(get_db),
) -> None:
    """Handle a WebSocket connection for real-time delivery signals."""
    user_id: Optional[str] = None

    try:
        # Wait for auth message (with timeout to prevent pre-auth resource exhaustion)
        await websocket.accept()
        try:
            raw = await asyncio.wait_for(websocket.receive_text(), timeout=10)
        except asyncio.TimeoutError:
            await websocket.close(code=4008, reason="Auth timeout")
            return
        if len(raw) > _MAX_AUTH_MESSAGE_BYTES:
            await websocket.close(code=4009, reason="Message too large")
            return
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Invalid JSON"})
            )
            await websocket.close(code=4002)
            return

        if msg.get("type") != "auth" or not msg.get("token"):
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Expected auth message with token"})
            )
            await websocket.close(code=4001)
            return

        token = msg["token"]

        # Verify signed auth token against stored public key
        parts = token.split(".")
        if len(parts) < 3 or len(parts) > 4:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Authentication failed"})
            )
            await websocket.close(code=4003)
            return

        claimed_user_id = parts[0]
        result = await db.execute(
            select(Identity.public_signing_key, Identity.pqc_signing_key).where(
                Identity.user_id == claimed_user_id
            )
        )
        row = result.one_or_none()
        # Explicitly close the DB session after auth to release the connection
        # back to the pool. Without this, the session stays open for the entire
        # WebSocket lifetime (potentially hours), exhausting the pool.
        # Any later DB use must be short and close the session again.
        await db.close()
        if row is None:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Authentication failed"})
            )
            await websocket.close(code=4003)
            return
        pub_key_b64, pqc_signing_key_b64 = row

        try:
            user_id = verify_token(token, pub_key_b64, pqc_signing_key_b64 or "")
        except ValueError as e:
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Authentication failed"})
            )
            await websocket.close(code=4003)
            return

        # Register with the manager (accept was already called above)
        await ws_manager.register(user_id, websocket)

        # A key rotation or deletion that committed after the lookup above ran
        # its disconnect_user() before this socket was registered, so the purge
        # missed it. Re-check now that we are registered: anything committing
        # from here on will find and close this socket.
        # (A closed AsyncSession can be used again; close it again right after.)
        try:
            current = (await db.execute(
                select(Identity.public_signing_key).where(Identity.user_id == user_id)
            )).scalar_one_or_none()
        finally:
            await db.close()
        if current != pub_key_b64:
            await ws_manager.disconnect(user_id, websocket)
            await websocket.send_text(
                json.dumps({"type": "error", "message": "Authentication failed"})
            )
            await websocket.close(code=4003)
            user_id = None  # already unregistered; skip the cleanup in finally
            return
        logger.info("WebSocket authenticated: %s", user_id[:8])

        # Which Sotto the relay runs, and whether that app is out: a phone on an older app
        # says so (services/app_release.py). Older apps ignore the extra fields.
        release = app_release_service.app_release
        await websocket.send_text(
            json.dumps({"type": "auth_ok", "relay_version": release.version, "app_ready": release.ready})
        )

        # Flush pending call signals AFTER auth_ok so the client doesn't
        # mistake a buffered signal for a failed auth response. The flush also
        # opens the socket to sends: until now everything for it was held back.
        await ws_manager.flush_pending_signals(user_id, websocket)

        # Keep alive loop — idle connections are closed after timeout
        while True:
            try:
                raw = await asyncio.wait_for(
                    websocket.receive_text(),
                    timeout=WS_IDLE_TIMEOUT_SECONDS,
                )
            except asyncio.TimeoutError:
                await websocket.close(code=4008, reason="Idle timeout")
                break
            if len(raw) > _MAX_SIGNAL_MESSAGE_BYTES:
                await websocket.send_text(
                    json.dumps({"type": "error", "message": "Message too large"})
                )
                continue
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_text(
                    json.dumps({"type": "error", "message": "Invalid JSON"})
                )
                continue

            msg_type = msg.get("type")

            if msg_type == "ping":
                await websocket.send_text(json.dumps({"type": "pong"}))

            # Nothing else is taken from the socket: call signals go through
            # POST /v1/calls/signal (no app ever sent them here).

    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("WebSocket error for user %s", user_id[:8] if user_id else "unknown")
    finally:
        if user_id:
            await ws_manager.disconnect(user_id, websocket)
            # Rate limit state is preserved for the TTL duration even after disconnect
            # to prevent quota reset via reconnect. Stale entries are pruned periodically.
