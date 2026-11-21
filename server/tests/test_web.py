"""The invite page and Android App Links."""

import base64
import hashlib
import re

import pytest
from httpx import AsyncClient

from app import config


def _hash(text: str) -> str:
    return "'sha256-" + base64.b64encode(hashlib.sha256(text.encode("utf-8")).digest()).decode() + "'"


@pytest.mark.asyncio
async def test_invite_page_allows_only_its_own_script_and_style(client: AsyncClient):
    resp = await client.get("/invite")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/html")
    page = resp.text
    scripts = re.findall(r"<script>(.*?)</script>", page, re.S)
    styles = re.findall(r"<style>(.*?)</style>", page, re.S)
    assert len(scripts) == 1 and len(styles) == 1
    csp = resp.headers["content-security-policy"]
    assert "default-src 'none'" in csp
    assert f"script-src {_hash(scripts[0])};" in csp
    assert f"style-src {_hash(styles[0])};" in csp
    assert "frame-ancestors 'none'" in csp
    assert resp.headers["x-robots-tag"] == "noindex"


@pytest.mark.asyncio
async def test_invite_page_loads_nothing_from_elsewhere(client: AsyncClient):
    page = (await client.get("/invite")).text
    assert "src=" not in page  # no external scripts, images or frames
    assert "innerHTML" not in page  # the code from the link only ever goes in as text
    assert "location.hash" in page  # the code is read in the browser, never sent here
    assert f'href="{config.APP_DOWNLOAD_URL}"' in page
    # By default that's Sotto's page on GitHub, never the APK itself.
    assert config.APP_DOWNLOAD_URL == "https://github.com/gergelyvagujhelyi/Sotto"
    assert ".apk" not in page
    assert "sotto://invite?r=" in page


@pytest.mark.asyncio
async def test_asset_links_name_the_app(client: AsyncClient):
    resp = await client.get("/.well-known/assetlinks.json")
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "application/json"
    [statement] = resp.json()
    assert statement["relation"] == ["delegate_permission/common.handle_all_urls"]
    assert statement["target"]["namespace"] == "android_app"
    assert statement["target"]["package_name"] == "com.vagujhelyigergely.sotto"
    fingerprints = statement["target"]["sha256_cert_fingerprints"]
    assert fingerprints and all(re.fullmatch(r"([0-9A-F]{2}:){31}[0-9A-F]{2}", f) for f in fingerprints)


@pytest.mark.asyncio
async def test_asset_links_follow_the_settings(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(config, "APP_LINK_PACKAGE", "com.example.sotto")
    monkeypatch.setattr(config, "APP_LINK_CERT_FINGERPRINTS", ["AB:" * 31 + "CD"])
    [statement] = (await client.get("/.well-known/assetlinks.json")).json()
    assert statement["target"]["package_name"] == "com.example.sotto"
    assert statement["target"]["sha256_cert_fingerprints"] == ["AB:" * 31 + "CD"]

    monkeypatch.setattr(config, "APP_LINK_CERT_FINGERPRINTS", [])
    assert (await client.get("/.well-known/assetlinks.json")).json() == []
