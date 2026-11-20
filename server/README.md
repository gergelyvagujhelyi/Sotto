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

| Method | Path | Description |
|--------|------|-------------|
| POST | `/v1/identity/bootstrap` | Register a guest identity (`invite_code`: a member's invite, when the relay needs one) |
| GET | `/v1/identity/{user_id}/keys` | Fetch public key bundle |
| GET | `/v1/identity/resolve/{share_code}` | Resolve share code to keys |
| PUT | `/v1/identity/me/keys` | Rotate identity keys (proof-of-possession required) |
| DELETE | `/v1/identity/me` | Delete identity and all associated data |
| POST | `/v1/invites` | Create a one-time invite (valid 7 days; 5 open per member, 10 a day) |
| POST | `/v1/messages/send` | Submit encrypted envelope |
| GET | `/v1/messages/pending` | Fetch pending envelopes |
| POST | `/v1/messages/ack` | Acknowledge receipt |
| POST | `/v1/push/register` | Register the push target: `fid` (Firebase installation ID) or, from apps before 0.5.5, `token` |
| DELETE | `/v1/push/register` | Unregister the push target |
| POST | `/v1/calls/signal` | Relay call signal to peer |
| GET | `/v1/calls/ice-servers` | Get ICE/TURN server config |
| WS | `/v1/ws` | Real-time delivery + call signaling |
| GET | `/health` | Health check; `invite_required` says whether sign-up needs an invite |
| GET | `/metrics` | Prometheus metrics (token-protected) |

All authenticated endpoints require `Authorization: Bearer <user_id>.<timestamp_ms>.<ed25519_signature>[.<mldsa65_signature>]` header. The ML-DSA-65 signature is required when the user has a PQC signing key registered (hybrid post-quantum authentication).

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
