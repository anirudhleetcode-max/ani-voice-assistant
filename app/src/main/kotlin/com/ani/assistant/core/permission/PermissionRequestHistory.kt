package com.ani.assistant.core.permission

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers which permission dialogs we have already shown.
 *
 * Android has no API for "will the dialog appear if I ask again?" — `shouldShowRationale`
 * returns false both before the first request *and* after a permanent denial, which are
 * opposite situations. Recording our own history is the only way to tell them apart, and
 * telling them apart is what decides whether the UI says "Allow" or "Open Settings".
 *
 * Nothing sensitive is stored: booleans keyed by permission name.
 */
class PermissionRequestHistory(context: Context) {

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun hasBeenRequested(permission: AniPermission): Boolean =
        preferences.getBoolean(requestedKey(permission), false)

    /**
     * True when we have shown the system rationale and the user still said no, which is
     * Android's definition of "stop asking".
     */
    fun wasRationaleShownAndStillDenied(permission: AniPermission): Boolean =
        preferences.getBoolean(exhaustedKey(permission), false)

    fun record(permission: AniPermission, granted: Boolean, rationaleWasShown: Boolean) {
        preferences.edit {
            putBoolean(requestedKey(permission), true)
            if (granted) {
                putBoolean(exhaustedKey(permission), false)
            } else if (rationaleWasShown) {
                putBoolean(exhaustedKey(permission), true)
            }
        }
    }

    /** Used by the Privacy Center's "ask me again" affordance. */
    fun reset(permission: AniPermission) {
        preferences.edit {
            remove(requestedKey(permission))
            remove(exhaustedKey(permission))
        }
    }

    private fun requestedKey(permission: AniPermission) = "requested_${permission.storageKey}"
    private fun exhaustedKey(permission: AniPermission) = "exhausted_${permission.storageKey}"

    private companion object {
        const val FILE_NAME = "ani_permission_history"
    }
}
