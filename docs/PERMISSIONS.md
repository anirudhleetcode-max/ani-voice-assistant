# Permissions

Every permission Ani declares, why it is there, and what stops working without it.
Nothing is declared "just in case", and every one maps to a feature the user can switch
off in the Privacy Center.

## The distinction that matters

Android grants capabilities two completely different ways, and conflating them is how
assistants end up with a "read my notifications" button that silently does nothing.

**Runtime permissions** are a dialog. The app asks, the user taps, the app finds out
immediately.

**Special access** is a Settings screen the app cannot shortcut, cannot pre-approve, and
cannot be notified about. There is no dialog and no callback — the only way to know is to
check again afterwards.

Ani encodes this in the type system (`GrantMechanism.Runtime` vs
`GrantMechanism.SpecialAccess`), so no code path can treat one as the other.

## Runtime permissions

| Permission | Used for | Without it |
| --- | --- | --- |
| `RECORD_AUDIO` | Hearing the wake phrase and every command | Nothing works |
| `READ_CONTACTS` | Resolving "Amma" to a number | Names cannot be resolved |
| `CALL_PHONE` | Placing the call directly | Ani opens the dialler with the number filled in; you press call |
| `POST_NOTIFICATIONS` (API 33+) | The listening notification and reminder delivery | Reminders cannot reach you; the listening service cannot run |
| `ACCESS_COARSE_LOCATION` | Only "location share chey" | Location sharing is unavailable; nothing else changes |

Each is requested at the point of use, not at launch.

## Special access — Settings screens, not dialogs

| Access | Screen | Without it |
| --- | --- | --- |
| Notification listener | Settings → Notifications → Device & app notifications | Ani cannot read notifications, and says so rather than guessing |
| Do Not Disturb policy | Settings → Do Not Disturb access | Ani opens the DND settings screen instead of toggling |
| Exact alarms (API 31+) | Settings → Alarms & reminders | Reminders may be delivered a few minutes late |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | Settings → Apps → Display over other apps, or Keep Ani Ready | A command spoken while Ani is in the background cannot open the dialler, Spotify or any app directly. Ani offers it as a notification and says so — it never claims it launched |

Ani takes you to the right screen and re-checks on every resume, because the user can
revoke any of these in the background.

## Install-time permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talking to the AI backend. Every phone command is offline |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Required from Android 14 for any service that records audio |
| `RECEIVE_BOOT_COMPLETED` | Re-arming reminders after a restart — AlarmManager forgets them |
| `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM` | Reminders at the time you asked for |
| `SET_ALARM` | Handing alarms to your clock app |
| `ACCESS_NOTIFICATION_POLICY` | Declares intent to change DND; the user still has to grant policy access |
| `VIBRATE` | Haptic feedback |
| `USE_FULL_SCREEN_INTENT` | Surfacing a blocked action over a locked screen. Android 14 grants it only to apps it classes as calling or alarm apps; Ani checks `canUseFullScreenIntent()` and falls back to a heads-up notification |
| `SYSTEM_ALERT_WINDOW` | **Not for drawing anything.** It is the platform's documented exemption from the Android 10+ background activity start block, which is what lets a hands-free command actually open an app. Ani draws no overlay windows, checks the grant live before every launch, and works without it |

## Not requested, deliberately

| Permission | Why not |
| --- | --- |
| `QUERY_ALL_PACKAGES` | Play restricts it to apps whose core function needs a full inventory. Ani's does not. The `<queries>` block in the manifest is enough to launch apps and resolve the ones it integrates with |
| `SEND_SMS` | Would let Ani send silently, but Play restricts it to default SMS apps and it would get this app rejected. Composing into the messaging app costs one tap and no policy risk |
| `READ_SMS`, `READ_CALL_LOG` | Restricted permission groups. Missed calls and message previews come from the notification listener instead, which the user grants explicitly |
| `WRITE_SETTINGS` | Would allow changing screen brightness. Play treats it as restricted and users reasonably refuse. Ani opens the display settings screen |
| `BIND_ACCESSIBILITY_SERVICE` | An accessibility service could automate other apps' UIs — driving WhatsApp's send button, for example. That is exactly the technique this project refuses to use |
| `PACKAGE_USAGE_STATS` | Not needed for anything Ani does |
| Camera, storage, calendar, SMS | Not needed for anything Ani does |

## Permanent denial

Android's `shouldShowRequestPermissionRationale` returns `false` both *before* the first
request and *after* a permanent denial — opposite situations with the same answer. Ani
keeps its own request history (`PermissionRequestHistory`) to tell them apart, which is
what decides whether the button says "Allow" or "Open Settings". Without that, a
permanently denied permission shows a button that does nothing when tapped.

## Where to see all of this in the app

**Settings → Privacy Center.** Every capability, its live state, and a button that takes
you to whichever screen can change it. The state is read from the platform on every
resume, never from a stored flag, so it cannot drift out of date.
