"""Test fixtures for the relay server."""

import os
import tempfile

# Must run before anything imports app.config. Tests that use Starlette's
# TestClient run the app's lifespan, which runs `alembic upgrade head` against
# DATABASE_URL; with the default that was the developer's ./sotto.db,
# which the test suite then migrated. Always point it at a throwaway file.
os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///" + os.path.join(
    tempfile.mkdtemp(prefix="sotto-tests-"), "lifespan.db"
)
# Tests create accounts freely; the invite tests switch invite-only on themselves.
# Set here so a developer's server/.env can't change it either.
os.environ["INVITE_ONLY"] = "false"
# Never look for app releases on GitHub from the tests (TestClient runs the lifespan).
os.environ["APP_RELEASE_APK_URL"] = ""

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.database import get_db
from app.main import app
from app.models.db import Base

# In-memory SQLite for tests
TEST_DATABASE_URL = "sqlite+aiosqlite:///:memory:"

engine = create_async_engine(TEST_DATABASE_URL, echo=False)
test_session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def override_get_db():
    async with test_session() as session:
        yield session


app.dependency_overrides[get_db] = override_get_db


@pytest_asyncio.fixture(autouse=True)
async def setup_db():
    """Create tables before each test, drop after. Reset rate limiter state."""
    from app.services.ip_rate_limiter import _buckets
    _buckets.clear()
    from app.services import call_rate_limiter
    call_rate_limiter.reset()
    from app.routers import receipts
    receipts.limiter.reset()
    from app.routers import invites
    invites.limiter.reset()
    from app.routers import attachments
    attachments.limiter.reset()

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


@pytest_asyncio.fixture
async def client():
    """Async HTTP client for testing."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
