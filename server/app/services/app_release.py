"""Whether the app release that goes with this relay is out, and telling phones when it is.

A merge to develop deploys the relay within minutes, but the app's APK is only built once
the release is tagged, minutes (or days) later. Every answer from the relay names its
version and whether that version's app is out (main.py, the WebSocket's auth_ok), so a
phone on an older app can say an update is on its way, and that it's ready once it is.

The release workflow tells the relay when the APK is published: a call signed with the
workflow's own key (POST /v1/app-release; the public half is CI_RELEASE_PUBLIC_KEY). The
relay then tells every phone at once: over its socket, or with a push. The worst a
stolen key could do is say the app is out early, and then a download fails: the app only
ever downloads the official release (see AppUpdate.kt).

Should that call never come (a relay restart forgets it, or CI couldn't reach the relay),
the relay also looks on GitHub every few minutes until the APK is there. Phones only ask
GitHub through the relay, never themselves.
"""

import asyncio
import base64
import json
import logging
from typing import Awaitable, Callable, Optional

import httpx
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app import config
from app.models.db import Identity
from app.services.push import send_push_notification
from app.services.websocket_manager import ws_manager

log = logging.getLogger(__name__)

# GitHub answers an existing release asset with a redirect to its storage, and a
# missing one (not built or not uploaded yet) with 404.
_READY_STATUSES = {200, 301, 302, 303, 307, 308}

# A signed call older (or further ahead) than this is refused.
CI_CALL_MAX_AGE_MS = 10 * 60 * 1000


class AppRelease:
    def __init__(self, url_template: str, version: str):
        self.version = version
        self.url = url_template.format(version=version) if url_template else ""
        self.ready = False
        # Tells the phones; set at startup (main.py). Runs when the app comes out while
        # this relay runs, not when it finds the app already out at startup.
        self.on_ready: Optional[Callable[[], Awaitable[None]]] = None
        self._seen_missing = False

    @property
    def download_url(self) -> Optional[str]:
        return self.url if self.ready else None

    async def _became_ready(self) -> None:
        self.ready = True
        log.info("App %s is out", self.version)
        if self.on_ready is not None:
            try:
                await self.on_ready()
            except Exception:
                log.exception("Telling phones that the app is out failed")

    async def released_by_ci(self) -> None:
        """The release workflow says the APK is published (its call is verified already)."""
        if not self.ready:
            await self._became_ready()

    async def check(self, client: httpx.AsyncClient) -> bool:
        """Look on GitHub once; True once the APK can be downloaded."""
        if self.ready or not self.url:
            return self.ready
        try:
            response = await client.head(self.url, follow_redirects=False)
        except httpx.HTTPError as e:
            log.info("App release check failed: %s", e)
            return False
        if response.status_code not in _READY_STATUSES:
            self._seen_missing = True
            return False
        if self._seen_missing:
            await self._became_ready()
        else:
            # Out before this relay started: phones heard then, or see it on their next request.
            self.ready = True
        return True

    async def watch(self, interval_s: float, client: Optional[httpx.AsyncClient] = None) -> None:
        """Look every [interval_s] seconds until the APK is there (or CI said so)."""
        if not self.url:
            return
        async with client or httpx.AsyncClient(timeout=10) as http:
            while not await self.check(http):
                await asyncio.sleep(interval_s)


def ci_call_payload(version: str, ts_ms: int) -> bytes:
    """What the release workflow signs (.github/scripts/notify_relay.sh)."""
    return f"sotto-app-release:{version}:{ts_ms}".encode()


def verify_ci_call(version: str, ts_ms: int, signature_b64: str, now_ms: int) -> None:
    """Raise ``ValueError`` unless the release workflow's key signed this call recently."""
    if not config.CI_RELEASE_PUBLIC_KEY:
        raise ValueError("No CI key configured")
    if abs(now_ms - ts_ms) > CI_CALL_MAX_AGE_MS:
        raise ValueError("Call too old or too far ahead")
    try:
        key = Ed25519PublicKey.from_public_bytes(base64.b64decode(config.CI_RELEASE_PUBLIC_KEY))
        key.verify(base64.b64decode(signature_b64, validate=True), ci_call_payload(version, ts_ms))
    except (InvalidSignature, ValueError) as e:
        raise ValueError("Bad signature") from e


async def announce_to_phones(db: AsyncSession, version: str) -> None:
    """Tell every phone the app is out: connected ones over their socket, the others with a push.

    The push is a bare wake-up like a message's: the app fetches, and the answer's
    headers say the app is out. Older apps just fetch.
    """
    await ws_manager.broadcast(json.dumps({"type": "app_release", "version": version}))
    rows = (await db.execute(
        select(Identity.user_id, Identity.fcm_token, Identity.fcm_fid)
        .where(or_(Identity.fcm_token.isnot(None), Identity.fcm_fid.isnot(None)))
    )).all()
    for user_id, token, fid in rows:
        if ws_manager.is_online(user_id):
            continue  # told over the socket
        await send_push_notification(
            fcm_token=token, recipient_id=user_id, fid=fid, signal="app_release",
        )


app_release = AppRelease(config.APP_RELEASE_APK_URL, config.RELAY_VERSION)
