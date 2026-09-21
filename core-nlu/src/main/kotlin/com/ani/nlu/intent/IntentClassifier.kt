package com.ani.nlu.intent

import com.ani.nlu.command.CustomCommandMatcher
import com.ani.nlu.dialog.ConversationContext
import com.ani.nlu.dialog.PendingConfirmation
import com.ani.nlu.dialog.PendingSlotRequest
import com.ani.nlu.dialog.WakeWordMatcher
import com.ani.nlu.lexicon.LanguageDetector
import com.ani.nlu.lexicon.Lexicon
import com.ani.nlu.lexicon.Numbers
import com.ani.nlu.lexicon.SemanticTag
import com.ani.nlu.lexicon.SemanticTag.*
import com.ani.nlu.text.Language
import com.ani.nlu.text.NormalizedText
import com.ani.nlu.text.TextNormalizer
import com.ani.nlu.text.Token
import com.ani.nlu.time.TeluguTimeParser
import com.ani.nlu.time.TimeKind
import com.ani.nlu.time.TimeSpec

/**
 * Turns a recognised utterance into a typed [ParsedCommand].
 *
 * Every rule below scores independently and the highest score wins, with the runners-up
 * kept in [ParsedCommand.alternatives]. Scoring rather than first-match-wins matters
 * because real sentences trip several rules at once: "repu 10 ki Rahul ki call cheyyali
 * ani gurthu chey" contains a calling verb, a contact, a time and a reminder verb, and
 * only the last of those is what the user asked for.
 *
 * Nothing here calls out to a network. An AI provider is consulted only for
 * [IntentType.GENERAL_QUESTION] and [IntentType.CONVERSATION], and only ever to produce
 * *words* — never to choose an action. That boundary is the whole safety design: the set
 * of things Ani can do to the phone is fixed at compile time.
 */
class IntentClassifier(
    private val wakeMatcher: WakeWordMatcher = WakeWordMatcher(),
    private val customCommands: CustomCommandMatcher = CustomCommandMatcher.empty(),
    /** Injectable so pending-question expiry can be tested without waiting two minutes. */
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    fun classify(raw: String, context: ConversationContext = ConversationContext.EMPTY): ParsedCommand {
        val full = TextNormalizer.normalize(raw)
        val language = LanguageDetector.detect(full)
        if (full.isEmpty) return ParsedCommand.unknown(raw, "", language)

        val wake = wakeMatcher.match(full)
        val body = if (wake.matched) wake.remainder else full

        if (body.isEmpty) {
            return ParsedCommand(
                type = if (wake.matched) IntentType.WAKE_ONLY else IntentType.UNKNOWN,
                confidence = if (wake.matched) wake.confidence else 0.0,
                language = language,
                normalizedText = full.normalized,
                originalText = raw
            )
        }

        answerToPendingQuestion(raw, body, language, context)?.let { return it }

        customCommands.match(body)?.let { match ->
            return ParsedCommand(
                type = IntentType.CUSTOM_COMMAND,
                confidence = match.confidence,
                slots = mapOf(
                    SlotKey.CUSTOM_COMMAND_ID to match.command.id,
                    SlotKey.COMMAND_PHRASE to match.command.phrase
                ),
                language = language,
                normalizedText = body.normalized,
                originalText = raw
            )
        }

        val candidates = allRules(body, context).sortedByDescending { it.score }
        val best = candidates.firstOrNull()
            ?: return ParsedCommand.unknown(raw, body.normalized, language)

        return ParsedCommand(
            type = best.type,
            confidence = best.score,
            slots = best.slots,
            timeSpec = best.timeSpec,
            language = language,
            normalizedText = body.normalized,
            originalText = raw,
            needsSlots = best.needsSlots,
            alternatives = candidates.drop(1).take(3)
        )
    }

    // =================================================================================
    // Conversation state
    // =================================================================================

    /**
     * Handles the turn immediately after Ani asked something.
     *
     * A pending question changes what short utterances mean: "avunu" is a confirmation,
     * not small talk, and "repu 10 ki kaluddam" is a message body, not a reminder.
     */
    private fun answerToPendingQuestion(
        raw: String,
        body: NormalizedText,
        language: Language,
        context: ConversationContext
    ): ParsedCommand? {
        val pendingSlot = context.pendingSlot?.takeIf { !it.hasExpired() }
        if (pendingSlot != null) {
            if (isDenial(body)) {
                return ParsedCommand(
                    type = IntentType.CANCEL,
                    confidence = 0.95,
                    language = language,
                    normalizedText = body.normalized,
                    originalText = raw
                )
            }
            val value = valueForSlot(pendingSlot.slot, body)
            if (value != null) {
                return pendingSlot.command
                    .withSlot(pendingSlot.slot, value)
                    .copy(
                        confidence = 0.9,
                        language = language,
                        normalizedText = body.normalized,
                        originalText = raw
                    )
            }
        }

        val pendingConfirmation = context.pendingConfirmation?.takeIf { !it.hasExpired() }
        if (pendingConfirmation != null) {
            if (isAffirmation(body)) {
                // `confirmed = true` is the whole point. Without it the orchestrator
                // re-evaluates the same command against the same policy, reaches the same
                // verdict, and asks again — the action never runs.
                return pendingConfirmation.command.copy(
                    confirmed = true,
                    confidence = 0.98,
                    normalizedText = body.normalized,
                    originalText = raw
                )
            }
            if (isDenial(body)) {
                return ParsedCommand(
                    type = IntentType.DENY,
                    confidence = 0.95,
                    language = language,
                    normalizedText = body.normalized,
                    originalText = raw
                )
            }
            // Anything else means the user moved on; fall through to normal classification.
        }
        return null
    }

    private fun PendingConfirmation.hasExpired(): Boolean =
        nowMillis() - askedAtEpochMillis > ConversationContext.PENDING_TIMEOUT_MILLIS

    private fun PendingSlotRequest.hasExpired(): Boolean =
        nowMillis() - askedAtEpochMillis > ConversationContext.PENDING_TIMEOUT_MILLIS

    private fun valueForSlot(slot: SlotKey, body: NormalizedText): String? = when (slot) {
        SlotKey.MESSAGE_BODY -> SlotExtractors.messageBody(body, isFollowUpAnswer = true)
        SlotKey.CONTACT_NAME -> SlotExtractors.contactName(body)
        SlotKey.MUSIC_QUERY -> SlotExtractors.musicQuery(body)
        SlotKey.APP_NAME -> SlotExtractors.appName(body)
        SlotKey.REMINDER_TEXT -> SlotExtractors.reminderText(body) ?: body.normalized.ifEmpty { null }
        SlotKey.DESTINATION -> SlotExtractors.destination(body)
        else -> body.normalized.ifEmpty { null }
    }

    private fun isAffirmation(body: NormalizedText): Boolean {
        val content = body.tokens.filterNot { Lexicon.has(it, F_WAKE) || Lexicon.has(it, F_FILLER) }
        if (content.isEmpty() || content.size > 3) return false
        return content.any { Lexicon.has(it, F_AFFIRM) } && content.none { Lexicon.has(it, F_DENY) }
    }

    private fun isDenial(body: NormalizedText): Boolean {
        val content = body.tokens.filterNot { Lexicon.has(it, F_WAKE) || Lexicon.has(it, F_FILLER) }
        if (content.isEmpty() || content.size > 3) return false
        return content.any { Lexicon.has(it, F_DENY) }
    }

    // =================================================================================
    // Rules
    // =================================================================================

    private fun allRules(body: NormalizedText, context: ConversationContext): List<ScoredIntent> =
        listOfNotNull(
            readNotifications(body),
            sendMessage(body),
            callContact(body),
            musicControl(body, context),
            playMusic(body, context),
            openSettings(body),
            openApp(body),
            alarm(body),
            timer(body),
            reminder(body),
            batteryStatus(body),
            flashlight(body),
            volume(body),
            doNotDisturb(body),
            deviceStatus(body),
            clock(body),
            calendarDate(body),
            weather(body),
            navigate(body),
            shareLocation(body),
            teachCommand(body),
            rememberFact(body),
            repeatLast(body),
            yesNo(body),
            generalQuestion(body),
            smallTalk(body)
        )

    // ---- Notifications --------------------------------------------------------------

    private fun readNotifications(body: NormalizedText): ScoredIntent? {
        if (body.has(V_SEND)) return null
        val notifications = body.has(N_NOTIFICATION)
        val messages = body.has(N_MESSAGE)
        val missed = body.has(N_MISSED)
        val asking = body.has(F_QUESTION)
        val reading = body.has(V_READ) || body.has(V_TELL)

        if (missed && (body.has(N_CALL) || asking || reading)) {
            return ScoredIntent(IntentType.READ_MISSED_CALLS, 0.94)
        }
        if (!notifications && !messages) return null

        val score = when {
            asking || reading -> 0.93
            body.has(F_ALL) -> 0.85
            notifications -> 0.7
            else -> return null
        }
        return ScoredIntent(
            type = IntentType.READ_NOTIFICATIONS,
            score = score,
            slots = mapOf(SlotKey.NOTIFICATION_FILTER to SlotExtractors.notificationFilter(body))
        )
    }

    // ---- Messaging ------------------------------------------------------------------

    private fun sendMessage(body: NormalizedText): ScoredIntent? {
        val sending = body.has(V_SEND)
        val messageWithVerb = body.has(N_MESSAGE) && (body.has(LIGHT_DO) || body.has(LIGHT_PUT))
        if (!sending && !messageWithVerb) return null
        if (body.has(F_QUESTION) && !sending) return null
        if (body.has(V_READ)) return null

        val contact = SlotExtractors.contactName(body)
        val text = SlotExtractors.messageBody(body)
        val channel = SlotExtractors.messageChannel(body)

        val slots = buildMap {
            contact?.let { put(SlotKey.CONTACT_NAME, it) }
            text?.let { put(SlotKey.MESSAGE_BODY, it) }
            channel?.let { put(SlotKey.MESSAGE_CHANNEL, it) }
        }
        val missing = buildList {
            if (contact == null) add(SlotKey.CONTACT_NAME)
            if (text == null) add(SlotKey.MESSAGE_BODY)
        }
        return ScoredIntent(
            type = IntentType.SEND_MESSAGE,
            score = if (contact != null) 0.94 else 0.82,
            slots = slots,
            needsSlots = missing
        )
    }

    // ---- Calling --------------------------------------------------------------------

    private fun callContact(body: NormalizedText): ScoredIntent? {
        if (!body.has(V_CALL)) return null
        if (body.has(N_MISSED)) return null
        // "Rahul ki call cheyyali ani gurthu chey" is a reminder about a call.
        if (body.has(V_REMIND) || body.has(N_REMINDER)) return null

        val contact = SlotExtractors.contactName(body)
        return ScoredIntent(
            type = IntentType.CALL_CONTACT,
            score = if (contact != null) 0.95 else 0.78,
            slots = contact?.let { mapOf(SlotKey.CONTACT_NAME to it) }.orEmpty(),
            needsSlots = if (contact == null) listOf(SlotKey.CONTACT_NAME) else emptyList()
        )
    }

    // ---- Music ----------------------------------------------------------------------

    private fun musicControl(body: NormalizedText, context: ConversationContext): ScoredIntent? {
        val action = when {
            body.has(V_NEXT) -> "next"
            body.has(V_PREVIOUS) -> "previous"
            body.has(V_PAUSE) -> "pause"
            body.has(V_RESUME) -> "resume"
            body.has(V_STOP) && (context.mediaActive || body.has(N_MUSIC)) -> "stop"
            else -> null
        } ?: return null

        // "malli play chey" with nothing to search for means replay, not a new search.
        val replaying = body.has(F_AGAIN) && body.has(V_PLAY) && SlotExtractors.musicQuery(body) == null
        val resolved = if (replaying) "replay" else action

        val musicEvidence = body.has(N_MUSIC) || context.mediaActive ||
            context.activeApp in com.ani.nlu.intent.KnownApps.musicProviders
        return ScoredIntent(
            type = IntentType.MUSIC_CONTROL,
            score = if (musicEvidence) 0.95 else 0.86,
            slots = mapOf(SlotKey.MEDIA_ACTION to resolved)
        )
    }

    private fun playMusic(body: NormalizedText, context: ConversationContext): ScoredIntent? {
        val playing = body.has(V_PLAY)
        val puttingMusic = body.has(LIGHT_PUT) && body.has(N_MUSIC)
        // "Kesariya pettu" names no domain at all — "pettu" is the light verb "put" and
        // the only other word is something Ani does not recognise. Nothing else in the
        // sentence claims it, and a song title is overwhelmingly the likeliest reading,
        // so take it at reduced confidence rather than dropping to small talk.
        val puttingSomethingUnknown = body.has(LIGHT_PUT) && !namesAnotherDomain(body)
        if (!playing && !puttingMusic && !puttingSomethingUnknown) return null
        if (body.has(N_ALARM) || body.has(N_TIMER) || body.has(N_REMINDER)) return null

        val query = SlotExtractors.musicQuery(body)
        if (!playing && !puttingMusic && query == null) return null
        val provider = SlotExtractors.musicProvider(body) ?: context.activeApp
            ?.takeIf { it in KnownApps.musicProviders }

        if (query == null && body.has(F_AGAIN)) {
            return ScoredIntent(
                type = IntentType.MUSIC_CONTROL,
                score = 0.9,
                slots = mapOf(SlotKey.MEDIA_ACTION to "replay")
            )
        }

        val slots = buildMap {
            query?.let { put(SlotKey.MUSIC_QUERY, it) }
            provider?.let { put(SlotKey.MUSIC_PROVIDER, it) }
        }
        val score = when {
            query == null -> 0.74
            playing || puttingMusic -> 0.94
            else -> 0.72 // inferred from a bare "<something> pettu"
        }
        return ScoredIntent(
            type = IntentType.PLAY_MUSIC,
            score = score,
            slots = slots,
            needsSlots = if (query == null) listOf(SlotKey.MUSIC_QUERY) else emptyList()
        )
    }

    /** True when some other feature's noun is present, which rules music out. */
    private fun namesAnotherDomain(body: NormalizedText): Boolean = OTHER_DOMAIN_NOUNS.any { body.has(it) }

    // ---- Apps and settings ----------------------------------------------------------

    private fun openApp(body: NormalizedText): ScoredIntent? {
        if (!body.has(V_OPEN)) return null
        // A system toggle named in the sentence means the user wants Settings, not an app.
        if (SlotExtractors.settingsTarget(body) != null) return null
        val app = SlotExtractors.appName(body) ?: return null
        return ScoredIntent(
            type = IntentType.OPEN_APP,
            score = 0.92,
            slots = mapOf(SlotKey.APP_NAME to app)
        )
    }

    /**
     * Anything that ends in a system Settings screen.
     *
     * Wi-Fi, Bluetooth, airplane mode and brightness cannot be toggled by an ordinary app
     * on modern Android, so asking to turn them on and asking to open their settings are
     * the same intent here. The tool layer is what tells the user the difference.
     */
    private fun openSettings(body: NormalizedText): ScoredIntent? {
        val target = SlotExtractors.settingsTarget(body) ?: return null
        if (target in HANDLED_ELSEWHERE) return null
        if (body.has(F_QUESTION)) return null

        val wantsSettings = body.has(V_OPEN) || body.has(V_SHOW) || body.has(N_SETTINGS) ||
            body.has(V_TURN_ON) || body.has(V_TURN_OFF) ||
            body.has(V_INCREASE) || body.has(V_DECREASE)
        if (!wantsSettings) return null

        val slots = buildMap {
            put(SlotKey.SETTINGS_TARGET, target)
            SlotExtractors.toggleState(body)?.let { put(SlotKey.TOGGLE_STATE, it) }
            SlotExtractors.level(body)?.let { put(SlotKey.LEVEL, it) }
        }
        return ScoredIntent(IntentType.OPEN_SETTINGS, 0.9, slots)
    }

    // ---- Time-based ------------------------------------------------------------------

    private fun alarm(body: NormalizedText): ScoredIntent? {
        val explicit = body.has(N_ALARM)
        val waking = body.has(V_WAKE)
        if (!explicit && !waking) return null
        if (body.has(N_TIMER)) return null

        val time = TeluguTimeParser.parse(body)
        return ScoredIntent(
            type = IntentType.SET_ALARM,
            score = if (time != null) 0.95 else 0.8,
            timeSpec = time,
            needsSlots = emptyList()
        )
    }

    private fun timer(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_TIMER)) return null
        val time = TeluguTimeParser.parse(body)
            ?.takeIf { it.kind == TimeKind.DURATION }
        return ScoredIntent(
            type = IntentType.SET_TIMER,
            score = if (time != null) 0.96 else 0.8,
            timeSpec = time
        )
    }

    private fun reminder(body: NormalizedText): ScoredIntent? {
        if (!body.has(V_REMIND) && !body.has(N_REMINDER)) return null
        if (body.has(N_ALARM)) return null

        val time = TeluguTimeParser.parse(body)
        val text = SlotExtractors.reminderText(body)
        val missing = if (text == null) listOf(SlotKey.REMINDER_TEXT) else emptyList()
        return ScoredIntent(
            type = IntentType.CREATE_REMINDER,
            score = if (time != null && text != null) 0.96 else 0.86,
            slots = text?.let { mapOf(SlotKey.REMINDER_TEXT to it) }.orEmpty(),
            timeSpec = time,
            needsSlots = missing
        )
    }

    // ---- Device ----------------------------------------------------------------------

    private fun batteryStatus(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_BATTERY)) return null
        return ScoredIntent(IntentType.GET_BATTERY, 0.95)
    }

    private fun flashlight(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_FLASHLIGHT)) return null
        val state = SlotExtractors.toggleState(body) ?: "toggle"
        return ScoredIntent(
            type = IntentType.CONTROL_FLASHLIGHT,
            score = 0.95,
            slots = mapOf(SlotKey.TOGGLE_STATE to state)
        )
    }

    private fun volume(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_VOLUME)) return null
        val level = SlotExtractors.level(body) ?: return null
        return ScoredIntent(
            type = IntentType.CONTROL_VOLUME,
            score = 0.94,
            slots = mapOf(SlotKey.LEVEL to level)
        )
    }

    private fun doNotDisturb(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_DND)) return null
        if (body.has(F_QUESTION)) return null
        val state = SlotExtractors.toggleState(body) ?: return null
        return ScoredIntent(
            type = IntentType.CONTROL_DND,
            score = 0.94,
            slots = mapOf(SlotKey.TOGGLE_STATE to state)
        )
    }

    private fun deviceStatus(body: NormalizedText): ScoredIntent? {
        val target = SlotExtractors.settingsTarget(body) ?: return null
        if (target == "battery") return null
        val asking = body.has(F_QUESTION)
        if (!asking) return null
        return ScoredIntent(
            type = IntentType.GET_DEVICE_STATUS,
            score = 0.92,
            slots = mapOf(SlotKey.SETTINGS_TARGET to target)
        )
    }

    private fun clock(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_TIME)) return null
        if (body.has(N_ALARM) || body.has(N_TIMER) || body.has(N_REMINDER)) return null
        return ScoredIntent(IntentType.GET_TIME, 0.93)
    }

    private fun calendarDate(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_DATE)) return null
        return ScoredIntent(IntentType.GET_DATE, 0.93)
    }

    private fun weather(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_WEATHER)) return null
        return ScoredIntent(IntentType.GET_WEATHER, 0.93)
    }

    // ---- Navigation and location ------------------------------------------------------

    private fun navigate(body: NormalizedText): ScoredIntent? {
        val navigating = body.has(V_NAVIGATE)
        val maps = body.has(N_MAPS)
        if (!navigating && !maps) return null

        // "Google Maps open chey" is not a navigation request — it is opening an app.
        // Only an explicit navigation verb, or a destination marked with the dative
        // ("college ki"), means the user wants a route.
        val destination = if (navigating) {
            SlotExtractors.destination(body)
        } else {
            SlotExtractors.dativeDestination(body)
        }
        if (!navigating && destination == null) return null
        return ScoredIntent(
            type = IntentType.NAVIGATE,
            score = if (destination != null) 0.93 else 0.76,
            slots = destination?.let { mapOf(SlotKey.DESTINATION to it) }.orEmpty(),
            needsSlots = if (destination == null) listOf(SlotKey.DESTINATION) else emptyList()
        )
    }

    private fun shareLocation(body: NormalizedText): ScoredIntent? {
        if (!body.has(N_LOCATION) || !body.has(V_SHARE)) return null
        return ScoredIntent(IntentType.SHARE_LOCATION, 0.93)
    }

    // ---- Teaching and memory -----------------------------------------------------------

    private fun teachCommand(body: NormalizedText): ScoredIntent? {
        if (!body.has(V_TEACH)) return null
        return ScoredIntent(
            type = IntentType.TEACH_COMMAND,
            score = 0.88,
            slots = mapOf(SlotKey.COMMAND_PHRASE to body.normalized)
        )
    }

    private fun rememberFact(body: NormalizedText): ScoredIntent? {
        val pair = SlotExtractors.memoryPair(body)
        if (pair != null) {
            return ScoredIntent(
                type = IntentType.REMEMBER_FACT,
                score = 0.92,
                slots = mapOf(SlotKey.MEMORY_KEY to pair.first, SlotKey.MEMORY_VALUE to pair.second)
            )
        }
        if (body.has(V_REMEMBER)) {
            return ScoredIntent(IntentType.REMEMBER_FACT, 0.8)
        }
        return null
    }

    private fun repeatLast(body: NormalizedText): ScoredIntent? {
        if (!body.has(F_AGAIN)) return null
        if (!body.has(V_TELL)) return null
        if (body.has(V_PLAY) || body.has(N_MUSIC)) return null
        return ScoredIntent(IntentType.REPEAT_LAST, 0.9)
    }

    // ---- Fallbacks ---------------------------------------------------------------------

    /** A bare "avunu"/"vaddu" with no pending question still deserves a typed answer. */
    private fun yesNo(body: NormalizedText): ScoredIntent? = when {
        isAffirmation(body) -> ScoredIntent(IntentType.CONFIRM, 0.6)
        isDenial(body) -> ScoredIntent(IntentType.DENY, 0.6)
        else -> null
    }

    private fun generalQuestion(body: NormalizedText): ScoredIntent? {
        if (!body.has(F_QUESTION)) return null
        return ScoredIntent(
            type = IntentType.GENERAL_QUESTION,
            score = 0.45,
            slots = mapOf(SlotKey.QUESTION to body.normalized)
        )
    }

    /**
     * The floor. Anything the rules did not claim is treated as conversation and handed
     * to the AI provider for a reply — which can produce words, and nothing else.
     */
    private fun smallTalk(body: NormalizedText): ScoredIntent? {
        if (body.isEmpty) return null
        return ScoredIntent(
            type = IntentType.CONVERSATION,
            score = 0.3,
            slots = mapOf(SlotKey.QUESTION to body.normalized)
        )
    }

    // =================================================================================

    private fun NormalizedText.has(tag: SemanticTag): Boolean = tokens.any { Lexicon.has(it, tag) }

    private companion object {
        /** Targets that have a real Android API and therefore their own intent. */
        val HANDLED_ELSEWHERE = setOf("flashlight", "battery", "storage", "network")

        /** Nouns that mean the sentence is about something other than music. */
        val OTHER_DOMAIN_NOUNS = listOf(
            N_ALARM, N_TIMER, N_REMINDER, N_DND, N_VOLUME, N_BRIGHTNESS, N_WIFI,
            N_BLUETOOTH, N_MESSAGE, N_NOTIFICATION, N_SETTINGS, N_FLASHLIGHT,
            N_LOCATION, N_AIRPLANE, N_MAPS, N_BATTERY, N_STORAGE, N_NETWORK,
            N_TIME, N_DATE, N_WEATHER, N_CALL, N_APP, N_PHONE
        )
    }
}
