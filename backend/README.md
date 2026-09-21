# Ani backend

One endpoint. It takes a sentence and returns a sentence.

## Why it exists

Ani answers open-ended questions with a language model, and that model needs an API key.
An API key inside an Android app is an API key handed to everyone who downloads it —
string obfuscation, NDK storage and split constants all lose to `apktool` and ten minutes.
So the key lives here, and the app talks to this.

This also means Ani's persona is defined server-side, where a user cannot accidentally
edit it, and the model can be swapped without an app release.

## What it deliberately cannot do

- It never receives contacts, notification contents, message bodies or audio.
- It cannot make the phone do anything. It returns text; the app has already decided what
  action it is taking before this is ever called.
- It stores nothing. No transcripts, no user records, no logs of what anyone said.

## Running it

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env          # then fill in ANI_ANTHROPIC_API_KEY
uvicorn app.main:app --reload --port 8000
```

Then point the app at it:

```bash
./gradlew assembleDebug -Pani.backendUrl=http://10.0.2.2:8000    # emulator
./gradlew assembleDebug -Pani.backendUrl=http://192.168.1.5:8000 # phone on the same Wi-Fi
```

`10.0.2.2` is how the Android emulator reaches the host machine's localhost.

## Without a key

With no key configured the service starts anyway and serves the `echo` provider, which
returns "Adi naaku ippudu teliyadu ra" for everything. Every phone command in the app —
calls, messages, alarms, notifications, music, app launching — works regardless, because
none of them touch the network. `/health` reports which provider is actually active so the
fallback is never silent.

## Tests

```bash
pytest
```

14 tests, no network, no key required.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Active provider and whether credentials are present |
| `POST` | `/v1/reply` | `{text, language, persona, turns, context}` → `{reply}` |

`X-Ani-Install` is an opaque random id the app generates on first run, used only for rate
limiting. It is not an account and is not tied to the user's identity; it can be
regenerated from the app's Privacy Center.
