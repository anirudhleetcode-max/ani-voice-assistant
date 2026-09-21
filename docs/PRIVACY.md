# Privacy

Ani has access to a microphone, contacts, notifications and the phone. This page says
exactly what it does with them.

## The short version

- **No audio is ever written to disk.** There is no field for a recording anywhere in the
  storage layer and no code that would write one.
- **Nothing leaves the device except the sentences you ask open questions with.** Contacts,
  notifications, message bodies, location and the installed app list are never sent
  anywhere.
- **Nothing sensitive is logged.** Not a contact name, not a notification, not a message.
  The logger takes structured fields rather than interpolated strings specifically so that
  it cannot happen by accident.
- **Everything stored is visible and deletable** from Settings → Privacy Center.

## What is stored, and where

| Data | Where | Default retention | Turn it off |
| --- | --- | --- | --- |
| Conversation transcripts | `conversation_history.json`, app-private | 30 days | Privacy → Keep conversation history |
| Recent notifications | `notifications.json`, app-private | 24 hours | Privacy → Keep recent notifications |
| Remembered facts and aliases | `memory.json`, app-private | Until deleted | Memory screen |
| Custom commands | `custom_commands.json`, app-private | Until deleted | Commands screen |
| Reminders | `reminders.json`, app-private | Until fired | Cancel individually |
| Settings | DataStore preferences | Until reset | Privacy → reset |
| Integration tokens | `EncryptedSharedPreferences` | Until deleted | Privacy → delete everything |

All of it is in the app's private data directory, unreadable by other apps on a
non-rooted device. Retention is enforced on every cold start, not on read — so history set
to seven days is pruned the next time any part of Ani runs, even if you never open it.

Cloud backup and device-to-device transfer are both disabled for every one of these files
(`res/xml/data_extraction_rules.xml`). You did not consent to this data travelling to a
new phone, so it does not.

## The microphone

The wake-word loop and command capture both use the platform speech recogniser, which
streams audio to the recognition service and returns text. Ani receives text, uses it, and
stores text.

While the listening service runs there is a **permanent notification** saying so, and
Android shows its own microphone indicator. Both are mandated by the platform for exactly
this reason, and Ani leans into them — the notification names your wake phrase and offers
a one-tap stop.

Wake-word listening is off by default. Tap-to-talk opens the microphone only while you
hold the conversation.

## Notifications

Covered in full in [NOTIFICATION_ACCESS.md](NOTIFICATION_ACCESS.md). The essentials:

- Only apps you ticked are captured at all, checked per notification.
- Message text is discarded **at capture** unless you chose "Who, and what they said" —
  turning previews off means the text never reaches disk, not merely that it is not read
  aloud.
- Content is downgraded to counts on the lock screen (on by default) and, optionally, when
  no headphones are connected.

## What goes to the AI backend

Only when you ask something that is not a phone command:

- Your sentence.
- The detected language and your persona setting.
- The last few turns of transcript — **only if** conversation history is enabled.
- An opaque random install id, for rate limiting. Not an account, not tied to you,
  regenerable.

The backend stores nothing. No transcripts, no user records, no logs of what anyone said —
only the shape of failures.

Choose **Settings → Integrations → Stay offline** and nothing reaches the network at all.
Every phone command keeps working.

## Logging

`AniLog` takes structured fields, not interpolated strings:

```kotlin
AniLog.d(TAG, "resolved contact", "matches" to matches.size)   // fine
AniLog.d(TAG, "calling ${contact.name}")                        // never
```

`AniLog.redact` turns "Rahul" into "R***(5)" for the rare case where a value has to appear
at all — enough to debug a parsing problem, not enough to identify anyone from a bug
report. Debug and info logging is compiled out of release builds entirely, and exceptions
are logged by type rather than with a stack trace, because platform exception messages can
carry user data.

## Encryption

`SecureStore` uses `EncryptedSharedPreferences` with the master key in the Android
Keystore, for integration tokens and the install id.

If keystore initialisation fails — it does on a small number of OEM builds, and after a
keystore reset — the store **refuses to persist** rather than falling back to plaintext.
Losing a token is an inconvenience; writing it unencrypted after promising otherwise is a
breach. Diagnostics reports when this happens.

The bulk data files are not separately encrypted: they live in app-private storage, which
is protected by full-disk encryption on every device Ani supports (API 26+), and adding a
second layer with a key stored on the same device would be theatre.

## Deleting everything

**Settings → Privacy Center → Delete all Ani data.** Removes every transcript, remembered
fact, saved command, notification record and stored token in one action.

Uninstalling removes all of it too — there is no server-side copy, because there is no
server-side anything.

## What Ani will never do

These are design constraints, not policy statements:

- Record audio without the platform's indicator showing.
- Read notifications from an app you did not tick.
- Send a message or place a call without either your explicit confirmation or your
  explicit setting saying not to ask.
- Upload notification contents, contacts or location.
- Use an accessibility service to drive another app's interface.
- Read another app's private storage.
- Learn from your behaviour without being told to. Everything in Memory got there because
  you said so, which is what makes that screen an honest list.
