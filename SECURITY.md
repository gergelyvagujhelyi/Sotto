# Security Notes & Threat Model

## Cryptographic Design

### Key Establishment (Hybrid, session protocol v2)
The initiator ("Alice") is whoever sends first; the responder ("Bob") is the other side.
```
SK = HKDF-SHA256(
    IKM  = X25519(IK_local, IK_remote) || ML-KEM-768 shared secret (Alice encapsulates to Bob's ek),
    salt = "ChatControll-v2-session",
    info = "hybrid-key-establishment" || LP(IK_Alice) || LP(IK_Bob),
    length = 32
)
Alice: DoubleRatchet.initAlice(SK, IK_Bob)   — fresh ephemeral ratchet key
Bob:   DoubleRatchet.initBob(SK, IK_Bob)     — identity key acts as initial ratchet key
```
Every message Alice sends carries a `SessionInit` (random 128-bit session tag + ML-KEM
ciphertext) inside the authenticated ratchet header until Bob replies, so a lost first
message does not break the session. From Bob's first reply on, every turn performs a DH
ratchet step with fresh ephemeral X25519 keys. If both sides initiate simultaneously, the
session created by the peer with the lower identity key wins and both converge on it;
messages from the losing session are still decrypted.

- **Classical component**: X25519 (Curve25519 ECDH). Well-vetted, widely deployed.
- **Post-quantum component**: ML-KEM-768 (NIST FIPS 203) via Bouncy Castle 1.79+. Production post-quantum key encapsulation is active for all hybrid sessions.
- **Key derivation**: HKDF-SHA256 (RFC 5869) combines both shared secrets.
- **Payload encryption**: AES-256-GCM with random 12-byte nonces.

### Key Authenticity (relay server is not trusted)
- **Share code = key fingerprint**: `fingerprint = SHA-256("ChatControll-fingerprint-v1" || LP(identity) || LP(signing) || LP(ML-KEM ek) || LP(ML-DSA pk))`; the share code is its first 96 bits (base64url). When adding a contact by share code, the client recomputes the fingerprint of the bundle the server returned and rejects a mismatch, so the server cannot substitute keys. Contacts added this way are marked `verified`.
- **Pinned keys**: Each contact stores its fingerprint. Every key bundle fetched later (session setup, calls, control messages) is checked against it by `ContactKeyVerifier`.
- **Endorsed rotation**: A rotating client signs `"ChatControll-key-rotation-v1" || LP(user_id) || LP(old_fp) || LP(new_fp)` with its OLD Ed25519 key (and OLD ML-DSA key if it has one). The server verifies and publishes this endorsement with the bundle. Contacts accept new keys only if the endorsement verifies against the keys they hold; any other key change is rejected.
- **Trust on first use**: A message request from a sender you never added uses the server's keys unverified (contact stays `verified = false`). Adding that person by their share code afterwards verifies — or, on mismatch, refuses — those keys.

### Identity
- **Classical signing**: Ed25519 (identity assertions, message authentication, auth tokens).
- **Post-quantum signing**: ML-DSA-65 (NIST FIPS 204) via Bouncy Castle 1.79+. Used for hybrid dual-signing of auth tokens, messages, and call signals alongside Ed25519.
- **Key agreement**: X25519 (used for session establishment).
- **Storage**: EncryptedSharedPreferences backed by Android Keystore AES-256-GCM master key.

### Database Encryption
- SQLCipher AES-256, keyed from Android Keystore.

## Threat Model

### Assets
1. Message plaintext
2. Identity private keys
3. Session keys
4. Contact graph (who talks to whom)
5. Conversation metadata (timestamps, message counts)

### Threat Actors

| Actor | Capability | Mitigations |
|-------|-----------|-------------|
| **Passive network observer** | Sees encrypted traffic between device and relay server | TLS 1.3, certificate pinning, encrypted payloads inside TLS |
| **Relay server operator** | Sees encrypted envelopes, routing metadata, timing | E2E encryption means server cannot read content. Metadata minimization: no PII stored, messages deleted after ACK |
| **Stolen/seized device** | Physical access to device storage | SQLCipher database encryption, EncryptedSharedPreferences, Android Keystore for key material, FLAG_SECURE, auto-wipe option |
| **Quantum adversary (future)** | Harvest-now-decrypt-later of key exchanges | Hybrid key establishment with ML-KEM-768 (Bouncy Castle 1.79+). Production PQC active since v0.2.0 |
| **Call peer** | Takes part in your call; learns the addresses its phone connects to | Calls between earlier contacts who both have direct calls on (the default since 0.5.8) go direct, and each side sees the other's IP addresses. Turning it off on either phone keeps a call relayed, and so does calling anyone who isn't an earlier contact: then the peer sees only the relay's address |
| **Malicious contact** | Can send messages, attempt abuse | Share-code-based discovery limits spam. Block/report mechanisms (future). No address book exposure |
| **Stranger with the public app** | Installs the app from GitHub and tries to use someone's relay | Invite-only sign-up (0.5.17): a relay creates accounts only with a member's one-time invite, and the app comes with no relay address |
| **App store supply chain** | Modified APK | Code signing, reproducible builds (future), ProGuard/R8 obfuscation |

### What the Server Knows
- User ID (random, no PII)
- Public key bundle
- Push target: the Firebase installation ID (an FCM token from apps before 0.5.5), which can be correlated with the device's Google account (see limitations)
- Encrypted message envelopes in transit (deleted after delivery ACK, or purged after 30 days if undelivered)
- Timing of message submission and retrieval
- Who invited whom (since 0.5.17): each invite records the member who created it and the account that used it, until 30 days after its use
- The IP address a phone connects from, while it's connected, as any server sees it. The IP rate limits count requests per address in memory. No log keeps addresses (see Relay Logs and Scanners).
- For files sent through its object store (0.7.7): who sends a file to whom, its encrypted size and when, until the recipient has it (at most 30 days); and each account's bytes uploaded over 30 days, for its allowance. Never the file's name, type, content or key.

### What the Server Does NOT Know
- Message content
- Contact names or display names (your own name, if you set one, reaches contacts only inside an end-to-end encrypted message; see Display Names below)
- Phone numbers, emails, real names
- Which messages belong to which conversation (from the server's perspective, it's just sender → recipient)

## Known Limitations & Risks

### ML-KEM-768 via Bouncy Castle
`BouncyCastlePqcProvider` uses Bouncy Castle 1.79+ for ML-KEM-768 (NIST FIPS 203). This provides real post-quantum key encapsulation. The hybrid X25519 + ML-KEM design means both classical and quantum-resistant security layers are active. Note: Bouncy Castle's ML-KEM has not yet undergone FIPS certification for Android. For the highest assurance, evaluate NIST-certified modules as they become available.

### Forward Secrecy via Double Ratchet
The `RatchetSessionManager` implements a Signal-style Double Ratchet:
- **Per-message forward secrecy**: Each message uses a unique key derived from a symmetric chain ratchet. Used keys are deleted.
- **Break-in recovery**: DH ratchet steps generate new ephemeral X25519 keypairs, so even if session state is compromised, future messages become secure after the next ratchet step.
- **Out-of-order tolerance**: Up to 256 skipped message keys are cached for messages that arrive out of order.
- **Persistence**: One session record per peer (current session, up to 4 receive-only previous sessions, seen session tags) is persisted to EncryptedSharedPreferences and survives app restarts. Initial chain keys are never stored separately. Full DB-backed persistence for ratchet chains is a future improvement for multi-device support.
- **Transactional decryption**: Decryption runs on a copy of the session state that is committed only if the message authenticates. Tampered, forged or replayed messages never change or delete a session.
- **Replay detection**: Already-consumed message keys, past ratchet chains and previously seen session tags are recognised; such messages are dropped silently and never trigger a session reset.

### Key Rotation (v0.3.3+, crash recovery hardened v0.4.1)
`rotateIdentityKeys()` generates new Ed25519 + X25519 + ML-KEM-768 + ML-DSA-65 keys and registers them with the relay server. The protocol uses crash-safe staged promotion:
1. New keys are staged locally before the server call.
2. Server validates proof-of-possession signatures (Ed25519 and ML-DSA-65 new keys sign themselves).
3. On server acceptance, a `server_confirmed` flag is persisted synchronously, then staged keys are promoted to active.
4. On app crash between staging and server call, the next launch detects staged keys without `server_confirmed` and discards them (the server still has the old keys).
5. On app crash between server acceptance and local promotion, the next launch detects staged keys with `server_confirmed` and promotes them.
6. All session caches are invalidated — peers re-establish on next message.

**Remaining work**: No automated rotation schedule and no old-key grace period for in-flight messages.

### Session Reset Protocol (v0.3.5+, hardened in session protocol v2)
When a correctly signed message cannot be decrypted by any session (the peer's session is out of sync), the session reset protocol handles this:
1. After `MAX_DECRYPT_RETRIES` (3) failed decrypt attempts, a `session_reset` control message is sent to the sender via the existing message pipeline (store-and-forward, guaranteed delivery even if the sender is offline). Signature failures and duplicates never count toward this.
2. Control messages carry `{"ctrl":…,"ts":<ms>,"n":<random>}`. They are verified against the sender's **stored** keys; only if that fails is the server bundle consulted, and then only an endorsed rotation is accepted. `ts` must be newer than the last control message accepted from that sender (and at most 31 days old), so replays are rejected.
3. On receiving the signal, the current session is retired (kept receive-only for in-flight messages) and the conversation is blocked (`needsSessionReset` flag).
4. The sender must manually tap "Re-establish session" in the chat UI, which checks the peer's key bundle against the stored fingerprint, creates a fresh ratchet session, and unlocks sending.
5. Deduplication prevents multiple `session_reset` signals to the same peer; the tracking is cleared when a successful decrypt from that peer occurs.

### Account Deletion Protocol (v0.3.9+)
When a user wipes all local data, the app notifies contacts and cleans up server-side state:
1. An `account_deleted` control message (distinct from `session_reset`) is sent to every contact via the message pipeline.
2. The server-side identity is deleted (`DELETE /v1/identity/me`) **before** local keys are wiped, so the auth token remains valid for the API call.
3. If the server is unreachable, the user is prompted to retry or skip server deletion. Retrying preserves local keys until the server call succeeds.
4. On the recipient side, the `account_deleted` control message sets a `peerDeleted` flag on the conversation. This permanently blocks sending and displays "Peer deleted their account" in the chat UI.
5. Signature verification of the `account_deleted` message uses the locally stored contact keys (never the server bundle), with the same timestamp replay protection as `session_reset`.

### Key Change Events in Chat History (v0.3.9+)
Key rotation and account deletion events are recorded as messages in the local chat history:
- **Local key rotation**: When you rotate your keys, a `KEY_ROTATED_LOCAL` event is inserted into each contact's conversation.
- **Remote key rotation**: When a peer's endorsed key rotation is accepted, a `KEY_ROTATED_REMOTE` event is recorded.
- **Account deletion**: When a peer's `account_deleted` control message is processed, an `ACCOUNT_DELETED` event is recorded.
These events are rendered as centered, non-interactive items with distinct icons, giving users a clear audit trail of key changes in each conversation.

### Device Loss = Identity Loss
Since identity lives only on the device, losing the device means losing:
- The identity (cannot prove you are the same user)
- All message history
- All contacts
This is a deliberate privacy choice. Optional encrypted backup (e.g., to user-controlled cloud storage with a passphrase) could be added as an opt-in feature.

### Push Target Linkability
The push target (since v0.5.5 the app's Firebase installation ID, before that its FCM registration token) is registered with Google through the device's Google Play services. A sophisticated adversary (Google, or someone with access to Google's systems) could correlate it with the device's Google account and so with a real identity. The pushes themselves carry only a wake-up signal. Phones without Google Play services register no push target at all. Mitigations:
- Use a push proxy service that breaks the direct link (future)
- Support alternative push mechanisms (UnifiedPush, WebSocket fallback)

### Message Authentication (v0.3.0+)
Messages are signed with Ed25519 before sending. The recipient verifies the signature against the sender's stored public signing key before decryption. Since v0.3.10, unsigned messages are unconditionally rejected — the legacy backward-compatibility exemption for contacts with `signatureRequired=false` has been removed to prevent impersonation via unsigned message injection.

### Call Signal Authentication (v0.3.1+)
Call signaling messages (offer, answer, ICE candidates) are signed with Ed25519. The recipient verifies the signature against the sender's public signing key before processing. This prevents call signal injection by a compromised relay server. Since v0.3.8, if the sender is not yet a local contact (e.g. newly added contact with no prior messages), the key bundle is fetched from the server and the contact is persisted only after signature verification succeeds. Since v0.4.0, call signals are also dual-signed with ML-DSA-65 when the sender has PQC signing keys, with the same mandatory enforcement as messages (PQC-capable senders cannot omit ML-DSA signatures).

### Signature Verification Robustness (v0.3.1+)
When a signed message or call signal arrives from an unknown sender, the client fetches the sender's key bundle from the server before verification (trust on first use; see Key Authenticity). If the sender cannot be resolved, the signal is silently dropped. For call signals, the fetched contact is kept in memory during verification and only persisted to the database after the signature is confirmed valid (v0.3.8).

### PQC Negotiation & Downgrade Protection (v0.3.4+)
- **Initiator encapsulates**: Whoever starts a session encapsulates to the peer's (fingerprint-verified) ML-KEM key; the responder decapsulates the ciphertext carried in `SessionInit`.
- **Responder-side enforcement**: A client that publishes an ML-KEM key rejects any new session from a peer that lacks an ML-KEM ciphertext.
- **PQC downgrade rejection**: If a contact was previously established as a hybrid PQ session, any subsequent classical-only session establishment is rejected. This prevents a MITM from silently stripping PQC protection.

### Base64 Input Validation (v0.3.4+)
All `Base64.decode` calls on externally-received data (key bundles, KEM ciphertext, message envelopes) are wrapped in try/catch. Malformed Base64 from the server or a peer is logged and rejected rather than crashing the app.

### Call Encryption (v0.3.9+, hybrid PQC v0.4.0)
Voice call media and signaling are end-to-end encrypted:

**Signal encryption** (v0.4.0): Call signal payloads (SDP offers/answers, ICE candidates) are encrypted with AES-256-GCM using per-call ephemeral keys derived from session keys via SHAKE-256 KDF. This bypasses the Double Ratchet to avoid advancing the message chain — call signals are ephemeral and may be lost/reordered.

**Hybrid PQC key agreement** (v0.4.0): Call session keys are derived independently from the messaging ratchet to avoid corrupting ratchet state:
```
call_shared_secret = SHAKE-256-KDF(
    IKM = X25519(local_priv, remote_pub) || ML-KEM-768.Encapsulate(remote_ek),
    salt = "ChatControll-v1-call-init",
    info = "hybrid-key-establishment",
    length = 32
)
chain_material = SHAKE-256-KDF(call_shared_secret, "ChatControll-v1-call-chains", "bidirectional-chains", 64)
send_key = chain_material[0:32]  (initiator) / chain_material[32:64]  (responder)
```
The entire call encryption path uses SHAKE-256 (SHA-3 family) instead of HMAC-SHA-256 to avoid SHA-2 dependencies in the post-quantum path. KEM ciphertext is attached to the `call_offer` signal and decapsulated by the responder.

**Media encryption**: Frame-level AES-GCM via WebRTC FrameCryptor API. Media keys are derived from session keys using SHAKE-256 KDF with the call ID as salt. The commutative XOR of send/receive keys ensures both peers derive the same media key.
- **Implementation**: `WebRtcEngine.enableFrameEncryption(key, salt)` creates separate sender and receiver `FrameCryptor` instances with a per-call HKDF salt (call ID) for session binding. Encryption state changes are surfaced via callback for UI feedback.
- **Cleanup**: Frame cryptors are disposed alongside the peer connection to prevent key material leaks.

### ML-DSA-65 Dual-Signing (v0.4.0)
All auth tokens, messages, and call signals are now dual-signed with Ed25519 + ML-DSA-65 when PQC keys are available:
- **Auth tokens**: Format extended to `<user_id>.<timestamp_ms>.<ed25519_sig>.<mldsa_sig>`. The server verifies both signatures when the user has a PQC signing key registered.
- **Messages**: `pqcSignature` field added to `SendMessageRequest`. Recipients enforce mandatory ML-DSA verification for PQC-capable senders.
- **Call signals**: `pqcSignature` field added to `CallSignalRequest`. Same enforcement as messages.
- **Anti-downgrade**: If a user has a PQC signing key, it cannot be cleared via key rotation. An attacker who compromises Ed25519 cannot downgrade the user from hybrid PQC+Ed25519 auth back to Ed25519-only.
- **Key rotation**: Proof-of-possession is required for both Ed25519 (new key signs itself) and ML-DSA-65 (new PQC key signs its own Base64 encoding).

### Call Ringing Confirmation (v0.4.0)
A `call_ringing` signal is sent by the callee when it receives a `call_offer`, confirming the device is actively ringing. The caller transitions from "Connecting..." to "Ringing..." only after receiving this confirmation, providing accurate call status to the user.

### Call Signal Reliability (v0.3.4–0.3.8)
Call signaling has been progressively hardened:
- **v0.3.4**: `rejectCall()` and `hangup()` send the signaling message before tearing down local call state, so the peer always receives reject/hangup.
- **v0.3.7**: Call signals now have FCM push fallback and server-side buffering (30s TTL) for offline recipients. The client checks the `delivered` field from `CallSignalResponse` instead of assuming delivery on HTTP success. `sendSignal()` only retries on network errors — if the server accepted but couldn't deliver (recipient offline), the signal is already buffered and FCM push is sent, so client retries would be redundant. `hangup()`/`rejectCall()` end the call UI immediately and send the signal fire-and-forget in the background (no UI blocking). A 35s ringing timeout ends unanswered calls with `UNAVAILABLE` status.
- **v0.3.8**: Calls to newly added contacts (no prior messages) no longer silently fail. Unknown callers are resolved by fetching their key bundle from the server, with signature verification before persisting the contact. Contact resolution runs outside the signal mutex to avoid blocking ICE candidate processing during network requests.

### Rate Limiter Hardening (server v0.3.4)
- **Disconnect bypass fix**: Per-user WebSocket signal rate limit state is no longer cleared on disconnect. Previously, a malicious user could reset their quota by reconnecting. Stale entries are pruned periodically (idle > 5 min) instead.
- **Pruning performance**: Rate limit pruning in both WebSocket and REST call signaling routers now uses a high-water mark (2000 entries) with time-gated scans (once per 60s) to avoid O(N) dictionary iteration under the global lock on every request.

### Security Hardening (v0.3.10 / server v0.3.5)
- **Key material zeroization**: Private signing keys, PQC decapsulation keys, classical/PQC shared secrets, HKDF inputs, and chain material are zeroized after use to limit lifetime in memory.
- **Ratchet state invalidation on failure**: If persistence fails after ratchet mutation, the session is invalidated (removed from memory and disk) to force re-establishment, preventing key/nonce reuse.
- **Ratchet header validation**: `messageNumber` and `previousChainLength` from untrusted input are validated as non-negative before use.
- **PQC downgrade on failure**: If KEM ciphertext is present but session re-establishment fails, the message is rejected rather than silently falling back to classical-only keys.
- **Server TOCTOU fix**: Message queue depth check uses `FOR UPDATE` row lock on the recipient's Identity to prevent concurrent requests bypassing `MAX_PENDING_MESSAGES_PER_USER`.
- **Share code collision on rotation**: Key rotation now catches `IntegrityError` on commit, matching bootstrap's collision protection.
- **Nginx security headers**: HSTS, CSP, X-Frame-Options, X-Content-Type-Options, and Referrer-Policy added.
- **Rate limiter multi-worker compensation**: Per-worker IP rate limit is divided by `UVICORN_WORKERS` count.
- **WebSocket DB session scoping**: DB session is explicitly closed after auth to avoid pool exhaustion on long-lived connections.

### Security Hardening (v0.4.0)
- **endCallGuard race fix**: The guard preventing double-dispose of WebRTC resources is now reset at the start of each new call, preventing leaked native resources and stuck audio mode when calls start within the 2s cleanup window.
- **Late call_answer rejection**: The `call_answer` handler now checks for terminal call status, preventing a queued answer from reviving a call the user already hung up.
- **SPKI OID validation**: Server-side ML-DSA-65 SPKI header parsing now validates the OID bytes, not just length, preventing garbage SPKI headers from being accepted.
- **PQC signing key persistence**: Contact PQC signing keys are now stored during session upgrade in `tryEstablishSession`, closing a gap where PQC signatures were never verified for contacts upgraded via inbound messages.
- **Control message retry on PQC sig missing**: Control messages (session_reset, account_deleted) with missing PQC signatures are skipped for retry on next sync to handle the key propagation race. Since v0.4.1, retries are bounded by `MAX_PQC_SIG_MISS_RETRIES` (3) to prevent infinite retry loops.
- **Process kill after wipe**: `wipeLocal()` now calls `exitProcess(0)` after database close to prevent the dead `@Singleton AppDatabase` from crashing subsequent DAO access.
- **ML-DSA private key cleanup**: `BouncyCastlePqcProvider.sign()` now calls `tryDestroy()` on the reconstructed private key JCA object.
- **Auth token deduplication**: Dual-signed token generation extracted from `KtorApiService`/`WebSocketClient` into `KeyManager.generateAuthToken()` to prevent implementations drifting apart.
- **Unconditional secret zeroization**: `pqcSecret` is now always zeroized (was conditional on `isPqcEstablished`).
- **ML-DSA failure logging**: Silent ML-DSA signing failures in `SessionResetSender`, `MessageRepositoryImpl`, and `IdentityRepositoryImpl` now log warnings.
- **Schema 6.json restored**: Retroactive modification of the v6 schema (which broke migration testing) was reverted.
- **404 handling in deleteIdentity**: Fragile string matching replaced with direct HTTP status code check.

### WebRTC IP Privacy (v0.5.0, corrected in 0.5.8)
Calls are relay-only: ICE transport `RELAY`, so the only address the peer learns is the relay's, never the phone's. A release build refuses to call when the relay offers no TURN server (`NoRelayException`, shown as "relay unavailable"); only debug builds, for local development without coturn, fall back to `ALL`. (This section used to say every build fell back to `ALL` without TURN; release builds never did.)

Since 0.5.8 a relay-only call is given the TURN server alone, no STUN server, and the app no longer falls back to Google's public STUN server when it can't fetch the ICE servers. It also ignores any STUN server that isn't on the relay's own host, whatever the server list says. Since 0.5.9 a call that may go direct also gets STUN on the relay itself from the start. The phone then knows its public address before it shares any candidates, and nobody learns anything new: the relay sees that address anyway.

**Reconnecting calls (0.5.8).** When a connected call loses its connection (say, Wi-Fi to mobile), the call stays relay-only. WebRTC keeps gathering relay candidates on the new network and sends them to the peer like the first ones; the app also reconnects its socket to the relay at once. Nothing new reaches the peer: relay candidates only, the same end-to-end encryption, the same media key. If the call isn't back within 30 seconds, it ends and the peer is told.

**Direct calls (0.5.8, on by default).** With "Direct calls" on, call audio can go straight between the phones, and then each phone sees the other's IP addresses: the public one and the local ones (home network, mobile). The setting is on by default, the owner's choice for better call quality and less relay traffic (ARCHITECTURE.md, ADR-8). A call goes direct only when both sides agree:
- The caller proposes a direct call (`direct: 1` in the end-to-end encrypted, signed offer) only if its own setting is on and the callee is a contact it added (verified) or accepted (approved conversation) before the call.
- The callee agrees in its answer only if its own setting is on, it trusts the caller the same way, and the caller proposed it. An unknown caller never gets a direct call, even one the user accepts.
- Until then each phone holds back every candidate but its relay ones. The callee shows its addresses once it agrees; the caller only once the answer says the callee agreed. One-sided consent would show the consenting phone's addresses while the audio still went through the other side's relay, so the protocol never does that.
- The relay can't make a call direct, or tell whether one is: the flags are inside the encrypted payload. It still sees both phones' IP addresses, as always, through their connections to it.
- Media stays end-to-end encrypted with the same frame keys; only the path changes. The call screen shows "Direct" or "Via relay", from the candidate pair in use. Apps up to 0.5.7 never send the flags, so calls with them stay relayed.

**ICE restarts (0.5.8).** Some lost connections can't heal by themselves: both phones moved, candidates went into a dead socket, the relay restarted, or the TURN credentials are about to expire. Then the caller restarts ICE, with new ICE credentials and candidates offered in a `call_ice_restart` signal:
- Like every call signal it is signed (Ed25519 and ML-DSA-65), end-to-end encrypted with the call's key and bound to the call's ID. Only apps that announced support (`caps`) in their encrypted offer or answer get one.
- Only the caller offers; the callee answers, or asks the caller to restart. Each restart has a generation number that only grows: an older generation's signal (replayed or late) is ignored, and a repeat of the current one only gets the same answer again.
- A restart changes nothing about who sees what. Relay-only calls stay relay-only, direct calls stay direct, and the media key is the same.
- TURN credentials last an hour, and the relay refuses expired ones. A restart ten minutes before they expire renews them, so relayed calls longer than an hour keep their relay.

**STUN only on our own relay (2026-11-19).** WebRTC sends STUN requests to every STUN server it is given while it gathers candidates, also in `RELAY` mode, which only hides the other candidates. The relay used to list Google's public STUN server first, so Google could learn each phone's IP address and when it made or took a call. `/v1/calls/ice-servers` now lists only the relay's own coturn (STUN and TURN on port 3478). The change is on the server, so it covers every app version.

### WebSocket Frame Size Limits (v0.5.0)
WebSocket frames are capped at 8 KB pre-authentication and 80 KB post-authentication to prevent memory exhaustion from oversized frames. The post-auth limit accommodates the largest valid call signal (65 KB encrypted payload + signatures + metadata). The server-side pending call signal buffer is also globally capped at 10K recipient entries with stale-entry pruning.

### Calls on the lock screen (0.5.12, no full-screen alert since 0.6.0)
An incoming call is a notification that rings, with Answer and Decline; it never takes over the screen (the app doesn't ask for `USE_FULL_SCREEN_INTENT`). Answer opens the call's own screen (`CallActivity`), which the manifest allows to show over the lock screen, so a call can be answered without unlocking. Only that screen shows there:
- Leaving it on a locked phone sends it to the background; the chats stay behind the lock.
- On the lock screen the call notification says only "Incoming call". The caller's name shows once unlocked (the notification's public version).

### Receipts (delivered since 0.5.10)
- **Read receipts** are sent by the reader's app only while its user has them on. They're signed by the reader and go through `/v1/receipts`, apart from the message queue. The relay learns which messages were read and when.
- **Delivery receipts** come from the relay itself. When a recipient's app acknowledges envelopes (and the relay deletes them), the relay queues a receipt for each message's author, listing those relay message ids. The relay already knew about that delivery. What's new is that the sender learns when the other phone fetched its message, and so that the phone was online then, as in other messengers.
- **Delivery receipts aren't signed.** The relay could fake or withhold them, as it could already delay or drop messages. They don't depend on the read receipts setting. Queued ones are capped per author, purged with undelivered messages after their lifetime, and deleted with either account.

### Display Names (v0.5.3+)
Your own name, if you set one, is sent to a contact inside an ordinary ratchet-encrypted, signed message when you add them or accept their message request. The relay sees only an encrypted envelope (and its length), like any other message. Received names are sanitised (control and text-direction characters removed, 40 characters at most) and never replace a nickname you gave the contact. Names are stored only on the device, in the encrypted database and encrypted preferences, and are deleted with the account. A contact can still choose any name, including one that looks like someone else's; the share code or QR code you added them with is what identifies them.

### Background Connection (v0.5.4+)
Off by default, except on relays that can't push to the app, where setup turns it on (0.5.17, see Invite-only Sign-up). When it's on, a foreground service keeps the WebSocket to the relay open while the app is closed, so the relay sees the phone's IP address and that it's online for as long as it runs, just as it does while the app is open. When it's off (since 0.5.11), the app closes the WebSocket about 2 seconds after it leaves the screen, unless a call is running; a push wake-up opens it again for a few seconds. So the relay sees the phone online only while it is in use. Nothing else changes: messages stay end-to-end encrypted and the relay learns no content.

### Invite-only Sign-up (0.5.17)
A relay creates an account only with a one-time invite from an existing member (`INVITE_ONLY`, on by default); the first account on a new relay needs none. Members create invites in Settings. A code is 60 random bits, shown as 12 characters, and works once, for 7 days. Each member can have 5 open and create 10 a day. The relay stores only each code's SHA-256, so a copy of its database holds no usable codes, and codes travel only in request bodies, never in URLs that a proxy would log. Retrying a sign-up with the same keys returns the same account, so a lost reply can't use up an invite. Deleting an account deletes its open invites.

- **What an invite reveals:** an invite link or QR code holds the relay's address, the code and the inviter's share code. Whoever gets it can join that relay once while it's valid, and can add the inviter as a contact, as with any share code.
- **Invite links** (0.6.0): the link is `https://<relay>/invite#c=<code>&s=<share code>`. The code and share code sit after the `#`, which browsers never send, so opening the link sends the relay only a plain request for its invite page, and the relay's web server and logs never see the code. The page is static and the same for every invite: it loads nothing from elsewhere, its Content-Security-Policy allows only its own script and style by their hashes, and that script reads the `#` part in the browser, checks it, and builds the button that opens the app. With the app installed, Android opens the author's relay's links in the app directly, through App Links verified by the relay's `/.well-known/assetlinks.json`, which names the app's package and signing certificates. The app treats an https link like any other invite: it shows the relay's address and asks before joining.
- **What the relay learns:** who invited whom. It keeps that until 30 days after the invite was used.
- **Choosing a relay:** the app comes with no relay address. Setup takes it from the invite or from an address the user types, checks that a Sotto relay answers there, and shows the address before joining. A link can point at any relay, and that relay's operator would see what's listed in "What the Server Knows". The inviter is added through the usual share-code check against their keys (see "Key Authenticity"), so a relay can't substitute other keys. Apps set up before 0.5.17 stay on the author's relay.
- **Push:** only the author's relay has the app's Firebase key. On other relays the app registers no push target at all, and setup turns the background connection on instead.

### Certificate Pinning
Network security config (generated at build time for the owner's relay, `sotto.ownerRelayHost`, so its address stays out of the repository) pins the relay server's certificate chain to Let's Encrypt's root keys (ISRG Root X1, ISRG Root X2 and ISRG Root YE), so certificate renewals and Let's Encrypt's intermediate rotations don't lock the app out (since v0.5.1; before, it pinned the server's own certificate and intermediate). Pins expire 2028-10-01 and must be renewed before then. Since v0.5.0, cleartext traffic exemptions for local development domains are restricted to debug builds only.

### Relay Logs and Scanners (2026-11-20)
**No addresses in the logs.**
- nginx logs each request's time, method, path, protocol, status, size and duration, but not the client's address. The account id in `/v1/identity/<id>/keys` and the share code in `/v1/identity/resolve/<code>` are cut from the path.
- nginx adds the client's address to every error it logs about a connection, so its error log keeps only emergencies, which are about nginx itself.
- coturn keeps no log, because its log names every client's address.
- The app server logs no addresses.
- Before this, nginx's default log format recorded every request's address, and coturn's log recorded every call's.

**Only its own name.** With `RELAY_HOSTNAME` set in `.env`:
- nginx refuses a TLS connection that asks for no name or another name, before it sends the certificate.
- It closes plain-HTTP connections for other names.
- Scanners that sweep IP addresses get nothing back, not even the relay's name from its certificate.

The name itself is public, because Certificate Transparency logs list every certificate. So this cuts the noise of address sweeps rather than hiding the relay; a scanner that knows the name gets through to nginx. Paths the relay doesn't serve get a closed connection instead of an answer. The relay stays on port 443, for the reasons in ARCHITECTURE.md ADR-26.

### Voice Messages, Photos and Files (0.7.7)
- **Small media inline.** A voice message (up to 60 s) or a photo shrunk to about 1280 px travels inside an ordinary ratchet-encrypted, signed message, at most about 190 KB. It is as protected as a text message, post-quantum included. The relay can tell it from text only by its size. Photos are decoded and re-encoded as JPEG on the phone, so no EXIF (location, camera, time) leaves it.
- **Files through an object store.** A larger file is encrypted on the phone with AES-256-GCM in 64 KiB segments under a random key (each segment's nonce holds a random prefix, its number and a last-segment flag, so changes, reordering and truncation are detected). The ciphertext goes straight to an S3-compatible bucket (Tigris, for the author's relay) with a URL the relay signs, size included. The key, name and type go to the recipient inside an end-to-end encrypted message; the relay and the store see only ciphertext, sizes, timing and the phones' addresses. The recipient checks every segment before keeping the file, then the object is deleted; anything uncollected goes after 30 days.
- **HD photos** (the HD switch in the photo preview). The normal photo travels inline as above; its full-size copy (up to 4096 px, re-encoded the same way) goes through the object store like a file, its key inside the photo's message.
- **At rest.** Media and files sit under `files/media`, encrypted, with their keys in the message's SQLCipher row. Playing and viewing decrypt into memory; opening a file in another app decrypts a copy into the app's cache, which is removed within an hour. Files of deleted messages are swept, and their keys went with the rows.
- **Sizes are padded** (Padmé, at most about 12%): files and HD copies with zeros before encryption, inline media with a filler field. The real size travels inside the encryption.
- **Message requests bring text only.** Media and files from someone whose request isn't accepted are not stored or downloaded; the receiving phone enforces this, whatever the sender's app does.
- **Large uploads go in parts** (8 MiB, multipart), each part's size signed into its URL; downloads resume with HTTP Range. Every segment is still checked before a file is kept.
- **The relay's key** to the bucket can read and write that bucket only. It can't create buckets, keys or policies.

## Security Checklist

- [x] Messages encrypted before leaving device (AES-256-GCM)
- [x] Hybrid key establishment framework (X25519 + PQC interface)
- [x] No plaintext in logs or crash reports
- [x] No PII required for sign-up (since 0.5.17 it takes a member's invite instead)
- [x] Invite-only sign-up: one-time invites valid for 7 days, stored only as hashes
- [x] SQLCipher encrypted database
- [x] Android Keystore for key material
- [x] FLAG_SECURE for screen security (optional; off by default since v0.5.6)
- [x] No cloud backup by default
- [x] Lock screen notification previews configurable (hidden by default until v0.5.6; sender and message since)
- [x] No telemetry or third-party trackers
- [x] ProGuard/R8 enabled for release builds
- [x] Production ML-KEM-768 library (Bouncy Castle 1.79+)
- [x] Double Ratchet for forward secrecy
- [x] Real relay server (Python/FastAPI) with rate limiting
- [x] WebSocket real-time delivery
- [x] Message signatures (Ed25519) — sender signs, recipient verifies
- [x] Signed request authentication (Ed25519 token-based, prevents spoofing)
- [x] Ephemeral TURN credentials (HMAC-based, per-session)
- [x] Metrics endpoint authentication (bearer token)
- [x] WebSocket call signal validation and rate limiting
- [x] Debug logging gated behind BuildConfig.DEBUG
- [x] Call signal signatures (Ed25519) — prevents signal injection
- [x] Per-challenge TURN nonce rotation (RFC 5389 compliant, handled by coturn)
- [x] ICE candidate bounds (max 100 pending per call)
- [x] Certificate pinning with real SPKI hashes
- [x] Ratchet state persistence (survive app restart) — sessions persisted to EncryptedSharedPreferences
- [x] PQC downgrade rejection — classical-only re-establishment blocked for hybrid contacts
- [x] Base64 input validation on all externally-received key material
- [x] Call signal reliability — FCM push fallback, server-side buffering, ringing timeout, delivery status check
- [x] Session reset on key rotation — peers notified and blocked until re-keyed
- [x] Account deletion protocol — contacts notified, server identity deleted, send permanently blocked
- [x] Key change audit trail — rotation and deletion events visible in chat history
- [x] Call frame encryption — AES-GCM via WebRTC FrameCryptor, HKDF-derived keys with per-call salt
- [x] Key material zeroization — signing keys, shared secrets, chain material wiped after use
- [x] Mandatory message signatures — unsigned messages rejected unconditionally
- [x] Nginx security headers — HSTS, CSP, X-Frame-Options, nosniff, Referrer-Policy
- [x] Server TOCTOU protection — row-level locking on message queue depth check
- [x] Ratchet header validation — bounds checking on untrusted messageNumber/previousChainLength
- [x] ML-DSA-65 dual-signing — auth tokens, messages, and call signals signed with Ed25519 + ML-DSA-65
- [x] Hybrid PQC call encryption — ML-KEM-768 key agreement + SHAKE-256 KDF for call session keys
- [x] PQC signing key anti-downgrade — server prevents clearing PQC signing key once set
- [x] PQC proof-of-possession — ML-DSA key rotation requires signing proof
- [x] Call ringing confirmation — caller shows accurate Ringing status based on peer signal
- [x] WebRTC relay-only calls — release builds refuse to call without TURN; direct calls only between earlier contacts who both have them on (on by default since 0.5.8)
- [x] WebSocket frame size limits — pre-auth and post-auth caps prevent memory exhaustion
- [ ] Push proxy to break FCM linkability
- [x] Key rotation protocol — `rotateIdentityKeys()` with crash-safe staged promotion
- [ ] Automated key rotation schedule + old-key grace period
- [ ] Reproducible builds

## Abuse Controls

Abuse controls that preserve privacy:

1. **Share-code gating**: You cannot message someone without their share code. This prevents unsolicited spam.
2. **Rate limiting** (server-side): The relay server rate-limits message submission to 60 messages/minute per user ID, with a 1000-message pending queue cap per recipient.
3. **Local block list** (future): Users can block contacts locally. Blocked contacts' messages are silently dropped.
4. **Report mechanism** (future): Users can forward an encrypted envelope + decrypted content to an abuse review system. This is opt-in and requires the reporter to reveal the message content.
5. **No global directory**: There is no way to enumerate users or discover contacts without knowing their share code.
6. **Invite-only sign-up** (0.5.17): A relay creates accounts only with a member's one-time invite, so the public app can't sign anyone up on a relay without one. Each member can have 5 invites open and create 10 a day, and the relay knows who invited whom.
