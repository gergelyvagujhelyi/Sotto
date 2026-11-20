"""The relay names the release it belongs to: the app's version in this repository."""

import re
from pathlib import Path

from app import config


def test_relay_version_is_the_app_version():
    # /health said 0.6.0 for five releases because nothing tied the two together.
    gradle = Path(__file__).resolve().parents[2] / "app" / "build.gradle.kts"
    app_version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle.read_text()).group(1)
    assert config.RELAY_VERSION == app_version, (
        f"Set RELAY_VERSION in server/app/config.py to {app_version}, the app's versionName"
    )
