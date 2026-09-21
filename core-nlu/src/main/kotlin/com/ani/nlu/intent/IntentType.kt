package com.ani.nlu.intent

/**
 * Every action Ani can decide to take.
 *
 * The set is closed on purpose. The language model in front of it may phrase things a
 * thousand ways, but it can only ever select one of these — an LLM in Ani is a language
 * understander, never a shell. Adding a capability means adding a member here *and* a
 * tool that implements it, which is exactly the review point we want.
 */
enum class IntentType(val risk: ActionRisk) {

    /** "Rey" with nothing after it — Ani just acknowledges and keeps listening. */
    WAKE_ONLY(ActionRisk.SAFE),

    CALL_CONTACT(ActionRisk.SENSITIVE),
    SEND_MESSAGE(ActionRisk.SENSITIVE),
    READ_NOTIFICATIONS(ActionRisk.NORMAL),
    READ_MISSED_CALLS(ActionRisk.NORMAL),

    PLAY_MUSIC(ActionRisk.NORMAL),
    MUSIC_CONTROL(ActionRisk.SAFE),

    OPEN_APP(ActionRisk.NORMAL),
    OPEN_SETTINGS(ActionRisk.NORMAL),

    SET_ALARM(ActionRisk.NORMAL),
    SET_TIMER(ActionRisk.NORMAL),
    CREATE_REMINDER(ActionRisk.NORMAL),

    GET_BATTERY(ActionRisk.SAFE),
    GET_DEVICE_STATUS(ActionRisk.SAFE),
    GET_TIME(ActionRisk.SAFE),
    GET_DATE(ActionRisk.SAFE),
    GET_WEATHER(ActionRisk.SAFE),

    CONTROL_FLASHLIGHT(ActionRisk.SAFE),
    CONTROL_VOLUME(ActionRisk.SAFE),
    CONTROL_DND(ActionRisk.DANGEROUS),

    NAVIGATE(ActionRisk.NORMAL),
    SHARE_LOCATION(ActionRisk.SENSITIVE),

    /** A user-defined phrase that expands into a saved sequence of actions. */
    CUSTOM_COMMAND(ActionRisk.NORMAL),

    /** "Rey, X ante Y" / "save this as a command" — teaches Ani something new. */
    TEACH_COMMAND(ActionRisk.NORMAL),
    REMEMBER_FACT(ActionRisk.NORMAL),
    FORGET_FACT(ActionRisk.SENSITIVE),

    /** "avunu" / "sare" / "vaddu" answering a pending confirmation. */
    CONFIRM(ActionRisk.SAFE),
    DENY(ActionRisk.SAFE),
    CANCEL(ActionRisk.SAFE),

    REPEAT_LAST(ActionRisk.SAFE),

    /** Needs the AI provider: open-ended knowledge question. */
    GENERAL_QUESTION(ActionRisk.SAFE),

    /** Small talk — "em chesthunnav", "thanks ra". */
    CONVERSATION(ActionRisk.SAFE),

    UNKNOWN(ActionRisk.SAFE);

    val isDeviceAction: Boolean
        get() = this !in setOf(GENERAL_QUESTION, CONVERSATION, UNKNOWN, CONFIRM, DENY, WAKE_ONLY)
}

/**
 * How much damage getting this wrong would do, which drives the confirmation prompt.
 *
 * The user can raise or lower the threshold in Settings, but the ordering here is fixed:
 * placing a phone call is never less consequential than reading out the battery level.
 */
enum class ActionRisk {
    /** Read-only or trivially reversible. Just do it. */
    SAFE,

    /** Visible side effect the user can undo. Do it, say what you did. */
    NORMAL,

    /** Reaches another person or costs money. Ask first by default. */
    SENSITIVE,

    /** Changes device state in a way that can silence calls or lose data. Always ask. */
    DANGEROUS
}
