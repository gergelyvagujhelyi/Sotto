# Attachments: research notes (deferred)

**Status:** deferred on 2026-11-19, after 0.5.13; restarted on 2026-11-23 with the plan below, and built for 0.7.7 except the direct transfer (ADR-29 in [ARCHITECTURE.md](../ARCHITECTURE.md)). The rest of these notes are the original research, kept so it isn't repeated. ADR-19 still holds: no file bytes through the GCP relay.

## The plan (2026-11-23)

Three paths, chosen by size, none of which puts file bytes through the relay's Premium-tier egress except the small inline ones:

| What | Path | Relay carries | Cost |
|---|---|---|---|
| Voice messages (up to about a minute) and photos shrunk to about 1280 px | Inline, inside the ordinary end-to-end encrypted message (option 3 below) | The message, at most about 190 KB | Under 1% of the free gigabyte at normal use |
| Larger files and videos | An encrypted drop in Tigris object storage | A small descriptor message and short-lived signed URLs | 0: Tigris has free egress, and an account without a card stops at the free tier instead of billing |
| Larger files, later | Directly phone to phone over a WebRTC data channel that gets no TURN credentials, with the drop as fallback | A descriptor and the connection signals | 0 |

### Inline media

- **Payload.** `"\u0001sotto/media/v1:" + JSON` with `kind` (`voice` or `photo`), `mime`, the media as base64, a caption, and for voice its length, for photos width and height. It may also carry a reply (`to`, `quote`, `mine`, as in `ReplyPayload`). Like replies and reactions it is ratchet-encrypted and signed, so the relay can't tell it from text, apart from its size.
- **Size.** The relay accepts 262,144 base64 characters per message; that leaves about 140 KB of media. Voice is Opus at 16 kbit/s (Android 10+) or HE-AAC (older), at most 60 s. Photos are decoded at most 1280 px on the long side and re-encoded as JPEG until they fit; re-encoding drops EXIF, so no location leaves the phone.
- **On the phone.** The media is kept out of the database row: it goes to `files/media/<message id>`, encrypted with AES-GCM under a random key stored in the (SQLCipher) row. Playing and showing decrypt into memory only. Files whose message is gone (deleted, disappeared, chat cleared, history limit) are swept.
- **Older apps.** 0.7.6 and older show the payload as raw text, as with replies (ADR-27). Everyone updates together. New apps show any `\u0001sotto/…` payload they don't know as "Update Sotto to see this message", so the next format change is gentler.

### Encrypted drop (Tigris)

- **Bucket.** `sotto-drop-…` in Tigris, single region `fra`, private, Standard tier, a 30-day expiry rule, soft delete off. The relay's key has Read-write on that bucket only, with "default permissions" off (it can't create buckets, keys or policies).
- **Relay.** It never touches file bytes. `POST /v1/attachments` reserves an upload for a recipient and size, checks the sender's monthly allowance in Postgres and returns a presigned PUT whose signed `content-length` Tigris enforces (checked on 2026-11-22: a bigger body gets 403). `POST /v1/attachments/{id}/complete` checks the stored size with a HEAD. The recipient gets a presigned GET from `GET /v1/attachments/{id}`, and `DELETE` removes the object once it has the file. Settings come from `S3_*` in `.env` (forwarded by `docker-compose.yml`); without them the relay offers no drop.
- **File format.** The phone encrypts in 64 KiB segments with AES-256-GCM: a random 7-byte nonce prefix, a 4-byte segment counter and a last-segment flag make each nonce, so reordering and truncation are detected (the construction Tink documents for streaming AEAD). The key is random per file and travels inside the ratchet-encrypted descriptor (`"\u0001sotto/file/v1:"`), so the file is as post-quantum protected as the message. The store sees IP addresses, sizes and timing, never content or keys.
- **Checked on 2026-11-22** with a throwaway script: presigned PUT, GET, HEAD and DELETE; a bigger body than signed is refused; a URL for one object can't read another. The console showed no payment method and a banner that usage beyond the free tier needs one "to avoid interruptions".

### Direct transfer (later)

A second, data-only `PeerConnection` with only the relay's STUN server and no TURN entry, so it can never fall back to the relay; consent as for direct calls (ADR-17); the drop when no direct path is found or the receiver is offline. Home Wi-Fi to some mobile networks has no direct path (see the call tests), so the drop is the path that always works and comes first.


## Why it's deferred

The owner keeps the relay on Google's Premium network tier: "the whole point about this app is the premium tier connection because whatsapp was not always as stable as we wanted". On Premium the relay's free egress is 1 GB a month and relayed calls need it, so sending photos, files and voice through the relay waits.

## The egress budget

- **Free allowance.** The relay VM (an e2-micro on the Premium tier) gets 1 GB a month of free internet egress under the Compute Engine free tier.
- **Calls.** A relayed call costs about 60 MB of egress an hour (ADR-17).
  - Calls go direct between earlier contacts when both phones can reach each other.
  - Home Wi-Fi ↔ mobile data stays relayed (strict NAT, no IPv6).
  - Roughly 15 hours of relayed calls use up the free gigabyte.
- **Measured.** The relay sent 35 MB (`ens4` tx_bytes) in the 18 hours after its reboot at 2026-11-18 23:35 UTC, a day of call testing. A whole month at that pace would be about 1.4 GB.
- **Past the allowance.** Egress past 1 GB is billed. If a month's cost passed the 10 SEK budget cap, the cap would unlink billing, which stops the VM and changes its IP.
- **Standard tier.** It includes 200 GiB a month free per region (since 1 October 2023), but its traffic crosses the public internet instead of Google's network. It was declined, to keep calls steady.

## Options considered

### 1. Encrypted blobs on the relay (store and forward)

This is how WhatsApp and Signal do it, and it works when the other phone is off.

- **Encryption.** The phone encrypts each attachment with a fresh 256-bit key:
  - chunked AES-256-GCM in 64 KiB chunks, with the chunk index as the nonce;
  - a final-chunk flag in the associated data, so the file can't be cut short or reordered;
  - padding to size buckets.
- **Delivery.** The phone uploads the ciphertext, then sends the key, the blob id and a SHA-256 of the ciphertext inside the normal end-to-end message. The recipient downloads it once, checks it, and deletes the blob.
- **Relay API sketch:**
  - `POST /v1/attachments {recipient_id, size}` reserves an upload. It checks the limits and counts the expected download against a monthly allowance stored in Postgres.
  - `PUT /v1/attachments/{id}` streams the body to a file on a new volume, resumably.
  - `GET` serves the file to the recipient only, with Range support.
  - `DELETE` removes it.
  - An hourly purge removes expired blobs and stale reservations, and account deletion cleans up.
- **Cost.** It needs the Standard tier, or limits of a few hundred MB a month for everyone together with files up to about 10 MB.

### 2. Peer to peer over WebRTC data channels

This would reuse the call stack: ICE with the relay's own coturn, and the direct-connection consent of ADR-17, with a data channel instead of audio. Its limits:

- Both phones must be online at the same moment.
- The receiving phone must be woken first, which was the hard part of calls.
- Between home Wi-Fi and mobile data there is no direct path, so the transfer would go through TURN and cost the same egress as option 1.
- It can't deliver to a phone that's off.

### 3. Inline in the end-to-end message (the light option)

The attachment travels inside the normal ratchet-encrypted message, like text. Nothing new is stored on the relay, and it is deleted on delivery like any message.

- **Size limit.** The relay caps `encrypted_body` at 262,144 base64 characters, about 192 KiB of ciphertext. The media is base64 again inside the JSON plaintext, which leaves about 145 KB of media per message:
  - **Voice:** up to about a minute with Opus at 16 kbps (about 0.12 MB a minute). Recording Opus with `MediaRecorder` needs Android 10+.
  - **Photos:** shrunk to about 1280 px and 100 KB.
- **Budget.** Typical use (dozens of voice notes and photos) is a few MB a month, under 1% of the free gigabyte.

This is the starting point if attachments come back while the relay stays on Premium.

## Compatibility with older apps

- **Old apps show unknown payloads as text.** A message's plaintext is plain UTF-8 text. Special payloads are recognised by a prefix: `ProfilePayload` is `"\u0001chatcontroll/profile/v1:" + JSON`.
  - Apps up to 0.5.13 store any other payload verbatim and show it in the bubble, the chat list and the notification. Lock-screen previews show the full text by default.
  - Apps up to 0.5.2 do the same even with profile payloads.
- **Gate sending on a capability.** An attachment must only go to phones that announced they understand it.
  - Preferred: the app sends an `X-CC-Caps` header with every request, and the relay stores it per identity in the update it already makes for `last_seen_at`. A downgrade clears it automatically.
  - The composer then asks the relay before offering attach and mic.
- **Announcing capabilities in the profile payload has holes.**
  - Users without a name never send a profile.
  - `processPendingMessage` upserts an unknown sender's keys before the profile check, so a capabilities-only profile from a former contact would create a ghost contact.
- **Show unknown payloads as a placeholder.** New apps should render any unknown `\u0001sotto/…` payload as "Update Sotto to see this" instead of raw text, so the next format change is easier.

## App: what to watch out for

- **Retry re-encrypts the text.** `MessageRepositoryImpl.retryFailedInternal` re-encrypts the row's `plaintext`, so attachment messages need their own retry path.
- **Startup fails pending sends.** `failInterruptedSends` turns every SENDING row into FAILED when the app starts. Background transfers must be excluded and resumed instead.
- **A failed migration deletes the database.**
  - `DatabaseModule.provideDatabase` deletes the database whenever it can't open it, a failed migration included; only a dialog tells the user.
  - Fix that before the next schema change: rethrow migration and validation errors, and delete only when SQLCipher reports that the file isn't a database.
  - Test migrations on the JVM with `room-migration` and `sqlite-jdbc`, or use `@AutoMigration`; schemas are exported to `app/schemas`.
- **AES-GCM is slow by default.** Bouncy Castle is registered as crypto provider #1, so `Cipher.getInstance("AES/GCM/NoPadding")` runs Bouncy Castle's pure-Java AES-GCM. Ask for `"AndroidOpenSSL"` for anything large.
- **The chat query copies ciphertext.** It is `SELECT *`, so every emission copies each message's ciphertext and ratchet header. A projection without them would help, since attachment state changes would re-run it.
- **What's there and what isn't.**
  - Not there yet: a FileProvider, pickers, an image loader, a recorder or a player.
  - Already dependencies: WorkManager (with `HiltWorkerFactory`) and CameraX.
  - `androidx.exifinterface` only arrives transitively through CameraX.
- **Files need a sweep.** Deletion is bulk SQL (disappearing messages, message history, delete chat, wipe), so files on disk need a separate sweep. `wipeLocal` doesn't clear `filesDir` or `cacheDir`.
- **Validate received descriptors.** Check sizes, image dimensions (before decoding), file names (path separators, bidirectional controls) and MIME types.

## Relay: what to watch out for

- **2 MB request cap.** Request bodies are capped at 2 MB in two places: nginx `client_max_body_size 2m` and the app's `BodySizeLimitMiddleware` (`MAX_REQUEST_BODY_BYTES`).
- **No volume for files.** Only Postgres has one. Anything written inside the server container is lost on the next deploy.
- **Counters reset.** In-memory limiters reset on every deploy and restart, so monthly counters belong in Postgres (the row-lock pattern in `services/rate_limiter.py`).
- **Settings need the compose file.** A setting only reaches production if `docker-compose.yml` passes it in `environment`; `.env` alone isn't enough. Today `PENDING_MESSAGE_TTL_DAYS` and `MAX_REQUEST_BODY_BYTES` always run with their defaults.
- **nginx changes aren't deployed.**
  - `nginx.conf` is bind-mounted as a single file, and `deploy.sh` runs `git reset --hard` plus `docker compose up -d --build`. So nginx keeps its old configuration until it's recreated.
  - To apply a change: run `nginx -t`, then `docker compose up -d --force-recreate nginx`.
- **The purge waits an hour.** The purge loop in `main.py` sleeps before its first run, so frequent deploys postpone it.
- **Resumable downloads come free.** Starlette's `FileResponse` already answers Range requests.
- **The disk is slow.** pd-standard sustains a few MiB/s. File writes must stay off the event loop, and a full disk would take Postgres down too.

## The owner's preferences so far

- **Voice messages:** hold the mic to record, like WhatsApp. Release sends, slide left cancels, slide up locks.
- **File size limit:** not decided ("I am planning on direct transfer").
- **Network tier:** keep Premium.

## Sources

- [Google Cloud free tier](https://cloud.google.com/free/docs/free-cloud-features#compute): Compute Engine includes 1 GB of outbound data transfer from North America per month.
- [Announcing 200 GB free Standard Tier internet data transfer per month](https://cloud.google.com/blog/products/networking/standard-tier-network-now-includes-200-gb-data-transfer-per-month) (Google Cloud blog, September 2023).
- [Network Service Tiers pricing](https://cloud.google.com/network-tiers/pricing).
