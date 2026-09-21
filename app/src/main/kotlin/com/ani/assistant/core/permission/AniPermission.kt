package com.ani.assistant.core.permission

import android.Manifest
import android.os.Build

/**
 * How a capability is granted on Android.
 *
 * The distinction matters enough to be in the type system. A [Runtime] permission is a
 * dialog. [SpecialAccess] is a trip to a Settings screen the app cannot shortcut, cannot
 * pre-approve and cannot detect the result of except by polling — and pretending
 * otherwise is exactly how assistants end up with a "read my notifications" button that
 * silently does nothing.
 */
sealed interface GrantMechanism {
    /** A normal runtime permission dialog. */
    data class Runtime(val manifestPermission: String) : GrantMechanism

    /** A toggle the user has to find in system Settings. */
    data class SpecialAccess(val settingsAction: String) : GrantMechanism

    /** Granted at install or not applicable on this API level. */
    data object Automatic : GrantMechanism
}

/**
 * Every capability Ani asks for, why it asks, and what stops working without it.
 *
 * [whyNeeded] is shown to the user verbatim in onboarding and in the Privacy Center, so
 * it is written for them rather than for us.
 */
enum class AniPermission(
    val displayName: String,
    val whyNeeded: String,
    val withoutIt: String,
    val mechanism: GrantMechanism
) {
    MICROPHONE(
        displayName = "Microphone",
        whyNeeded = "So Ani can hear \"Rey\" and understand what you say next.",
        withoutIt = "Ani can't listen at all — nothing else works.",
        mechanism = GrantMechanism.Runtime(Manifest.permission.RECORD_AUDIO)
    ),

    CONTACTS(
        displayName = "Contacts",
        whyNeeded = "So \"Amma ki call chey\" can find Amma's number.",
        withoutIt = "Ani can't resolve names — you'd have to say the number.",
        mechanism = GrantMechanism.Runtime(Manifest.permission.READ_CONTACTS)
    ),

    PHONE(
        displayName = "Phone",
        whyNeeded = "So Ani can place the call instead of only opening the dialler.",
        withoutIt = "Ani opens the dialler with the number filled in; you press call.",
        mechanism = GrantMechanism.Runtime(Manifest.permission.CALL_PHONE)
    ),

    NOTIFICATIONS_POST(
        displayName = "Show notifications",
        whyNeeded = "So Ani can show the listening status and deliver your reminders.",
        withoutIt = "Reminders can't reach you and the listening service can't run.",
        mechanism = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantMechanism.Runtime(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantMechanism.Automatic
        }
    ),

    /**
     * Not a runtime permission. The user must enable Ani under
     * Settings > Notifications > Device & app notifications.
     */
    NOTIFICATION_ACCESS(
        displayName = "Notification access",
        whyNeeded = "So \"em messages vachayi?\" can tell you who messaged you.",
        withoutIt = "Ani can't read notifications — it will say so rather than guess.",
        mechanism = GrantMechanism.SpecialAccess(
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
        )
    ),

    /** Also not a runtime permission: Settings > Do Not Disturb access. */
    DO_NOT_DISTURB(
        displayName = "Do Not Disturb access",
        whyNeeded = "So \"good night\" can actually silence the phone.",
        withoutIt = "Ani opens the Do Not Disturb settings screen instead.",
        mechanism = GrantMechanism.SpecialAccess(
            "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"
        )
    ),

    /** Granted at install for an alarm app on most builds; user-grantable otherwise. */
    EXACT_ALARMS(
        displayName = "Exact alarms",
        whyNeeded = "So a 7:00 alarm goes off at 7:00 and not whenever the system feels like it.",
        withoutIt = "Alarms and reminders may be delayed by battery optimisation.",
        mechanism = GrantMechanism.SpecialAccess("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
    ),

    LOCATION(
        displayName = "Location",
        whyNeeded = "Only used when you ask Ani to share your location.",
        withoutIt = "Location sharing is unavailable. Everything else is unaffected.",
        mechanism = GrantMechanism.Runtime(Manifest.permission.ACCESS_COARSE_LOCATION)
    );

    /** True for the handful of things Ani genuinely cannot function without. */
    val isEssential: Boolean
        get() = this == MICROPHONE || this == NOTIFICATIONS_POST

    val storageKey: String get() = name
}

/** What we currently know about one capability. */
enum class PermissionStatus {
    GRANTED,

    /** Asked and refused, but the dialog will still appear if asked again. */
    DENIED,

    /**
     * Refused twice, or refused with "don't ask again". Android will no longer show the
     * dialog, so the only route left is the app's settings page.
     */
    PERMANENTLY_DENIED,

    /** Never requested. */
    NOT_REQUESTED,

    /** The platform grants this automatically on this API level. */
    NOT_APPLICABLE;

    val isUsable: Boolean get() = this == GRANTED || this == NOT_APPLICABLE
}
