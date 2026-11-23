"""Settings that only work if docker-compose.yml forwards them from .env to the relay."""

import re
from pathlib import Path

COMPOSE = (Path(__file__).resolve().parents[1] / "docker-compose.yml").read_text()


def _server_environment() -> str:
    """The relay service's `environment:` block."""
    service = COMPOSE.split("\n  server:\n", 1)[1].split("\n  nginx:", 1)[0]
    return service.split("environment:", 1)[1].split("depends_on:", 1)[0]


def test_single_use_tokens_reach_the_relay_and_stay_off_by_default():
    # Without this line, AUTH_SINGLE_USE_TOKENS=true in .env never reached the relay.
    line = re.search(r"AUTH_SINGLE_USE_TOKENS:\s*(\S+)", _server_environment())
    assert line, "docker-compose.yml must forward AUTH_SINGLE_USE_TOKENS to the server"
    assert line.group(1) == "${AUTH_SINGLE_USE_TOKENS:-false}"


def test_attachment_settings_reach_the_relay():
    # .env alone does nothing: docker-compose.yml must pass each one on.
    env = _server_environment()
    for name in (
        "S3_ENDPOINT", "S3_BUCKET", "S3_ACCESS_KEY_ID", "S3_SECRET", "S3_REGION",
        "ATTACHMENT_MAX_BYTES", "ATTACHMENT_MONTHLY_BYTES_PER_USER",
        "ATTACHMENT_STORE_LIMIT_BYTES", "ATTACHMENT_MONTHLY_UPLOADS", "ATTACHMENT_PART_BYTES",
    ):
        assert re.search(rf"\b{name}:\s*\$\{{{name}:-", env), f"docker-compose.yml must forward {name}"
