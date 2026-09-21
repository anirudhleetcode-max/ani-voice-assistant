package com.ani.assistant.platform.launch

/** The route [ActivityLauncher] should take for one launch attempt. */
enum class LaunchRoute {
    /** Start the activity now. */
    DIRECT,

    /** Android will not allow a background start; put it behind a notification. */
    DEFER,

    /** Nothing on the device handles it. */
    NO_HANDLER,

    /** It cannot be started and must not be deferred. */
    REFUSE
}

/**
 * Decides how to launch, with no Android types involved so it can be tested.
 *
 * The rule it encodes is the one that was missing entirely: from Android 10 (API 29) an
 * app in the background cannot start an activity, and `startActivity` gives no indication
 * that it failed. Code that assumed otherwise reported success for actions that never
 * happened.
 */
object LaunchPolicy {

    /** The API level at which background activity starts began to be blocked. */
    const val BACKGROUND_START_BLOCKED_FROM_SDK = 29

    fun route(
        hasHandler: Boolean,
        isInForeground: Boolean,
        sdkInt: Int,
        allowDeferral: Boolean
    ): LaunchRoute = when {
        !hasHandler -> LaunchRoute.NO_HANDLER

        // A resumed activity of ours means a direct start is permitted.
        isInForeground -> LaunchRoute.DIRECT

        // Before API 29 a background start still worked.
        sdkInt < BACKGROUND_START_BLOCKED_FROM_SDK -> LaunchRoute.DIRECT

        allowDeferral -> LaunchRoute.DEFER

        else -> LaunchRoute.REFUSE
    }
}
