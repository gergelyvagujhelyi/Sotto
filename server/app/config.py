"""Server configuration loaded from environment variables."""

import os
from typing import Optional

from dotenv import load_dotenv

load_dotenv()

# The release this relay belongs to: the app's versionName in app/build.gradle.kts, so
# /health tells which code a relay runs. tests/test_version.py keeps the two in step.
RELAY_VERSION = "0.7.0"

DATABASE_URL: str = os.getenv(
    "DATABASE_URL", "sqlite+aiosqlite:///./sotto.db"
)
FIREBASE_CREDENTIALS: Optional[str] = os.getenv("FIREBASE_CREDENTIALS")
HOST: str = os.getenv("HOST", "0.0.0.0")
PORT: int = int(os.getenv("PORT", "8000"))
DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"
MAX_MESSAGES_PER_MINUTE: int = int(os.getenv("MAX_MESSAGES_PER_MINUTE", "60"))
MAX_PENDING_MESSAGES_PER_USER: int = int(
    os.getenv("MAX_PENDING_MESSAGES_PER_USER", "1000")
)
# Share of one recipient's queue a single sender may occupy.
MAX_PENDING_PER_SENDER: int = int(
    os.getenv("MAX_PENDING_PER_SENDER", str(max(1, MAX_PENDING_MESSAGES_PER_USER // 4)))
)
# Accounts are free to create, so a handful of fresh ones could fill a
# recipient's queue. Senders younger than NEW_ACCOUNT_AGE_HOURS get a much
# smaller per-recipient allowance, and all young senders together may only
# use NEW_ACCOUNTS_PENDING_TOTAL of a recipient's queue.
NEW_ACCOUNT_AGE_HOURS: int = int(os.getenv("NEW_ACCOUNT_AGE_HOURS", "24"))
NEW_ACCOUNT_PENDING_PER_RECIPIENT: int = int(os.getenv("NEW_ACCOUNT_PENDING_PER_RECIPIENT", "20"))
NEW_ACCOUNTS_PENDING_TOTAL: int = int(os.getenv("NEW_ACCOUNTS_PENDING_TOTAL", "100"))
MAX_REQUEST_BODY_BYTES: int = int(os.getenv("MAX_REQUEST_BODY_BYTES", str(2 * 1024 * 1024)))  # 2 MB
CORS_ORIGINS: list = [o.strip() for o in os.getenv("CORS_ORIGINS", "").split(",") if o.strip()]
WS_IDLE_TIMEOUT_SECONDS: int = int(os.getenv("WS_IDLE_TIMEOUT_SECONDS", "300"))  # 5 min
METRICS_TOKEN: str = os.getenv("METRICS_TOKEN", "")
# Auto-detected from GCP metadata if unset. Override for non-GCP deployments.
TURN_RELAY_IP: str = os.getenv("TURN_RELAY_IP", "")
TURN_SECRET: str = os.getenv("TURN_SECRET", "")
TURN_CREDENTIAL_TTL: int = int(os.getenv("TURN_CREDENTIAL_TTL", "3600"))
PENDING_MESSAGE_TTL_DAYS: int = int(os.getenv("PENDING_MESSAGE_TTL_DAYS", "30"))
# Sign-up by invitation: a new account needs a one-time code a member created
# (POST /v1/invites). The very first account on a fresh relay needs none.
INVITE_ONLY: bool = os.getenv("INVITE_ONLY", "true").lower() == "true"
INVITE_TTL_DAYS: int = int(os.getenv("INVITE_TTL_DAYS", "7"))
MAX_OPEN_INVITES_PER_MEMBER: int = int(os.getenv("MAX_OPEN_INVITES_PER_MEMBER", "5"))
MAX_INVITES_PER_DAY: int = int(os.getenv("MAX_INVITES_PER_DAY", "10"))
# Invite links are https://<relay>/invite#...; the relay serves that page and, for Android App
# Links, /.well-known/assetlinks.json: the app that may open the links directly, by its package
# and the SHA-256 fingerprints of its signing certificates. The defaults are the official Sotto
# app (its release key, and the author's debug key); set your own if you build the app yourself.
APP_LINK_PACKAGE: str = os.getenv("APP_LINK_PACKAGE") or "com.vagujhelyigergely.sotto"
APP_LINK_CERT_FINGERPRINTS: list = [
    f.strip().upper()
    for f in (os.getenv("APP_LINK_CERT_FINGERPRINTS") or ",".join([
        "D5:52:AB:91:B2:79:95:7C:52:57:B7:0B:0B:6F:6D:59:0F:93:40:34:A3:A3:4E:0F:E7:88:1E:F5:96:3C:53:BC",
        "3C:C7:8A:B9:3B:48:1C:89:46:58:47:A9:96:90:04:70:2A:E0:91:06:9C:88:0F:1A:7E:85:10:11:FF:64:20:07",
    ])).split(",")
    if f.strip()
]
# Where the invite page sends people who don't have the app yet.
APP_DOWNLOAD_URL: str = (
    os.getenv("APP_DOWNLOAD_URL")
    or "https://github.com/gergelyvagujhelyi/Sotto/releases/latest/download/app-release.apk"
)
# Comma-separated list of trusted proxy IPs that may set X-Forwarded-For
# Entries may be single IPs or CIDR ranges (e.g. the Docker network nginx
# connects from: in compose, nginx is NOT 127.0.0.1).
TRUSTED_PROXIES: set = {
    ip.strip()
    for ip in os.getenv("TRUSTED_PROXIES", "127.0.0.1,::1").split(",")
    if ip.strip()
}
