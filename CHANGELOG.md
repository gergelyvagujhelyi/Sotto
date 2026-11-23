# Changelog

What each version added, newest first. **Unreleased** is on `develop` but not in a release yet.

## Unreleased

Nothing yet.

## 0.7.7 — 2026-11-23

Everyone needs this version: 0.7.6 and older show a voice message, photo or file as a line of raw text.

- **Voice messages** — Hold the microphone (where Send is while the message box is empty) to record, and let go to send. Slide left to cancel, or slide up to lock and keep recording hands-free, then send with the big button or throw it away with the bin. Up to a minute. A voice message plays straight from the chat, one at a time.
- **Photos** — The paperclip opens a menu: "Photo" opens the phone's photo picker, which shows Sotto only the photo you pick. The photo then opens in a preview, where you add a caption (it starts with whatever you'd typed) and send it. Photos are shrunk to 1280 pixels on the long side and turned upright, and their hidden details (location, camera, time) are left out. Tap a photo to see it full screen, and pinch to zoom.
- **HD photos** — The HD switch at the top of the preview also sends the photo at up to 4096 pixels, through the relay's file storage; it remembers your last choice while the chat is open. The chat shows the normal photo at once with an "HD" badge; in the full-screen view, "Download HD" fetches the full-size copy and shows it instead.
- **Files and videos** — "File or video" sends any file up to 100 MB. It's encrypted on your phone and goes to the relay's file storage, which only ever holds the encrypted copy; the key travels in the message itself. Your contact taps the file to download it, and it's deleted from the storage as soon as they have it (or after 30 days). Tap a downloaded file to open it in another app; if no app on the phone can open it, Sotto offers to save a copy.
- **Previews of videos and pictures** — A video or picture sent as a file shows a small preview in the chat before it's downloaded (a frame from the video's first second, with its length), carried inside the encrypted message.
- **Transfers in the background, and they resume** — Files and HD photos go up and down in the background, with a progress notification, so you can leave the chat or the app. If the connection drops, a download carries on where it stopped, and a large upload (over 8 MB, sent in 8 MB parts) only sends the parts that were missing.
- **Sizes padded** — Files, HD copies, voice messages and photos are padded before they're encrypted, so their size gives away little about what they are (at most about 12% more data).
- **Message requests bring text only** — Until you accept someone's message request, they can only send you text: a voice message, photo or file from them isn't received, and the chat says so. On your side, voice messages, photos and files unlock once your contact has accepted your request (written back, or sent their name).
- **Replies, reactions and notifications know about media** — You can reply to a voice message, photo or file, and reply with one. Chat previews and notifications say "🎤 Voice message", "📷 Photo" or the file's name.
- **Messages from newer versions** — A kind of message this version doesn't know yet now shows a note to update Sotto, instead of raw text.
- **Relay: file storage** — With `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY_ID` and `S3_SECRET` in `.env`, the relay signs short-lived upload and download URLs for an S3-compatible bucket; it never carries the files. It keeps each account to 2 GB per 30 days and the bucket under 4 GB, and removes uncollected files after 30 days. `/health` says the largest file it takes (`attachment_max_bytes`, 0 without storage). See `.env.example`.

## 0.7.6 — 2026-11-22

- **Double-tap to heart** — Double-tapping a message reacts to it with ❤️, as on Instagram. Double-tapping it again takes the heart back; if you had reacted with another emoji, the heart replaces it. Holding a message still opens all the reactions.
- **Relay: `/health` no longer names the APK** — It carried `app_download_url`, the release APK's address, once the app was out. No version of the app ever read it, and Sotto doesn't offer APK downloads any more (updates go through SourceDrop). The relay still checks that address itself to know when a release is out; phones hear it from the `Sotto-App-Ready` header as before.

## 0.7.5 — 2026-11-21

- **Updates through SourceDrop** — When a new Sotto is out, the notice in the chat list no longer downloads the APK: its "Open SourceDrop" button, and a tap on the update notification, open SourceDrop, which installs apps straight from their source. Without SourceDrop on the phone, they open its page instead.
- **Relay: the invite page links to Sotto on GitHub** — For people who don't have Sotto yet, step 1 of the invite page was "Download Sotto", straight to the APK. It's now "Get Sotto on GitHub", linking to the repository's main page. `APP_DOWNLOAD_URL` still changes where it points.

## 0.7.4 — 2026-11-21

- **Keys no longer linger in memory** — Your private keys and each conversation's session state (the keys of its next messages) were stored as text, and every use left copies in memory that couldn't be wiped. That included keys forward secrecy says are gone: on a phone, after 10 more messages, 166 old session states were still in memory, and an old chain key up to 35 times. Now keys are stored in their own encrypted files and read straight into memory that is wiped after use. Each used key is wiped as soon as the conversation moves past it, and the cryptography no longer makes copies of keys. Measured after an update: nothing of your signing or identity key, and nothing of any past session key, is left in memory. Of the two post-quantum keys, over 30 copies of key material were left after a single use; now 6 short-lived ones remain, made inside the BouncyCastle library while it computes, and none of the seeds or the parts that can sign. Your keys and conversations move to the new storage by themselves after updating: same keys, and no conversation needs to start over.
- **The call screen fits smaller screens** — On a Galaxy S25 with a larger display or font size, the "Direct" line ran into the call buttons. The caller's picture now shrinks to make room, so there's always space above the buttons. Taller phones look the same as before.
- **Relay: one-use sign-in tokens can be turned on** — 0.7.3 added `AUTH_SINGLE_USE_TOKENS`, but `docker-compose.yml` didn't pass it from `.env` to the relay, so setting it did nothing. It does now (still off unless `.env` says `true`).

## 0.7.3 — 2026-11-21

- **A departure board for sent messages** — A message you send now settles like a split-flap board at a station or airport: each letter flips forward through the board's letters and lands on the real one, the flaps folding over their middle. It's quicker too, at most 0.45 s instead of 0.8 s. Board letters stay within their character's width instead of running into each other.
- **Broken conversations repair themselves every time** — When a contact's messages can't be decrypted, Sotto asks their phone to start a new session. Until now it asked only once per contact while the app was running: if the same contact's session broke again later, their messages stayed unreadable until Sotto was restarted or you tapped re-establish. Now it asks again whenever a session that worked breaks.
- **Sending survives a blip** — If the relay answers with a server error (it's restarting, say) or the connection drops mid-send, Sotto now tries again twice within about 2 seconds before showing "Not delivered". The same goes for fetching messages and acknowledging them. Signing up, invites, key changes, deleting your account, receipts and call setup are never sent twice.
- **A message that couldn't be signed shows as not delivered** — If signing a message with your post-quantum key failed, it was sent without that signature: it showed as sent, but the other phone rejected it. Now it shows "Not delivered · Tap to retry". The same goes for the request to start a new session, and for the sign-in to the relay (which the relay refused without it).
- **Private keys are wiped from memory after every use** — including when setting up a chat session or a call fails halfway, which left them in memory before. A test keeps new code from reading them any other way. The secret a call's encryption keys are made from is wiped too when setting up the call fails (a refused downgrade, or a broken post-quantum key or ciphertext); before, only a call that got going wiped it.
- **Every sign-in to the relay is unique** — Requests made in the same millisecond carried identical sign-in tokens. Each now gets its own timestamp, which the relay's replay protection (below) relies on.
- **Relay: a sign-in token can work only once** — A token copied off the network or out of a log could be used again for 5 minutes. The relay can now accept each token once, with `AUTH_SINGLE_USE_TOKENS=true` in its `.env`. It's off by default, because a merge deploys the relay at once: turn it on only after every phone runs this version. Older versions sometimes sign two requests in the same millisecond, and the relay would refuse the second.
- **Clearer debug logs for unreadable messages** — When no session can decrypt a message, the error now records why each session failed, not just the last.
- **Hear when an update is ready** — When your relay runs a newer Sotto than your app, the chat list says so. Until that version's app is out, the message says an update is on its way and some things may not work. Once it's out, you get one notification, "Sotto 0.7.x is ready", and the chat list shows a **Download update** button. Either one downloads the update in your browser.
  - The app finds out at once: every answer from the relay says which version it runs and whether that version's app is out. When the release is published, the relay tells every phone straight away, over the open connection or with a push.
  - The download always comes from Sotto's official releases. The relay only says *whether* the update is out, so a relay can't send you to some other file. Your phone never contacts GitHub for this.
  - This version is the first that shows it, so it starts helping from the update after this one.
- **Relay: says which version it runs, and when its app is out** — Every HTTP answer has `Sotto-Relay-Version` and `Sotto-App-Ready` headers, and the WebSocket's `auth_ok` has `relay_version` and `app_ready`. The release workflow tells the relay when the APK is published with `POST /v1/app-release`, signed with the workflow's own Ed25519 key (GitHub secret `CI_RELEASE_KEY`; the relay checks it against `CI_RELEASE_PUBLIC_KEY`, whose default is the official key). The relay then tells connected phones (`app_release`) and pushes the others. In case that call doesn't come (a restart, or CI couldn't reach the relay), the relay also looks for the APK itself every 5 minutes (`APP_RELEASE_APK_URL`; empty turns it off). `/health` has `app_download_url` once the APK is out.
- **Reacting leaves the chat as it is** — Holding a message to react closed the keyboard, so the whole chat jumped down, and **+** opened a sheet that did the same. Now the keyboard stays open and nothing moves: the reactions open over the message, and **+** turns them into a grid of every reaction in the same place. Tap outside or press Back to close them.

## 0.7.2 — 2026-11-21

- **Reactions** — Hold a message to react to it with an emoji, as in WhatsApp: 👍 ❤️ 😂 😮 😢 🙏, or **+** for more. The reaction shows under the message's corner; when you both pick the same one it shows once with a 2. Tap a reaction (or hold the message again) to change yours; picking the same emoji again takes it back. You get a notification when someone reacts to your message, but it doesn't count as an unread message. Reactions travel inside the encryption like messages. **Both phones need this version:** an older Sotto shows a reaction as unreadable text.

## 0.7.1 — 2026-11-20

- **Addresses without `https://` are links too** — `444.hu`, `sl.se` or `index.hu/belfold` now open on tap, as in WhatsApp. 0.7.0 only made links of addresses starting with `https://`, `http://` or `www.`. An address counts when it ends in a real domain ending (every country's, and the common ones such as `.com` or `.org`), so file names such as `notes.txt` or `README.md` and email addresses stay plain text.

## 0.7.0 — 2026-11-20

- **Links in messages open on tap** — Web addresses in a chat (starting with `https://`, `http://` or `www.`) are underlined and open in your browser when you tap them. A Sotto invite link opens in Sotto. Addresses without those beginnings, such as `example.com`, stay plain text, so file names and abbreviations aren't mistaken for links.
- **Replies** — Swipe a message to the right to reply to it, as in WhatsApp. A bar above the text box shows what you're replying to (× cancels). The reply shows the start of the original above its text; tap it to jump to the original, which lights up for a moment. The quote travels inside the encrypted message, so it still shows if the original was deleted. Screen readers offer "Reply" as an action on each message. **Both phones need this version:** an older Sotto shows a reply as unreadable text.

## 0.6.9 — 2026-11-20

- **Calling each other at the same moment** — If you and the other person call each other at once, one call now goes through: both phones keep the same one, and the other phone rings for it. Before, each phone answered the other's call with "busy", and neither went through.
- **Calling back straight away** — A call that comes in, or that you start, right after another call ended now goes through. Before, for 2–4 seconds after a call (while "Call ended" showed), a new call got "busy", and calling back from the chat did nothing.

## 0.6.8 — 2026-11-20

- **Relay: messages are delivered promptly again** — Since 0.6.5 the relay made every message's push "collapsible", and Firebase lets a phone receive only 20 of those, then one every 3 minutes. After a busy while, messages stayed at "sent" until the other person opened the app. Message pushes are ordinary again. They still wait for a phone that's out of signal.
- **After a long time offline** — If more than 100 pushes waited for a phone and Firebase dropped them, the app now fetches its messages as soon as Firebase says so, instead of at its next 15-minute check.

## 0.6.7 — 2026-11-20

- **Missed calls are counted** — If you miss several calls from the same person, their notification now says how many, such as "2 missed calls", at the time of the last one. Before, each missed call replaced the previous notification. Opening the chat now clears its missed-call notification too, as it does for messages.
- **An answered call that didn't connect, in the chat list** — If you answer a call and either of you hangs up before it connects, the chat list now says "Incoming call", as the chat does. 0.6.6 said "Voice call", as if you had talked.

## 0.6.6 — 2026-11-20

- **A chat's notification shows all its new messages** — When several messages arrive, the chat's notification now lists all of them since you last opened the chat. Before, each new message replaced the previous one. When the notification hides message text (lock-screen preview setting), it says how many there are, such as "3 new messages".
- **A call you answer is never "missed"** — If you answer a call and it can't connect, the chat now records "Call failed", and no missed-call notification appears. Before, it was logged as a missed call, with a notification, though you had answered it. If either of you hangs up before it connects, it's logged as an incoming call. Answering without internet says "No internet connection".
- **Relay: its health page names its release** — The relay's `/health` reports the release it belongs to (now 0.6.6). It had said 0.6.0 since that release. A test keeps it in step with the app's version.
- **Release notes start with the changelog** — The release build puts the version's section of this changelog at the top of its release notes. It was added by hand before.
- **Build: current GitHub Actions** — The release and check builds use the versions that run on Node 24: setup-java v5, checkout v5, setup-python v6, setup-gradle v5, upload-artifact v6 and action-gh-release v3. GitHub had deprecated setup-java v4 and the Node 20 versions.

## 0.6.5 — 2026-11-20

- **A call without internet says so** — With no internet connection, a call now says "No internet connection" and the chat records "Call failed". Before, the call screen said "Call failed" and closed, and the chat recorded "Unanswered call", though the other phone never rang. The screen that says why a call ended now stays up long enough to read (4 seconds).
- **Remove a contact** — Each contact in New chat's list has a menu (⋮) with Remove contact, and so does a chat's menu. After asking, it removes the contact and deletes the chat with them from this phone. They aren't told, and if they write again, it arrives as a message request. Contacts whose accounts were deleted no longer have to stay in the list.
- **Delete a chat** — A chat's menu (⋮) has Delete chat. After asking, it removes the chat, its messages and its call history from this phone. The other person keeps their copy, and a new message from them starts a new chat. On a message request it does what Reject does.
- **Call buttons take a tap on their label too** — On the call screen, a tap on the word under a button (Accept, Decline, Mute, Louder, Speaker, End) now works like a tap on the button. Before, only the round button reacted, and a tap on "End" did nothing, which once left a call running. A screen reader reads each button once.
- **Lint** — Lint's code warnings are dealt with. Small fixes:
  - the launcher icon folder no longer has a redundant Android-version suffix;
  - the invite link filter has one attribute per tag;
  - the lock-screen attribute is marked as Android 8.1+;
  - Android's own helper functions in the QR code and in Settings.

  Warnings the app answers on purpose now say why where they come up: keys saved synchronously, release builds ARM-only, the battery exemption for the background connection, and two false alarms. What's left are notices that newer versions of the libraries and of Android's target exist: each of those upgrades is a change of its own, to test on its own.
- **Relay: messages after a signal gap** — A phone that comes back from more than a minute without signal (a tunnel, a flight, a dead zone) is now woken for the messages that came meanwhile. Before, the relay's push to wake it lasted only 60 seconds, so those messages waited for the app's 15-minute check or for it to be opened. A message's push now lasts as long as Firebase allows, and they collapse into one, so the phone wakes once and fetches everything. A call's push still lasts a minute, so a missed call never rings late.

## 0.6.4 — 2026-11-20

- **A call notices when the other phone is gone** — If the other phone disappears mid-call (switched off, out of reach, or Sotto stopped on it), Sotto now shows it. Within 10 seconds of nothing arriving from their phone, the call screen says "Can't reach <name>" and the call beeps. The bar above the app and the call notification say the same. If their phone doesn't come back within a minute, the call ends with "<name>'s phone stopped responding". Before, a call whose other phone had gone could go on indefinitely: the connection kept briefly reporting itself back, which cleared "Reconnecting…" each time, so the screen showed nothing wrong. Once their audio comes back, the warning goes away.

## 0.6.3 — 2026-11-20

- **Messages after a restart or an update** — After the phone restarts or Sotto updates, Sotto starts in the background, and the messages that came meanwhile are fetched in the same kind of expedited job as a push's. A message has no push left to wake the app (a push lasts a minute), and before, a slow start could be frozen halfway, so those messages could wait until you opened the app.
- **Less work when a push wakes Sotto** — Each start registered for push twice: once by itself, and again when Firebase confirmed it. And a push restarted the relay connection that the app's start had only just begun. Each of those signs a request with a post-quantum signature, in the seconds Android allows. Now each start registers once, and a push leaves a connection alone while it's being made or is working. A connection that has gone quiet is still replaced.
- **Direct calls on the same Wi-Fi stay direct** — Some phones set up a direct Wi-Fi link to the other phone (TDLS) a few seconds into a direct call on the same network, and when the other phone's Wi-Fi mishandles it, the call falls back to the relay. This happened between the owner's phone and the Note 9. During a direct call on the same Wi-Fi, Sotto now keeps that link off for the other phone, so the audio goes through the router, and turns it back on when the call ends. Sotto asks for the Wi-Fi permission this needs, which Android grants at install.
- **Building** — Every pull request and every push to `develop` runs the app's unit tests and lint and the relay's tests (the Checks workflow, free on a public repository). Lint's three errors are fixed: notifications go through one helper that also copes with the permission being taken back at that moment. Pushing a version tag should start the release build by itself again; since the move to this repository, every release had to be started by hand.

## 0.6.2 — 2026-11-20

- **Opening your own invite link** — Sotto now says "This is your own invite. Send it to the person you're inviting." Before, it opened Add contact with your own share code filled in, as if you could add yourself. Opening the link never used it up: only signing up does. Add contact also recognises your own share code at once, pasted or typed, and says so, instead of asking the relay and failing with a general error.
- **Relay: no IP addresses in its logs** — nginx's access log keeps each request's time, path, status and size, but not the client's address. Account ids and share codes are cut from paths. nginx's error log keeps only emergencies, because nginx adds the client's address to every other error. coturn keeps no log.
- **Relay: answers only its own name** — With `RELAY_HOSTNAME` in `.env`, nginx refuses connections that don't ask for the relay's name before sending its certificate. Scanners that sweep IP addresses get nothing back, not even the name. Paths the relay doesn't serve get a closed connection instead of a 404. Without `RELAY_HOSTNAME`, nginx answers any name, as before.

## 0.6.1 — 2026-11-20

- **Messages arrive while Sotto is closed** — A message that came in while Sotto was closed could stay unshown until you opened the app. A push wakes the app for only a few seconds. A slow start ran past them, and Android froze the app halfway: just after installing or updating, the app's code isn't optimised yet and the phone runs background apps on its slower cores. The push now starts an expedited job, which Android lets finish, so the notification comes as soon as the message is in.

## 0.6.0 — 2026-11-20

- **ChatControll is now Sotto** — A new name, from *sotto voce*: "speaking quietly so others can't overhear". Sotto is a new app (new app ID), so it doesn't update ChatControll: install Sotto, join with an invite (an invite from ChatControll works too), then wipe ChatControll's data in its Settings, which also deletes that account, and uninstall it. Messages, calls and the relay work as before.
- **No full-screen call alerts** — An incoming call no longer takes over the screen. It rings and shows as a notification with **Answer** and **Decline**; Answer on a locked phone still opens the call without unlocking. The app no longer asks for Android's "full-screen notifications" permission, so the reminder on the chat list and the "Show calls on the lock screen" row in Settings are gone.
- **Louder** — A new button on the call screen lifts the other person's voice, normal voices too, with a limiter that keeps it from distorting, and Sotto remembers it for each person. The call screen's buttons are now Mute, Louder and Speaker, with End below them.
- **Level quiet voices** — A new switch in Settings > Calls, off by default. When it's on, a quiet voice is lifted to a normal level by itself, gradually and only while they speak, so background noise isn't pumped up in the pauses; a normal or loud voice plays exactly as it comes.
- **The phone's own voice processing** — Your microphone now goes through the phone's own echo cancellation, noise suppression and volume, tuned for its hardware. Before, WebRTC also adjusted your microphone's volume on top. A new switch in Settings > Calls, **WebRTC voice processing**, uses WebRTC's instead, the same on every phone: try it if people hear an echo or you sound too quiet. Where a phone has no echo canceller or noise suppressor of its own, WebRTC's fills in.
- **Invites are one link** — An invite is now a single link, the relay's address with the one-time code: `https://<relay>/invite#c=K7QX-M2PA-9TRB&s=…`. Tapped, it opens Sotto directly (Android App Links); without Sotto, the relay's invite page offers the download and then a button that opens the invite in the app. **Settings > Invite someone** shows it as a QR code any camera can open, with **Copy link** and **Share**. Setup takes the link and nothing else: there's no code to type separately, and a relay's bare address only works where no invite is needed, like a new relay's first account. The code sits after the `#`, which browsers never send, so it doesn't reach the relay's web server or its logs. Pasting the link or a whole message into setup still works, as do ChatControll's `chatcontroll://` links and the app's own `sotto://` form.
- **Building** — The owner's relay (push, invite links that open the app, certificate pinning) is now a build setting, `sotto.ownerRelayHost` in `local.properties` or CI's `OWNER_RELAY_HOST` secret, instead of an address in the code; the pinning config is generated from it.
- **Relay** — `/health` now says `sotto-relay`; the app also accepts relays that still say `chatcontroll-relay`. The encryption's protocol labels (such as `ChatControll-fingerprint-v1`) keep their old names on purpose: they're part of the wire format and of every share code. `/invite` serves the invite page, with a Content-Security-Policy that allows only its own script and style, and `/.well-known/assetlinks.json` names the app that may open the relay's invite links (`APP_LINK_PACKAGE`, `APP_LINK_CERT_FINGERPRINTS`; `APP_DOWNLOAD_URL` for the page's download).

## 0.5.18 — 2026-11-20

- **High-quality calls** — A new switch in Settings > Calls. When both people in a call have it on, the voice is sent at 48 kbit/s instead of 32, for fuller sound, and the call screen shows "HD" next to Direct or Via relay. If either has it off, or has an older version, the call uses standard quality: the lower choice always wins. It uses about a quarter more data.
- **Earbuds use their better connection** — Earbuds that Android lists twice (LE Audio and classic Bluetooth, as with Galaxy Buds3 Pro) already showed up once. Now a call on them uses LE Audio, which carries clearer sound, and falls back to classic Bluetooth only if that fails. Before, it used whichever connection was already in use, or the first one Android listed. A wired headset is likewise used before plain headphones, for its microphone.

## 0.5.17 — 2026-11-19

- **Invite-only sign-up** — A relay now creates accounts only with a one-time invite from someone already on it, so people who find the app on GitHub can't use your relay. Any member can invite in **Settings > Invite someone**, which shows a QR code and a code like `K7QX-M2PA-9TRB` to show or send. An invite works once, within 7 days, and each member can have 5 open and create 10 a day. Existing accounts aren't affected.
- **Choose the relay at setup** — The app no longer comes with a relay address. Setup asks for one: scan an invite's QR code, paste the invite message (the whole message is fine), open an invite link, or type the address of your own relay. It shows the relay's address and asks before joining. The person who invited you becomes your first contact. Settings > Connection shows which relay your account is on. Apps updated from earlier versions stay on the relay they used.
- **Run your own relay** — The README explains how to run a relay from `server/` and set the app up with it. A new relay's first account needs no invite. Push reaches the app only through the author's relay, so on any other relay setup turns the background connection on instead, and Settings recommends it.
- **Relay: invites** — `POST /v1/invites` creates an invite, sign-up takes an `invite_code`, and `/health` says whether one is needed (`invite_required`). Codes are stored only as hashes. A sign-up retried with the same keys gets its account back instead of using up another invite, and deleting an account deletes its open invites. Coturn also takes `TURN_INTERNAL_IP`, for relays outside Google Cloud.

## 0.5.16 — 2026-11-19

- **Relay access renews on time, whatever its lifetime** — A call through the relay renews its access before it runs out: 10 minutes ahead for the relay's usual hour, as before, and proportionally sooner for shorter lifetimes. That lets the hourly renewal be tested in minutes by shortening the relay's lifetime for a while (`TURN_CREDENTIAL_TTL`, now settable from the relay's compose file).
- **A calmer reconnect sound** — When a call's lost connection comes back, you now hear two clean, airy notes a fifth apart, like glass, instead of the bright arpeggio and chord. It's also a little quieter, and it's still our own sound, synthesized in the app.

## 0.5.15 — 2026-11-19

- **Your chats survive a failed upgrade** — If a future update ever fails to upgrade the phone's stored chats, the app now stops with an error and keeps them, so an update with the fix can open them. Before, it deleted every chat and contact and only showed a notice. The database still starts over only when it really can't be read, after a lost key or with a corrupt file.
- **Messages keep flowing when the network changes** — When the phone switches networks (Wi-Fi to mobile data, or back), the app reconnects to the relay on the new network straight away. Before, it kept the old connection until that timed out, up to a minute when Wi-Fi was still connected but no longer working, and it stayed on mobile data after Wi-Fi came back. On the Note 9 the switch to mobile now takes about a second, and the return to Wi-Fi happens the moment Android starts using Wi-Fi.
- **Relay: nginx changes reach the server** — The deploy job now applies changes to nginx's configuration (after an `nginx -t` check) by recreating nginx. Before, the running nginx kept its old configuration.

## 0.5.14 — 2026-11-19

- **Sent messages fly out of the text box** — After Send, the text lifts out of the text box, turns into its bubble on the way up and lands in the chat. Only then do its letters flip and settle like a departure board. Messages longer than 280 characters rise into place instead.
- **Received messages arrive the same way** — A message that arrives while the chat is open rises into place while its text flips and settles. Opening a chat never animates its history, and of a batch that arrives at once only the newest three animate. "Reduce animations" turns both off.
- **Calls stay on your headset** — A call stays on the Bluetooth device or headset it's on until you pick another route. A device that connects mid-call, such as a car whose Android Auto keeps failing to connect, no longer takes it away. If another Bluetooth device grabs the audio anyway, or your headset drops out for a moment, the call goes back to the headset as soon as it can. A call on the phone's earpiece still moves to a headset that connects, once it has stayed connected for 2.5 seconds; a call you put on the earpiece or speaker yourself stays there.
- **Calmer sent bubbles** — Your own messages no longer glow in the bright brand gradient next to the other person's dark bubbles. They're a muted dark violet in the dark theme and a pale lavender in the light theme, and the read tick stays bright on both. The send button and badges keep the gradient.
- **Each device once in the audio list** — Earbuds that Android lists twice (classic Bluetooth and LE Audio, as with Galaxy Buds3 Pro) show up once, and the call uses whichever link is active.

## 0.5.13 — 2026-11-19

- **Choose where a call plays** — With a Bluetooth headset, a car, a wired or USB headset or a hearing aid connected, the call screen's Speaker button shows where the call plays. Tap it to pick phone, speaker or a device, listed by its own name; the microphone switches with it. A call starts on a connected headset, and a headset that connects mid-call takes over, as in phone calls. With only the phone's own earpiece and speaker, the button stays a plain Speaker toggle.
- **Send animation** — A sent message floats up into place while its text flips through "encrypted" characters and settles left to right, like a station departure board, in under a second. The bubble keeps its size while it does.
- **Reduce animations** — Settings > Display. It turns the send animation off and keeps the backgrounds, the glow and the call screen's rings still. It's also on while Android's "Remove animations" is. The reconnecting spinner keeps turning, since it tells you something.
- **Hang-ups survive a relay update** — Hang-up, decline and busy now keep trying for about 30 seconds while the relay is briefly unreachable, instead of about 1.5 seconds. An update to the relay during a call could otherwise leave the other phone believing the call was still on. Failed attempts log one line instead of a stack trace.

## 0.5.12 — 2026-11-19

- **Fix: incoming calls you couldn't notice** — On a locked phone an incoming call could give no sign at all, and even unlocked it didn't ring or vibrate.
  - **Ringing:** the app now rings and vibrates for a call itself, like the phone app, until you answer, decline or the caller hangs up. Silent mode, vibrate mode and Do Not Disturb are respected.
  - **Staying awake:** a foreground service runs from the first ring, so Android can't pause the app while a call rings.
  - **Silent notification:** the call notification itself is now silent, on a new channel, since the app plays the ringtone.
- **Answer on the lock screen** — Answer opens the call screen over the lock screen, and so does the call's full-screen alert, so you can answer without unlocking. Only the call screen shows there; leaving it on a locked phone hides it again.
- **Ask to show calls on the lock screen** — Android 14 and later only let a call take over a locked screen with your permission. Until you give it, the app asks: once on the chat list, and with a row in Settings > Calls. Without it, a call still rings, as a notification.

## 0.5.11 — 2026-11-19

- **Messages and calls reach a phone in the background faster** — With the background connection off, the app now closes its connection to the relay about 2 seconds after it leaves the screen, unless a call is running. Android would otherwise pause the app while the connection stayed open. The relay now knows at once that the phone is away and wakes it by push straight away, instead of after its fallback wait. The relay side changed too: if the connection a message or call went over dies right away, it pushes at once, and its general fallback for messages is down from 5 to 3 seconds. In a test, a message to a phone whose app had been killed took about 8 seconds to arrive, of which about 5 were that fallback wait.

## 0.5.10 — 2026-11-19

- **Fix: calls to a phone in the background got lost** — A phone that relies on push (one with Google Play and the background connection off) could miss calls while the app sat in the background. Android had paused the app, but its connection to the relay stayed open, so the relay treated the call as delivered over that connection and sent no push. Now, if the phone doesn't answer a call within 3 seconds, the relay closes that unread connection, keeps the call for it and wakes the phone by push. A message the phone hasn't fetched after 5 seconds gets its push too. The fix is on the relay, so it covers every app version.
- **Delivered ticks** — A sent message now shows the grey double tick once the other phone has it, before the coloured one when it's read. The relay already knows when a phone fetches a message; it now tells the sender (`/v1/receipts/delivered`, migration 009). This works even if the other side runs an older version, and doesn't depend on the read receipts setting.
- **A chime when the call is back** — When a lost connection comes back, the reconnecting beeps stop and the call plays a short, bright chime: an ascending major arpeggio that rings out in a chord. It's synthesized in the app, so there's no sound file, and only you hear it, not the other side. For a moment the call screen also says "Reconnected" in green.
- **Notification taps: diagnostics removed** — The temporary log lines added in 0.5.7 to trace notification taps are gone; taps open the right chat.

## 0.5.9 — 2026-11-19

- **Faster recovery after a network switch** — A lost connection is noticed after a second without audio instead of 2.5 seconds. A phone that switches networks renegotiates the call at once, without waiting to reconnect to the relay. Signals that went into a dead connection are sent again as soon as a fresh one is up. Backup paths are checked every 2 seconds, so a call can move to one that already works.
- **You see and hear when the connection is lost** — The phone whose network changes or drops says so at once, without waiting for the call's connection to notice. While the connection is lost, the call plays a soft double beep every 2 seconds. The call screen shows a spinning amber "Reconnecting… No connection right now" banner, which TalkBack announces, and the green call bar turns amber.
- **Direct calls on more networks** — A call that may go direct now gets STUN on the relay itself from the start, so a calling phone knows its public address before it shares candidates. Before, it relied on the TURN server doubling as STUN. Nobody learns anything new: the relay sees that address anyway.
- **Call diagnostics** — The release log shows which kinds of address each phone offered (host, reflexive or relay; IPv4 or IPv6). When a call both phones agreed to make direct runs through the relay, it also shows how the direct attempts fared. The addresses themselves are never logged.

## 0.5.8 — 2026-11-19

- **Direct calls (on by default)** — Calls between contacts now go straight between your phones instead of through the relay: better sound and no relay data, but each of you sees the other's IP addresses. Both phones agree to it inside the encrypted call setup. Turn it off in Settings > Privacy > Direct calls to keep your calls on the relay; a call is also relayed when the other side has it off or runs an older app, and always with people who weren't your contacts before the call. The call screen shows "Direct" or "Via relay".
- **Calls survive a network change** — A call no longer ends the moment its connection drops, say when the phone moves from Wi-Fi to mobile data. It shows "Reconnecting…" while the phones find a new path, and ends as "Connection lost" only after 30 seconds without one, telling the other phone too. Before, one side ended the call at once and the other was never told. After a network switch the app also reconnects to the relay right away, rather than after up to a minute.
- **Calls recover from bigger interruptions** — Sometimes a call's connection can't come back by itself: both phones switched networks, the relay restarted, or a message went into a dead connection. The phones now renegotiate it with fresh candidates (an ICE restart, in a new `call_ice_restart` signal) instead of waiting for the call to fail. Both phones need this version; with older apps a call recovers only as described above.
- **Relayed calls longer than an hour** — The relay's TURN credentials last an hour and weren't renewed during a call, so a relayed call could lose its relay after about an hour. A restart now renews them ten minutes before they expire.
- **No public STUN fallback** — If the app can't fetch the relay's ICE servers, it no longer falls back to Google's STUN server. Relay-only calls now get the relay's TURN server alone, and STUN servers not on the relay itself are ignored.
- **Fix: a repeated call answer ended the call** — An answer that arrived twice (replayed by the relay, say) put a connected call back to "Connecting…" and then failed it.
- **Steadier call setup** — WebRTC steps give up after 10 seconds instead of waiting forever when a call ends half-way, and ending a call just as the other side's audio arrives can no longer hang the app. Signals the relay refuses (rate limit, unknown recipient) aren't retried.

## 0.5.7 — 2026-11-19

- **One moving title** — On Chats and Settings, the title now moves and shrinks into the top bar as the list scrolls (and grows back), instead of a large title scrolling away while a second, small one fades in (`CollapsingTitleTopBar`).
- **Smaller download** — The release APK holds native code for ARM phones only (arm64 and 32-bit ARM), not for x86 emulators: 73 MB down to about 34 MB.
- **Use the app during a call** — Back (or the arrow on the call screen) returns to the app while the call goes on. A green bar above every screen shows who and how long, and a tap returns to the call, as does the ongoing-call notification. On a locked phone Back still sends the app to the background, so the chats never show over the lock screen.
- **Notifications open the chats** — Chat and missed-call notifications are bundled under ChatControll's own summary, which opens the chat list; each notification still opens its chat. Android's automatic bundle opened the app on whatever screen was shown last (Settings, say). The status bar shows the app's own icon instead of a generic one.
- **Share code field above the keyboard** — On New chat, the field ended up under the keyboard.
- **Key rotation moved** — "Rotate identity keys" sat right under "Your name", one mistap away. It now has its own "Keys" section near the bottom, with a clearer warning: every contact has to accept the new keys.
- **Name during setup** — First-time setup asks for your name, optionally: skip it or change it later in Settings. Like a name set in Settings, it's sent end-to-end encrypted to the people you add. The notifications step no longer says lock-screen previews are hidden by default.
- **Relay: STUN on our own server** — `/v1/calls/ice-servers` no longer lists Google's public STUN server. Phones contacted it on every call, even relay-only ones, which told Google their IP address and when they called. The relay's own coturn now answers STUN. This is a server change, so it covers every app version.
- **Relay: `call_ice_restart`** — A new call signal type for renegotiating a call after a network change, in preparation for reconnecting calls; older apps ignore it. Both signal routes now share one list of allowed types.

## 0.5.6 — 2026-11-19

- **Fix: tapping a notification crashed the app** — When the app wasn't already open, tapping a message notification (or a call ringing while it was closed) made it appear and close at once: it navigated before its navigation graph was set ("Navigation graph has not been set"). It now waits for the graph, then opens the chat or the call.
- **New defaults** — Notifications on the lock screen show the sender and the message, read receipts are on and screen security is off by default. The settings use new keys, so existing installs switch to these defaults once; either can still be changed in Settings.

## 0.5.5 — 2026-11-19

- **Push by installation ID** — Google has deprecated FCM registration tokens, so the app now registers its Firebase installation ID (`register()` / `onRegistered`, firebase-messaging 26 via Firebase BoM 35.2.0) and the relay sends wake-up pushes to it (`Message.fid`, firebase-admin 7.5.0 or later). The relay still accepts tokens from older app versions: registering one kind replaces the other, and a target that FCM reports as unregistered is cleared. Phones without Google Play services register nothing, as before. Relay migration 008 adds the `fcm_fid` column.
- **Docs** — The push carries only a wake-up signal; README, ARCHITECTURE and the server README no longer say it carries the sender ID.

## 0.5.4 — 2026-11-19

- **Background connection** — An option under Settings → Connection keeps a connection to the relay open while the app is closed, so messages and calls arrive on phones without Google push (it says so on such phones). It runs as a foreground service with a silent, lowest-priority notification, comes back after a reboot or an app update, and asks Android to stop optimising the app's battery use so Doze doesn't cut the connection off. Off by default.

## 0.5.3 — 2026-11-19

- **Names** — Rename any contact from the chat's ⋮ menu; the name stays on your phone. Set your own name in Settings: it's sent end-to-end encrypted to the people you add and to those whose message request you accept, so they see it instead of the start of your ID. A name you give a contact always wins over the one they sent. The name travels inside an ordinary ratchet-encrypted message, so the relay never sees it. Names are cleaned of control and text-direction characters and kept to 40 characters. When someone adds you, their request now shows up right away, under their name, with "Wants to connect". Database version 10.

## 0.5.2 — 2026-11-19

- **Scan and show QR codes on New chat** — "Scan QR code" opens the camera, reads the other person's share-code QR, adds them and opens the chat. "Show my QR code" shows your own code large, at full screen brightness, for them to scan. QR codes are read on the phone (CameraX and zxing), so scanning works without Google Play services. A typed share code must now be exactly 16 characters, so a wrong-length code gets a clear message instead of a generic error.

- **Calls between two phones** — Both phones in a call are relayed through the same TURN server, so coturn passes the audio from one relay to the other through the machine's own internal address. Its private-range deny list blocked that address, so every relayed call failed. coturn now allows exactly that address, relays only on the interface its public address maps to (not the Docker bridges), and maps the two addresses explicitly.

## 0.5.1 — 2026-11-19

- **New relay server** — The relay server moved to a new machine with a new certificate. The app now pins Let's Encrypt's root keys (ISRG Root X1, X2 and YE) instead of the server's own certificate and intermediate, so certificate renewals and Let's Encrypt's intermediate changes no longer lock the app out.

- **Calls through the TURN server** — coturn never told phones its public address: the image's entrypoint evaluated the start command too early and dropped `--external-ip`, so it handed out the machine's internal address. It now starts through a plain shell, retries the address lookup and logs the address it uses. Its health check no longer leaves a stray process behind every 30 seconds.

- **Deploys apply the whole compose file** — `deploy.sh` used to rebuild and restart only the app container, so changes to the coturn or nginx settings never reached the server.

## 0.5.0 — 2026-11-18

- **Calls in the background** — Incoming calls ring while the app is in the background or the phone is locked. They arrive as a call notification with Answer and Decline and a full-screen intent, and the caller's name stays off the lock screen. A microphone foreground service (`CallService`) keeps a call alive once the app leaves the foreground, with an ongoing notification that has Hang Up.

- **Blocking** — Block someone from a message request or from a chat's ⋮ menu. Their messages are dropped before any processing, and their calls are ignored; they aren't told. A blocked chat keeps its history and shows an Unblock banner, and Settings lists blocked users. Rejecting a message request no longer silently blocks the sender.

- **Message-history retention** — Settings → Message history: keep forever (the default), or 1 year, 90 days or 30 days. Choosing a period asks first, deletes older messages from the device, and keeps enforcing the limit afterwards.

- **Read receipts** — Receipts are mutual and off by default: with them off you neither send receipts nor see when others read yours. They travel over a separate `/v1/receipts` channel and are signed by the reader, so the relay can't forge read state.

- **Share-code retirement and new-account limits (server)** — Rotating keys or deleting an account retires the old share code, so it can't be taken over later. Messages from brand-new accounts are limited, so a handful of fresh accounts can't fill someone's message queue.

- **Redesigned interface** — A custom design system covers every screen: a violet-to-cyan brand gradient, glass surfaces and the Inter typeface, in light and dark. The redesign includes:
    - gradient chat bubbles with grouping, day separators and distinct read ticks;
    - a floating composer that glows while you type;
    - an always-dark call screen;
    - an animated onboarding;
    - a new launcher icon with a themed (monochrome) layer.

    Debug builds include Android Studio previews of every screen with sample data.

- **Bug-hunt rounds** — Several review rounds fixed bugs across messaging, calls and the relay server. The merged pull requests have the details.

- **Privacy & hardening** — WebRTC calls now force TURN relay when available, preventing IP address leakage via STUN direct connections. FrameCryptor key derivation uses per-call salt (call ID) for session binding. WebSocket frame size limits (8 KB pre-auth, 80 KB post-auth) prevent memory exhaustion from oversized frames. Pending call signal buffer capped at 10K recipients with global stale-entry pruning. Base64 validation added to key rotation endpoint for `public_identity_key` and `pqc_encapsulation_key`. Cleartext traffic exemptions moved to debug-only network security config.

## 0.4.1

- **QA security audit fixes** — Fixed 17 bugs across server and client from a full security audit: WebSocket pre-auth DoS (10s auth timeout), malformed Base64 envelope crashing sync loop, key rotation crash recovery promoting unconfirmed keys, FLAG_SECURE not reactive to setting changes, control message infinite retry without PQC signature, production logcat leak, self-send on message endpoint, Dockerfile running as root, metrics cardinality pollution, missing nginx WebSocket headers, path parameter validation, and `getDatabaseKey()` race condition. Added automatic purge of pending messages older than 30 days.

## 0.4.0

- **ML-DSA-65 dual-signing** — All auth tokens, messages, and call signals are now dual-signed with Ed25519 + ML-DSA-65 when PQC keys are available. The server enforces mandatory ML-DSA verification for users with PQC signing keys, preventing downgrade attacks. Key rotation requires proof-of-possession for both Ed25519 and ML-DSA-65.

- **Hybrid PQC call encryption** — Call session keys are derived via a hybrid X25519 + ML-KEM-768 key agreement using SHAKE-256 KDF (SHA-3 family), independent from the messaging Double Ratchet. Call signal payloads are encrypted with AES-256-GCM using per-call ephemeral keys. The `call_ringing` signal provides accurate "Ringing..." status based on peer confirmation.

- **Security hardening** — Fixed 21 security and correctness bugs including: endCallGuard race condition leaking WebRTC resources, late call_answer reviving terminal calls, PQC signing key not persisted on contact upgrade (signature verification gap), SPKI header OID validation, process kill after database wipe, duplicated auth token logic, and unconditional secret zeroization.

## 0.3.10

- **Push notification fix** — FCM push payload now includes sender ID for notification routing. Notifications are now shown by `MessageRepositoryImpl` after decryption, displaying the real sender name and decrypted message body instead of generic "New encrypted message" text. Notifications are suppressed when the conversation is already open. On FCM receipt, WebSocket is reconnected to flush buffered call signals.

## 0.3.9

- **Key change events in chat history** — Local and remote key rotations are now recorded as in-conversation events. When you rotate your keys, each contact's chat shows "You rotated your keys"; when a peer rotates, their chat shows "Peer rotated their keys". Events are rendered with a distinct key icon and centered layout.

- **Account deletion flow** — Wiping local data now sends an `account_deleted` control message to all contacts and deletes the server-side identity before clearing local storage. Peers see a distinct "Peer deleted their account" event (with error-colored icon) and sending is permanently blocked for that conversation. If the server is unreachable during wipe, a retry/skip dialog lets the user choose to retry or delete locally anyway.

- **Navigation ghost-click fix** — During Compose Navigation exit animations, both the outgoing and incoming composable are in the composition tree. Tapping the same screen position could trigger actions on the outgoing screen (e.g., call button firing when settings button was pressed). Fixed by checking `Lifecycle.State.RESUMED` before processing interactive actions.

- **Faster navigation transitions** — Screen transitions reduced to 150ms slide+fade for snappier navigation feel.

- **QA bug fixes (server 0.3.4)** — Fixed race conditions in WebSocket client connection management and call signal processing. Eliminated resource leak from `HttpClient` recreation on each WebSocket reconnect (reverted to singleton). Fixed rate limiter bypass where disconnecting reset per-user quota — rate limit state is now preserved across reconnects and pruned periodically. Improved rate limiter pruning performance under load with high-water mark and time-gated scans. Fixed replay cache eviction, `ContactEntity` equality, share code mismatch, and concurrent `_pendingNewContact` access during calls.

## 0.3.8

- **Call to new contacts** — Calls to newly added contacts now work even before any messages have been exchanged. The recipient fetches the caller's key bundle from the server on demand, verifies the call signal signature, and only then persists the contact locally.

## 0.3.7

- **Call signaling reliability** — Call signals now fall back to FCM push when the recipient's WebSocket is disconnected. The server buffers undelivered signals (30s TTL) and flushes them when the recipient reconnects. The caller sees "Contact unavailable" after a 35s ringing timeout instead of ringing indefinitely. Hangup/reject UI responds instantly (signal sent fire-and-forget in background).

## 0.3.5

- **Session reset on key rotation** — When a peer rotates their identity keys, the recipient detects decryption failure and sends a `session_reset` control message through the existing message pipeline (guaranteed delivery). The sender's UI blocks further messages until they manually re-establish the session with the peer's new keys. Contact key material is updated automatically from the server.

## 0.3.4

- **PQC negotiation & downgrade protection** — Either party can initiate ML-KEM-768 encapsulation (symmetric negotiation). PQC downgrade from hybrid to classical-only is rejected. Base64 input validation on all externally-received key material. Call signal reliability fix ensures reject/hangup reaches the peer.

## 0.3.3

- **Key rotation** — `rotateIdentityKeys()` regenerates Ed25519 + X25519 + ML-KEM-768 keys with crash-safe staged promotion. Server validates proof-of-possession before accepting new keys.

## 0.3.0–0.3.1

- **Security hardening** — Protected metrics endpoint, generic auth errors, WebSocket call signal validation and rate limiting, debug logging gated behind `BuildConfig.DEBUG`, certificate pinning configuration, call signal Ed25519 signatures, per-challenge TURN nonce rotation (RFC 5389), ICE candidate bounds checking.

## Foundations

The core that the versions above build on.

- **Production ML-KEM-768** — `BouncyCastlePqcProvider` uses Bouncy Castle 1.79+ for real NIST FIPS 203 post-quantum key encapsulation. The hybrid X25519 + ML-KEM key establishment is fully functional.

- **Double Ratchet protocol** — `RatchetSessionManager` implements Signal-style per-message forward secrecy with DH ratchet steps and symmetric chain ratchets. Session state is persisted to EncryptedSharedPreferences and survives app restarts. Compromising current state does not reveal past messages.

- **Python relay server** — Full FastAPI backend in `server/` with SQLite/PostgreSQL support, WebSocket real-time delivery, FCM push forwarding, rate limiting, and comprehensive test suite. `KtorApiService` + `WebSocketClient` connect the Android app to it.

- **Encrypted voice calls** — WebRTC-based 1:1 voice calls with end-to-end encrypted signaling and frame-level media encryption via WebRTC FrameCryptor (AES-GCM + HKDF). Ephemeral HMAC-based TURN credentials (coturn-compatible) are generated per session. Call signals are relayed via WebSocket with FCM push fallback and server-side buffering for offline recipients (v0.3.7). Call events (missed, answered with duration) appear inline in chat history.

- **Ed25519 authentication** — All API requests are authenticated with Ed25519 signed tokens. Message envelopes are signed by the sender and verified by the recipient.
