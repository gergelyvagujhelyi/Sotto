# Translations

Sotto's words live in Android string resources, in English (`res/values`) and Hungarian
(`res/values-hu`). The phone's language picks one; on Android 13+ each app can also have its own
language (Settings > Apps > Sotto > Language, or Sotto's own Settings > Language).

## Where the strings are

- One file per area: `strings_<area>.xml` (common, chat, settings, onboarding, call, contacts,
  notification…), the same name in `values` and `values-hu`. Keys start with the area:
  `chat_send_failed`, `settings_language`.
- `strings_common.xml`: buttons and words many screens share (`common_cancel`, `common_ok`…).
- Lint's MissingTranslation check runs in CI, so a string without its Hungarian copy fails the
  build. Text that is never translated (Sotto, protocol names) is `translatable="false"`.
- Counts are `<plurals>`. Hungarian has `one` and `other`; a noun after a number stays singular
  ("3 üzenet").
- Code without a Context (view models, pure functions) returns a `UiText`
  (`ui/text/UiText.kt`); the screen turns it into words with `asString()`, a service with
  `resolve(context)`. Tests compare `UiText` values, not sentences.

## Hungarian style

- Informal, like most messaging apps: *te*, not *Ön*. "Koppints", "Ellenőrizd", "Letiltottad".
- Buttons are nouns of action or short imperatives, as Android does: "Mentés", "Törlés",
  "Mégse", "Újra".
- Sentence case. Hungarian quotation marks „…” when a string quotes something.
- Dates and times in Hungarian order: "nov. 3., hétfő", "2025. nov. 3.", 24-hour clock.

## Glossary

| English | Hungarian |
|---|---|
| message | üzenet |
| chat, conversation | beszélgetés |
| contact | ismerős |
| add contact | ismerős hozzáadása |
| message request | üzenetkérés |
| accept / decline | elfogadás / elutasítás |
| block / unblock | letiltás / letiltás feloldása |
| call, voice call | hívás, hanghívás |
| missed call | nem fogadott hívás |
| incoming / outgoing call | bejövő / kimenő hívás |
| ringing | kicseng |
| end call, hang up | hívás vége, bontás |
| mute | némítás |
| speaker | kihangosítás |
| direct (call) / relayed | közvetlen / szerveren át |
| relay | szerver (the relay server Sotto runs on) |
| invite, invite link | meghívó, meghívólink |
| share code | megosztási kód |
| QR code | QR-kód |
| verify in person | ellenőrzés személyesen |
| verified in person | személyesen ellenőrizve |
| verified by call | hívással ellenőrizve |
| not verified | nincs ellenőrizve |
| safety number, fingerprint | biztonsági kód, ujjlenyomat |
| keys | kulcsok |
| end-to-end encrypted | végponttól végpontig titkosított |
| voice message | hangüzenet |
| photo, video, file | fénykép, videó, fájl |
| attachment | csatolmány |
| reply, react, reaction | válasz, reagálás, reakció |
| sent, delivered, read | elküldve, kézbesítve, olvasva |
| notification | értesítés |
| lock screen | lezárási képernyő |
| update | frissítés |
| account, delete account | fiók, fiók törlése |
| display name | megjelenített név |
| tap / hold | koppints / tartsd lenyomva |
| Settings | Beállítások |
| Today / Yesterday | Ma / Tegnap |
