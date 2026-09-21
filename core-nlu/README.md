# core-nlu

Ani's language engine: the part that turns *"rey ammaki call chey"* into a typed action.

It is a **plain Kotlin/JVM library with no Android dependencies**, wired into the app as a
Gradle [included build](https://docs.gradle.org/current/userguide/composite_builds.html).
That is deliberate — the Telugu/Tanglish understanding is where nearly all the iteration
happens, and it should never be gated on an emulator, an SDK download or a device.

```bash
cd core-nlu && ./gradlew test      # runs on any JDK 17+, no Android SDK needed
```

## What is in here

| Package | Responsibility |
| --- | --- |
| `text` | Transliteration (Telugu script → Tanglish), phonetic folding, tokenising, fuzzy distance |
| `lexicon` | The vocabulary: word → semantic tags, Telugu case-suffix stripping, numbers, language ID |
| `intent` | Typed intents, the rule-scoring classifier, slot extraction, app nicknames |
| `time` | "repu morning 7 ki", "ara ganta tarvata" → an unresolved, explicitly-ambiguous `TimeSpec` |
| `dialog` | Wake phrase matching, short-term conversation context, confirmation policy |
| `command` | Matching user-defined phrases like "college mode" |
| `response` | Everything Ani says, in Telugu and English, plus notification summarising |

## The two ideas that make it work

**Phonetic folding.** Tanglish has no spelling. `chey`, `cheyi`, `cheyyi`, `che` and the
transliteration of `చెయ్` all fold to the same key, so the lexicon holds one entry instead
of five hundred string literals. See `text/PhoneticKey.kt`.

**Rules score, they do not race.** Real sentences trip several rules at once — *"repu 10 ki
Rahul ki call cheyyali ani gurthu chey"* contains a calling verb, a contact, a time and a
reminder verb. Every rule scores independently and the best one wins, with the runners-up
kept for diagnostics.

## What it never does

No network calls, and no ability to choose an action from free text. An AI provider is
consulted only for `GENERAL_QUESTION` and `CONVERSATION`, and only to produce *words*. The
set of things Ani can do to a phone is fixed at compile time in `IntentType`.
