"""Server configuration loaded from environment variables."""

import os
from typing import Optional

from dotenv import load_dotenv

load_dotenv()

# The release this relay belongs to: the app's versionName in app/build.gradle.kts, so
# /health tells which code a relay runs. tests/test_version.py keeps the two in step.
RELAY_VERSION = "0.7.28"

DATABASE_URL: str = os.getenv(
    "DATABASE_URL", "sqlite+aiosqlite:///./sotto.db"
)
FIREBASE_CREDENTIALS: Optional[str] = os.getenv("FIREBASE_CREDENTIALS")
HOST: str = os.getenv("HOST", "0.0.0.0")
PORT: int = int(os.getenv("PORT", "8000"))
DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"
# Each account's message sends are a token bucket: up to MESSAGE_BURST at once, refilled at
# MESSAGE_RATE_PER_MINUTE. A group message goes out as one envelope per member (up to 31),
# so the burst has to cover a few of those; the relay can't tell them from other sends.
MESSAGE_BURST: int = int(os.getenv("MESSAGE_BURST", "160"))
MESSAGE_RATE_PER_MINUTE: int = int(os.getenv("MESSAGE_RATE_PER_MINUTE", "240"))
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
# For a phone with no push token (no Google Play services): its socket is its only way to
# hear of messages and calls, and while it sleeps its pings stop. After 5 minutes it found the
# socket closed at every wake (each Wi-Fi lease renewal, say) and logged in again, all day long.
# A socket kept longer hears of messages at once: an incoming frame wakes the phone. Nothing
# is held back by a socket that went dead meanwhile, as there's no push to hold back.
WS_NO_PUSH_IDLE_TIMEOUT_SECONDS: int = int(os.getenv("WS_NO_PUSH_IDLE_TIMEOUT_SECONDS", "1800"))  # 30 min
# Accept each auth token once (refuse replays). Off until every phone runs 0.7.3 or later:
# older apps sometimes sign two requests in the same millisecond, giving identical tokens.
# A merge to develop deploys the relay at once, so this is turned on in .env, not here.
AUTH_SINGLE_USE_TOKENS: bool = os.getenv("AUTH_SINGLE_USE_TOKENS", "false").lower() == "true"
METRICS_TOKEN: str = os.getenv("METRICS_TOKEN", "")
# Auto-detected from GCP metadata if unset. Override for non-GCP deployments.
TURN_RELAY_IP: str = os.getenv("TURN_RELAY_IP", "")
TURN_SECRET: str = os.getenv("TURN_SECRET", "")
TURN_CREDENTIAL_TTL: int = int(os.getenv("TURN_CREDENTIAL_TTL", "3600"))
PENDING_MESSAGE_TTL_DAYS: int = int(os.getenv("PENDING_MESSAGE_TTL_DAYS", "30"))
# Sign-up by invitation: a new account needs a one-time code a member created
# (POST /v1/invites). The very first account on a fresh relay needs none.
INVITE_ONLY: bool = os.getenv("INVITE_ONLY", "true").lower() == "true"
# Key lookups (GET /v1/identity/{id}/keys and /resolve/{code}) need a member's sign-in token.
# Apps sign them in since 0.7.11; turn this on once every phone runs 0.7.11 or later, or older
# apps can no longer add contacts or check keys. Only meaningful with INVITE_ONLY: on an open
# relay anyone can make an account and then look keys up.
KEY_LOOKUP_AUTH: bool = os.getenv("KEY_LOOKUP_AUTH", "false").lower() == "true"
INVITE_TTL_DAYS: int = int(os.getenv("INVITE_TTL_DAYS", "7"))
MAX_OPEN_INVITES_PER_MEMBER: int = int(os.getenv("MAX_OPEN_INVITES_PER_MEMBER", "5"))
MAX_INVITES_PER_DAY: int = int(os.getenv("MAX_INVITES_PER_DAY", "10"))
# Encrypted attachments (ADR-29): an S3-compatible bucket the phones upload to and download
# from with URLs the relay signs; the relay never carries file bytes. Unset: no attachments.
S3_ENDPOINT: str = os.getenv("S3_ENDPOINT", "")
S3_BUCKET: str = os.getenv("S3_BUCKET", "")
S3_ACCESS_KEY_ID: str = os.getenv("S3_ACCESS_KEY_ID", "")
S3_SECRET: str = os.getenv("S3_SECRET", "")
S3_REGION: str = os.getenv("S3_REGION", "auto")
# Largest file (its ciphertext), what one account may upload in 30 days, and what the
# bucket may hold at once: kept under Tigris's free 5 GB and 10,000 uploads a month.
ATTACHMENT_MAX_BYTES: int = int(os.getenv("ATTACHMENT_MAX_BYTES", str(100 * 1024 * 1024)))
ATTACHMENT_MONTHLY_BYTES_PER_USER: int = int(os.getenv("ATTACHMENT_MONTHLY_BYTES_PER_USER", str(2 * 1024 ** 3)))
ATTACHMENT_STORE_LIMIT_BYTES: int = int(os.getenv("ATTACHMENT_STORE_LIMIT_BYTES", str(4 * 1024 ** 3)))
ATTACHMENT_MONTHLY_UPLOADS: int = int(os.getenv("ATTACHMENT_MONTHLY_UPLOADS", "5000"))
# Files larger than this go up in parts of this size, so an interrupted upload resumes.
ATTACHMENT_PART_BYTES: int = int(os.getenv("ATTACHMENT_PART_BYTES", str(8 * 1024 * 1024)))
# Uncollected files go after this long (the bucket's own expiry rule is the backstop).
ATTACHMENT_TTL_DAYS: int = int(os.getenv("ATTACHMENT_TTL_DAYS", "30"))
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
# Where the invite page sends people who don't have the app yet: Sotto's page on GitHub,
# not the APK itself (the name is kept so existing .env files still work).
APP_DOWNLOAD_URL: str = (
    os.getenv("APP_DOWNLOAD_URL")
    or "https://github.com/gergelyvagujhelyi/Sotto"
)
# Where the APK of the app release that goes with this relay appears ({version} is
# RELAY_VERSION). Until it exists, phones on older apps say an update is on its way; then
# that it's ready. Empty: never check (phones then only hear that the relay is newer).
APP_RELEASE_APK_URL: str = os.getenv(
    "APP_RELEASE_APK_URL",
    "https://github.com/gergelyvagujhelyi/Sotto/releases/download/v{version}/app-release.apk",
)
# How often to look for it, until it's there (or the release workflow says so).
APP_RELEASE_CHECK_SECONDS: int = int(os.getenv("APP_RELEASE_CHECK_SECONDS", "300"))
# The release workflow's Ed25519 public key (base64, 32 bytes): its "the app is out" call
# must be signed with the private half (GitHub secret CI_RELEASE_KEY). Public, so the
# official one is the default; empty turns the call off (the relay then only looks itself).
CI_RELEASE_PUBLIC_KEY: str = os.getenv("CI_RELEASE_PUBLIC_KEY", "ihHC+n7ETJ/4NZzZETD4bfU4FbLCYS7NsexLVOhbHDE=")
# Comma-separated list of trusted proxy IPs that may set X-Forwarded-For
# Entries may be single IPs or CIDR ranges (e.g. the Docker network nginx
# connects from: in compose, nginx is NOT 127.0.0.1).
TRUSTED_PROXIES: set = {
    ip.strip()
    for ip in os.getenv("TRUSTED_PROXIES", "127.0.0.1,::1").split(",")
    if ip.strip()
}
