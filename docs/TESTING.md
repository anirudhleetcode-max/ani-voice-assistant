# Testing

## What runs

```bash
cd core-nlu && ./gradlew test                 # 106 tests  — the language engine
cd backend  && pytest                         # 14 tests   — the API
cd tools/compile-check && gradle test         # 13 tests   — app storage and settings,
                                              #              plus a full type-check of
                                              #              every Android source file
```

All three run and pass. None of them needs an Android SDK, which is the point — see
*What has not been verified* below for what that does and does not prove.

## The language corpus

`core-nlu/src/test/kotlin/com/ani/nlu/IntentClassificationTest.kt` holds 129 utterances
asserted as batches, so a regression reports *every* phrase it broke rather than stopping
at the first. That is what makes it practical to keep extending the lexicon.

The same request, in the shapes people actually use:

```
"rey amma ki call chey"      "rey amma ki phone cheyyi"     "rey amma ki call pettu"
"rey ammaki call chey"       "rey amma ki call kottu"       "rey amma ki piluvu"
"call mom"                   "please call my dad"           "అమ్మకి కాల్ చెయ్"
```

All nine resolve to `CALL_CONTACT` with `CONTACT_NAME = "Amma"`.

Coverage by area:

| Area | Cases |
| --- | --- |
| Calling, in twelve phrasings plus native script | 20 |
| Messaging, including the quotative "ani" | 10 |
| Reading notifications and missed calls | 13 |
| Music: play, and six transport controls | 21 |
| Opening apps, by nickname | 14 |
| Device controls and settings screens | 18 |
| Alarms, timers, reminders | 12 |
| Time, date, weather | 7 |
| Navigation and location | 3 |
| Small talk and unknown input | 4 |
| Wake phrases, elongation, punctuation, empty input | 7 |

Plus focused tests on slot extraction, time resolution, phonetic folding, transliteration,
wake matching, custom commands, notification summarising, response wording and
multi-turn conversation.

## The tests that encode a bug we already hit

Some tests exist because something specific went wrong:

**`a singer's name is not mistaken for the word song`.** "Singh" folds to "sing", one edit
from "song". At the original fuzzy tolerance, "Arijit Singh play chey" searched for the
word "song". The tolerance is now tight enough that it cannot, and this test pins it.

**`native Telugu script reaches the same intents`.** The anusvara in "ఎంత" was
transliterated to "emta" rather than "enta", two edits from the lexicon entry, so every
native-script question failed. The transliterator now picks the nasal from the following
consonant.

**`quotative ani closes the message body`.** "Rahul ki repu 10 ki kaluddam ani message
pampu" has two datives; only the first marks the recipient. The body used to come out as
"rahul ki repu 10 ki kaluddam".

**`a bare hour stays ambiguous so Ani can ask`.** "Repu 7 ki alarm pettu" must *not*
resolve to 07:00. It comes back ambiguous so Ani asks "Morning 7 aa, evening 7 aa?" — the
difference between a useful alarm and being woken at the wrong end of the day.

**`a step naming an intent that no longer exists is dropped, not fatal`.** A routine saved
in one version and read in another must lose the step it cannot understand, not crash.

## Multi-turn

`ConversationFlowTest` covers the exchanges that make this an assistant:

```
"rey Rahul ki message pampu"                → SEND_MESSAGE, needs MESSAGE_BODY
"Em message pampali?"                       ← Ani asks
"repu college ki late avutha ani cheppu"    → complete, body extracted correctly
```

```
"rey spotify open chey"                     → activeApp = spotify
"Arijit Singh play chey"                    → PLAY_MUSIC in Spotify, from context alone
```

Plus: confirming and denying pending actions in both languages, changing the subject
mid-confirmation, and the confirmation threshold behaving differently at each setting.

## What has not been verified

**The Android module has never been through AGP.** `dl.google.com` is blocked in the
build environment, so there is no Android SDK, no AGP and no AndroidX. That means:

- No APK exists.
- No Compose UI has ever been rendered.
- No instrumented test has run.
- Resource merging, R8 and lint have never run.
- **Nothing has ever run on a phone.** See `DEVICE_TEST_RESULTS.md`.

What *has* happened: `tools/compile-check` compiles all 67 Android source files against
the genuine Android 35 framework jar (`org.robolectric:android-all`, on Maven Central) and
the genuine Compose API (JetBrains Compose Multiplatform, also on Maven Central), plus the
real Vosk and Porcupine AARs. It found a genuine type error on its first run — a
`String` being passed where an `IntentType` extension was declared — that no amount of
static checking had caught.

Treat that as strong evidence about the Kotlin and no evidence at all about the rest. Six
AndroidX libraries are Google-Maven-only and are represented by hand-written stubs, so a
signature mismatch *there* is checked against the stub rather than the real library. The
Android framework and Compose halves are genuine.

The language engine was deliberately built as a plain JVM library precisely so none of
this would affect it.

## Adding a language case

Add the line to the right batch in `IntentClassificationTest`:

```kotlin
"rey amma ki video call chey" to CALL_CONTACT,
```

Run the tests. If it fails, the fix is almost always a spelling in
`Lexicon.buildTable()` — the tables are written the way people say things, and the
phonetic folding collapses most variants automatically, so adding one word usually covers
several.

## Manual device checklist

Nothing below can be automated without a phone. Work through it after the first build:

1. Install, complete onboarding, grant microphone and contacts.
2. "Rey" → "Cheppu ra."
3. "Rey battery entha undi" → a real percentage.
4. "Rey Amma ki call chey" → confirmation, then the call.
5. Grant notification access, tick WhatsApp. Send yourself a message.
6. "Rey em messages vachayi" → a summary naming the sender.
7. "Rey Spotify lo Arijit Singh play chey" → Spotify search opens, and Ani says so.
8. "Rey repu 7 ki alarm pettu" → asks morning or evening, then sets it.
9. Create a custom command, trigger it by voice.
10. Reboot. Check reminders survived and the listening service came back (only if you had
    it on).
11. `adb logcat | grep -i ani` — confirm no contact names, message text or notification
    content appears.
