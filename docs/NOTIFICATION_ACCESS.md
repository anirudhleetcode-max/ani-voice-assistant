# Notification access

This is the permission behind "Rey em messages vachayi?", and it is the one people get
stuck on — so it gets its own page.

## It is not a permission dialog

There is no runtime permission for reading notifications. `NotificationListenerService` is
bound by the system only after the user enables your app under:

**Settings → Notifications → Device & app notifications → Ani**

(The path varies: some builds call it "Notification access", some bury it under Special
app access.) An app cannot request this with a dialog, cannot pre-approve it, and gets no
callback when it is granted or revoked.

Ani takes you to the right screen with
`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` and re-checks on every resume. Until you
grant it, asking about messages gets:

> "Notification access ivvaledu. Settings lo enable chesthe messages chadivi cheptha."

Not a guess, not a silent empty list.

## Two separate switches

People conflate these and then wonder why nothing appears:

1. **The system toggle** — lets Ani see notifications at all.
2. **The per-app allow-list**, in Settings → Notification access inside Ani — decides
   which apps it keeps anything from.

Both are off by default. Granting the system toggle alone gets you nothing until you tick
at least one app.

## What Ani keeps, and what it throws away

For each notification from an allowed app:

| Field | Kept? |
| --- | --- |
| App name | Yes |
| Sender (notification title) | Only at "Who messaged" or above |
| Message text | **Only** at "Who, and what they said" |
| Timestamp | Yes |
| Icons, actions, the notification object | No |

The privacy level is applied **at capture**, in `AniNotificationListenerService`, not at
read time. Turning previews off does not merely stop Ani reading them aloud — the text
never reaches disk in the first place.

Dropped entirely, because they would make a summary useless: ongoing notifications, media
controls, progress bars, group summaries (their children are already captured), and
anything with no title and no text.

## What is never done with it

- Nothing is logged. Not the sender, not the text, not the app. Counts only.
- Nothing is sent anywhere. Notification content never reaches the AI backend or any
  other network destination.
- Nothing is kept longer than the retention window (24 hours by default), enforced on
  every cold start rather than on read.

## Speaking them out loud

Two gates downgrade a summary to counts only, regardless of the privacy level:

- **Lock screen.** On by default. A phone on a table should not read "Rahul: are you
  coming tonight" to the room.
- **Headphones required.** Off by default. Turn it on and Ani never speaks message content
  through the speaker.

Neither can *upgrade* the level. A user who chose "sender only" gets sender only even with
headphones on and the phone unlocked.

## Missed calls

Ani does not request `READ_CALL_LOG` — it is a restricted permission group and Play limits
it to default phone and assistant apps. "Missed calls unnaya?" is answered from the
dialler's own missed-call notification, which the notification listener already sees. That
means it works only if the dialler is on your allow-list and only for calls missed within
the retention window.

## If it stops working

The system sometimes unbinds a listener without the user revoking anything. Ani calls
`requestRebind` on disconnect, which usually recovers it within seconds. Diagnostics shows
the toggle state and the bind state separately, so you can tell "not granted" from
"granted, reconnecting".

If it stays disconnected: toggle Ani off and on in the notification access screen. That
forces a rebind. A reboot also does it.
