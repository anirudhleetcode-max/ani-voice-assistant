package com.ani.assistant.core.result

/**
 * The outcome of running a tool.
 *
 * [Success] carries what Ani should *say*, never a raw value, because the phrasing is
 * part of the feature. [Limitation] is the important one: it is what a tool returns when
 * Android or a third-party app genuinely will not let it do the thing, and it exists so
 * that "I can't do that, here's what I did instead" is a first-class result rather than
 * an error swept into a generic failure message.
 */
sealed interface AniResult {

    /** The action was performed. [spokenResponse] is read aloud and shown in the transcript. */
    data class Success(
        val spokenResponse: String,
        /** Extra detail for the transcript only, e.g. a notification list. */
        val detail: String? = null
    ) : AniResult

    /**
     * The action could not be performed as asked, and Ani did the closest legitimate
     * thing instead (opened a settings screen, prepared a draft, started a search).
     *
     * Kept separate from [Failure] so the UI can present it as an outcome rather than a
     * problem, and so nothing in the codebase is tempted to report it as success.
     */
    data class Limitation(
        val spokenResponse: String,
        /** What Ani did instead, if anything. */
        val fallbackTaken: String? = null
    ) : AniResult

    /** A permission or special access is missing. [action] tells the UI where to send the user. */
    data class NeedsPermission(
        val spokenResponse: String,
        val permissionKey: String
    ) : AniResult

    /** Ani needs one more piece of information before it can act. */
    data class NeedsInput(
        val spokenResponse: String,
        val slotKey: String
    ) : AniResult

    /** Ani is asking the user to approve the action it is about to take. */
    data class NeedsConfirmation(
        val spokenResponse: String
    ) : AniResult

    /** Something went wrong. [spokenResponse] is always human wording, never an exception. */
    data class Failure(
        val spokenResponse: String,
        val cause: Throwable? = null
    ) : AniResult

    val spoken: String
        get() = when (this) {
            is Success -> spokenResponse
            is Limitation -> spokenResponse
            is NeedsPermission -> spokenResponse
            is NeedsInput -> spokenResponse
            is NeedsConfirmation -> spokenResponse
            is Failure -> spokenResponse
        }
}
