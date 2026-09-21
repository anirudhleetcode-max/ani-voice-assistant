# AI integration

## Where the model sits

```
Android app  ──►  Ani backend  ──►  Anthropic / OpenAI
             ◄──               ◄──
```

Not:

```
Android app  ──►  Anthropic          ✗ the key ships to everyone who downloads the APK
```

## Why the app never holds a key

An API key inside an Android app is an API key published. `apktool` on any APK, ten
minutes, and it is on someone else's bill. String obfuscation, NDK storage, splitting the
constant and reassembling at runtime, fetching it on first launch and caching it — all of
these lose to someone who reads the decompiled code or attaches a debugger, and they only
make the eventual breach harder to notice.

So the key lives on the backend and the app talks to one endpoint. This also means:

- The model can be swapped without an app release.
- Ani's persona — the system prompt — is defined somewhere a user cannot accidentally edit.
- Rate limiting is enforced somewhere that cannot be bypassed by editing the client.

## What the model is not allowed to do

This is the part that matters more than which model is chosen.

**`IntentType` is a closed enum.** Every action Ani can take on the phone is a member of
it, fixed at compile time. Adding a capability means adding an enum member *and* a tool
that implements it, which is the review point.

**The model is consulted only for `GENERAL_QUESTION` and `CONVERSATION`.** By the time
`ConversationTool` calls the backend, the assistant has already decided it is not doing
anything to the phone. Every other intent — calls, messages, alarms, notifications, music,
settings — is routed by deterministic rules and never touches the network.

**The reply is spoken, never parsed.** `AiOutcome.Reply.text` goes to text-to-speech and
to the transcript. Nothing looks for commands in it. If a model returned "I have called
your mother", nothing would happen, because nothing downstream of that call can call
anyone.

**No function calling, no tool definitions, no structured output.** The request carries a
sentence and gets a sentence.

## What is sent

```json
{
  "text": "rey em chesthunnav",
  "language": "TELUGU",
  "persona": "FRIENDLY",
  "turns": [{"role": "user", "text": "..."}],
  "context": null
}
```

Plus an `X-Ani-Install` header: an opaque random UUID generated on first run, used only
for rate limiting. It is not an account, is not tied to the user's identity, and can be
regenerated from the Privacy Center.

**Not sent, ever:** contact names or numbers, notification contents, message bodies,
audio, location, the installed app list, or any device identifier.

`turns` is the recent transcript, and only when the user has conversation history enabled.
With history off, each question is answered cold and nothing about previous turns leaves
the device.

## The system prompt

In `backend/app/prompts.py`. It does three things:

1. Tells the model to answer in whatever the user spoke — Telugu in Latin letters if they
   used Tanglish, and never to "correct" casual Telugu into formal Telugu.
2. Caps the length. This is read aloud; nobody wants a paragraph spoken at them.
3. States plainly that the model has no ability to act, so it never claims to have done
   something.

There is a test asserting the third point is present, because it is the one that would
cause real harm if it were dropped in an edit.

## Swapping providers

`backend/app/providers/` has `AnthropicProvider` and `OpenAIProvider` behind a `Provider`
ABC. Change `ANI_PROVIDER` in `.env` and restart. Adding a third is one file plus a line
in `build_provider`.

Default model is `claude-sonnet-5`. Configurable via `ANI_ANTHROPIC_MODEL`.

## Offline

Two paths, both honest:

**User chose offline-only** (Settings → Integrations). `OfflineAiProvider` handles
greetings, thanks and "em chesthunnav" with fixed replies, and says "Internet ledu ra,
basic phone commands matram chestha" for anything needing real knowledge. It never invents
an answer.

**Backend unreachable.** `BackendAiProvider` returns `AiOutcome.Unavailable`, which the
tool layer turns into a `Limitation` — the user hears that the network is the problem
rather than a generic failure.

**No backend configured at all.** Diagnostics says "Not configured" and open questions get
the offline reply. Every phone command still works, because none of them use the network.

## Costs

`max_tokens` defaults to 200 and the prompt asks for one or two sentences, so a typical
reply is well under 100 output tokens. Rate limiting defaults to 20 requests per minute
per install. Only `GENERAL_QUESTION` and `CONVERSATION` reach the model — "Amma ki call
chey" costs nothing.
