package com.ani.assistant.core.permission

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single place that knows what Ani is and is not allowed to do.
 *
 * Every feature asks here before acting, and the Privacy Center and Diagnostics screens
 * read the same state — so what the UI shows and what the assistant does can never drift
 * apart.
 *
 * Special access (notification listener, Do Not Disturb, exact alarms) is re-read on
 * every [refresh] rather than cached, because the user can revoke it in Settings while
 * the app is in the background and Android gives us no callback.
 */
class PermissionManager(
    private val context: Context,
    /** Records "we have shown this dialog before", which is how permanent denial is detected. */
    private val requestHistory: PermissionRequestHistory
) {

    private val _statuses = MutableStateFlow<Map<AniPermission, PermissionStatus>>(emptyMap())
    val statuses: StateFlow<Map<AniPermission, PermissionStatus>> = _statuses.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads every capability from the platform. Cheap; call it on every resume. */
    fun refresh() {
        _statuses.value = AniPermission.entries.associateWith { status(it) }
    }

    fun status(permission: AniPermission): PermissionStatus = when (val mechanism = permission.mechanism) {
        is GrantMechanism.Automatic -> PermissionStatus.NOT_APPLICABLE

        is GrantMechanism.Runtime -> when {
            ContextCompat.checkSelfPermission(context, mechanism.manifestPermission) ==
                PackageManager.PERMISSION_GRANTED -> PermissionStatus.GRANTED

            !requestHistory.hasBeenRequested(permission) -> PermissionStatus.NOT_REQUESTED

            requestHistory.wasRationaleShownAndStillDenied(permission) ->
                PermissionStatus.PERMANENTLY_DENIED

            else -> PermissionStatus.DENIED
        }

        is GrantMechanism.SpecialAccess -> if (hasSpecialAccess(permission)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    fun isGranted(permission: AniPermission): Boolean = status(permission).isUsable

    /** The manifest permission string to hand to the Activity Result launcher, if any. */
    fun manifestPermissionOf(permission: AniPermission): String? =
        (permission.mechanism as? GrantMechanism.Runtime)?.manifestPermission

    // ---------------------------------------------------------------------------------
    // Special access — the three that are not runtime permissions
    // ---------------------------------------------------------------------------------

    private fun hasSpecialAccess(permission: AniPermission): Boolean = when (permission) {
        AniPermission.NOTIFICATION_ACCESS -> isNotificationListenerEnabled()
        AniPermission.DO_NOT_DISTURB -> isDndAccessGranted()
        AniPermission.EXACT_ALARMS -> canScheduleExactAlarms()
        else -> false
    }

    /**
     * Whether the user has ticked Ani in Settings > Notification access.
     *
     * Read from [Settings.Secure] because there is no API that answers this for your own
     * app, and because `NotificationListenerService.requestRebind` lies about it after a
     * revoke until the process restarts.
     */
    fun isNotificationListenerEnabled(): Boolean = try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val ourPackage = context.packageName
        enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == ourPackage }
    } catch (error: Exception) {
        AniLog.w(TAG, "could not read notification listener state", "error" to error.javaClass.simpleName)
        false
    }

    fun isDndAccessGranted(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.isNotificationPolicyAccessGranted
    }

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    // ---------------------------------------------------------------------------------
    // Where to send the user
    // ---------------------------------------------------------------------------------

    /**
     * The Settings screen that grants [permission], or null when a runtime dialog is the
     * right route.
     *
     * Returns null rather than throwing for an unreachable screen: some OEM builds ship
     * without the exact-alarm settings activity, and the caller falls back to the app's
     * own settings page.
     */
    fun settingsIntentFor(permission: AniPermission): Intent? {
        val mechanism = permission.mechanism as? GrantMechanism.SpecialAccess ?: return null
        val intent = when (permission) {
            AniPermission.EXACT_ALARMS -> Intent(mechanism.settingsAction)
                .setData(Uri.fromParts("package", context.packageName, null))
            else -> Intent(mechanism.settingsAction)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (intent.resolveActivity(context.packageManager) != null) intent else appSettingsIntent()
    }

    /** The app's own page in Settings — the only route left after a permanent denial. */
    fun appSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun recordRequested(permission: AniPermission, granted: Boolean, rationaleWasShown: Boolean) {
        requestHistory.record(permission, granted, rationaleWasShown)
        refresh()
    }

    private companion object {
        const val TAG = "AniPermissions"
    }
}
