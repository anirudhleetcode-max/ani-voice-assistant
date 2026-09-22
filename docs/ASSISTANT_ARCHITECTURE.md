# Becoming a real Android assistant

The audit behind the assistant work, and the order the rest of it has to happen in.

## The finding

Ani's manifest declares an `android.intent.action.ASSIST` intent filter on `MainActivity`
and nothing else. There is no `VoiceInteractionService`, no `VoiceInteractionSession`, and
no code that reads the assistant role.

Those are two unrelated mechanisms that both look like "being the assistant":

| | Activity with `ACTION_ASSIST` | Selected `VoiceInteractionService` |
| --- | --- | --- |
| Appears in the assist picker | yes | yes |
| Assist gesture / Bluetooth button reaches it | yes | yes |
| Session can show over the keyguard | **no** | yes, with `supportsLaunchVoiceAssistFromKeyguard` |
| `AlwaysOnHotwordDetector` reachable | **no** | yes, where hardware supports it |
| Background start latitude | ordinary app | assistant-level |

**Ani has the first.** So a user who sets Ani as their assist app gets the gesture and
concludes it worked — and then the lock-screen behaviour they actually wanted silently
does not happen, with nothing in the UI to explain the difference.

`RoleManager.isRoleHeld(ROLE_ASSISTANT)` returns **true** in that state on many builds,
which is why it cannot be the only thing consulted.

## What is read now

`AssistantRoleManager` reads three sources and reports where they disagree:

| Source | Answers |
| --- | --- |
| `RoleManager.isRoleAvailable` / `isRoleHeld` (API 29+) | the documented question, often answered for the legacy assist app |
| `Settings.Secure` `voice_interaction_service` | **the component that actually gets a session, keyguard access and hotword** |
| `Settings.Secure` `assistant` | the assist app, which may be an ordinary activity |

`AssistantRolePolicy` turns those into one of five states, with no Android types involved
so the awkward combinations are unit tests rather than field reports:

```
HELD                 our VoiceInteractionService is the selected one
LEGACY_ASSIST_ONLY   role held / assist app is us, but no VIS of ours is selected
AVAILABLE_NOT_HELD   the role exists, someone else has it
NOT_SUPPORTED        no assist framework on this build
UNKNOWN              could not read — never reported as either
```

Only `HELD` sets `grantsSystemAssistant`. That one property is what any future
lock-screen or hotword code must gate on, and a test asserts no other state claims it.

Both `Settings.Secure` keys are `@hide` constants referenced by their stable string
literals. They are ordinary reads of a public content provider — no restricted API — and
a rename surfaces as `null`, which reports `UNKNOWN` rather than a confident wrong answer.

## What a `VoiceInteractionService` will require

Not yet built. The constraints found while auditing, so the next step starts informed:

- **`android:recognitionService` is mandatory.** `VoiceInteractionServiceInfo` refuses to
  parse a `<voice-interaction-service>` without it, and an unparseable service is not
  selectable as the assistant at all. It must name a `RecognitionService` in the same
  package. Ani does not currently ship one, so this is real work and not a manifest line.
- **`android:sessionService`** must name a `VoiceInteractionSessionService`.
- The service needs `android:permission="android.permission.BIND_VOICE_INTERACTION"`.
- `supportsLaunchVoiceAssistFromKeyguard="true"` is what permits the keyguard session;
  it grants nothing unless the service is also the selected one.
- Declaring all of this changes **nothing** until the user selects Ani in
  Settings → Default digital assistant app. The code must keep working when they do not.

## Implementation order

Statuses are as of the current commit, and only `VERIFIED` would mean tested on the
Realme NARZO 90 5G. Nothing here is `VERIFIED`.

| Step | Work | Status |
| --- | --- | --- |
| 1 | Preserve diagnostics, A/B, Mic Test, TurnTimeline, MicArbiter, tests, CI | **IMPLEMENTED** — untouched, all suites green |
| 2 | ROLE_ASSISTANT / VoiceInteractionService investigation | **IMPLEMENTED** — this document + `AssistantRolePolicy` + live Diagnostics and Keep Ani Ready rows |
| 3 | Ship `VoiceInteractionService`, session service, session, recognition service; integrate with `MicArbiter` | **NOT STARTED** |
| 4 | "Rey" through the assistant lifecycle | **NOT STARTED** — needs step 3 |
| 5 | Screen-on / background / screen-off / lock-screen verification | **NOT TESTED** — needs a device |
| 6 | Recognition latency from A/B measurements | **BLOCKED** — deliberately. No recognition change without device measurements |
| 7 | NotificationListenerService | **IMPLEMENTED** — `AniNotificationListenerService` exists |
| 8 | Conversational context | **PARTIALLY_IMPLEMENTED** — `ConversationContext`, pending slots and confirmations exist; pronoun and omitted-object resolution do not |
| 9 | Contacts / communication | **PARTIALLY_IMPLEMENTED** — `ContactResolver`, `CommunicationLauncher`, ambiguity prompts exist; nickname and "office wala" metadata resolution do not |
| 10 | Alarms / reminders / timers | **PARTIALLY_IMPLEMENTED** — `AlarmLauncher`, `ReminderScheduler`, `BootReceiver` exist; AM/PM disambiguation not confirmed |
| 11 | App integrations | **PARTIALLY_IMPLEMENTED** — `AppResolver` exists; no `AppActionRegistry` |
| 12 | Spotify | **PARTIALLY_IMPLEMENTED** — launch, search and deep links via `MusicController`; no App Remote, so no real playback status |
| 13 | Device controls | **PARTIALLY_IMPLEMENTED** — `DeviceController` with panel fallbacks |
| 14 | Location / navigation | **PARTIALLY_IMPLEMENTED** — `NavigationTools` |
| 15 | Memory | **PARTIALLY_IMPLEMENTED** — `MemoryRepository`, `MemoryTools`, memory screen |
| 16 | Optional cloud AI | **PARTIALLY_IMPLEMENTED** — `AiProvider` with an offline default; the structured-intent boundary is not yet enforced as a contract |
| 17 | Interruption / barge-in | **NOT STARTED** — TTS is cancellable, but no "stop" mid-speech path |
| 18 | Privacy / security hardening | **PARTIALLY_IMPLEMENTED** — `SecureStore`, redacting logger, Privacy Center |
| 19 | Battery / OEM diagnostics | **IMPLEMENTED** — `BackgroundRestrictions`, Keep Ani Ready |
| 20 | Real-device regression | **NOT TESTED** |

## What must not be broken

Every recognition change from here is measured against the existing baseline, not
replaced by it. `RecognizerBenchmark`, `MicTestController`, `TurnTimeline`, `MicArbiter`,
`MicLifecycle`, both wake engines and both recogniser paths stay. The A/B screen is the
instrument; removing it to "simplify" would delete the only evidence the project has.
