# Troubleshooting

Start at **Settings → Diagnostics**. Every row is read live from the platform, and it will
usually name the problem outright.

## Ani does not respond to "Rey"

| Check | Fix |
| --- | --- |
| Is wake listening on? | Settings → Listen for the wake phrase. It is **off by default** |
| Is the listening notification showing? | If not, the service is not running — check microphone permission |
| Microphone in Diagnostics | If "Blocked", grant it in Privacy Center |
| Another app holding the mic | A call, a voice recorder or another assistant will win. Ani backs off for ten seconds and retries |
| Battery optimisation | Some OEM builds (Xiaomi, Oppo, Vivo, Samsung) kill foreground services aggressively. Add Ani to the "don't optimise" list |

Tap-to-talk always works even when the wake loop does not, and it costs no battery. If
tap-to-talk works and "Rey" does not, it is the service, not the recogniser.

## Ani mishears Telugu

This is the honest weak point, and it is upstream of everything Ani controls.

Android's speech recogniser does not handle Telugu-English code switching well. In `en-IN`
it transcribes Telugu words phonetically into Latin — which is exactly what Ani's engine
expects, and is why `en-IN` is the default for mixed speech. In `te-IN` it produces good
Telugu script but mangles the English words in the middle of a sentence.

Things that help:

- **Set Language to Mixed or leave it on Auto** (Settings → Voice and language). Forcing
  Telugu makes English app names worse.
- **Say the wake phrase, pause, then the command.** The recogniser's endpointing is tuned
  for English sentence rhythm.
- **Check what it heard.** The transcript on the home screen shows the raw transcription.
  If the words are right and the action is wrong, that is Ani's bug — the lexicon needs a
  spelling. If the words are wrong, it is the recogniser.

If the transcription is right but the intent is wrong, add the phrasing to
`Lexicon.buildTable()` and a case to `IntentClassificationTest`. The phonetic folding means
one added spelling usually covers several variants.

## Ani speaks Telugu with an English accent

Diagnostics → Telugu voice will say "Needs download".

**Settings → System → Languages & input → Text-to-speech output → Install voice data →
Telugu**

Some OEM builds ship a text-to-speech engine with no Telugu voice at all. Installing
Google Speech Services from Play and selecting it as the preferred engine fixes it.

Ani deliberately does not fake Telugu through an English voice beyond falling back to the
default — "Amma ki call chesthunna" read with English phonetics is not Telugu with an
accent, it is noise.

## "Em messages vachayi" says it has no access

Notification access is a Settings toggle, not a permission dialog. See
[NOTIFICATION_ACCESS.md](NOTIFICATION_ACCESS.md).

The most common cause is the second switch: the system toggle is on, but no apps are
ticked in Settings → Notification access inside Ani. Both are needed.

## "Em messages vachayi" says nothing is there when there is

- Notifications are only captured **while Ani is running and bound**. Anything that
  arrived before you granted access is not there.
- The buffer is 24 hours by default (Privacy → Keep recent notifications).
- Once read aloud, notifications are marked read and are not repeated.
- Ongoing notifications, media controls and group summaries are filtered out by design.

## Calls open the dialler instead of dialling

`CALL_PHONE` is not granted. That is a supported mode, not a bug — Ani fills in the number
and you press call. Grant it in Privacy Center to have Ani dial directly.

## Ani calls the wrong person

- **Add an alias.** "Rey, Amma ante Lakshmi", or Memory → add. Aliases beat the contact
  list.
- **Star the right contact.** With two matches, a starred contact wins outright.
- With more than one unstarred match Ani asks rather than guessing. If it is *not* asking,
  the other contact is probably spelled differently enough not to match at all.

## Reminders arrive late

Diagnostics → Exact alarms. If it says "Inexact", grant exact alarms in Privacy Center.
Android 12+ can refuse them, and Ani tells you which kind it actually got when it sets the
reminder rather than promising precision it does not have.

## Reminders vanish after a reboot

They should not — `BootReceiver` re-arms them. If they do:

- Check the app was not force-stopped. A force-stopped app receives no broadcasts at all,
  including `BOOT_COMPLETED`, until it is opened once.
- Some OEM builds require the app to be in an "auto-start" allow-list. It is usually in
  the battery or security settings.

## "Spotify lo search chesa" instead of playing

Working as intended. A third-party app cannot start a named track in Spotify —
[SPOTIFY_INTEGRATION.md](SPOTIFY_INTEGRATION.md) explains exactly why, and what would have
to change.

Transport controls — pause, next, previous — do work. If they do not, grant notification
access; without it Ani falls back to media key events, which reach whichever app the
system currently considers the media owner and can be the wrong one.

## Wi-Fi / Bluetooth / brightness just opens Settings

Also working as intended. Those were closed to third-party apps in Android 10, 13 and 6
respectively. Ani opens the right screen — a bottom sheet over the app on Android 10+ —
and says that is what it did.

## The app is using a lot of battery

Turn off wake-word listening. It runs the platform speech recogniser continuously, which
is the single largest cost in the app by a wide margin. Tap-to-talk costs nothing while
idle.

A proper low-power hotword model would fix this. Android's `AlwaysOnHotwordDetector` is
the API for it, but it is available only to the system voice interaction service, on
hardware with a supported DSP, for a phrase the platform will enrol — not to a
third-party app.

## Open questions get "Internet ledu ra"

- Check Settings → Integrations. If it is set to "Stay offline", that is the setting
  talking.
- Diagnostics → AI backend. "Not configured" means the app was built without
  `-Pani.backendUrl`.
- If it is configured, check the backend is reachable from the phone (`/health` in a
  browser on the same network) and that the URL is the LAN address, not `localhost`.

Every phone command works regardless.

## The build fails

`core-nlu` builds with no Android SDK at all — start there to confirm the checkout:

```bash
cd core-nlu && ./gradlew test
```

If that passes and `:app` does not, it is the Android toolchain: check `local.properties`
points at a real SDK with platform 35 installed.
