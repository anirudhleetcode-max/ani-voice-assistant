# Spotify, WhatsApp, and the things Android will not allow

Every assistant demo glosses over this. Ani does not, so here is exactly what works, what
does not, and why.

## Music: two capabilities, only one of them complete

### Transport control — real

Pause, resume, next, previous and replay genuinely work, on Spotify and on every other
media app.

With notification access granted, `MediaSessionManager.getActiveSessions` hands Ani a
`MediaController` for whatever is playing, and the commands go straight to it. Without
notification access there is still `AudioManager.dispatchMediaKeyEvent` — exactly what a
headset button does, needing no permission at all.

So "Rey next song", "Rey pause chey" and "Rey ee song malli pettu" do what they say, and
Ani confirms them as done.

### Starting a named track — not possible

"Rey Arijit Singh play chey" cannot start playback in Spotify from a third-party app.

Spotify's App Remote SDK can do it, but it is distributed separately from Maven Central,
requires a registered client ID tied to a Spotify developer account, requires the user to
authenticate, and requires Spotify Premium for some operations. That is a reasonable thing
for a commercial integration to take on and a poor fit for a personal build — and shipping
it would mean either bundling a developer's credentials or walking every user through an
OAuth registration.

What Ani does instead is open Spotify's documented `spotify:search:` deep link with the
query filled in, landing on the results page. And it **says that is what it did**:

> "Spotify lo 'Arijit Singh' search chesi icha. Play press cheyyi ra."

Never "play chesthunna". This distinction is enforced in the type system —
`MusicOutcome.SearchOpened` is a different thing from `MusicOutcome.Controlled`, and
`AniResult.Limitation` is a different thing from `AniResult.Success`.

### Adding real playback later

`MusicController.openSearch` is the single place to change. Add the App Remote SDK, keep
the deep link as the fallback for users without Premium or without the SDK connected, and
return `MusicOutcome.Controlled` on success. Nothing above that method needs to change —
the response wording follows the outcome type automatically.

Other providers (YouTube Music, Gaana, JioSaavn) use the same search-and-open path.

## WhatsApp: composing, not sending

Ani opens the right chat with the message typed, using WhatsApp's documented `wa.me` deep
link. You press send.

There is no API to do better. WhatsApp exposes no send endpoint to other apps, and the
ways around that are all things this project refuses to do:

- **Accessibility service automation** — driving WhatsApp's UI to find and tap the send
  button. Technically possible, a gross misuse of an accessibility API, and grounds for
  Play removal.
- **Reading WhatsApp's database** — requires root, breaks its encryption model, and would
  be a straightforward privacy violation.
- **Session or credential extraction** — not happening.

So: one tap. Ani removes all the typing, the contact lookup and the app switching, and the
send button stays yours. The response says so:

> "WhatsApp lo Rahul ki message ready chesa. Nuvvu send press cheyyi."

SMS has the same shape. `SmsManager` *could* send silently, but it needs `SEND_SMS`, which
Play restricts to default SMS apps and which would get this app rejected. Composing into
the messaging app costs one tap and no policy risk.

## System toggles Android closed off

| Thing | When it closed | What Ani does |
| --- | --- | --- |
| Wi-Fi on/off | `setWifiEnabled` became a no-op for third-party apps in Android 10 (API 29) | Opens the Wi-Fi panel as a sheet over the app |
| Bluetooth on/off | `BluetoothAdapter.enable/disable` deprecated and no-op in Android 13 (API 33) | Opens Bluetooth settings |
| Airplane mode | System-only since Android 4.2 | Opens airplane mode settings |
| Screen brightness | Needs `WRITE_SETTINGS`, a restricted permission | Opens display settings |

In every case Ani says which it is:

> "Wi-Fi ni nenu direct ga marchalenu — Android alaa allow cheyyadu. Settings open chesa."

On Android 10+ the Wi-Fi and internet panels open as a bottom sheet, so it is one tap and
you are straight back.

## What Ani does control directly

Because these have real APIs:

- **Torch** — `CameraManager.setTorchMode`
- **Media volume** — `AudioManager`, including the honest failure when Do Not Disturb is
  blocking volume changes
- **Do Not Disturb** — `NotificationManager.setInterruptionFilter`, once the user has
  granted notification-policy access
- **Alarms and timers** — handed to your own clock app via `AlarmClock` intents, so they
  appear in the same list as ones you set by hand and ring with the sound you chose
- **Reminders** — Ani's own `AlarmManager` alarms, re-armed after reboot

## The rule

If Android or another app will not allow something, Ani:

1. detects the limitation rather than failing blindly,
2. does the closest legitimate thing,
3. says what it actually did,
4. and keeps the seam open for a future integration that can do better.

A button that claims to work and does not is worse than no button.
