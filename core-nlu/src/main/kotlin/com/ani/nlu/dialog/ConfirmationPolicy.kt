package com.ani.nlu.dialog

import com.ani.nlu.intent.ActionRisk
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand

/** How cautious the user wants Ani to be, from Settings. */
enum class ConfirmationLevel(val confirmsFrom: ActionRisk) {
    /** Only ask before things that silence the phone or lose data. */
    RELAXED(ActionRisk.DANGEROUS),

    /** Ask before anything that reaches another person. The default. */
    BALANCED(ActionRisk.SENSITIVE),

    /** Ask before any visible change. */
    CAREFUL(ActionRisk.NORMAL),

    /** Ask before everything. */
    ALWAYS(ActionRisk.SAFE)
}

/**
 * Decides whether Ani asks before acting.
 *
 * Two independent gates, and both have to pass. The user's threshold is one; the other is
 * how sure the classifier is. A low-confidence parse gets confirmed even for a harmless
 * action, because "Kesariya play chesthunna" after mishearing something else is annoying,
 * whereas an unwanted phone call is not.
 */
object ConfirmationPolicy {

    /** Below this, Ani checks before doing anything with a side effect. */
    const val LOW_CONFIDENCE_THRESHOLD = 0.6

    fun requiresConfirmation(
        command: ParsedCommand,
        level: ConfirmationLevel = ConfirmationLevel.BALANCED
    ): Boolean {
        // The user already said yes to this exact action. Asking again is the bug this
        // flag exists to prevent.
        if (command.confirmed) return false
        if (!command.type.isDeviceAction) return false
        if (!command.isComplete) return false // still gathering slots; ask for those first
        if (command.type == IntentType.CUSTOM_COMMAND) return false // approved when saved

        if (command.risk.ordinal >= level.confirmsFrom.ordinal) return true
        return command.confidence < LOW_CONFIDENCE_THRESHOLD && command.risk != ActionRisk.SAFE
    }
}
