# Architecture Decision Record

## ADR-1: Clean Architecture with MVVM

**Decision**: Separate the app into UI, domain, data, and crypto layers with strict dependency rules.

**Why**: The crypto layer and transport layer are the most likely components to change (new PQC library, new backend). Clean boundaries make these swappable without touching UI or business logic. MVVM with StateFlow gives reactive, testable ViewModels that work naturally with Compose.

**Tradeoff**: More boilerplate than a single-module app. Acceptable for a security-critical application where correctness and maintainability matter more than initial velocity.

---

## ADR-2: Hilt over Koin

**Decision**: Use Hilt for dependency injection.

**Why**: Compile-time verification of the DI graph catches configuration errors early. First-class support for Android components (ViewModels, WorkManager, Services). Scoping lifecycle is handled automatically.

**Tradeoff**: Heavier annotation processing than Koin. Worth it for the safety guarantees in a security-sensitive app.

---

## ADR-3: SQLCipher-backed Room database

**Decision**: Encrypt the local database with SQLCipher, keyed from Android Keystore.

**Why**: The database contains encrypted message blobs, conversation metadata, and contact keys. Even though message bodies are encrypted at the application layer, metadata (who talked to whom, when) is sensitive. SQLCipher encrypts the entire database file at rest.

**Tradeoff**: ~2MB APK size increase. Minor performance overhead on queries (negligible for chat workloads).

---

## ADR-4: Hybrid classical + post-quantum key establishment

**Decision**: Combine X25519 ECDH with ML-KEM-768 via HKDF, behind a `CryptoEngine` interface.

**Why**: X25519 is battle-tested and provides strong classical security today. ML-KEM-768 (FIPS 203) provides quantum resistance. The hybrid approach means:
- If the PQC component is broken, classical security remains.
- If classical crypto is broken by a quantum computer, the PQC component provides protection.
- The `CryptoEngine` interface allows swapping implementations without redesigning the app.

**Current state**: `BouncyCastlePqcProvider` provides production ML-KEM-768 via Bouncy Castle 1.79+. The hybrid X25519 + ML-KEM key establishment is fully functional with symmetric negotiation (either party can initiate encapsulation). PQC downgrade from hybrid to classical-only is rejected. `MockPqcProvider` exists for JVM unit tests only.

**Tradeoff**: Larger key bundles and slight handshake overhead. Acceptable for a messaging app where handshakes are infrequent.

---

## ADR-5: Anonymous guest identity

**Decision**: Generate cryptographic identity on first launch with no PII.

**Why**: Traditional registration (email/phone) creates a correlation point. A locally-generated Ed25519 signing key + X25519 identity key serves as the user's identity. Discovery happens via share codes (truncated fingerprint over all public keys, verified by the client — see SECURITY.md "Key Authenticity").

**Tradeoffs**:
- **No account recovery**: If the device is lost, the identity is gone. This is a deliberate privacy choice. Opt-in encrypted backup could be added later.
- **No server-side identity verification**: The server cannot verify that a user "owns" an identity beyond possession of the private key.
- **Spam/abuse**: Without registration friction, spam is easier. Mitigated by share-code-based contact discovery (you must know someone's code to message them) and, since 0.5.17, by invite-only sign-up (ADR-20).

---

## ADR-6: FCM as wake-up signal only

**Decision**: Use Firebase Cloud Messaging to wake the device, but never send message content through FCM.

**Why**: FCM payloads pass through Google's infrastructure. Even if encrypted, metadata (sender, timing) would be visible. Instead, the FCM message is a bare wake-up signal (`{"type": "new_message"}`): no sender, never message content. The app then fetches the encrypted envelope directly from the relay server.

**Tradeoff**: Slightly higher latency (FCM wake-up + fetch) vs. including the payload in the push. Worth the privacy improvement.

**Extension (v0.3.7)**: The same wake-up-only pattern now applies to call signaling. When a call offer cannot be delivered via WebSocket (recipient offline), the server sends an FCM wake-up push and buffers the signal. The recipient's app reconnects WebSocket on push receipt and receives the buffered signal. Call signal content never passes through FCM.

**Extension (v0.3.10)**: On FCM receipt, the client now also calls `webSocketClient.ensureConnected()` to ensure buffered call signals are delivered immediately. Notifications are shown by `MessageRepositoryImpl` after decryption — not by `FcmService` — so they display the real sender name and decrypted message body instead of generic text. Notifications are suppressed for the conversation currently open on screen.

**Extension (v0.6.1)**: `FcmService` no longer fetches inside the push callback. It reconnects the socket (for buffered call signals) and queues `PushSyncWorker`, an expedited WorkManager job. Each push's job is appended after any running one, so every push gets a fetch that starts after it arrived. Android gives a push only seconds. In tests on Android 17, an app woken in the background lost its network about 25 s after it started and was frozen at 30 s. A fresh install's first fetch can take longer than that: its code isn't optimised yet, every request is signed with ML-DSA, and background apps run on the slow cores. The download then went through, but the confirmation and the notification didn't, and the message waited until the app was opened (seen on the owner's phone on 2026-11-20). A running job keeps the app out of the freezer and on the network until it ends. The job waits for a fetch that is already running (`PendingFetchGate.fetchAndWait`) instead of returning at once, and retries a failed fetch. Out of expedited quota it runs as ordinary work.

**Extension (2026-11-20, relay)**: A push's lifetime depends on what it's for. A call offer's push lasts 60 s (`CALL_PUSH_TTL`): later, it would ring for a call that's over. A message's push lasts 28 days (`MESSAGE_PUSH_TTL`, Firebase's longest), so a phone that comes back from a signal gap is woken, and its fetch brings everything waiting. Pushes don't collapse: 0.6.5 to 0.6.7 gave message pushes a `collapse_key`, and Firebase throttles collapsible messages to a burst of 20 per app and device, then 1 every 3 minutes ([FCM throttling](https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas)), so after a busy while messages stayed at "sent" until the app was opened. Firebase keeps up to 100 ordinary pushes for an offline phone; past that it drops them and calls `onDeletedMessages`, where the app fetches everything (0.6.8). Before, every push lasted 60 s. A phone without signal for more than a minute then got no wake-up, and its messages waited for the app's 15-minute check, or for the app to be opened.

**Extension (v0.5.5)**: Devices are addressed by their Firebase installation ID (`FirebaseMessaging.register()` / `onRegistered`; the server sends with `Message.fid`) instead of the FCM registration token, which Google deprecated. The relay still accepts tokens from older apps; an identity has one push target or none. Phones without Google Play services register none and rely on the optional background connection (v0.5.4).

**Extension (v0.5.10)**: An open socket doesn't prove the app reads it. Android pauses background apps that have no foreground service, and their sockets stay open, so the relay counted calls and messages as delivered and sent no push. The relay now waits for proof:
- **Call offer.** The callee's app answers within 3 s (ringing, busy, reject or hangup). Otherwise the relay closes that socket, keeps the offer and the caller's candidates for the app's next connection, and pushes.
- **Message.** The app fetches it within 5 s, or the relay pushes.

---

## ADR-7: Mock API service layer

**Decision**: Define the full API contract as an interface (`ApiService`) with an in-process mock implementation for development.

**Why**: Allows the full app to run without a backend server. The interface contract is well-defined, making it straightforward to swap in a real Ktor client implementation. The mock maintains in-memory state, simulating the relay server's behavior.

**Tradeoff**: Mock doesn't simulate network latency, failures, or concurrent access from multiple devices. These must be tested with a real backend.

---

## ADR-8: Privacy defaults over convenience

**Decision**: Privacy settings default to the most restrictive option, except four the owner chose for convenience: three in v0.5.6 and direct calls in v0.5.8.

- Lock screen previews: sender and message (since v0.5.6; each level down to none can be chosen)
- Read receipts: on (since v0.5.6; they are mutual, so turning them off also stops seeing others')
- Screen security: off (since v0.5.6; FLAG_SECURE when turned on)
- Direct calls: on (since v0.5.8; calls between earlier contacts go straight between the phones, which then see each other's IP addresses; turning it off on either phone keeps calls relayed, see ADR-17)
- Cloud backup: disabled
- Telemetry: none

**Why**: Users who want convenience can opt in. Users who want privacy shouldn't have to remember to opt out.

---

## ADR-9: No contact book upload

**Decision**: Contact discovery is exclusively via share codes, typed or scanned as QR codes.

**Why**: Uploading contact books (even hashed) creates a social graph on the server and risks de-anonymization. Share-code-based discovery is more friction but fundamentally more private.

**Tradeoff**: Less convenient onboarding. Users must manually exchange share codes. QR code scanning reduces this friction, and joining with someone's invite adds them as a contact (ADR-20).

---

## ADR-10: Ratchet state persistence via EncryptedSharedPreferences

**Decision**: Persist Double Ratchet session state (root key, chain keys, message counters, skipped keys) to EncryptedSharedPreferences rather than the Room database.

**Why**: Ratchet state changes on every message sent or received. Using EncryptedSharedPreferences avoids Room schema migrations for rapidly evolving state and keeps crypto material out of the SQLCipher database, which may be backed up or exported. EncryptedSharedPreferences is backed by Android Keystore, providing hardware-backed encryption at rest.

**Tradeoff**: Not suitable for multi-device sync (SharedPreferences is device-local). Full DB-backed persistence will be needed for multi-device support.

---

## ADR-11: Crash-safe key rotation with staged promotion

**Decision**: Use a two-phase commit for identity key rotation: stage new keys locally before the server call, then promote to active only after server acceptance.

**Why**: If the app crashes after the server accepts new keys but before the client updates its local storage, the client would be out of sync with the server. Staged keys allow automatic recovery on next launch: detect staged keys, promote them, and invalidate session caches.

**Tradeoff**: Slightly more complex key storage (active + staged slots in KeyManager). Justified by the severity of a split-brain key state.

---

## ADR-12: Session reset via control messages in the message pipeline

**Decision**: After key rotation causes decrypt failures, notify the sender via a control message sent through the existing `/v1/messages/send` endpoint rather than introducing a new WebSocket signal type or REST endpoint.

**Why**: WebSocket signals are ephemeral — if the sender is offline, the signal is lost and they keep sending undecryptable messages. The message pipeline is store-and-forward with FCM fallback, guaranteeing delivery. Control messages are distinguished by a JSON nonce (`{"ctrl":"session_reset","ts":…,"n":…}`) with an empty body. No server changes are required — the server relays the control message like any other opaque envelope.

**Why manual re-establishment**: The sender must tap "Re-establish session" rather than auto-re-keying. This gives the sender explicit visibility that the peer rotated keys, which is important for security awareness (similar to Signal's "safety number changed" notification). Auto-re-keying would silently mask key changes.

**Tradeoff**: The sender sees a blocked conversation until they act. This is the intended UX — security visibility over convenience.

---

## ADR-13: Account deletion via control message with `peerDeleted` flag

**Decision**: Distinguish account deletion from key rotation by introducing an `account_deleted` control message type and a `peerDeleted` boolean on the conversation entity. When a user wipes their data, each contact receives `{"ctrl":"account_deleted"}` instead of `{"ctrl":"session_reset"}`.

**Why**: Key rotation and account deletion have very different implications for the peer. After key rotation, the peer can re-establish the session and continue chatting. After account deletion, the peer identity is gone — further messages would be undeliverable. Using a distinct control message lets the recipient's UI show "Peer deleted their account" with permanent send-blocking, rather than offering a "Re-establish session" action that would fail.

**Signature verification**: The account deletion control message is signed by the sender. Since the sender may have already deleted their server-side identity by the time the message is processed, verification falls back to the locally stored contact keys (which were established during a prior verified session).

**Server-first wipe**: The wipe flow deletes the server-side identity (`DELETE /v1/identity/me`) before clearing local keys. This ensures the auth token (which requires the local signing key) is still available for the API call and for retries if the first attempt fails.

**Tradeoff**: If the server is unreachable, the user must choose between retrying or deleting locally without server cleanup. The retry/skip dialog makes this explicit.

---

## ADR-14: Lifecycle guard against navigation ghost-clicks

**Decision**: Guard interactive actions (call button, send button) behind a `Lifecycle.State.RESUMED` check to prevent ghost-clicks during Compose Navigation exit animations.

**Why**: During a screen transition, both the outgoing and incoming composable are in the composition tree. The outgoing composable's lifecycle drops from `RESUMED` to `STARTED`, but its UI is still visible and tappable during the exit animation. If the outgoing screen's call button occupies the same position as the incoming screen's settings button, a tap triggers the call. Checking lifecycle state is the standard Compose Navigation pattern for preventing stale interactions.

**Tradeoff**: None significant. The check is a single property read and is the idiomatic solution.

---

## ADR-15: ML-DSA-65 dual-signing for hybrid post-quantum authentication

**Decision**: Sign all auth tokens, messages, and call signals with both Ed25519 and ML-DSA-65 when PQC keys are available. Enforce mandatory ML-DSA verification for users who have a PQC signing key registered.

**Why**: Ed25519 alone is vulnerable to quantum attacks. Adding ML-DSA-65 (NIST FIPS 204) creates a hybrid authentication model where an attacker must break both classical and post-quantum signature schemes. The mandatory enforcement (PQC-capable senders cannot omit ML-DSA signatures) prevents downgrade attacks where an attacker who compromises Ed25519 silently strips the PQC layer. The server prevents clearing a PQC signing key once set, ensuring the anti-downgrade protection is permanent.

**Implementation**: Auth token generation is centralised in `KeyManager.generateAuthToken()` (used by both `KtorApiService` and `WebSocketClient`) to prevent drift. The token format is `<user_id>.<ts>.<ed25519_sig>[.<mldsa_sig>]`. Key rotation requires proof-of-possession for both Ed25519 and ML-DSA-65.

**Tradeoff**: ML-DSA-65 signatures are ~3.3 KB each, increasing message and signal sizes. Signing is ~1-2ms on modern hardware. Acceptable for a security-critical messaging app where messages are infrequent relative to computation cost.

---

## ADR-16: SHAKE-256 KDF for call encryption (independent of messaging ratchet)

**Decision**: Derive call session keys using SHAKE-256 (SHA-3 family) KDF instead of HKDF-SHA256, independently from the messaging Double Ratchet.

**Why**: Call keys must be derived without advancing the message ratchet (which would desynchronise the messaging chain). Using a separate KDF with a distinct domain separator (`ChatControll-v1-call-init`) ensures call keys are cryptographically isolated. SHAKE-256 was chosen over HMAC-SHA-256 so the entire post-quantum call path avoids SHA-2 dependencies. Each input is length-prefixed (4-byte big-endian) to prevent concatenation collisions.

**Tradeoff**: Introduces a second KDF alongside HKDF-SHA256 (used by the messaging layer). The separation is intentional — the messaging layer's HKDF-SHA256 is part of the Signal Double Ratchet specification, while the call layer is free to use a SHA-3-family construction.

---

## ADR-17: Direct calls by mutual opt-in, agreed in the encrypted call setup

**Decision**: A call goes direct only when both users have "Direct calls" on (the default, ADR-8) and each phone trusts the other as an earlier contact (added by share code, or an accepted conversation). Every other call is relay-only (TURN). The caller proposes it with `direct: 1` in the encrypted offer, and the callee agrees with `direct: 1` in its answer. The callee then shows all its candidates from the start. The caller starts relay-only and switches to all transports (`setConfiguration`) once the answer agrees, which also releases the candidates it gathered meanwhile (`surfaceIceCandidatesOnIceTransportTypeChanged`).

**Why**: Direct audio sounds better and costs the relay nothing; relayed audio is about 60 MB an hour of the server's free 1 GB a month. But a direct path shows each phone's IP addresses to the other, so either user can refuse it, and a call only goes direct when both phones agree. One-sided consent would show the consenting phone's addresses while the audio still went through the other side's relay, so the protocol never does that. With the flags inside the end-to-end encrypted, signed payload, the relay can neither force the choice nor see it. Older apps leave the flags out and ignore unknown keys, so calls between mixed versions stay relayed.

**Tradeoff**: A direct call starts relayed and moves to the direct path once it works, usually within a second. People who weren't contacts before the call never get a direct call, even when the user accepts the call.

---

## ADR-18: ICE restarts led by the caller, in their own signal type

**Decision**: When a connected call's connection doesn't come back by itself (lost for more than 2 seconds, or failed), when the caller's network changes, or before the TURN credentials expire, the caller restarts ICE. It sends the new offer in its own signal type, `call_ice_restart` (kinds `offer`, `answer` and `request`, numbered by generation). Only the caller offers; the callee answers, or asks the caller to restart (every 4 seconds while its connection is lost, and after its own network change). An unanswered offer is sent again after 4, 8, 16 and then 30 seconds. ICE candidates carry their generation and wait until that generation's description is applied (`IceCandidateGate`).

**Why**: Reusing `call_offer` and `call_answer` would ring again, fail the offer's freshness check, count against the relay's offer limit, or end the call. Older apps ignore unknown signal types, and both sides announce the capability in their offer or answer, so a mixed-version call never sees a restart. With a single offerer, both sides never offer at once (glare). Generation numbers make resent and replayed restart signals harmless.

**Tradeoff**: When the callee's network changes, recovery goes through a request round trip, a little slower than if the callee could restart by itself.

---

## ADR-19: The relay stays on the Premium network tier; no attachments yet

**Decision**: The relay keeps its Premium-tier external IP, and the app doesn't send photos, files or voice messages yet.

**Why**: Premium carries traffic on Google's network from close to each phone to the relay, which keeps relayed calls and the message socket steady; a connection steadier than WhatsApp's is the point of the app. Its free egress is 1 GB a month, and relayed calls (about 60 MB an hour, ADR-17) need it. The Standard tier would include 200 GiB a month free, but its traffic crosses the public internet. Attachments sent through the relay would compete with calls for the free gigabyte. The research for a later version is in [docs/attachments-research.md](docs/attachments-research.md), including a light option that carries short voice notes and small photos inside ordinary messages.

**Tradeoff**: No photos, files or voice messages for now. If relayed calls ever pass about 15 hours a month, the extra egress is billed.

---

## ADR-20: Invite-only sign-up; the relay is chosen at setup

**Decision**: A relay creates an account only with a one-time invite from an existing member (`INVITE_ONLY`, on by default); its first account needs none. Every member can invite: 5 open invites at a time, 10 a day, each valid for 7 days. Release builds carry no relay address. Setup takes one from an invite (a link, a pasted message or a QR code) or from an address the user types, checks that a Sotto relay answers there (`/health`), and shows it before joining. An account stays on its relay; wiping the app's data clears the choice. Apps set up before 0.5.17 stay on the author's relay.

**Why**: The code and the APKs are public, so anyone could install the app and use the author's relay: its message queue, its TURN server and its free gigabyte of egress (ADR-19). Invites keep a relay to the people its members bring in, without asking anyone for personal details (ADR-5). Shipping no relay address keeps the public app from pointing anyone at the author's server, and lets anyone use it with a relay of their own. Retrying a sign-up with the same keys returns the same account, so a lost reply can't use up an invite.

**Tradeoff**: Joining takes an invite from a member. A new relay's first account goes to whoever reaches it first, so its operator should join right after starting it. The relay learns who invited whom (kept until 30 days after use). Push works only through the author's relay, which holds the app's Firebase key, so on any other relay the app keeps a background connection open, at some cost in battery.

---

## ADR-21: Call sound quality: both phones agree, and the lower choice wins

**Decision**: "High-quality calls" (off by default) sends the voice with Opus at 48 kbit/s instead of WebRTC's usual 32 kbit/s, only when both phones have it on. The caller's encrypted offer says its level (`quality`), the callee's answer says the lower of the two, and an app that says nothing counts as standard. Each phone then sets its own encoder by adding `maxaveragebitrate` to the other phone's session description before applying it, for the first exchange and for every ICE restart (ADR-18).

**Why**: One person's choice to save data, or an older app, mustn't be overruled by the other side, so the lower level wins, as with the consent for direct calls (ADR-17). Each phone raises only its own sending, and only after both agreed, so an older app is never sent more than it asked for. The level travels inside the end-to-end encrypted, signed call setup, so the relay can neither see nor change it.

**Tradeoff**: The difference is subtle for speech, since Opus at 32 kbit/s already carries a voice well, and there's none on a classic Bluetooth headset, whose call link is narrower than either. HD calls use about a quarter more data. Through the relay that's about 75 MB an hour instead of 60, so relayed HD calls use up the free gigabyte (ADR-19) about a quarter faster; direct calls cost the relay nothing.

---

## ADR-22: Renamed to Sotto, with a new app ID; protocol names unchanged

**Decision**: In 0.6.0 the app, its app ID (`com.vagujhelyigergely.sotto`), code packages, invite links (`sotto://`), the relay's self-description (`sotto-relay`) and the repository became Sotto. Unchanged on purpose: the encryption's domain-separation labels (`ChatControll-fingerprint-v1`, `ChatControll-ratchet`, `ChatControll-call-media` and the rest), the profile message marker (`chatcontroll/profile/v1`), the relay's address and its database names. The app still reads `chatcontroll://` invites and accepts relays that say `chatcontroll-relay`.

**Why**: The owner wanted a name of its own. The labels are part of the wire format: share codes are fingerprints computed with one of them, by the app and the relay alike, so renaming them would change every share code and break encryption between versions, for nothing users can see. The relay's database names belong to a running server, not to the brand. Keeping the old invite links working lets people move from ChatControll to Sotto with an invite made in the old app.

**Tradeoff**: A new app ID means Sotto can't update ChatControll: everyone installs Sotto as a new app, with a new account, and removes ChatControll. That was acceptable with three accounts. The old name lives on in the protocol labels, where only code readers see it.

---

## ADR-23: Levelling the incoming voice inside WebRTC's playback

**Decision**: Since 0.6.0, the other person's voice passes through the app's own code just before it plays. WebRTC's audio processing module calls a render pre-processor (`ExternalAudioProcessingFactory.setRenderPreProcessing` in the `io.github.webrtc-sdk` build) every 10 ms with the decoded samples, and the app's `VoiceLeveler` changes them in place. It measures the voice's level over the blocks that hold speech, above a noise floor it tracks, averaged over about 1.5 s. Levelling, off by default ("Level quiet voices" in Settings > Calls), lifts a quiet voice towards −23 dBFS, by at most 15 dB, and never makes a voice quieter, so a normal or loud voice plays exactly as it came. **Louder**, a toggle on the call screen remembered for each contact (`contacts.louderVoice`), aims for −16 dBFS: at least 6 dB more, but not above −12 dBFS, and at most 21 dB in all. The gain rises at most 6 dB a second, falls at most 20, and only follows the voice while it speaks. A peak limiter like WebRTC's own AGC2 limiter (the peak of each millisecond, an attack that starts a millisecond early, a 60 ms release) keeps the lifted voice below −1 dBFS. With levelling and Louder both off, the voice plays exactly as it came.

**Why**: People in calls were hard to hear. The other choice was `AudioTrack.setVolume`, a plain gain WebRTC applies with hard clipping: it can lift a quiet voice, but it can't make a normal voice louder without distorting it, so Louder would have done little. The pre-processor sees every sample, so it can lift and limit; and because it runs inside WebRTC's processing, the echo canceller hears the voice as it's played. The library keeps one audio processing module for the whole app, so the app makes the factory once and never destroys it: its `destroy()` would leave that module dangling. Each call's `PeerConnectionFactory` holds its own reference to the module, so calls can follow one another safely. The bridge never throws into WebRTC's audio thread: after an error, the voice plays unprocessed.

**Tradeoff**: The leveler adds a little work on the playback thread (a few multiplications per sample) and a Room migration (10 to 11). A lifted voice also lifts whatever noise comes with it, which the sender's own noise suppression keeps low; the gain holds still in pauses so it doesn't swell in them. With Louder on speakerphone, the phone's echo canceller has more to remove.

---

## ADR-24: An invite is one https link to the relay

**Decision**: Since 0.6.0, an invite is `https://<relay>/invite#c=<code>&s=<inviter's share code>`. The relay serves `/invite`, a static page that reads the part after `#` in the browser, offers the download, and opens the invite in the app through `sotto://invite?r=<relay>&c=<code>&s=<share code>`. It also serves `/.well-known/assetlinks.json` for Android App Links. The app's manifest claims `/invite` on the author's relay (`sotto.ownerRelayHost`, a build setting kept out of the repository, like the relay's certificate pins it also feeds), so on phones with Sotto Android opens those links in the app directly. The app still reads `sotto://` links and ChatControll's `chatcontroll://` ones, and still finds a link inside a whole pasted message. Setup has no code field: an invite arrives only as the link (opened, pasted or scanned), and a bare relay address only works where no invite is needed, like a new relay's first account.

**Why**: The owner asked for "one single link with url and code combined", and for nobody to type a relay's address and then a code. Before, an invite was a message with a download link, a `sotto://` link that chat apps don't make tappable, and an instruction to paste the whole message, plus a code shown on its own. An https link is tappable everywhere, and any camera opens its QR code. Putting the code after the `#` keeps it out of every request: browsers never send that part, so the relay's web server and logs never see it, as before when codes travelled only in request bodies.

**Tradeoff**: App Links work only for a host built into the app, so invites for other relays go through their page's button, and a self-built app needs its own host and fingerprints. The page needs JavaScript to read the code. The relay now serves a web page, kept static, with a Content-Security-Policy that allows only its own script and style by hash, and nothing loaded from elsewhere.

---

## ADR-25: The phone's own voice processing by default; WebRTC's on request

**Decision**: Since 0.6.0, each call builds its own `JavaAudioDeviceModule` and chooses whose processing the microphone goes through (Settings > Calls > "WebRTC voice processing", off by default). By default (`VoiceProcessing.PHONE`), the phone's own echo canceller and noise suppressor (Android's audio effects on the call's recording) are on wherever the phone has them, and WebRTC's automatic gain is off, so the microphone level is the phone's own. Echo cancellation and noise suppression are still requested from WebRTC, which then defers to the phone's and fills in only where a phone has none, so no call goes without. With the switch on (`VoiceProcessing.WEBRTC`), the phone's effects are off, and WebRTC's echo cancellation, noise suppression and automatic gain run instead. The log says which one each call got.

**Why**: The owner asked for WebRTC's processing to be optional, with the phone's own as the default. Phone makers tune their echo cancellation and noise suppression for their own microphones and speakers. Before, the app already used the phone's echo canceller and noise suppressor, but WebRTC's automatic gain ran on top of the phone's own handling of the microphone level. Releasing the audio device module with the call also ends a small leak: the factory used to make one per call and never release it.

**Tradeoff**: Without WebRTC's automatic gain, a phone with a quiet microphone sends a quieter voice. Louder (ADR-23) lifts it on the listener's side, and "WebRTC voice processing" brings the gain back on the speaker's side. Phones differ, so calls sound less alike from phone to phone than with WebRTC's processing everywhere.


---

## ADR-26: The relay stays on 443, answers only its own name and logs no addresses

**Decision**: nginx refuses every connection that doesn't ask for the relay's name (`RELAY_HOSTNAME`, set in `.env`). A TLS handshake without that name fails before the certificate is sent (`ssl_reject_handshake`), and plain HTTP for another name is closed. Paths the relay doesn't serve get a closed connection (444) instead of a 404. nginx logs requests without the client's address, and without the account ids and share codes in paths. Its error log keeps only emergencies, and coturn keeps no log. The relay stays on port 443.

**Why**: The owner asked whether a random port would keep scanners away. It would cut the noise, but every install stores the relay's address, so moving it needs an app update and leaves older installs unable to connect. Android verifies App Links only on 443, so invite links would open the browser instead of the app. Hotel and office networks often allow only 443. Nor would it hide the relay: Certificate Transparency logs publish its name, and scanners such as Censys sweep every port. Most scanners sweep IP addresses without a name, so refusing unnamed connections turns them away on 443, and they don't even learn the name from the certificate. The owner also wants no IP addresses logged. Before this, nginx's default log format recorded every client's address, and coturn's log recorded every call's.

**Tradeoff**: Without `RELAY_HOSTNAME`, nginx answers any name, as before, so existing relays keep working until it's set. The error log no longer shows upstream failures; the access log still shows them by their status.

## ADR-27: A reply carries its quote inside the encryption

**Decision**: A reply's plaintext is `"\u0001sotto/reply/v1:" + {"text", "to", "quote", "mine"}` (`ReplyPayload`), sent like any other message. `to` names the original by the relay's id for it (`ReceiptManager.relayMessageId`), the one id both phones know. `mine` says whether the sender wrote the original. `quote` is the first 200 characters of the original. The receiver stores the text as the message and the reference in `messages.replyToId`, `replyQuote` and `replyToOwn` (Room 12), pointing at its own id for the original when it still has it.

**Why**: The owner asked for WhatsApp-style replies. A separate kind of envelope would show the relay which messages are replies; inside the encryption the relay sees an ordinary message, as with `ProfilePayload`. Sending the quote rather than only the id means the reply still shows what it answers when the original was deleted or disappeared on either phone, or arrived before the receiver joined from a new install.

**Tradeoff**: Sotto 0.6.9 and older don't know the prefix, and show a reply from a newer phone as its raw payload. Everyone has to update together. The quote is a copy, so editing or deleting the original never changes it.

## ADR-28: A reaction is an unstored message naming another

**Decision**: A reaction's plaintext is `"\u0001sotto/reaction/v1:" + {"to", "emoji", "mine"}` (`ReactionPayload`), sent like a profile: an ordinary envelope that neither phone stores as a message. `to` and `mine` name the message the way a reply names its original (ADR-27). An empty `emoji` takes the reaction back. Each message holds at most one reaction from each side, in `messages.myReaction` and `theirReaction` (Room 13). The sender shows its reaction at once and puts the old one back if the send fails. The receiver drops a reaction to a message it no longer has, or to one outside the sender's chat. A reaction to the user's own message is notified, but counts as no unread message and leaves the chat's preview alone, as on WhatsApp.

**Why**: The owner asked for reactions "like in facebook or whatsapp". As with replies, a separate kind of envelope would show the relay which messages are reactions. Chats are one-to-one, so two columns hold every reaction a message can have, and a reaction goes when its message does (deleted, disappeared, or the chat cleared).

**Tradeoff**: Sotto 0.7.1 and older show a reaction as raw payload, so everyone has to update together. A reaction that can't be sent isn't queued: it goes back, and the user is told to try again. A reaction from "+" accepts any short emoji-looking text (`ReactionPayload.isEmoji`), not only the offered ones.

## ADR-29: Media inline when small, through an encrypted drop when large

**Decision**: Voice messages (up to 60 s) and photos shrunk to fit (about 1280 px, at most about 140 KB) travel inside the ordinary ratchet-encrypted message as a `"\u0001sotto/media/v1:"` payload. Larger files are encrypted on the phone (AES-256-GCM in 64 KiB segments, a random key per file) and uploaded straight to a Tigris bucket with a URL the relay signs; the key travels in a `"\u0001sotto/file/v1:"` message, and the receiver deletes the object once it has the file. On the phone, media sits in `files/media`, encrypted under a key kept in the message's (SQLCipher) row. Details: [docs/attachments-research.md](docs/attachments-research.md).

**Why**: ADR-19 keeps the relay on the Premium tier with 1 GB of free egress a month, which calls need. Small inline media costs a few MB a month of it and needs nothing new; large files never touch the relay, and Tigris has free egress and, without a card on file, stops at its free tier rather than billing. The relay signs every URL, so it decides who may upload how much, and a signed size can't be exceeded.

**Tradeoff**: A third party (Tigris) sees the phones' addresses, file sizes and timing, though never content or keys. Inline media makes a message as large as about 190 KB, so it shows its kind by size. Sotto 0.7.6 and older show the new payloads as raw text, so everyone updates together. Files go phone to phone first when both sides consent (as for direct calls), with the drop as the fallback.

---

## ADR-30: Trust comes only from meeting in person

**Decision**: Every contact shows "Not verified" (until 0.7.11: "Not verified in person") until the user checks their keys face to face: holding the two phones together (NFC) or scanning the QR code on the other phone's "Verify in person" screen. Both carry an `InPersonCode`, `sotto-verify:1:<user id>:<base64url fingerprint>`: the full 256-bit fingerprint over all four public keys, not the share code's 96 bits. `InPersonVerifier` compares it with the fingerprint pinned for the contact (following a rotation their old keys endorsed first) and only then sets `contacts.verifiedInPerson`. Before the first message to a contact who isn't verified in person, the chat warns once (`contacts.inPersonWarned`). The existing `verified` flag keeps its meaning (the relay's keys matched a share code) and still decides direct calls (ADR-17).

- **NFC**: both phones have the screen open. Android listens as a card (host card emulation, `InPersonApduService`, proprietary AID `F0534F54544F01`, category "other", only when unlocked) and, for random stretches of 0.1 to 1 s (0.3 to 0.8 s until 0.7.20; widely spread lengths get two phones out of step sooner, while short ones rarely leave the 100–200 ms a swap needs), reads in reader mode (`InPersonNfcEffect`), so two phones held together soon find each other in opposite roles. One exchange APDU carries the reader's code and the answer carries the card's, so a single tap checks both phones. The card answers only while a verify or Add contact screen is in front. Until 0.7.11 the reading went through foreground dispatch, whose NDEF check broke the link on the owner's phone, so the first tap did nothing; reader mode skips that check, and since it stops the phone answering as a card, the two modes alternate. Once the phones have found each other (a Sotto SELECT answered, either way round), both buzz briefly, so the people holding them keep still for the exchange.
- **Live QR codes** (since 0.7.11): a QR code could be a screenshot passed on, so, as BankID's, it changes every second. Version 2 of the code adds the time (Unix seconds) and an Ed25519 signature by the account's own key over "Sotto-live-code-v1" ‖ user ID ‖ fingerprint ‖ time. A scanned QR code verifies only if it's within 60 s of the scanner's clock (phones' clocks differ a little) and the signature checks against the signing key in the fingerprint that just matched; otherwise the result is "not live". NFC keeps the plain code (version 1): it only reaches a phone held against this one. The QR codes on Verify in person, New chat (Show my QR code) and Settings (Identity, with the share code as text below it) are all live.
- **Adding in person** (since 0.7.11): on New chat, an NFC tap or scanning the other phone's live QR code adds them and marks them verified in person in one step. The share code inside the fingerprint finds them on the relay; their whole fingerprint must match the keys it hands out, or the new contact is removed again. A QR code that isn't live (a picture of one) still adds them, as "Not verified", like a typed or pasted share code.
- **QR**: each phone checks only the code it scanned, so both scan.
- **Kept and cleared**: a rotation endorsed by the old keys keeps the mark; keys replaced by a share code without an endorsement clear it. Existing contacts start unverified.

**Why**: The owner asked that "a new contact even if accepted should be flagged as not trusted until we scan qr or nfc somehow in person". A share code proves the relay didn't swap the keys, but not who sent the code: one pasted into another messenger is only as trustworthy as that messenger. A message request has no check at all (trust on first use). A code read off the phone in front of you can only have come from that phone. The full fingerprint also keeps the check at 128-bit strength against a quantum attacker (Grover), which 96 bits wouldn't.

**Tradeoff**: Nothing proves the phone is physically there: someone can pass on a screenshot of the QR code. The screen says to verify only with the person in front of you. NFC reaches a few centimetres and is the better proof. Phones without NFC use the QR code. The mark is local: the relay never learns who verified whom.

---

## ADR-31: Verifying by phone (in a call), as a weaker level than in person

**Decision**: During a connected Sotto call with a saved contact, either person can tap "Verify by phone" (under the call's details; until 0.7.11 a "123" button in the corner). Once both have, each phone shows seven emoji, each with its name (0.7.10: six digits); the two people read them to each other, and "It matches" marks the contact `contacts.verifiedByCall`. The chat then says "Verified by phone only" (0.7.10: "Verified by call only"), never "Verified in person" (ADR-30), and the one-time warning before the first message no longer shows for them. That warning offers both checks: Verify in person, and Verify by phone (which starts a call). Only meeting in person removes the "only".

- **Protocol** (`CallVerification`, signal `call_verify`, kinds `commit` and `reveal`, end-to-end encrypted and signed like every call signal): each phone sends SHA-256 of a random 32-byte value, and reveals the value only after it has sent its own commitment and received the other's. The code is SHA-256(`Sotto-call-verify-v1` ‖ call ID ‖ caller's fingerprint ‖ callee's fingerprint ‖ caller's value ‖ callee's value), with each fingerprint as that phone has it pinned. Its first 42 bits pick seven emoji, 6 bits each, from a fixed list of 64 (`VerifyEmoji.ALL`, the list Matrix uses for its emoji verification; the order is part of the protocol).
- **Why the fingerprints are in the code**: someone in the middle who passed every message through unchanged would otherwise give both phones the same values and the same code. Each phone sees other keys for the other person then, so the codes differ.
- **Why the commitments**: seven emoji are 42 bits, but someone in the middle could otherwise try key after key offline until both codes matched. Someone in the middle must commit to each side before it learns that side's value, so it can't search for keys or values that make both codes match: it gets one guess, about one in 4.4 trillion (as ZRTP's short authentication string). Emoji instead of digits, at the owner's request: easier to compare and to say, as in Element and Telegram.
- **Kept and cleared** like `verifiedInPerson`: marked only while the fingerprint used is still the pinned one; an endorsed rotation keeps it, keys replaced by a share code without an endorsement clear it.

**Why**: The owner asked for a way to verify online but said a call "is not good enough" to count as verified: "still flag the chat as only verified by phone". A code read aloud catches the relay or anyone else swapping keys, as long as each person knows the other's voice.

**Tradeoff**: It trusts the voice: a convincing live voice fake defeats it, and between people who have never met it proves little. Hence the weaker level. Both phones need 0.7.10: an older Sotto ignores the signal, and the asking phone keeps waiting. The relay forwards `call_verify` like any in-call signal and never pushes for it.

---

## ADR-32: Group chats as pairwise copies, with an admin-signed roster

**Decision**: A group (2 to 32 people) has no key of its own. Each message is stored once on the sender's phone and goes to every other member as its own copy, sealed on that member's ordinary 1:1 Double Ratchet session and signed like any message (Ed25519 + ML-DSA-65, ADR-15). Inside the encryption a copy is a group packet, `"\u0001sotto/group/v1:" + JSON` (`group/GroupPacket.kt`): the group's id, the message's own id, its sender and its one recipient, the epoch of the roster its sender held, its time, and the content a 1:1 message would carry (text, or a media or file payload; ADR-27 to ADR-29). Who is in the group is a **roster** (`group/GroupRoster.kt`): the members (each with the full 256-bit key fingerprint the admin has pinned for them, their name, invited or active, and how the admin verified them), the title, the disappearing-messages timer and the admin, at an epoch that only grows, signed by the admin over a length-prefixed canonical form (domain `Sotto-group-roster-v1`).

- **Only the admin changes the group** (members, title, timer, deletion). A phone takes a roster only with a higher epoch, issued and sent by the admin it holds, or one reached from it by signed handovers (domain `Sotto-group-admin-v1`), each naming the new admin's fingerprint; the last 8 travel with every roster, so a phone that missed some can follow.
- **Invitations** come only from an accepted, unblocked contact who is the roster's admin. An invited phone gets nothing from the group until the user accepts: the acceptance goes to the admin, whose next roster makes them active.
- **What a phone takes**: content only from active members of the newest roster it holds, while it's active itself; the packet's sender and recipient must be the envelope's. Content from an older epoch is still taken from members who are in the newest roster (so a rename or a new member doesn't lose messages already on their way), never from one who left or was removed. A packet with a newer epoch than the phone holds, or from a member whose roster hasn't arrived, is kept (already decrypted, in `group_held`) for up to a week until that roster comes.
- **Every member's phone stores a message under the same id**, `GroupIds.rowId(group, sender, message id)`, so replies and reactions name it the same way everywhere and a copy that comes twice is kept once. One member can't name another's message.
- **The outbox** (`group_outbox`): each copy has its own relay client id (`GroupIds.copyClientId(message id, recipient)`), and is kept sealed until the relay has it. A retry sends the same bytes under the same id, never re-encrypting once the ratchet has moved, and only copies that failed go again. Control packets (rosters, invitations, answers, reactions) are retried in the background (`GroupOutboxWorker`); a message's failed copies wait for the user, who sees per member what happened.
- **Receipts per member**: delivery receipts (the relay's) and read receipts (signed, mutual, as in 1:1 chats) name the relay id of each member's copy, so one member's receipt marks only their copy. A message shows delivered (or read) once every member's copy is.
- **Reactions** are per member (`group_reactions`), sent as unstored packets to every member; replies carry the original's sender and id and a quote.
- **Files** go up once for every member (ADR-35); there are no direct (phone to phone) transfers in groups.
- **No group calls.** The chat has no call button.
- Group packets are handled right after the envelope's signature checked out and it decrypted, before anything one-to-one: no packet ever opens a 1:1 chat or a message request.

**Why**: The owner chose pairwise copies over a shared group key: every copy has the same forward secrecy, post-quantum protection and signatures as a 1:1 message, the relay needs nothing new to carry them, and for up to 32 people the extra copies are cheap. A signed roster with growing epochs makes the admin the only one who can change the group, lets every member check that without asking the relay, and gives removal a clear meaning: once a phone holds the roster without someone, it takes nothing more from them.

**Tradeoff**: A message to 31 people is 31 sends (hence the relay's burst, ADR-34). Removal can't reach a phone that's offline: it learns of it once it's back, and until then keeps sending to the group; the admin sees which members the latest roster reached. A phone holding an older roster can't follow more than 8 handovers in a row. Sotto 0.7.25 and older show group packets as "needs a newer Sotto": everyone updates together.

---

## ADR-33: Members who aren't contacts: the admin vouches for them

**Decision**: A member the user doesn't have as a contact is pinned by the fingerprint the admin put in the roster: the phone fetches their keys from the relay and keeps them only if they match it, as a contact row marked `groupOnly`. Those rows are never listed as contacts, never get a chat, a message request or a direct call; the user's own contacts keep their own pins, and a member whose pin differs from the admin's is shown as "keys don't match", and nothing goes to or comes from them in the group. Each member shows how they're verified: by the user, for their own contacts, otherwise "Verified in person / by phone / added by share code / not verified" by the admin. Adding them in person or accepting a message request from them makes them an ordinary contact. Their envelopes must be verified with exactly the vouched keys; an endorsed key rotation is followed as for contacts (SECURITY.md, "Key Authenticity"), and the admin's next roster vouches for the new keys.

**Why**: The owner chose this ("vouched for by the creator is ok") over limiting groups to people who all know each other, and over trusting the relay's keys on first use. The vouch is as strong as the admin's own check of that member, and the screen says so.

**Tradeoff**: Members trust the admin about people they don't know. The relay learns nothing new from a lookup a group makes, but it does see the lookups.

---

## ADR-34: The relay limits message sends with a token bucket

**Decision**: Since the group chats, each account may send a burst of 160 messages, refilled at 240 a minute (`MESSAGE_BURST`, `MESSAGE_RATE_PER_MINUTE`), instead of 60 in a fixed one-minute window. The bucket's level and when it was last filled are kept in `rate_limits` (migration 013), read under the row's lock. The queue limits per recipient and sender are unchanged.

**Why**: A group message is one send per member, up to 31, so the old window let through about two group messages a minute. The relay can't tell group copies from other sends, and shouldn't: so the limit itself grew, with a burst for a few group messages in a row and a sustained rate that still bounds a flood. A bucket also has no reset at which twice the limit gets through (PR #49).

**Tradeoff**: One account can now queue more in a short time than before; the per-recipient queue caps still bound what lands on any one phone.

---

## ADR-35: A group's file goes up once, for every member

**Decision**: A file (or HD photo copy) sent to a group is encrypted once with a fresh key (ADR-29) and uploaded once; the reservation names every member it's for (`recipient_ids`, up to 31; table `attachment_recipients`, migration 014). The key, the attachment's id and the file's size travel to each member inside their own copy of the message. Only a named recipient gets a download URL; a recipient's delete drops only its own claim, and the object goes with the last one, the sender's delete, or after 30 days. It counts once against the sender's allowance and the store.

**Why**: The owner chose a fresh key for each group file, delivered to the members over their secure channels, rather than one upload per member. One upload instead of 31 keeps a phone's upload and the store's 4 GB (and Tigris's free tier) for what's worth it, and per-recipient claims keep the relay deciding who may fetch what.

**Tradeoff**: The relay learns that these accounts got the same file. It could already guess they belong together from the copies sent at the same moment (ADR-32).

## ADR-36: Unsending is an unstored message naming one's own

**Decision**: A one-to-one unsend's plaintext is `"\u0001sotto/unsend/v1:" + {"to"}` (`UnsendPayload`), sent like a reaction (ADR-28): an ordinary envelope neither phone stores as a message. Only one's own message can be unsent, so `to` is always the relay's id for the sender's local id, the id the receiver stores it under; the receiver drops an unsend naming a message it doesn't have, one that isn't from the envelope's sender, or one outside their chat. In a group it's a packet of kind `unsend` whose target must be the packet's own sender (ADR-32), checked by the codec, sent to every active member through the outbox as a control packet. Both phones keep the row as a note (`MessageState.UNSENT`) and empty everything else in it (`MessageDao.wipeUnsent`): text, media and file keys and their files, reply, reactions, ciphertext; replies quoting it keep the link but lose the words (`UnsendPayload.UNSENT_QUOTE`). The sender's own row is `UNSENDING` until the relay has the unsend (the clock tick); the unsend itself waits in a table of its own (`pending_unsends`, schema 18), written in the same transaction as the claim, so deleting the message, its timer or deleting the chat can't lose it. `ResendWorker` sends it again under one client id (`unsend:<local id>`), however old the message: backing off over about a day, and again after every reconnect and app start. In a group the row is `UNSENT` at once and the outbox sends the control copies with no limit on tries (one waiting for a member's keys goes once they're vouched for); the message's own copies that hadn't gone are dropped, and one already being posted is waited for (up to 30 s), so the unsend can't reach a member first and be dropped as naming nothing. Unsend isn't offered in a group the user has left. The sender deletes its file from the relay's store; a receiver drops its claim. A receipt, send result or finished transfer never changes an unsent row's state or file state again. An unread unsent message leaves the unread count no higher than what's still unread (not one lower: it may never have been counted). A reply quoting it that goes out later carries no quote. A notification listing the message is rebuilt from what's still unread, silently, or cancelled.

**Why**: The owner asked to "unsend messages if i want to". A separate kind of envelope, or a call to the relay to delete a waiting message, would show the relay which messages were taken back and when; this way it sees one more ordinary message. A note in its place (as in iMessage and WhatsApp) says why a message someone may have seen is gone, and keeps the chat's order and replies making sense.

**Tradeoff**: An unsend can't take back what was read, copied, saved or forwarded: the confirmation says so. It can't work against a modified app either: a receiver's phone can keep anything. A message still waiting on the relay is delivered, then emptied in the same fetch, so its notification can show (and sound) for a moment. Sotto 0.7.30 and older show a one-to-one unsend as "needs a newer Sotto" and drop a group one, keeping the message. A deleted account can't be told; the unsend then counts as done. An unsend that crosses a reply or reaction on the way is covered: a reply quoting a message already unsent here stores no quote, and a reaction to it is dropped. A decrypted copy made for opening a file stays in the cache until its hourly sweep. Deleting a group one has left drops any unsend copies still waiting (its members would drop them now anyway).
