package com.ani.assistant.platform.device

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.ani.assistant.core.log.AniLog

/** What Android will actually tell us about background restrictions. */
enum class RestrictionState {
    /** Read from the platform: Ani is exempt / unrestricted. */
    ALLOWED,

    /** Read from the platform: Ani is restricted. */
    RESTRICTED,

    /**
     * The platform exposes no API for this. Shown as "Unknown", never guessed.
     *
     * OEM auto-start managers are the main case: ColorOS, MIUI, One UI and others keep
     * their own allow-lists with no public API and no documented way to query them.
     */
    UNKNOWN
}

/** One thing the user may need to change, and where to change it. */
data class RestrictionCheck(
    val id: String,
    val title: String,
    val explanation: String,
    val state: RestrictionState,
    /** Null when we found no screen on this device that can change it. */
    val settingsIntent: Intent?
)

/**
 * Background-execution restrictions, and the OEM settings that control them.
 *
 * This is the difference between an assistant that answers at 3am and one that quietly
 * stopped listening an hour after you put the phone down. Stock Android's Doze is
 * survivable for a foreground service; several OEM Android skins are not, because they
 * add their own kill logic on top.
 *
 * **Two rules this class holds to.**
 *
 * First, it never reports a state it did not read. `isIgnoringBatteryOptimizations` and
 * `isBackgroundRestricted` are real APIs and their answers are reported as fact. OEM
 * auto-start allow-lists have no API at all, so that check reports
 * [RestrictionState.UNKNOWN] — and the UI says "we cannot check this one, here is the
 * screen" rather than inventing a green tick.
 *
 * Second, it does not assume a menu path. OEM settings activities get renamed and removed
 * between versions, so every candidate is checked with `resolveActivity` before being
 * offered, and the app falls back to the standard Android screen when none resolves.
 */
class BackgroundRestrictions(private val context: Context) {

    fun checks(): List<RestrictionCheck> = listOf(
        batteryOptimisationCheck(),
        backgroundRestrictionCheck(),
        autoStartCheck()
    )

    /** True only when every check the platform can answer says we are unrestricted. */
    fun looksReady(): Boolean = checks()
        .filter { it.state != RestrictionState.UNKNOWN }
        .all { it.state == RestrictionState.ALLOWED }

    // ---------------------------------------------------------------------------------

    /** Doze exemption. Readable via [PowerManager.isIgnoringBatteryOptimizations]. */
    fun isIgnoringBatteryOptimisations(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    private fun batteryOptimisationCheck() = RestrictionCheck(
        id = "battery_optimisation",
        title = "Battery optimisation",
        explanation = "While Ani is optimised, Android may suspend it after the screen has " +
            "been off for a while, and the wake phrase stops being heard.",
        state = if (isIgnoringBatteryOptimisations()) {
            RestrictionState.ALLOWED
        } else {
            RestrictionState.RESTRICTED
        },
        settingsIntent = firstResolvable(
            listOf(
                // The list screen, which needs no special permission. Deliberately not
                // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: that needs a permission
                // Play treats as restricted, and it is not needed to get the job done.
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetailsIntent()
            )
        )
    )

    /**
     * "Restricted" app standby, set by the user or by an OEM's battery manager.
     * Readable from API 28 via [ActivityManager.isBackgroundRestricted].
     */
    private fun backgroundRestrictionCheck(): RestrictionCheck {
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val manager = context.getSystemService(ActivityManager::class.java)
            when {
                manager == null -> RestrictionState.UNKNOWN
                manager.isBackgroundRestricted -> RestrictionState.RESTRICTED
                else -> RestrictionState.ALLOWED
            }
        } else {
            RestrictionState.UNKNOWN
        }

        return RestrictionCheck(
            id = "background_restriction",
            title = "Background activity",
            explanation = "With background activity restricted, Android stops the listening " +
                "service as soon as you leave the app.",
            state = state,
            settingsIntent = firstResolvable(listOf(appDetailsIntent()))
        )
    }

    /**
     * OEM auto-start / startup manager.
     *
     * There is no API to read this, on any skin. Reporting [RestrictionState.UNKNOWN] is
     * the honest answer, and the UI asks the user to confirm it by eye.
     */
    private fun autoStartCheck() = RestrictionCheck(
        id = "auto_start",
        title = "Auto-start / startup manager",
        explanation = "Many phones — realme, OPPO, Xiaomi, vivo, Samsung, Huawei — keep a " +
            "separate list of apps allowed to start themselves and keep running. Android " +
            "gives no way to read it, so this one has to be checked by eye. Ani must be " +
            "allowed there, or it will stop listening once the screen has been off a while, " +
            "and it will not come back after a restart.",
        state = RestrictionState.UNKNOWN,
        settingsIntent = firstResolvable(oemAutoStartIntents() + appDetailsIntent())
    )

    /**
     * Candidate OEM start-up managers, most specific first.
     *
     * Every one is probed with `resolveActivity` before being offered. These component
     * names are renamed and removed between OS versions, so a hard-coded path that is not
     * checked first is a crash — and on realme specifically the package moved from
     * `com.coloros.*` to `com.oplus.*` when ColorOS was rebased.
     */
    private fun oemAutoStartIntents(): List<Intent> = listOf(
        // realme / OPPO / OnePlus — ColorOS, newer OPlus packages first.
        "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
        "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
        // Xiaomi / Redmi / POCO — MIUI and HyperOS.
        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        // vivo — Funtouch / OriginOS.
        "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
        "com.iqoo.secure/.safeguard.PurviewTabActivity",
        // Huawei / Honor.
        "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager/.optimize.process.ProtectActivity",
        // Samsung.
        "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
        // Letv / others.
        "com.letv.android.letvsafe/.AutobootManageActivity"
    ).mapNotNull { flattened ->
        ComponentName.unflattenFromString(flattened)?.let { Intent().setComponent(it) }
    }

    private fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )

    private fun firstResolvable(candidates: List<Intent>): Intent? = candidates.firstOrNull {
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        it.resolveActivity(context.packageManager) != null
    }

    /** @return true when a screen was opened. */
    fun open(check: RestrictionCheck): Boolean {
        val intent = check.settingsIntent ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (error: Exception) {
            // An OEM activity can resolve and still refuse a third-party caller.
            AniLog.w(TAG, "settings screen refused", "check" to check.id)
            false
        }
    }

    /** Manufacturer and model, so the setup screen can name the right menu. */
    fun deviceDescription(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    /** True for the skins known to need the auto-start step. */
    fun hasAggressiveBatteryManager(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return AGGRESSIVE_MANUFACTURERS.any { manufacturer.contains(it) }
    }

    private companion object {
        const val TAG = "AniBackground"

        val AGGRESSIVE_MANUFACTURERS = listOf(
            "realme", "oppo", "oneplus", "xiaomi", "redmi", "poco",
            "vivo", "iqoo", "huawei", "honor", "samsung", "meizu", "letv"
        )
    }
}
