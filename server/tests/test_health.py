"""Health check test."""

import pytest
from httpx import AsyncClient

from app import config


@pytest.mark.asyncio
async def test_health_endpoint(client: AsyncClient):
    resp = await client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["version"] == config.RELAY_VERSION
    assert "version" in data
