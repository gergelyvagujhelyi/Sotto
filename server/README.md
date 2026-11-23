# Sotto Relay Server

Privacy-first encrypted message relay built with Python and FastAPI.

## What the server does

- Stores public key bundles for contact discovery via share codes
- Creates accounts only with a member's one-time invite (`INVITE_ONLY`, on by default); a new relay's first account needs none
- Relays encrypted envelopes between clients
- Sends FCM wake-up signals for offline delivery (messages and call offers); the payload is a bare wake-up signal, with no sender and never message content
- Provides WebSocket connections for real-time delivery
- Buffers undelivered call signals for offline recipients (30s TTL)
- Deletes envelopes after recipient acknowledgement
- Purges undelivered messages older than a configurable TTL (default 30 days)
- Rate-limits senders to prevent abuse

## What the server does NOT do

- Decrypt or inspect message content
- Store message logs after delivery
- Require personally identifiable information
- Upload or process contact books

## Setup

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# Edit .env as needed
```

## Run

```bash
python run.py
# or
uvicorn app.main:app --reload --port 8000
```

API docs available at http://localhost:8000/docs (debug mode only).

## Run tests

```bash
pip install -r requirements.txt
pytest -v
```

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/v1/identity/bootstrap` | — | Register a guest identity (`invite_code`: a member's invite, when the relay needs one) |
| GET | `/v1/identity/{user_id}/keys` | member, with `KEY_LOOKUP_AUTH` | Fetch public key bundle |
| GET | `/v1/identity/resolve/{share_code}` | member, with `KEY_LOOKUP_AUTH` | Resolve share code to keys |
| PUT | `/v1/identity/me/keys` | ✓ | Rotate identity keys (proof-of-possession required) |
| DELETE | `/v1/identity/me` | ✓ | Delete identity and all associated data |
| POST | `/v1/invites` | ✓ | Create a one-time invite (valid 7 days; 5 open per member, 10 a day) |
| POST | `/v1/messages/send` | ✓ | Submit encrypted envelope |
| GET | `/v1/messages/pending` | ✓ | Fetch pending envelopes |
| POST | `/v1/messages/ack` | ✓ | Acknowledge receipt (the relay then deletes them) |
| POST | `/v1/receipts` | ✓ | Send read receipts |
| GET | `/v1/receipts/pending` | ✓ | Fetch read receipts |
| POST | `/v1/receipts/ack` | ✓ | Acknowledge read receipts |
| GET | `/v1/receipts/delivered` | ✓ | Fetch delivery receipts |
| POST | `/v1/receipts/delivered/ack` | ✓ | Acknowledge delivery receipts |
| POST | `/v1/push/register` | ✓ | Register the push target: `fid` (Firebase installation ID) or, from apps before 0.5.5, `token` |
| POST | `/v1/calls/signal` | ✓ | Relay an encrypted call signal to the peer (pushes when a call offer isn't answered) |
| GET | `/v1/calls/ice-servers` | ✓ | Get ICE/TURN server config with short-lived TURN credentials |
| POST | `/v1/attachments` | ✓ | Reserve an upload of an encrypted file for a recipient (when `S3_*` is set) |
| POST | `/v1/attachments/{id}/parts` | ✓ | Signed upload URLs for the file's parts |
| POST | `/v1/attachments/{id}/complete` | ✓ | Finish the upload |
| GET | `/v1/attachments/{id}` | ✓ | Signed download URL (the recipient) |
| DELETE | `/v1/attachments/{id}` | ✓ | Delete it once downloaded |
| WS | `/v1/ws` | token in the first frame | Real-time delivery: the relay sends `new_message`, `receipts`, call signals, `app_release`; the app sends only `auth` and `ping` |
| POST | `/v1/app-release` | CI release key | The release workflow says the app of this relay's version is published |
| GET | `/health` | — | Health check; `invite_required` says whether sign-up needs an invite |
| GET | `/invite` | — | The page an invite link opens in a browser (the code stays after `#`, never sent here) |
| GET | `/.well-known/assetlinks.json` | — | Lets invite links open the app directly (Android App Links) |
| GET | `/metrics` | `METRICS_TOKEN` | Prometheus metrics; not passed through nginx, read inside the container |

Authenticated endpoints require `Authorization: Bearer <user_id>.<timestamp_ms>.<ed25519_signature>[.<mldsa65_signature>]`. The ML-DSA-65 signature is required when the user has a PQC signing key registered (hybrid post-quantum authentication).

Key lookups are open to anyone (IP rate-limited) unless `KEY_LOOKUP_AUTH=true`. Apps sign them in since 0.7.11; turn it on once every phone runs 0.7.11 or later, so outsiders can't confirm an account from a share code or watch its key changes.

## Invites

With `INVITE_ONLY=true` (the default), `/v1/identity/bootstrap` needs an `invite_code` from a member, except for the relay's first account. Members create codes with `POST /v1/invites` and get back `{"code": "K7QX-M2PA-9TRB", "expires_at": "…Z"}`; the relay stores only each code's SHA-256.

- **Sign-up refusals (403):** `invite_required` (no code), `invite_invalid`, `invite_used` or `invite_expired`.
- **Limits (429):** `invite_limit_open` (5 open per member), `invite_limit_daily` (10 in 24 hours) or `invite_rate_limited` (a burst).
- **Retries:** a sign-up with exactly the keys of an existing account returns that account and uses no invite.
- **Cleanup:** expired invites are purged hourly, used ones 30 days after their use, and a deleted account's open invites with it.

## Production Deployment

```bash
# Configure .env with production values (see .env.example)
# Then:
docker compose up -d --build
```

This starts PostgreSQL + the app server + nginx with TLS. Alembic migrations run automatically on container start.

See `.env.example` for all configuration options including `TURN_SECRET`, `METRICS_TOKEN`, and TLS certificate paths.

### Automated Deployment

A cron-based pull deploy script (`deploy.sh`) is included for lightweight servers. It polls for new commits on `develop`, only rebuilds Docker when files in `server/` actually change, and skips otherwise. The script operates from the repository root so `git diff -- server/` resolves correctly.

**Setup on the server:**

```bash
crontab -e
# Add (checks every 5 minutes):
*/5 * * * * /path/to/Sotto/server/deploy.sh >> ~/deploy.log 2>&1
```

Features: file-based locking (no overlapping deploys), Docker layer caching, corrupt/force-pushed commit recovery, and dirty working tree cleanup.

### Manual Deployment

To deploy manually without waiting for the cron cycle:

```bash
cd /path/to/Sotto
git pull origin develop
docker compose -f server/docker-compose.yml build server
docker compose -f server/docker-compose.yml up -d --force-recreate server
docker image prune -f
```

## Database

Default: SQLite (for development). Switch to PostgreSQL by changing
`DATABASE_URL` in `.env`:

```
DATABASE_URL=postgresql+asyncpg://user:password@localhost/sotto
```

Tables are auto-created in debug mode. In production, use Alembic migrations:

```bash
alembic upgrade head
```
