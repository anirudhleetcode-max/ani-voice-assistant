# Architecture

## The pipeline

```
microphone
   ↓  SpeechRecognizerProvider          (interface; Android impl today)
raw text
   ↓  TextNormalizer                    transliterate → lowercase → tokenise → phonetic keys
   ↓  LanguageDetector                  Telugu / English / Mixed
   ↓  WakeWordMatcher                   strip "Rey"
   ↓  CustomCommandMatcher              user-taught phrases win first
   ↓  IntentClassifier                  ~27 scoring rules → one typed ParsedCommand
   ↓  slot check                        missing argument → a question, not a failure
   ↓  ConfirmationPolicy                consequential action → ask first
   ↓  ToolRegistry                      permission gate → exactly one tool
AniResult
   ↓  Responses                         phrased in Telugu or English, by persona
   ↓  TtsProvider                       spoken
```

Two steps in the middle are what make this an assistant rather than a command parser.
A command missing a slot becomes a question; a consequential command becomes a
confirmation. Both park the parsed command in the conversation context, so the *next*
utterance completes it:

```
"Rahul ki message pampu"              → SEND_MESSAGE, needs MESSAGE_BODY
"Em message pampali?"                 ← Ani asks
"repu college ki late avutha ani cheppu"  → the same command, now complete
```

## Modules

```
ani-voice-assistant/
├── core-nlu/            plain Kotlin/JVM — the language engine
├── app/                 the Android application
└── backend/             FastAPI service that holds the model API key
```

### Why `core-nlu` is a separate build

It is included with Gradle's `includeBuild`, which means it compiles and tests with no
Android SDK on the machine at all:

```bash
cd core-nlu && ./gradlew test     # any JDK 17+
```

That is the point. The Telugu/Tanglish understanding is where nearly all the iteration
happens, and gating it on an emulator, an SDK download or a device would have halved the
number of times it could be tried. It also means the 106 tests run in seconds in CI
without an Android runner.

### Why `app` is one module rather than fifteen

Module splitting buys build parallelism and enforced boundaries. It costs a Gradle file
per module, and every one of those is a surface that can be wrong. This app was written in
an environment where the Android toolchain could not run, so each additional unverifiable
Gradle file was pure risk with no way to check it.

The package structure mirrors the module graph exactly, so splitting later is mechanical:

| Package | Would become |
| --- | --- |
| `core/` | `:core:common` |
| `data/` | `:core:data` |
| `platform/` | `:core:platform` |
| `voice/`, `ai/`, `assistant/` | `:domain` |
| `ui/screens/*` | `:feature-*` |
| `ui/theme`, `ui/components` | `:core-ui` |

## Key decisions

### Dependency injection without a framework

`AppGraph` is the composition root: a few dozen `by lazy` singletons, every dependency
visible in one screen of code. Every class above it takes what it needs as a constructor
parameter and none of them reach for a global, which is what makes them testable — the DI
*discipline* is intact; only the code generator is absent.

Hilt would generate most of that file. On a larger team it earns its annotation processor;
here it would add a code-generation step with no way to verify it.

### Storage without Room

Settings are DataStore preferences. Lists — transcripts, memories, commands, the
notification buffer, reminders — are `JsonListStore`, a `DataStore<List<T>>` with a
kotlinx-serialization codec, atomic writes and a corruption handler.

None of these need querying. They are read whole, capped at a few hundred entries, and a
Room schema plus migrations for each would be more machinery than the problem has. The
repositories are the seam: if a feature ever needs real queries, they are the only thing
that changes.

### Rules score, they do not race

Real sentences trip several intent rules at once:

> "repu 10 ki Rahul ki call cheyyali **ani gurthu chey**"

contains a calling verb, a contact, a time and a reminder verb. Every rule scores
independently and the highest wins, with the runners-up kept in
`ParsedCommand.alternatives` for diagnostics. First-match-wins would have made rule order
load-bearing, which is a debugging nightmare.

### Phonetic folding

`PhoneticKey.of` collapses aspiration, gemination, vowel length and the consonants Telugu
speakers use interchangeably:

```
"call" → "kal"      "kaal"     → "kal"
"chey" → "key"      "cheyyi"   → "keyi"
"vachayi" → "vakayi"  "vacchayi" → "vakayi"
"whatsapp" → "vatsap"
```

Tolerances on the fuzzy fallback are deliberately tight — at one edit on short words,
"singh" folds to "sing" and lands next to "song", which would read Arijit Singh's surname
as the word for music. There is a test pinning exactly that.

### Honesty as a type

`AniResult.Limitation` is a first-class outcome, distinct from both success and failure.
It is what a tool returns when Android or a third-party app genuinely will not allow the
thing, and the tool did the closest legitimate alternative. Having it in the type system
means nothing in the codebase can quietly report it as success.

### The model cannot act

`IntentType` is a closed enum. The AI provider is consulted only for `GENERAL_QUESTION`
and `CONVERSATION`, receives only the user's sentence and recent transcript, and its reply
is spoken verbatim — never parsed for commands. If a model returned "I have called your
mother", nothing would happen, because nothing downstream of that call can call anyone.

## Threading

| Where | Dispatcher | Why |
| --- | --- | --- |
| `SpeechRecognizer` | Main | The platform requires it; calling off-main is a silent no-op |
| Contacts, apps, storage | IO | Content provider and disk |
| Classification | Caller's | Pure CPU, microseconds |
| `AniOrchestrator.handle` | Caller's, behind a `Mutex` | Utterances can arrive from the service and the UI at once |

`AppGraph.settingsState` is a hot `StateFlow` specifically so the wake-word loop — which
runs on the main dispatcher — can read the current wake phrases without blocking.
