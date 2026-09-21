package com.ani.assistant.platform.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.ani.assistant.core.log.AniLog
import java.text.DecimalFormat

data class BatteryStatus(val percent: Int, val isCharging: Boolean)

data class StorageStatus(val freeBytes: Long, val totalBytes: Long) {
    val freeGigabytes: String get() = format(freeBytes)
    val totalGigabytes: String get() = format(totalBytes)
    private fun format(bytes: Long) = DecimalFormat("#.#").format(bytes / 1_073_741_824.0)
}

/**
 * Direct control of the device, for the handful of things a normal app is actually
 * allowed to change.
 *
 * The boundary is drawn deliberately. Torch, media volume and — with the user's explicit
 * grant — Do Not Disturb have real APIs. Wi-Fi, Bluetooth, airplane mode and screen
 * brightness do not: they were removed from third-party control in Android 10 and 6
 * respectively, and no amount of wanting it back changes that. Those go through
 * [SystemSettingsLauncher] instead, and Ani says what it is doing.
 */
class DeviceController(private val context: Context) {

    // ---- Battery ---------------------------------------------------------------------

    fun batteryStatus(): BatteryStatus {
        val manager = context.getSystemService(BatteryManager::class.java)
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = manager?.isCharging ?: false
        return BatteryStatus(percent.coerceIn(0, 100), charging)
    }

    fun storageStatus(): StorageStatus {
        val stats = StatFs(Environment.getDataDirectory().path)
        return StorageStatus(
            freeBytes = stats.availableBytes,
            totalBytes = stats.totalBytes
        )
    }

    // ---- Flashlight -------------------------------------------------------------------

    private val cameraManager: CameraManager?
        get() = context.getSystemService(CameraManager::class.java)

    @Volatile
    private var torchOn: Boolean = false

    fun hasFlashlight(): Boolean = flashCameraId() != null

    /** @return true when the torch state changed, false when there is no flash unit. */
    fun setFlashlight(enabled: Boolean): Boolean {
        val cameraId = flashCameraId() ?: return false
        return try {
            cameraManager?.setTorchMode(cameraId, enabled)
            torchOn = enabled
            true
        } catch (error: CameraAccessException) {
            // Another app holding the camera is the usual cause, and it is not our bug.
            AniLog.w(TAG, "torch unavailable", "reason" to error.reason)
            false
        }
    }

    fun toggleFlashlight(): Boolean = setFlashlight(!torchOn)

    fun isFlashlightOn(): Boolean = torchOn

    private fun flashCameraId(): String? = try {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (error: Exception) {
        AniLog.w(TAG, "could not enumerate cameras")
        null
    }

    // ---- Volume -----------------------------------------------------------------------

    private val audioManager: AudioManager?
        get() = context.getSystemService(AudioManager::class.java)

    fun mediaVolumePercent(): Int {
        val manager = audioManager ?: return 0
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return 0
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    /** @return the resulting percentage, or null when volume could not be changed. */
    fun setMediaVolumePercent(percent: Int): Int? {
        val manager = audioManager ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        val target = (percent.coerceIn(0, 100) * max / 100).coerceIn(0, max)
        return try {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            mediaVolumePercent()
        } catch (error: SecurityException) {
            // Changing volume is blocked while Do Not Disturb is on without policy access.
            AniLog.w(TAG, "volume change blocked by DND policy")
            null
        }
    }

    fun adjustMediaVolume(up: Boolean): Int? {
        val manager = audioManager ?: return null
        return try {
            manager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            mediaVolumePercent()
        } catch (error: SecurityException) {
            AniLog.w(TAG, "volume adjust blocked by DND policy")
            null
        }
    }

    /** True when headphones or a Bluetooth audio device is connected. */
    fun isHeadsetConnected(): Boolean {
        val manager = audioManager ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }
    }

    // ---- Do Not Disturb ----------------------------------------------------------------

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    fun isDndAccessGranted(): Boolean = notificationManager?.isNotificationPolicyAccessGranted == true

    fun isDndOn(): Boolean {
        val manager = notificationManager ?: return false
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /**
     * @return true when the filter was changed. False means the user has not granted
     *         notification-policy access, and the caller must say so rather than pretend.
     */
    fun setDoNotDisturb(enabled: Boolean): Boolean {
        val manager = notificationManager ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false
        return try {
            manager.setInterruptionFilter(
                if (enabled) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }
            )
            true
        } catch (error: SecurityException) {
            AniLog.w(TAG, "DND change refused")
            false
        }
    }

    private companion object {
        const val TAG = "AniDevice"
    }
}

/**
 * Opens the system Settings screen for things Ani cannot change itself.
 *
 * Every method here exists because the corresponding direct API was taken away from
 * third-party apps, not because implementing it was inconvenient:
 *
 * - `WifiManager.setWifiEnabled` — no-op for third-party apps since Android 10 (API 29).
 * - `BluetoothAdapter.enable`/`disable` — deprecated and no-op since Android 13 (API 33).
 * - Airplane mode — system-only since Android 4.2.
 * - `Settings.System.SCREEN_BRIGHTNESS` — needs WRITE_SETTINGS, which Play treats as a
 *   restricted permission and which users reasonably refuse.
 *
 * On Android 10+ the Wi-Fi and internet panels open as a bottom sheet over the app, so
 * the user can toggle and come straight back.
 */
class SystemSettingsLauncher(private val context: Context) {

    /** @return true when a settings screen was opened. */
    fun open(target: String): Boolean {
        val intents = intentsFor(target)
        for (intent in intents) {
            val launchable = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchable.resolveActivity(context.packageManager) != null) {
                return try {
                    context.startActivity(launchable)
                    true
                } catch (error: Exception) {
                    AniLog.w(TAG, "settings screen refused to open", "target" to target)
                    false
                }
            }
        }
        AniLog.w(TAG, "no settings screen for target", "target" to target)
        return false
    }

    /** Ordered candidates: the panel first where one exists, then the full settings page. */
    private fun intentsFor(target: String): List<Intent> = when (target) {
        "wifi" -> buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_WIFI))
            }
            add(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        "bluetooth" -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

        "airplane" -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))

        "brightness" -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))

        "dnd" -> listOf(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            Intent(Settings.ACTION_SOUND_SETTINGS)
        )

        "volume" -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))

        "location" -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

        "network" -> buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            }
            add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }

        "storage" -> listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))

        "battery" -> listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

        else -> listOf(Intent(Settings.ACTION_SETTINGS))
    }

    /** Human label for the screen, used in what Ani says. */
    fun labelFor(target: String): String = when (target) {
        "wifi" -> "Wi-Fi"
        "bluetooth" -> "Bluetooth"
        "airplane" -> "Airplane mode"
        "brightness" -> "Display"
        "dnd" -> "Do Not Disturb"
        "volume" -> "Sound"
        "location" -> "Location"
        "network" -> "Network"
        "storage" -> "Storage"
        "battery" -> "Battery"
        else -> "Settings"
    }

    private companion object {
        const val TAG = "AniSettingsLauncher"
    }
}
