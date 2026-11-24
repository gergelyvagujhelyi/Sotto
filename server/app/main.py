"""Sotto Relay Server

A minimal, privacy-first relay server that:
- Stores public key bundles for contact discovery
- Routes encrypted envelopes between clients
- Sends FCM wake-up signals for offline delivery
- Provides WebSocket connections for real-time delivery
- Deletes envelopes after recipient acknowledgement
- Rate-limits senders to prevent abuse

The server NEVER:
- Sees plaintext message content
- Stores persistent message logs
- Requires personally identifiable information
- Uploads contact books or social graphs
"""

import asyncio
import logging
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi.responses import JSONResponse
from app.config import CORS_ORIGINS, DEBUG, MAX_REQUEST_BODY_BYTES, METRICS_TOKEN, PENDING_MESSAGE_TTL_DAYS, TURN_RELAY_IP, TURN_SECRET
from sqlalchemy import delete, text

from app.database import async_session, engine, get_db
from app.models.schemas import AppReleaseCall, HealthResponse
from app import config
from app.routers import attachments, calling, identity, invites, messages, push, receipts, web, websocket
from app.services import app_release as app_release_service
from app.services.invites import relay_founded
from app.services.object_store import configured_store
from app.services.metrics import MetricsMiddleware, metrics_endpoint
from app.services.websocket_manager import ws_manager

if DEBUG:
    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
else:
    # Structured JSON logging for production (one JSON object per line)
    import json as _json

    class _JsonFormatter(logging.Formatter):
        def format(self, record: logging.LogRecord) -> str:
            return _json.dumps({
                "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
                "level": record.levelname,
                "logger": record.name,
                "msg": record.getMessage(),
                **({"exc": self.formatException(record.exc_info)} if record.exc_info else {}),
            })

    _handler = logging.StreamHandler()
    _handler.setFormatter(_JsonFormatter())
    logging.basicConfig(level=logging.INFO, handlers=[_handler])

# The HTTP client logs every request the relay makes at INFO (each push sent, each release
# check): noise, and a timeline of pushes. Only its warnings and errors are kept.
for _name in ("httpx", "httpcore"):
    logging.getLogger(_name).setLevel(logging.WARNING)


async def _run_alembic_upgrade() -> None:
    """Run Alembic migrations to bring the database schema up to date.

    This is the single source of truth for schema management — both
    in local development and production (Docker entrypoint also runs
    ``alembic upgrade head``; running it here is idempotent).

    Runs in a thread because Alembic's ``command.upgrade`` calls
    ``asyncio.run()`` internally for async engines, which conflicts
    with the already-running event loop.
    """
    import asyncio
    import os

    def _upgrade():
        from alembic.config import Config
        from alembic import command
        from sqlalchemy import inspect, create_engine

        alembic_cfg = Config(os.path.join(os.path.dirname(__file__), "..", "alembic.ini"))
        alembic_cfg.set_main_option("script_location",
            os.path.join(os.path.dirname(__file__), "..", "migrations"))
        from app.config import DATABASE_URL
        alembic_cfg.set_main_option("sqlalchemy.url", DATABASE_URL)

        # If tables already exist (e.g. from create_all in tests), stamp head
        # so Alembic doesn't try to recreate them. Only do this when the
        # alembic_version table is absent (i.e. Alembic has never run).
        sync_url = DATABASE_URL.replace("+aiosqlite", "").replace("+asyncpg", "+psycopg2")
        sync_engine = create_engine(sync_url)
        try:
            inspector = inspect(sync_engine)
            tables = inspector.get_table_names()
            has_alembic = "alembic_version" in tables
            has_app_tables = "identities" in tables
        finally:
            sync_engine.dispose()

        if has_app_tables and not has_alembic:
            log = logging.getLogger(__name__)
            log.info("Tables exist without alembic_version; stamping head")
            command.stamp(alembic_cfg, "head")
            return

        command.upgrade(alembic_cfg, "head")

    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, _upgrade)


async def _detect_gcp_external_ip() -> str:
    """Query the GCP metadata server for this instance's external IP."""
    import httpx
    try:
        async with httpx.AsyncClient(timeout=2) as client:
            resp = await client.get(
                "http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip",
                headers={"Metadata-Flavor": "Google"},
            )
            resp.raise_for_status()
            return resp.text.strip()
    except Exception:
        logging.getLogger(__name__).debug("GCP metadata lookup failed, TURN relay IP not auto-detected")
        return ""


async def _purge_expired_messages() -> None:
    """Periodically delete pending messages older than PENDING_MESSAGE_TTL_DAYS."""
    from datetime import datetime, timedelta, timezone
    from app.models.db import DeliveryReceipt, Invite, PendingMessage, ReadReceipt
    logger = logging.getLogger(__name__)
    while True:
        await asyncio.sleep(3600)  # run once per hour
        try:
            cutoff = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(days=PENDING_MESSAGE_TTL_DAYS)
            async with async_session() as db:
                result = await db.execute(
                    delete(PendingMessage).where(PendingMessage.created_at < cutoff)
                )
                await db.execute(delete(ReadReceipt).where(ReadReceipt.created_at < cutoff))
                await db.execute(delete(DeliveryReceipt).where(DeliveryReceipt.created_at < cutoff))
                # Invites: unused ones once they expire, used ones after 30 days.
                now = datetime.now(timezone.utc).replace(tzinfo=None)
                await db.execute(delete(Invite).where(Invite.used_at.is_(None), Invite.expires_at < now))
                await db.execute(delete(Invite).where(Invite.used_at < now - timedelta(days=30)))
                await db.commit()
                # Attachments: expired, never finished, or whose sender or recipient is gone.
                try:
                    await attachments.purge_attachments(db)
                except Exception:
                    logger.exception("Failed to purge attachments")
                if result.rowcount:
                    logger.info("Purged %d expired pending messages (older than %d days)",
                                result.rowcount, PENDING_MESSAGE_TTL_DAYS)
        except Exception:
            logger.exception("Failed to purge expired pending messages")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start services and run database migrations."""
    await _run_alembic_upgrade()

    # TURN is handled by the coturn Docker container; the app server
    # only needs the relay IP to build ICE server responses.
    turn_ip = TURN_RELAY_IP
    if not turn_ip:
        turn_ip = await _detect_gcp_external_ip()
    if turn_ip and TURN_SECRET:
        app.state.turn_relay_ip = turn_ip
    elif TURN_SECRET:
        logging.getLogger(__name__).warning(
            "TURN_SECRET is set but no relay IP found; TURN relay will be disabled."
        )

    purge_task = asyncio.create_task(_purge_expired_messages())

    async def tell_phones() -> None:
        async with async_session() as db:
            await app_release_service.announce_to_phones(db, app_release_service.app_release.version)

    async def on_app_ready() -> None:
        # In the background: pushes to every phone shouldn't hold up the workflow's call.
        ws_manager.spawn(tell_phones())

    app_release_service.app_release.on_ready = on_app_ready
    release_task = asyncio.create_task(
        app_release_service.app_release.watch(config.APP_RELEASE_CHECK_SECONDS)
    )

    yield

    for task in (purge_task, release_task):
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
    await ws_manager.shutdown()
    await engine.dispose()


app = FastAPI(
    title="Sotto Relay",
    description="Privacy-first encrypted message relay server.",
    version=config.RELAY_VERSION,
    lifespan=lifespan,
    # Disable docs and OpenAPI schema in production
    docs_url="/docs" if DEBUG else None,
    redoc_url=None,
    openapi_url="/openapi.json" if DEBUG else None,
)

class BodySizeLimitMiddleware:
    """ASGI middleware that rejects requests whose body exceeds the limit.

    Checks Content-Length header when present, and also wraps the ASGI
    receive channel to count bytes as they stream in — protecting against
    chunked-encoded requests that omit Content-Length.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(
            (k.lower(), v)
            for k, v in (scope.get("headers") or [])
        )
        content_length = headers.get(b"content-length")
        if content_length is not None:
            try:
                cl = int(content_length)
            except (ValueError, TypeError):
                response = JSONResponse(status_code=400, content={"detail": "Invalid Content-Length"})
                await response(scope, receive, send)
                return
            if cl < 0 or cl > MAX_REQUEST_BODY_BYTES:
                response = JSONResponse(status_code=413, content={"detail": "Request body too large"})
                await response(scope, receive, send)
                return

        # Wrap receive to track cumulative body size for chunked requests
        total_bytes = 0
        response_started = False

        class _BodyTooLarge(Exception):
            pass

        async def counting_receive():
            nonlocal total_bytes
            msg = await receive()
            if msg["type"] == "http.request":
                total_bytes += len(msg.get("body", b""))
                if total_bytes > MAX_REQUEST_BODY_BYTES:
                    raise _BodyTooLarge()
            return msg

        async def tracking_send(message):
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, counting_receive, tracking_send)
        except _BodyTooLarge:
            if not response_started:
                response = JSONResponse(status_code=413, content={"detail": "Request body too large"})
                await response(scope, receive, send)


app.add_middleware(BodySizeLimitMiddleware)
app.add_middleware(MetricsMiddleware)

# CORS — restrict in production
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"] if DEBUG else CORS_ORIGINS,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["*"],
)

# Register routers
app.include_router(identity.router)
app.include_router(messages.router)
app.include_router(push.router)
app.include_router(websocket.router)
app.include_router(calling.router)
app.include_router(receipts.router)
app.include_router(invites.router)
app.include_router(attachments.router)
app.include_router(web.router)


if DEBUG or METRICS_TOKEN:
    app.add_api_route("/metrics", metrics_endpoint, methods=["GET"], tags=["ops"], include_in_schema=False)


@app.middleware("http")
async def relay_version_headers(request: Request, call_next):
    """Every answer says which Sotto this relay runs and whether that app is out, so a phone
    on an older app knows at once (services/app_release.py)."""
    response = await call_next(request)
    release = app_release_service.app_release
    response.headers["Sotto-Relay-Version"] = release.version
    response.headers["Sotto-App-Ready"] = "true" if release.ready else "false"
    return response


@app.post("/v1/app-release", status_code=202, tags=["health"])
async def app_release_published(call: AppReleaseCall) -> dict:
    """The release workflow says the app of this relay's version is published. Only a call
    signed with the workflow's key counts (CI_RELEASE_PUBLIC_KEY)."""
    try:
        app_release_service.verify_ci_call(call.version, call.ts, call.signature, int(time.time() * 1000))
    except ValueError:
        raise HTTPException(status_code=401, detail="Not signed by the release workflow")
    release = app_release_service.app_release
    if call.version != release.version:
        # Released before this relay was deployed (or after a newer one): it finds out by itself.
        raise HTTPException(status_code=409, detail=f"This relay runs {release.version}")
    await release.released_by_ci()
    return {"ready": True}


@app.get("/health", response_model=HealthResponse, tags=["health"])
async def health_check(db: AsyncSession = Depends(get_db)) -> HealthResponse:
    try:
        await db.execute(text("SELECT 1"))
        founded = await relay_founded(db)
    except Exception:
        return JSONResponse(
            status_code=503,
            content={"status": "unhealthy", "version": app.version, "detail": "Database unreachable"},
        )
    return HealthResponse(
        version=app.version,
        invite_required=config.INVITE_ONLY and founded,
        attachment_max_bytes=config.ATTACHMENT_MAX_BYTES if configured_store() is not None else 0,
    )
