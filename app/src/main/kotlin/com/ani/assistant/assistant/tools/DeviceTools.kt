package com.ani.assistant.assistant.tools

import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.platform.device.DeviceController
import com.ani.assistant.platform.device.SystemSettingsLauncher
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.Responses

/** Battery, storage and the other read-only facts about the phone. */
class DeviceStatusTool(private val device: DeviceController) : AniTool {

    override val id: String = "device_status"
    override val handles: Set<IntentType> =
        setOf(IntentType.GET_BATTERY, IntentType.GET_DEVICE_STATUS)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        if (command.type == IntentType.GET_BATTERY) {
            val battery = device.batteryStatus()
            return AniResult.Success(
                Responses.batteryLevel(battery.percent, battery.isCharging, context.style)
            )
        }

        val telugu = context.style.speaksTelugu
        return when (command[SlotKey.SETTINGS_TARGET]) {
            "storage" -> {
                val storage = device.storageStatus()
                AniResult.Success(
                    if (telugu) {
                        "${storage.freeGigabytes} GB khaali undi, motham ${storage.totalGigabytes} GB${context.style.particle}."
                    } else {
                        "${storage.freeGigabytes} GB free of ${storage.totalGigabytes} GB."
                    }
                )
            }

            "dnd" -> {
                val on = device.isDndOn()
                AniResult.Success(
                    when {
                        telugu && on -> "Avunu, Do Not Disturb on lo undi${context.style.particle}."
                        telugu -> "Ledu, phone normal lo ne undi${context.style.particle}."
                        on -> "Yes, Do Not Disturb is on."
                        else -> "No, Do Not Disturb is off."
                    }
                )
            }

            "volume" -> {
                val percent = device.mediaVolumePercent()
                AniResult.Success(
                    if (telugu) "Volume $percent percent lo undi${context.style.particle}."
                    else "Volume is at $percent percent."
                )
            }

            // Wi-Fi and Bluetooth state cannot be read without the connectivity
            // permissions, and Ani does not ask for those to answer a question.
            else -> AniResult.Limitation(
                spokenResponse = if (telugu) {
                    "Adi Settings lo chudali${context.style.particle}. Open chestha."
                } else {
                    "I can't read that directly — opening Settings."
                },
                fallbackTaken = "opened settings"
            )
        }
    }
}

/** The torch. One of the few device controls a normal app genuinely owns. */
class FlashlightTool(private val device: DeviceController) : AniTool {

    override val id: String = "flashlight"
    override val handles: Set<IntentType> = setOf(IntentType.CONTROL_FLASHLIGHT)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        if (!device.hasFlashlight()) {
            return AniResult.Failure(Responses.noFlashlight(context.style))
        }

        val wantOn = when (command[SlotKey.TOGGLE_STATE]) {
            "on" -> true
            "off" -> false
            else -> !device.isFlashlightOn()
        }

        val changed = device.setFlashlight(wantOn)
        if (!changed) {
            return AniResult.Failure(
                if (context.style.speaksTelugu) {
                    "Light on cheyyaledu${context.style.particle}, camera vere app vadutondi."
                } else {
                    "Couldn't use the torch — another app is holding the camera."
                }
            )
        }

        return AniResult.Success(
            if (wantOn) Responses.flashlightOn(context.style) else Responses.flashlightOff(context.style)
        )
    }
}

/** Media volume. */
class VolumeTool(private val device: DeviceController) : AniTool {

    override val id: String = "volume"
    override val handles: Set<IntentType> = setOf(IntentType.CONTROL_VOLUME)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val level = command[SlotKey.LEVEL]
        val result = when (level) {
            "up" -> device.adjustMediaVolume(up = true)
            "down" -> device.adjustMediaVolume(up = false)
            else -> level?.toIntOrNull()?.let { device.setMediaVolumePercent(it) }
        }

        return when {
            result != null -> AniResult.Success(Responses.volumeSet(result, context.style))

            // A SecurityException here means Do Not Disturb is blocking volume changes.
            device.isDndOn() && !device.isDndAccessGranted() -> AniResult.Limitation(
                spokenResponse = if (context.style.speaksTelugu) {
                    "Do Not Disturb on lo undi, anduke volume marchaledu${context.style.particle}."
                } else {
                    "Do Not Disturb is on, so I couldn't change the volume."
                },
                fallbackTaken = null
            )

            else -> AniResult.Failure(Responses.somethingWentWrong(context.style))
        }
    }
}

/**
 * Do Not Disturb.
 *
 * Declared as requiring [AniPermission.DO_NOT_DISTURB] so the registry stops the call
 * before it starts and the user gets "grant me policy access" instead of a silent no-op.
 */
class DndTool(private val device: DeviceController) : AniTool {

    override val id: String = "dnd"
    override val handles: Set<IntentType> = setOf(IntentType.CONTROL_DND)
    override val requiredPermissions: List<AniPermission> = listOf(AniPermission.DO_NOT_DISTURB)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val turningOn = when (command[SlotKey.TOGGLE_STATE]) {
            "on" -> true
            "off" -> false
            else -> !device.isDndOn()
        }

        val changed = device.setDoNotDisturb(turningOn)
        if (!changed) {
            return AniResult.NeedsPermission(
                Responses.dndNeedsPermission(context.style),
                AniPermission.DO_NOT_DISTURB.storageKey
            )
        }

        val telugu = context.style.speaksTelugu
        return AniResult.Success(
            when {
                telugu && turningOn -> "Do Not Disturb on chesa${context.style.particle}."
                telugu -> "Do Not Disturb off chesa${context.style.particle}."
                turningOn -> "Do Not Disturb is on."
                else -> "Do Not Disturb is off."
            }
        )
    }
}

/**
 * Everything the platform will not let a third-party app change directly.
 *
 * This tool exists precisely so those requests have an honest home. Wi-Fi, Bluetooth,
 * airplane mode and brightness were all closed to third-party apps by design; Ani opens
 * the right screen and says what it did rather than reporting a success it did not have.
 */
class SystemSettingsTool(
    private val settingsLauncher: SystemSettingsLauncher
) : AniTool {

    override val id: String = "system_settings"
    override val handles: Set<IntentType> = setOf(IntentType.OPEN_SETTINGS)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val target = command[SlotKey.SETTINGS_TARGET] ?: "settings"
        val label = settingsLauncher.labelFor(target)

        if (!settingsLauncher.open(target)) {
            return AniResult.Failure(
                if (context.style.speaksTelugu) {
                    "$label settings open cheyyaledu${context.style.particle}."
                } else {
                    "I couldn't open $label settings."
                }
            )
        }

        // If they asked for a toggle, be explicit that Android is the one saying no.
        val askedToToggle = command[SlotKey.TOGGLE_STATE] != null || command[SlotKey.LEVEL] != null
        return if (askedToToggle && target in NOT_DIRECTLY_CONTROLLABLE) {
            AniResult.Limitation(
                spokenResponse = Responses.cannotToggleDirectly(label, context.style),
                fallbackTaken = "opened $label settings"
            )
        } else {
            AniResult.Success(Responses.openedSettingsScreen(label, context.style))
        }
    }

    private companion object {
        /** Closed to third-party apps: Wi-Fi in API 29, Bluetooth in 33, brightness needs WRITE_SETTINGS. */
        val NOT_DIRECTLY_CONTROLLABLE = setOf("wifi", "bluetooth", "airplane", "brightness")
    }
}
