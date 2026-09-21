package com.ani.assistant.voice

import com.ani.assistant.voice.audio.AudioVerdict

/** What Ani should say when a recognition attempt produced nothing usable. */
enum class FailureMessage {
    /** Say nothing. Silence is not worth narrating. */
    NONE,

    /** "Sarigga vinapadatledu ra" — audio arrived, the words did not resolve. */
    DID_NOT_CATCH,

    /** "Malli cheppu" — the second attempt also produced nothing. */
    SAY_IT_AGAIN,

    /**
     * Something else has the microphone.
     *
     * Never phrased as "I didn't hear you", because the user was not the problem and
     * repeating themselves will not help.
     */
    MICROPHONE_BLOCKED,

    PERMISSION_MISSING,
    NO_INTERNET,
    NO_RECOGNIZER,
    GENERIC_ERROR
}

/** The decision for one failed attempt. */
data class RetryOutcome(
    /** True when the caller should listen again immediately. */
    val retry: Boolean,
    val message: FailureMessage
)

/**
 * How many times to listen, and what to say when listening stops helping.
 *
 * Two things this stops:
 *
 * **Blaming the user for a system fault.** A missing permission, a busy recogniser or a
 * microphone another process holds all used to arrive as "I didn't catch that". The
 * remedy for each is different and none of them is "speak up", so each gets its own
 * message.
 *
 * **Announcing every stumble.** One failed attempt is normal — a door closed, a cough,
 * a false wake. Ani retries it quietly and only speaks when the second attempt also
 * comes back empty, which is the difference between an assistant that seems attentive
 * and one that nags.
 */
object RecognitionRetryPolicy {

    /** Listen at most this many times per turn before giving up and re-arming. */
    const val MAX_ATTEMPTS = 2

    /**
     * @param error what the recogniser reported, or null when it simply produced nothing.
     * @param verdict what the microphone level did; see [AudioVerdict].
     * @param attempt 1-based count of attempts already made in this turn.
     */
    fun decide(error: SpeechError?, verdict: AudioVerdict, attempt: Int): RetryOutcome {
        val lastAttempt = attempt >= MAX_ATTEMPTS

        // Faults that another attempt cannot fix. Report the real cause at once.
        when (error) {
            SpeechError.PERMISSION_MISSING ->
                return RetryOutcome(retry = false, message = FailureMessage.PERMISSION_MISSING)

            SpeechError.RECOGNIZER_UNAVAILABLE ->
                return RetryOutcome(retry = false, message = FailureMessage.NO_RECOGNIZER)

            SpeechError.NETWORK ->
                return RetryOutcome(retry = false, message = FailureMessage.NO_INTERNET)

            else -> Unit
        }

        // The microphone was open but empty. Retrying re-acquires it, which can genuinely
        // fix it — once. After that, say what is actually wrong.
        if (verdict == AudioVerdict.NO_AUDIO) {
            return if (lastAttempt) {
                RetryOutcome(retry = false, message = FailureMessage.MICROPHONE_BLOCKED)
            } else {
                RetryOutcome(retry = true, message = FailureMessage.NONE)
            }
        }

        return when (error) {
            // Another recognition is still winding down. Transient, worth one quiet retry.
            SpeechError.BUSY, SpeechError.MICROPHONE_UNAVAILABLE -> if (lastAttempt) {
                RetryOutcome(retry = false, message = FailureMessage.MICROPHONE_BLOCKED)
            } else {
                RetryOutcome(retry = true, message = FailureMessage.NONE)
            }

            // Nothing was said at all. Not worth announcing: the user walked away, or the
            // wake word fired on something that was not them.
            SpeechError.NO_SPEECH, null -> if (lastAttempt) {
                RetryOutcome(retry = false, message = FailureMessage.NONE)
            } else {
                RetryOutcome(retry = true, message = FailureMessage.NONE)
            }

            SpeechError.NOT_UNDERSTOOD -> if (lastAttempt) {
                RetryOutcome(retry = false, message = FailureMessage.SAY_IT_AGAIN)
            } else {
                // Audio arrived and the words did not resolve. Say so once, then listen
                // again — this is the only case where speaking before retrying helps,
                // because the user can choose to say it differently.
                RetryOutcome(retry = true, message = FailureMessage.DID_NOT_CATCH)
            }

            SpeechError.OTHER -> if (lastAttempt) {
                RetryOutcome(retry = false, message = FailureMessage.GENERIC_ERROR)
            } else {
                RetryOutcome(retry = true, message = FailureMessage.NONE)
            }

            else -> RetryOutcome(retry = false, message = FailureMessage.GENERIC_ERROR)
        }
    }
}
